#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
}
#if __has_include(<libavcodec/jni.h>)
extern "C" {
#include <libavcodec/jni.h>
}
#define FOX_AVJNI 1
#endif

#include <glm/glm.hpp>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <vector>

#include "fox_audio_mix.h"
#include "fox_anim.h"
#include "fox_rig.h"

#define TAG "FoxExporter"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

constexpr float kBrushSpace = 512.f;
constexpr size_t kFlushVerts = 6 * 6000;
constexpr int kAudioRate = 48000;

struct Vert {
    glm::vec2 pos;
    glm::vec4 seg;
    float r;
    glm::vec4 col;
};
static_assert(sizeof(Vert) == 11 * sizeof(float));
static_assert(std::is_standard_layout_v<Vert>);

struct StrokeData {
    int cel = 0, layer = 0;
    uint32_t argb = 0xFF000000u;
    float size = 10.f;
    bool erase = false;
    std::vector<float> pts;
};

struct Job {
    std::string path, codec, preset, extra;
    int side = 1080, fps = 30, srcFps = 12, videoKbps = 4000, audioKbps = 0;
    uint32_t bg = 0xFFFFFFFFu;
    float crf = -1.f;
    std::vector<int> celIds, celLens, celRows, rowStarts, layerIds;
    std::vector<float> rowXf;   // 7 floats per row: tx, ty, sx, sy, rotDeg, pivotX, pivotY (paper units)
    std::vector<int> rowKeyCounts;     // keyframes per row (fox_anim.h flat layout, row-local source frames)
    std::vector<float> rowKeys;        // every row's keys back to back, kKeyStride floats each
    std::vector<size_t> rowKeyOff;     // float offset of each row's first key in rowKeys (filled by execute)
    std::vector<int> rowAttach;        // 2 ints per row: parent row, bone (-1 = not attached to a bone of another row)
    std::vector<float> rigBlob;        // fox_rig.h set blob: [nRows, (len, floats) per row]; rows with a rig are drawn as a deformed mesh
    std::unordered_map<int, std::vector<size_t>> byCel;   // cel id -> indices into strokes (draw order)
    std::vector<uint8_t> layerVis;
    std::vector<StrokeData> strokes;

    std::atomic<int> done{0}, total{1};
    std::atomic<bool> cancel{false};
    std::mutex mu;
    std::string error, encoder, warning;

    void setError(std::string s) { std::lock_guard<std::mutex> l(mu); error = std::move(s); }
    void setEncoder(std::string s) { std::lock_guard<std::mutex> l(mu); encoder = std::move(s); }
    void setWarning(std::string s) { std::lock_guard<std::mutex> l(mu); warning = std::move(s); }
    std::string getError() { std::lock_guard<std::mutex> l(mu); return error; }
    std::string getEncoder() { std::lock_guard<std::mutex> l(mu); return encoder; }
    std::string getWarning() { std::lock_guard<std::mutex> l(mu); return warning; }
};

std::mutex gMu;
std::unordered_map<jlong, std::shared_ptr<Job>> gJobs;
jlong gNext = 1;

std::shared_ptr<Job> findJob(jlong h) {
    std::lock_guard<std::mutex> l(gMu);
    auto it = gJobs.find(h);
    return it == gJobs.end() ? nullptr : it->second;
}

int fail(Job& j, int code, const std::string& msg) {
    LOGE("%s", msg.c_str());
    j.setError(msg);
    return code;
}

std::string averr(int r) {
    char b[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(r, b, sizeof b);
    return b;
}

const char* kStrokeVS = R"(#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec4 aSeg;
layout(location=2) in float aR;
layout(location=3) in vec4 aC;
uniform float uT;
out vec2 vP;
flat out vec4 vSeg;
flat out float vR;
flat out vec4 vC;
void main() {
    vP = aPos; vSeg = aSeg; vR = aR; vC = aC;
    gl_Position = vec4(aPos / uT * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kStrokeFS = R"(#version 300 es
precision highp float;
in vec2 vP;
flat in vec4 vSeg;
flat in float vR;
flat in vec4 vC;
out vec4 o;
void main() {
    vec2 a = vSeg.xy, b = vSeg.zw, ab = b - a;
    float l2 = dot(ab, ab);
    float t = l2 > 0.0 ? clamp(dot(vP - a, ab) / l2, 0.0, 1.0) : 0.0;
    float d = length(vP - (a + ab * t));
    float al = clamp(vR - d + 0.5, 0.0, 1.0) * vC.a;
    o = vec4(vC.rgb * al, al);
}
)";

// Layer quad with a whole-layer transform in paper units (same maths as the on-screen canvas):
//   shown = pivot + t + R(rot, clockwise, y down) * S * (c - pivot);  vUV keeps the source texel.
const char* kQuadVS = R"(#version 300 es
uniform vec2 uPivot, uTr, uSc;
uniform float uRot;
uniform mat2 uAM;        // attachment to another row's bone (paper units), identity when none
uniform vec2 uAT;
out vec2 vUV;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUV = c;
    vec2 d = (c - uPivot) * uSc;
    float cs = cos(uRot), sn = sin(uRot);
    vec2 p = uPivot + uTr + vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
    p = uAM * p + uAT;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

// Smooth deformed surface. The CPU only evaluates the rig at the GRID vertices (uPos = their shown positions, one RG32F
// texel each); this shader re-tessellates every cell into uSub x uSub quads and places each new vertex on the
// bicubic Catmull-Rom surface through the 4x4 neighbouring grid points. The surface passes through every grid point (so the
// rest pose is exact) but bends smoothly in between: no kinked / faceted outlines at joints, no stair-steps.
const char* kSmoothFn = R"(
uniform sampler2D uPos;
uniform int uGrid, uSub;
uniform vec4 uRect;      // rest rect l t r b (paper units)
vec2 gridP(int i, int j) { return texelFetch(uPos, ivec2(clamp(i, 0, uGrid), clamp(j, 0, uGrid)), 0).xy; }
vec4 crW(float t) {
    float t2 = t * t, t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);
}
// vertex gl_VertexID of the attribute-less draw: 6 per sub-quad. Returns shown position, sets rest (paper units).
vec2 smoothVertex(out vec2 rest) {
    int q = gl_VertexID / 6;
    int k = gl_VertexID - q * 6;
    int n = uGrid * uSub;
    int sx = q % n, sy = q / n;
    int cx = (k == 1 || k == 3 || k == 4) ? 1 : 0;
    int cy = (k == 2 || k == 4 || k == 5) ? 1 : 0;
    float fx = float(sx + cx) / float(uSub), fy = float(sy + cy) / float(uSub);
    int i = min(int(floor(fx)), uGrid - 1), j = min(int(floor(fy)), uGrid - 1);
    vec4 wx = crW(fx - float(i)), wy = crW(fy - float(j));
    vec2 pos = vec2(0.0);
    for (int b = 0; b < 4; b++) {
        int jj = j + b - 1;
        vec2 row = gridP(i - 1, jj) * wx.x + gridP(i, jj) * wx.y + gridP(i + 1, jj) * wx.z + gridP(i + 2, jj) * wx.w;
        pos += row * wy[b];
    }
    rest = mix(uRect.xy, uRect.zw, vec2(fx, fy) / float(uGrid));
    return pos;
}
)";


// Deformed grid mesh of a rigged row, re-tessellated + smoothed in the shader (see kSmoothFn). The row's own transform
// (keyframed move / rotate / resize) and the bone attachment are applied on top, with the same maths as kQuadVS.
const char* kMeshVSHead = R"(#version 300 es
precision highp float;
precision highp int;
uniform vec2 uPivot, uTr, uSc;
uniform float uRot;
uniform mat2 uAM;
uniform vec2 uAT;
out vec2 vUV;
)";
const char* kMeshVSMain = R"(
void main() {
    vec2 rest;
    vec2 pos = smoothVertex(rest);
    vUV = rest;
    vec2 d = (pos - uPivot) * uSc;
    float cs = cos(uRot), sn = sin(uRot);
    vec2 p = uPivot + uTr + vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
    p = uAM * p + uAT;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kQuadFS = R"(#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 o;
void main() {
    o = texture(uTex, vUV);
}
)";

GLuint compile(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

GLuint link(const char* vs, const char* fs) {
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    if (!v || !f) {
        if (v) glDeleteShader(v);
        if (f) glDeleteShader(f);
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        LOGE("program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

glm::vec4 unpackArgb(uint32_t c) {
    return glm::vec4((float) ((c >> 16) & 255u), (float) ((c >> 8) & 255u),
                     (float) (c & 255u), (float) (c >> 24)) / 255.f;
}

void pushSeg(std::vector<Vert>& v, glm::vec2 a, glm::vec2 b, float r, const glm::vec4& c) {
    const float m = r + 1.5f;
    const glm::vec2 lo = glm::min(a, b) - m;
    const glm::vec2 hi = glm::max(a, b) + m;
    const glm::vec2 corner[6] = {
        {lo.x, lo.y}, {hi.x, lo.y}, {lo.x, hi.y},
        {hi.x, lo.y}, {hi.x, hi.y}, {lo.x, hi.y},
    };
    Vert vt{glm::vec2(0.f), glm::vec4(a, b), r, c};
    for (const glm::vec2& p : corner) {
        vt.pos = p;
        v.push_back(vt);
    }
}

void emitCR(std::vector<Vert>& v, glm::vec2 p0, glm::vec2 p1, glm::vec2 p2, glm::vec2 p3, float r, const glm::vec4& c) {
    const int n = std::clamp((int) std::ceil(glm::distance(p1, p2) / 3.f), 1, 32);
    const glm::vec2 k1 = -p0 + p2;
    const glm::vec2 k2 = 2.f * p0 - 5.f * p1 + 4.f * p2 - p3;
    const glm::vec2 k3 = -p0 + 3.f * p1 - 3.f * p2 + p3;
    glm::vec2 prev = p1;
    for (int k = 1; k <= n; k++) {
        const float t = (float) k / n, t2 = t * t, t3 = t2 * t;
        const glm::vec2 q = 0.5f * (2.f * p1 + k1 * t + k2 * t2 + k3 * t3);
        pushSeg(v, prev, q, r, c);
        prev = q;
    }
}

struct Renderer {
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface surf = EGL_NO_SURFACE;
    bool current = false;
    int S = 0;
    GLuint strokeProg = 0, quadProg = 0, vaoStroke = 0, vaoEmpty = 0, vbo = 0;
    GLint uT = -1, uTex = -1, uPivot = -1, uTr = -1, uSc = -1, uRot = -1, uAM = -1, uAT = -1;
    GLuint meshProg = 0, vaoMesh = 0, vboMesh = 0, iboMesh = 0;
    GLint mPos = -1, mGrid = -1, mSub = -1, mRect = -1;
    GLuint posTexE = 0;
    int posTexW = 0;
    std::vector<float> posTmpE;
    GLint mTex = -1, mPivot = -1, mTr = -1, mSc = -1, mRot = -1, mAM = -1, mAT = -1;
    GLuint layerTex = 0, layerFbo = 0, outTex = 0, outFbo = 0;

    // Rasterised (drawing, layer) textures. A moving row re-composites the same pixels every frame, so only
    // the first use of a (cel, layer) pair pays for the strokes. LRU, sized to ~96 MB (1 entry at huge sides).
    struct CachedLayer { int cel = -1, lid = -1; GLuint tex = 0, fbo = 0; uint64_t used = 0; };
    std::vector<CachedLayer> cache;
    size_t cacheMax = 1;
    uint64_t useTick = 0;

    std::vector<Vert> batch;
    bool erase = false;

    ~Renderer() { shutdown(); }

    bool target(GLuint& tex, GLuint& fbo) {
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, S, S);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        return glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    }

    bool init(int side, std::string& err) {
        S = side;
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, nullptr, nullptr)) { err = "EGL display init failed"; return false; }
        const EGLint ca[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_NONE,
        };
        EGLConfig cfg = nullptr;
        EGLint n = 0;
        if (!eglChooseConfig(dpy, ca, &cfg, 1, &n) || n < 1) { err = "no EGL ES3 pbuffer config"; return false; }
        const EGLint xa[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, xa);
        if (ctx == EGL_NO_CONTEXT) { err = "EGL context creation failed"; return false; }
        const EGLint pa[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surf = eglCreatePbufferSurface(dpy, cfg, pa);
        if (surf == EGL_NO_SURFACE) { err = "EGL pbuffer creation failed"; return false; }
        if (!eglMakeCurrent(dpy, surf, surf, ctx)) { err = "eglMakeCurrent failed"; return false; }
        current = true;

        GLint maxTex = 0, maxRb = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
        glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &maxRb);
        if (S > maxTex || S > maxRb) {
            err = "resolution " + std::to_string(S) + " exceeds GPU limit " + std::to_string(std::min(maxTex, maxRb));
            return false;
        }

        strokeProg = link(kStrokeVS, kStrokeFS);
        quadProg = link(kQuadVS, kQuadFS);
        const std::string meshVS = std::string(kMeshVSHead) + kSmoothFn + kMeshVSMain;
        meshProg = link(meshVS.c_str(), kQuadFS);
        if (!strokeProg || !quadProg || !meshProg) { err = "shader build failed"; return false; }
        mTex = glGetUniformLocation(meshProg, "uTex");
        mPivot = glGetUniformLocation(meshProg, "uPivot");
        mTr = glGetUniformLocation(meshProg, "uTr");
        mSc = glGetUniformLocation(meshProg, "uSc");
        mRot = glGetUniformLocation(meshProg, "uRot");
        mPos = glGetUniformLocation(meshProg, "uPos");
        mGrid = glGetUniformLocation(meshProg, "uGrid");
        mSub = glGetUniformLocation(meshProg, "uSub");
        mRect = glGetUniformLocation(meshProg, "uRect");
        mAM = glGetUniformLocation(meshProg, "uAM");
        mAT = glGetUniformLocation(meshProg, "uAT");
        uAM = glGetUniformLocation(quadProg, "uAM");
        uAT = glGetUniformLocation(quadProg, "uAT");
        uT = glGetUniformLocation(strokeProg, "uT");
        uTex = glGetUniformLocation(quadProg, "uTex");
        uPivot = glGetUniformLocation(quadProg, "uPivot");
        uTr = glGetUniformLocation(quadProg, "uTr");
        uSc = glGetUniformLocation(quadProg, "uSc");
        uRot = glGetUniformLocation(quadProg, "uRot");

        glGenVertexArrays(1, &vaoStroke);
        glGenVertexArrays(1, &vaoEmpty);
        glGenBuffers(1, &vbo);
        glGenVertexArrays(1, &vaoMesh);
        glGenBuffers(1, &vboMesh);
        glGenBuffers(1, &iboMesh);
        glBindVertexArray(vaoMesh);
        glBindBuffer(GL_ARRAY_BUFFER, vboMesh);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) 0);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) (2 * sizeof(float)));
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, iboMesh);
        glBindVertexArray(0);
        glBindVertexArray(vaoStroke);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        const GLsizei stride = (GLsizei) sizeof(Vert);
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, pos));
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, seg));
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, r));
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, col));
        glBindVertexArray(0);

        if (!target(layerTex, layerFbo) || !target(outTex, outFbo)) { err = "offscreen framebuffer incomplete"; return false; }
        cache.push_back(CachedLayer{-1, -1, layerTex, layerFbo, 0});
        cacheMax = (size_t) std::clamp<int64_t>((int64_t) 96 * 1024 * 1024 / ((int64_t) S * S * 4), 1, 16);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DITHER);
        batch.reserve(kFlushVerts + 6 * 32 + 64);
        return true;
    }

    void shutdown() {
        if (current) {
            for (CachedLayer& e : cache) {
                if (e.fbo) glDeleteFramebuffers(1, &e.fbo);
                if (e.tex) glDeleteTextures(1, &e.tex);
            }
            cache.clear();
            if (outFbo) glDeleteFramebuffers(1, &outFbo);
            if (outTex) glDeleteTextures(1, &outTex);
            if (vbo) glDeleteBuffers(1, &vbo);
            if (posTexE) glDeleteTextures(1, &posTexE);
            posTexE = 0; posTexW = 0;
            if (vboMesh) glDeleteBuffers(1, &vboMesh);
            if (iboMesh) glDeleteBuffers(1, &iboMesh);
            if (vaoMesh) glDeleteVertexArrays(1, &vaoMesh);
            if (meshProg) glDeleteProgram(meshProg);
            vboMesh = iboMesh = vaoMesh = meshProg = 0;
            if (vaoStroke) glDeleteVertexArrays(1, &vaoStroke);
            if (vaoEmpty) glDeleteVertexArrays(1, &vaoEmpty);
            if (strokeProg) glDeleteProgram(strokeProg);
            if (quadProg) glDeleteProgram(quadProg);
            layerFbo = outFbo = layerTex = outTex = vbo = vaoStroke = vaoEmpty = strokeProg = quadProg = 0;
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            current = false;
        }
        if (surf != EGL_NO_SURFACE) { eglDestroySurface(dpy, surf); surf = EGL_NO_SURFACE; }
        if (ctx != EGL_NO_CONTEXT) { eglDestroyContext(dpy, ctx); ctx = EGL_NO_CONTEXT; }
    }

    // Slot holding (cel, lid); [fresh] = it must be (re)rasterised. Points layerTex / layerFbo at the slot.
    bool acquire(int cel, int lid) {
        ++useTick;
        for (CachedLayer& e : cache) {
            if (e.cel == cel && e.lid == lid) { e.used = useTick; layerTex = e.tex; layerFbo = e.fbo; return false; }
        }
        CachedLayer* slot = nullptr;
        if (cache.size() < cacheMax) {
            CachedLayer n;
            if (target(n.tex, n.fbo)) { cache.push_back(n); slot = &cache.back(); }
            else { if (n.fbo) glDeleteFramebuffers(1, &n.fbo); if (n.tex) glDeleteTextures(1, &n.tex); }
        }
        if (!slot) {
            slot = &cache[0];
            for (CachedLayer& e : cache) if (e.used < slot->used) slot = &e;
        }
        slot->cel = cel; slot->lid = lid; slot->used = useTick;
        layerTex = slot->tex; layerFbo = slot->fbo;
        return true;
    }

    void flush() {
        if (batch.empty()) return;
        glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
        glViewport(0, 0, S, S);
        glUseProgram(strokeProg);
        glUniform1f(uT, (float) S);
        glEnable(GL_BLEND);
        if (erase) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
        else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(vaoStroke);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (batch.size() * sizeof(Vert)), batch.data(), GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, (GLsizei) batch.size());
        batch.clear();
    }

    void drawStroke(const StrokeData& s) {
        if (erase != s.erase) { flush(); erase = s.erase; }
        const size_t n = s.pts.size() / 2;
        if (n == 0) return;
        const float T = (float) S;
        const float r = s.size * T / kBrushSpace * 0.5f;
        const glm::vec4 c = unpackArgb(s.argb);
        auto P = [&](size_t i) { return glm::vec2(s.pts[i * 2], s.pts[i * 2 + 1]) * T; };
        if (n == 1) {
            const glm::vec2 p = P(0);
            pushSeg(batch, p, p, r, c);
        } else {
            for (size_t j = 0; j + 1 < n; j++) {
                emitCR(batch, P(j == 0 ? 0 : j - 1), P(j), P(j + 1), P(std::min(j + 2, n - 1)), r, c);
                if (batch.size() >= kFlushVerts) flush();
            }
        }
        if (batch.size() >= kFlushVerts) flush();
    }

    // One output frame = every timeline row bottom -> top; inside a row every visible layer bottom -> top.
    // Each (row, layer) is rasterised in its own FBO so an eraser only cuts through its own layer,
    // exactly like the on-screen canvas. [active] holds the cel each row shows now (-1 = row has ended).
    // [rigs] / [rverts]: rows with a valid rig are drawn as the deformed mesh in rverts[row] (x y u v per vertex).
    void renderFrame(const Job& j, const std::vector<int>& active, const std::vector<fox::anim::Xf>& xfs,
                     const std::vector<fox::rig::Rig>& rigs, const std::vector<std::vector<float>>& rverts,
                     const std::vector<fox::rig::Aff>& atts, std::vector<uint8_t>& rgba) {
        glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
        glViewport(0, 0, S, S);
        const glm::vec4 bg = unpackArgb(j.bg);
        glClearColor(bg.r, bg.g, bg.b, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);

        for (size_t ri = 0; ri < active.size(); ri++) {
            const int cel = active[ri];
            if (cel < 0) continue;
            const fox::rig::Aff am = ri < atts.size() ? atts[ri] : fox::rig::Aff{};
            const float amv[4] = {am.a, am.b, am.c, am.d};
            const fox::anim::Xf& xf = xfs[ri];   // this row's transform at this output frame (keyframes already applied)
            auto it = j.byCel.find(cel);
            if (it == j.byCel.end()) continue;
            const std::vector<size_t>& idx = it->second;
            const bool meshRow = ri < rigs.size() && rigs[ri].valid && ri < rverts.size() && !rverts[ri].empty();
            bool meshUploaded = false;

            for (size_t li = 0; li < j.layerIds.size(); li++) {
                if (!j.layerVis[li]) continue;
                const int lid = j.layerIds[li];
                bool any = false;
                for (size_t si : idx) if (j.strokes[si].layer == lid) { any = true; break; }
                if (!any) continue;

                if (acquire(cel, lid)) {
                    glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
                    glViewport(0, 0, S, S);
                    glClearColor(0.f, 0.f, 0.f, 0.f);
                    glClear(GL_COLOR_BUFFER_BIT);
                    erase = false;
                    for (size_t si : idx) if (j.strokes[si].layer == lid) drawStroke(j.strokes[si]);
                    flush();
                    erase = false;
                }

                glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
                glViewport(0, 0, S, S);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, layerTex);
                // flat quads sample 1:1 (nearest keeps them bit-exact); a deformed mesh needs smooth sampling
                const GLint filter = meshRow ? GL_LINEAR : GL_NEAREST;
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
                glEnable(GL_BLEND);
                glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                if (meshRow) {
                    glUseProgram(meshProg);
                    glUniform1i(mTex, 0);
                    glUniform2f(mPivot, xf.px, xf.py);
                    glUniform2f(mTr, xf.tx, xf.ty);
                    glUniform2f(mSc, xf.sx, xf.sy);
                    glUniform1f(mRot, xf.rot * 3.14159265358979f / 180.f);
                    glUniformMatrix2fv(mAM, 1, GL_FALSE, amv);
                    glUniform2f(mAT, am.tx, am.ty);
                    const int grid = rigs[ri].grid;
                    const int sub = fox::rig::meshSub(grid);
                    glActiveTexture(GL_TEXTURE1);
                    if (!posTexE) {
                        glGenTextures(1, &posTexE);
                        glBindTexture(GL_TEXTURE_2D, posTexE);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);   // texelFetch: exact values
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    }
                    glBindTexture(GL_TEXTURE_2D, posTexE);
                    if (!meshUploaded) {   // once per row and frame, shared by all its layers
                        const int W = grid + 1;
                        const size_t n = rverts[ri].size() / 4;
                        posTmpE.resize(n * 2);
                        for (size_t k = 0; k < n; k++) { posTmpE[k * 2] = rverts[ri][k * 4]; posTmpE[k * 2 + 1] = rverts[ri][k * 4 + 1]; }
                        if (posTexW != W) { glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, W, W, 0, GL_RG, GL_FLOAT, posTmpE.data()); posTexW = W; }
                        else glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, W, GL_RG, GL_FLOAT, posTmpE.data());
                        meshUploaded = true;
                    }
                    glActiveTexture(GL_TEXTURE0);
                    glUniform1i(mPos, 1);
                    glUniform1i(mGrid, grid);
                    glUniform1i(mSub, sub);
                    glUniform4f(mRect, rigs[ri].l, rigs[ri].t, rigs[ri].r, rigs[ri].b);
                    glBindVertexArray(vaoEmpty);
                    glDrawArrays(GL_TRIANGLES, 0, (GLsizei) (grid * grid * sub * sub * 6));
                } else {
                    glUseProgram(quadProg);
                    glUniform1i(uTex, 0);
                    glUniform2f(uPivot, xf.px, xf.py);
                    glUniform2f(uTr, xf.tx, xf.ty);
                    glUniform2f(uSc, xf.sx, xf.sy);
                    glUniform1f(uRot, xf.rot * 3.14159265358979f / 180.f);
                    glUniformMatrix2fv(uAM, 1, GL_FALSE, amv);
                    glUniform2f(uAT, am.tx, am.ty);
                    glBindVertexArray(vaoEmpty);
                    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                }
            }
        }

        glBindFramebuffer(GL_FRAMEBUFFER, outFbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, S, S, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
    }
};

inline int clampU8(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline uint8_t lumaOf(const uint8_t* p) { return (uint8_t) (((47 * p[0] + 157 * p[1] + 16 * p[2] + 128) >> 8) + 16); }

void rgbaToYuv(const uint8_t* src, int S, AVFrame* f, bool nv12) {
    for (int y = 0; y < S; y += 2) {
        const uint8_t* r0 = src + (size_t) y * S * 4;
        const uint8_t* r1 = r0 + (size_t) S * 4;
        uint8_t* y0 = f->data[0] + (ptrdiff_t) y * f->linesize[0];
        uint8_t* y1 = y0 + f->linesize[0];
        uint8_t* u = f->data[1] + (ptrdiff_t) (y / 2) * f->linesize[1];
        uint8_t* v = nv12 ? nullptr : f->data[2] + (ptrdiff_t) (y / 2) * f->linesize[2];
        for (int x = 0; x < S; x += 2) {
            const uint8_t* a = r0 + x * 4;
            const uint8_t* b = r0 + (x + 1) * 4;
            const uint8_t* c = r1 + x * 4;
            const uint8_t* d = r1 + (x + 1) * 4;
            y0[x] = lumaOf(a); y0[x + 1] = lumaOf(b);
            y1[x] = lumaOf(c); y1[x + 1] = lumaOf(d);
            const int r = (a[0] + b[0] + c[0] + d[0] + 2) >> 2;
            const int g = (a[1] + b[1] + c[1] + d[1] + 2) >> 2;
            const int bl = (a[2] + b[2] + c[2] + d[2] + 2) >> 2;
            const uint8_t cu = (uint8_t) clampU8(((-26 * r - 87 * g + 112 * bl + 128) >> 8) + 128, 16, 240);
            const uint8_t cv = (uint8_t) clampU8(((112 * r - 102 * g - 10 * bl + 128) >> 8) + 128, 16, 240);
            if (nv12) { u[x] = cu; u[x + 1] = cv; }
            else { u[x / 2] = cu; v[x / 2] = cv; }
        }
    }
}

bool isX26x(const char* n) { return !std::strcmp(n, "libx264") || !std::strcmp(n, "libx265"); }

std::vector<std::string> candidates(const std::string& c) {
    if (c == "h264") return {"libx264", "h264_mediacodec", "libopenh264", "mpeg4"};
    if (c == "hevc") return {"libx265", "hevc_mediacodec"};
    return {c};
}

AVCodecContext* openVideo(const Job& j, const AVCodec* codec, AVPixelFormat pf, bool globalHdr, std::string& err) {
    AVCodecContext* c = avcodec_alloc_context3(codec);
    if (!c) { err = "alloc failed"; return nullptr; }
    c->width = j.side;
    c->height = j.side;
    c->time_base = AVRational{1, j.fps};
    c->framerate = AVRational{j.fps, 1};
    c->pix_fmt = pf;
    c->gop_size = std::max(1, j.fps * 2);
    c->colorspace = AVCOL_SPC_BT709;
    c->color_range = AVCOL_RANGE_MPEG;
    c->color_primaries = AVCOL_PRI_BT709;
    c->color_trc = AVCOL_TRC_BT709;
    if (globalHdr) c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

    AVDictionary* o = nullptr;
    if (isX26x(codec->name)) {
        if (j.crf >= 0.f) {
            char b[32];
            std::snprintf(b, sizeof b, "%.2f", j.crf);
            av_dict_set(&o, "crf", b, 0);
        } else {
            c->bit_rate = (int64_t) std::max(j.videoKbps, 100) * 1000;
        }
        if (!j.preset.empty()) av_dict_set(&o, "preset", j.preset.c_str(), 0);
        if (!std::strcmp(codec->name, "libx265")) av_dict_set(&o, "x265-params", "log-level=error", 0);
    } else {
        c->bit_rate = (int64_t) std::max(j.videoKbps, 500) * 1000;
    }
    if (!j.extra.empty()) av_dict_parse_string(&o, j.extra.c_str(), "=", ";", 0);

    const int r = avcodec_open2(c, codec, &o);
    av_dict_free(&o);
    if (r < 0) {
        err = std::string(codec->name) + ": " + averr(r);
        avcodec_free_context(&c);
        return nullptr;
    }
    return c;
}

struct Ctx {
    AVFormatContext* oc = nullptr;
    AVCodecContext* vc = nullptr;
    AVCodecContext* ac = nullptr;
    AVFrame* vf = nullptr;
    AVFrame* af = nullptr;
    AVPacket* pkt = nullptr;

    ~Ctx() {
        av_packet_free(&pkt);
        av_frame_free(&vf);
        av_frame_free(&af);
        avcodec_free_context(&vc);
        avcodec_free_context(&ac);
        if (oc) {
            if (oc->pb) avio_closep(&oc->pb);
            avformat_free_context(oc);
        }
    }
};

int execute(Job& j) {
    j.side = std::max(16, j.side) & ~1;
    j.fps = std::clamp(j.fps, 1, 120);
    j.srcFps = std::max(1, j.srcFps);

    if (j.celIds.empty() || j.celIds.size() != j.celLens.size() || j.celRows.size() != j.celIds.size())
        return fail(j, -1, "empty or inconsistent timeline");

    // Per-row timelines (rows run in parallel, bottom -> top). The movie is as long as the longest row.
    struct Row { int64_t start = 0; std::vector<int> ids; std::vector<int64_t> ends; };
    int nRows = (int) j.rowStarts.size();
    for (int r : j.celRows) { if (r < 0) return fail(j, -1, "bad row index"); nRows = std::max(nRows, r + 1); }
    std::vector<Row> rows((size_t) nRows);
    for (size_t r = 0; r < j.rowStarts.size(); r++) rows[r].start = std::max(0, j.rowStarts[r]);   // row offset (frames)
    for (size_t i = 0; i < j.celIds.size(); i++) {
        Row& R = rows[(size_t) j.celRows[i]];
        const int64_t prev = R.ends.empty() ? R.start : R.ends.back();
        R.ids.push_back(j.celIds[i]);
        R.ends.push_back(prev + std::max(1, j.celLens[i]));
    }
    int64_t srcTotal = 1;
    for (const Row& R : rows) if (!R.ends.empty()) srcTotal = std::max(srcTotal, R.ends.back());

    // where each row's keys start inside j.rowKeys (rows without a count have no keys)
    j.rowKeyOff.assign(rows.size() + 1, 0);
    for (size_t r = 0; r < rows.size(); r++) {
        const size_t cnt = r < j.rowKeyCounts.size() ? (size_t) std::max(0, j.rowKeyCounts[r]) : 0;
        j.rowKeyOff[r + 1] = j.rowKeyOff[r] + cnt * fox::anim::kKeyStride;
    }
    if (j.rowKeyOff.back() > j.rowKeys.size()) return fail(j, -1, "inconsistent keyframe table");
    for (size_t i = 0; i < j.strokes.size(); i++) j.byCel[j.strokes[i].cel].push_back(i);

    // rigs: row r <-> timeline row r. Skin weights + curve binding are computed once, the pose per output frame.
    std::vector<fox::rig::Rig> rigs;
    fox::rig::parseSet(j.rigBlob.data(), j.rigBlob.size(), rigs);
    for (fox::rig::Rig& R : rigs) { if (R.valid) fox::rig::prepare(R, nullptr, true); else fox::rig::prepareBones(R); }
    const int64_t total = (srcTotal * j.fps + j.srcFps - 1) / j.srcFps;
    j.total = (int) total;

    Renderer rd;
    {
        std::string e;
        if (!rd.init(j.side, e)) return fail(j, -2, e);
    }

    Ctx x;
    int r = avformat_alloc_output_context2(&x.oc, nullptr, "mp4", j.path.c_str());
    if (r < 0 || !x.oc) return fail(j, -3, "mp4 muxer unavailable in this FFmpeg build: " + averr(r));
    const bool globalHdr = (x.oc->oformat->flags & AVFMT_GLOBALHEADER) != 0;

    std::string last;
    for (const auto& name : candidates(j.codec)) {
        const AVCodec* codec = avcodec_find_encoder_by_name(name.c_str());
        if (!codec) continue;
        for (AVPixelFormat pf : {AV_PIX_FMT_YUV420P, AV_PIX_FMT_NV12}) {
            x.vc = openVideo(j, codec, pf, globalHdr, last);
            if (x.vc) break;
        }
        if (x.vc) { j.setEncoder(name); break; }
    }
    if (!x.vc) return fail(j, -4, "no usable '" + j.codec + "' encoder in this FFmpeg build" + (last.empty() ? "" : " (" + last + ")"));
    const bool nv12 = x.vc->pix_fmt == AV_PIX_FMT_NV12;

    AVStream* vs = avformat_new_stream(x.oc, nullptr);
    if (!vs) return fail(j, -3, "cannot create video stream");
    avcodec_parameters_from_context(vs->codecpar, x.vc);
    vs->time_base = x.vc->time_base;
    vs->avg_frame_rate = x.vc->framerate;
    if (x.vc->codec_id == AV_CODEC_ID_HEVC) vs->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');

    std::shared_ptr<FoxMixSnapshot> snap;
    AVStream* as = nullptr;
    int64_t totalAudio = 0;
    int audioBlock = 1024;
    if (j.audioKbps > 0) {
        snap = foxAudioSnapshot();
        if (foxAudioHasClips(*snap)) {
            const AVCodec* ac = avcodec_find_encoder(AV_CODEC_ID_AAC);
            if (!ac) {
                j.setWarning("AAC encoder missing in this FFmpeg build, exported without audio");
            } else {
                x.ac = avcodec_alloc_context3(ac);
                x.ac->sample_rate = kAudioRate;
                av_channel_layout_default(&x.ac->ch_layout, 2);
                x.ac->sample_fmt = AV_SAMPLE_FMT_FLTP;
                x.ac->bit_rate = (int64_t) j.audioKbps * 1000;
                x.ac->time_base = AVRational{1, kAudioRate};
                if (globalHdr) x.ac->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
                r = avcodec_open2(x.ac, ac, nullptr);
                if (r < 0) {
                    avcodec_free_context(&x.ac);
                    j.setWarning("AAC encoder failed to open (" + averr(r) + "), exported without audio");
                } else {
                    as = avformat_new_stream(x.oc, nullptr);
                    avcodec_parameters_from_context(as->codecpar, x.ac);
                    as->time_base = x.ac->time_base;
                    totalAudio = (total * kAudioRate + j.fps - 1) / j.fps;
                    if (x.ac->frame_size > 0) audioBlock = x.ac->frame_size;
                }
            }
        }
    }

    r = avio_open(&x.oc->pb, j.path.c_str(), AVIO_FLAG_WRITE);
    if (r < 0) return fail(j, -5, "cannot open output: " + averr(r));
    AVDictionary* mo = nullptr;
    av_dict_set(&mo, "movflags", "+faststart", 0);
    r = avformat_write_header(x.oc, &mo);
    av_dict_free(&mo);
    if (r < 0) return fail(j, -6, "write header: " + averr(r));

    x.pkt = av_packet_alloc();
    x.vf = av_frame_alloc();
    x.vf->format = x.vc->pix_fmt;
    x.vf->width = j.side;
    x.vf->height = j.side;
    x.vf->colorspace = AVCOL_SPC_BT709;
    x.vf->color_range = AVCOL_RANGE_MPEG;
    x.vf->color_primaries = AVCOL_PRI_BT709;
    x.vf->color_trc = AVCOL_TRC_BT709;
    if (av_frame_get_buffer(x.vf, 0) < 0) return fail(j, -7, "video frame alloc failed");

    std::vector<float> mix;
    if (as) {
        x.af = av_frame_alloc();
        x.af->format = AV_SAMPLE_FMT_FLTP;
        x.af->nb_samples = audioBlock;
        x.af->sample_rate = kAudioRate;
        av_channel_layout_copy(&x.af->ch_layout, &x.ac->ch_layout);
        if (av_frame_get_buffer(x.af, 0) < 0) return fail(j, -7, "audio frame alloc failed");
        mix.resize((size_t) audioBlock * 2);
    }

    auto drain = [&](AVCodecContext* c, AVStream* s) -> int {
        for (;;) {
            const int e = avcodec_receive_packet(c, x.pkt);
            if (e == AVERROR(EAGAIN) || e == AVERROR_EOF) return 0;
            if (e < 0) return e;
            av_packet_rescale_ts(x.pkt, c->time_base, s->time_base);
            x.pkt->stream_index = s->index;
            const int w = av_interleaved_write_frame(x.oc, x.pkt);
            if (w < 0) return w;
        }
    };
    auto encode = [&](AVCodecContext* c, AVStream* s, AVFrame* f) -> int {
        const int e = avcodec_send_frame(c, f);
        if (e < 0 && e != AVERROR_EOF) return e;
        return drain(c, s);
    };

    int64_t aPos = 0;
    auto audioChunk = [&]() -> int {
        const int n = (int) std::min<int64_t>(audioBlock, totalAudio - aPos);
        foxAudioMix(*snap, aPos, n, mix.data());
        x.af->nb_samples = n;
        const int e = av_frame_make_writable(x.af);
        if (e < 0) return e;
        float* L = reinterpret_cast<float*>(x.af->data[0]);
        float* R = reinterpret_cast<float*>(x.af->data[1]);
        for (int k = 0; k < n; k++) { L[k] = mix[(size_t) k * 2]; R[k] = mix[(size_t) k * 2 + 1]; }
        x.af->pts = aPos;
        aPos += n;
        return encode(x.ac, as, x.af);
    };

    std::vector<uint8_t> rgba((size_t) j.side * j.side * 4);
    std::vector<int> active(rows.size(), -1), lastActive;
    std::vector<fox::anim::Xf> xfs(rows.size()), lastXfs(rows.size());
    std::vector<std::vector<float>> chvs(rows.size()), lastChvs(rows.size()), rverts(rows.size());
    std::vector<fox::rig::Pose> poses(rows.size());
    std::vector<fox::rig::Aff> atts(rows.size()), lastAtts(rows.size());
    std::vector<int> attPar(rows.size(), -1), attBone(rows.size(), -1);
    for (size_t ri = 0; ri < rows.size() && (ri + 1) * 2 <= j.rowAttach.size(); ri++) { attPar[ri] = j.rowAttach[ri * 2]; attBone[ri] = j.rowAttach[ri * 2 + 1]; }
    bool haveFrame = false;

    for (int64_t i = 0; i < total; i++) {
        if (j.cancel.load()) return 1;
        const int64_t tf = std::min<int64_t>(i * j.srcFps / j.fps, srcTotal - 1);
        for (size_t ri = 0; ri < rows.size(); ri++) {
            const Row& R = rows[ri];
            if (R.ends.empty() || tf < R.start) { active[ri] = -1; continue; }   // before the row starts
            const auto it = std::upper_bound(R.ends.begin(), R.ends.end(), tf);
            active[ri] = it == R.ends.end() ? -1 : R.ids[(size_t) (it - R.ends.begin())];
        }
        // Keyframes are sampled at the FRACTIONAL source time of this output frame, so a 12 fps drawing exported
        // at 30 fps still moves smoothly (drawings hold, motion does not).
        const double srcT = (double) i * j.srcFps / j.fps;
        for (size_t ri = 0; ri < rows.size(); ri++) {
            fox::anim::Xf base;
            if ((ri + 1) * fox::anim::kXfStride <= j.rowXf.size()) base = fox::anim::readXf(&j.rowXf[ri * fox::anim::kXfStride]);
            const int cnt = (int) ((j.rowKeyOff[ri + 1] - j.rowKeyOff[ri]) / fox::anim::kKeyStride);
            xfs[ri] = fox::anim::evalRow(cnt ? &j.rowKeys[j.rowKeyOff[ri]] : nullptr, cnt, (float) (srcT - (double) rows[ri].start), base);
        }
        // rig channels (bones, IK targets, curve points) at the same fractional source time
        for (size_t ri = 0; ri < rows.size() && ri < rigs.size(); ri++) {
            if (rigs[ri].valid) fox::rig::channelValues(rigs[ri], (float) (srcT - (double) rows[ri].start), chvs[ri]);
        }
        // attachments: parent bones' skin matrices at this time (computePose is cheap; buildMesh below repeats it)
        {
            std::vector<const fox::rig::Pose*> pp(rows.size(), nullptr);
            for (size_t ri = 0; ri < rows.size() && ri < rigs.size(); ri++) {
                if (rigs[ri].valid && !chvs[ri].empty()) { fox::rig::computePose(rigs[ri], chvs[ri].data(), poses[ri]); pp[ri] = &poses[ri]; }
            }
            fox::rig::attachMatrices(attPar.data(), attBone.data(), rows.size(), xfs, pp, atts);
        }
        const bool sameAtt = haveFrame && std::memcmp(atts.data(), lastAtts.data(), atts.size() * sizeof(fox::rig::Aff)) == 0;
        const bool sameXfs = haveFrame && std::memcmp(xfs.data(), lastXfs.data(), xfs.size() * sizeof(fox::anim::Xf)) == 0;
        const bool sameRig = haveFrame && chvs == lastChvs;
        if (!haveFrame || active != lastActive || !sameXfs || !sameRig || !sameAtt) {   // re-render only when a row switched drawing, moved or deformed
            for (size_t ri = 0; ri < rows.size() && ri < rigs.size(); ri++) {
                if (rigs[ri].valid && active[ri] >= 0) fox::rig::buildMesh(rigs[ri], chvs[ri].data(), rverts[ri], poses[ri]);
                else rverts[ri].clear();
            }
            lastChvs = chvs;
            lastAtts = atts;
            rd.renderFrame(j, active, xfs, rigs, rverts, atts, rgba);
            r = av_frame_make_writable(x.vf);
            if (r < 0) return fail(j, -7, "frame buffer: " + averr(r));
            rgbaToYuv(rgba.data(), j.side, x.vf, nv12);
            lastActive = active;
            lastXfs = xfs;
            haveFrame = true;
        }
        x.vf->pts = i;
        r = encode(x.vc, vs, x.vf);
        if (r < 0) return fail(j, -8, "video encode: " + averr(r));

        while (as && aPos < totalAudio && aPos <= (i + 1) * kAudioRate / j.fps + kAudioRate / 2) {
            r = audioChunk();
            if (r < 0) return fail(j, -9, "audio encode: " + averr(r));
        }
        j.done = (int) (i + 1);
    }

    r = encode(x.vc, vs, nullptr);
    if (r < 0) return fail(j, -8, "video flush: " + averr(r));
    if (as) {
        while (aPos < totalAudio) {
            if (j.cancel.load()) return 1;
            r = audioChunk();
            if (r < 0) return fail(j, -9, "audio encode: " + averr(r));
        }
        r = encode(x.ac, as, nullptr);
        if (r < 0) return fail(j, -9, "audio flush: " + averr(r));
    }

    r = av_write_trailer(x.oc);
    if (r < 0) return fail(j, -10, "write trailer: " + averr(r));
    r = avio_closep(&x.oc->pb);
    if (r < 0) return fail(j, -10, "close output: " + averr(r));
    j.done = (int) total;
    LOGI("exported %lld frames with %s", (long long) total, j.getEncoder().c_str());
    return 0;
}

int runJob(Job& j) {
    const int rc = execute(j);
    if (rc != 0) std::remove(j.path.c_str());
    return rc;
}

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

std::vector<int> ints(JNIEnv* env, jintArray a) {
    std::vector<int> v;
    if (!a) return v;
    const jsize n = env->GetArrayLength(a);
    v.resize((size_t) n);
    if (n) env->GetIntArrayRegion(a, 0, n, reinterpret_cast<jint*>(v.data()));
    return v;
}

}  // namespace

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_fox_foxiru_foxcat_fox2d_jnicallers_NativeExporter_##name

FN(jstring, nativeEncoders)(JNIEnv* env, jclass) {
    static const char* names[] = {"libx264", "libx265", "h264_mediacodec", "hevc_mediacodec", "libopenh264", "mpeg4", "aac"};
    std::string out;
    for (const char* n : names) {
        if (avcodec_find_encoder_by_name(n)) { if (!out.empty()) out += ','; out += n; }
    }
    if (av_guess_format("mp4", nullptr, nullptr)) { if (!out.empty()) out += ','; out += "mux:mp4"; }
    return env->NewStringUTF(out.c_str());
}

FN(jlong, nativeCreate)(JNIEnv* env, jclass, jstring path, jint side, jint fps, jint srcFps, jint bg,
                        jstring codec, jfloat crf, jint videoKbps, jstring preset, jint audioKbps, jstring extra,
                        jintArray celIds, jintArray celLens, jintArray celRows, jintArray rowStarts, jfloatArray rowXf, jintArray rowKeyCounts, jfloatArray rowKeys, jfloatArray rigBlob, jintArray rowAttach, jintArray layerIds, jbooleanArray layerVis,
                        jintArray strokeMeta, jfloatArray strokeSize, jfloatArray strokePts) {
#ifdef FOX_AVJNI
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) == JNI_OK && vm) av_jni_set_java_vm(vm, nullptr);
#endif
    auto job = std::make_shared<Job>();
    job->path = str(env, path);
    job->codec = str(env, codec);
    job->preset = str(env, preset);
    job->extra = str(env, extra);
    job->side = side;
    job->fps = fps;
    job->srcFps = srcFps;
    job->bg = (uint32_t) bg;
    job->crf = crf;
    job->videoKbps = videoKbps;
    job->audioKbps = audioKbps;
    job->celIds = ints(env, celIds);
    job->celLens = ints(env, celLens);
    job->celRows = ints(env, celRows);
    job->rowStarts = ints(env, rowStarts);
    if (rowXf) {
        const jsize n = env->GetArrayLength(rowXf);
        job->rowXf.resize((size_t) n);
        if (n) env->GetFloatArrayRegion(rowXf, 0, n, job->rowXf.data());
    }
    job->rowKeyCounts = ints(env, rowKeyCounts);
    if (rowKeys) {
        const jsize n = env->GetArrayLength(rowKeys);
        job->rowKeys.resize((size_t) n);
        if (n) env->GetFloatArrayRegion(rowKeys, 0, n, job->rowKeys.data());
    }
    job->rowAttach = ints(env, rowAttach);
    if (rigBlob) {
        const jsize n = env->GetArrayLength(rigBlob);
        job->rigBlob.resize((size_t) n);
        if (n) env->GetFloatArrayRegion(rigBlob, 0, n, job->rigBlob.data());
    }
    job->layerIds = ints(env, layerIds);
    if (layerVis) {
        const jsize n = env->GetArrayLength(layerVis);
        job->layerVis.resize((size_t) n);
        if (n) env->GetBooleanArrayRegion(layerVis, 0, n, reinterpret_cast<jboolean*>(job->layerVis.data()));
    }
    if (job->layerVis.size() != job->layerIds.size()) return 0;

    const std::vector<int> meta = ints(env, strokeMeta);
    const jsize nStrokes = (jsize) (meta.size() / 5);
    if (strokeSize && env->GetArrayLength(strokeSize) < nStrokes) return 0;
    std::vector<float> sizes((size_t) nStrokes);
    if (nStrokes) env->GetFloatArrayRegion(strokeSize, 0, nStrokes, sizes.data());
    const jsize ptsLen = strokePts ? env->GetArrayLength(strokePts) : 0;
    std::vector<float> pts((size_t) ptsLen);
    if (ptsLen) env->GetFloatArrayRegion(strokePts, 0, ptsLen, pts.data());

    size_t off = 0;
    job->strokes.reserve((size_t) nStrokes);
    for (jsize i = 0; i < nStrokes; i++) {
        StrokeData s;
        s.cel = meta[(size_t) i * 5];
        s.layer = meta[(size_t) i * 5 + 1];
        s.argb = (uint32_t) meta[(size_t) i * 5 + 2];
        s.erase = meta[(size_t) i * 5 + 3] != 0;
        const size_t cnt = (size_t) std::max(0, meta[(size_t) i * 5 + 4]) * 2;
        if (off + cnt > pts.size()) return 0;
        s.size = sizes[(size_t) i];
        s.pts.assign(pts.begin() + (ptrdiff_t) off, pts.begin() + (ptrdiff_t) (off + cnt));
        off += cnt;
        job->strokes.push_back(std::move(s));
    }

    std::lock_guard<std::mutex> l(gMu);
    const jlong h = gNext++;
    gJobs[h] = std::move(job);
    return h;
}

FN(jint, nativeRun)(JNIEnv*, jclass, jlong h) {
    auto j = findJob(h);
    if (!j) return -100;
    return runJob(*j);
}

FN(jfloat, nativeProgress)(JNIEnv*, jclass, jlong h) {
    auto j = findJob(h);
    if (!j) return 0.f;
    const int t = std::max(1, j->total.load());
    return std::min(1.f, (float) j->done.load() / (float) t);
}

FN(void, nativeCancel)(JNIEnv*, jclass, jlong h) {
    if (auto j = findJob(h)) j->cancel = true;
}

FN(jstring, nativeError)(JNIEnv* env, jclass, jlong h) {
    auto j = findJob(h);
    return env->NewStringUTF(j ? j->getError().c_str() : "");
}

FN(jstring, nativeEncoderUsed)(JNIEnv* env, jclass, jlong h) {
    auto j = findJob(h);
    return env->NewStringUTF(j ? j->getEncoder().c_str() : "");
}

FN(jstring, nativeWarning)(JNIEnv* env, jclass, jlong h) {
    auto j = findJob(h);
    return env->NewStringUTF(j ? j->getWarning().c_str() : "");
}

FN(void, nativeDestroy)(JNIEnv*, jclass, jlong h) {
    std::lock_guard<std::mutex> l(gMu);
    gJobs.erase(h);
}
