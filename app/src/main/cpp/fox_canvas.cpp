// canvas_gl.cpp - native GLES3 canvas for the fox2d editor.
//
// Threading:
//   * UI thread  : JNI setters only push into a mutex-guarded command queue (cheap, no GL).
//   * GL thread  : nativeSurfaceCreated / nativeSurfaceChanged / nativeDrawFrame. Every command is
//                  drained and executed inside nativeDrawFrame, so all history/GL state is single-threaded.
//
// Rendering:
//   * One RGBA8 texture + FBO per NON-EMPTY layer (paper resolution, kPaperDefault^2). Layers are one per
//     (timeline row, drawing layer); textures are created lazily and freed again when nothing is shown on them. Strokes are rasterised
//     INCREMENTALLY into the layer FBO (only the new segments each frame), never re-drawn from scratch
//     while drawing. Eraser = blend (ZERO, 1-srcA) on the same FBO.
//   * Each segment is a screen-aligned quad; the fragment shader evaluates a capsule SDF -> round caps
//     and 1px antialiasing for free. Catmull-Rom subdivision makes the polyline smooth.
//   * The screen pass draws paper (shadow + rounded corners + checker) then every visible layer with the
//     pan/zoom/rotate transform applied in the vertex shader, so gestures never touch layer pixels.
//
// Rigging: rows that carry a rig (fox_rig.h) are drawn as a deformed grid MESH instead of one quad. The mesh is
// rebuilt on the GL thread only when the time / rig changed (skinning + curve bending on the CPU, ~2-10k vertices),
// uploaded to a per-row VBO and drawn once per layer of the row. Row keyframes (LayerXf) still apply on top.
//
// Math: GLM (header-only). The pan/zoom/rotate transform is built once per frame on the CPU as a single
// mat4 (ortho * T(centre) * R * S * T(-side/2)) and the vertex shader is one mat4 * vec4. Vertex data is a
// tightly packed `Vert` (11 floats, same GL layout as before) built from glm::vec2/vec4.
//
// Paper space matches the Compose code: points are normalised 0..1, brush size is in 512-units.

#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <type_traits>
#include <unordered_set>
#include <utility>
#include <vector>

#include "fox_anim.h"
#include "fox_rig.h"

#define TAG "FoxCanvas"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kPaperDefault = 2048;
constexpr float kBrushSpace = 512.f;         // InkStroke.size is expressed in a 512-unit paper
constexpr float kDeg2Rad = 0.017453292f;
constexpr size_t kFlushVerts = 6 * 6000;     // flush the batch once it holds this many vertices
constexpr size_t kBatchSlack = 6 * 32 + 64;  // one Catmull-Rom span is at most 32 segments (6 verts each)

// ------------------------------------------------------------------------------------------ data

// One vertex of a segment quad. Layout == the old 11-float interleave: pos2 seg4 radius1 color4.
struct Vert {
    glm::vec2 pos;   // paper px
    glm::vec4 seg;   // a.xy, b.xy (paper px)
    float r;         // radius (paper px)
    glm::vec4 col;   // straight rgb + a
};
static_assert(sizeof(Vert) == 11 * sizeof(float), "Vert must be tightly packed");
static_assert(std::is_standard_layout_v<Vert>, "Vert must be standard layout (offsetof)");

struct Stroke {
    int cel = 0, layer = 0;
    uint32_t argb = 0xFF000000u;
    float size = 10.f;
    bool erase = false;
    std::vector<float> pts;   // xy pairs, paper-normalised
    size_t emitted = 0;       // segments already rasterised (live stroke only)
};

enum class Op { Begin, Point, End, Cancel, Undo, Redo, SetShown, SetLayers, SetXf, AddStroke, ResetStrokes, SetAnim, SetTime, SetRig, SetAttach };

struct Cmd {
    Op op{};
    int i0 = 0, i1 = 0;
    uint32_t argb = 0;
    float f0 = 0, f1 = 0;
    bool b = false;
    std::vector<float> pts;
    std::vector<int> ids;
    std::vector<uint8_t> vis;
};

struct ViewState {
    float scale = 1.f, rot = 0.f;
    glm::vec2 off{0.f};
    uint32_t bg = 0xFF101010u, ca = 0xFF303030u, cb = 0xFF383838u;
    float density = 1.f;
};

// Batch of segment quads. One persistent instance (g.emit): keeps its capacity between frames, so the
// live-stroke path does no per-frame heap traffic. Always flushed empty at the end of every use.
struct Emitter {
    std::vector<Vert> v;
    bool erase = false;
    Emitter() { v.reserve(kFlushVerts + kBatchSlack); }
};

// shared between threads
std::mutex gMu;
std::vector<Cmd> gQueue;
ViewState gView;

// Rig mesh snapshot for the UI thread (touch -> rest position while a rig is posed). Written by the GL thread.
struct PubRig {
    bool valid = false;
    int grid = 0;
    std::vector<float> verts;   // x y u v per vertex (fox::rig::buildMesh)
};
std::vector<PubRig> gPub;                 // guarded by gMu
std::vector<fox::rig::Aff> gPubAtt;       // guarded by gMu: per-row attachment matrix (paper units) as drawn
std::atomic<bool> gRigPosed{true};        // false: rigged rows are shown in their REST pose (rig editing)

// ---- GL-thread-only state -------------------------------------------------------------------

// Whole-layer transform in PAPER units (paper = 0..1, y down). Shown position of a layer point p:
//   pivot + t + R(rot clockwise) * S * (p - pivot)
struct LayerXf {
    float tx = 0.f, ty = 0.f, sx = 1.f, sy = 1.f, rot = 0.f, px = 0.5f, py = 0.5f;
};

struct LayerGL {
    int id = 0;
    bool visible = true;
    GLuint tex = 0, fbo = 0;
    LayerXf xf;   // all layers of one timeline row share the same transform (set from Kotlin)
};

struct PaperProg {
    GLuint p = 0;
    GLint uMVP = -1, uScale = -1, uSide = -1, uMargin = -1, uRadius = -1, uTile = -1, uShadow = -1,
          uCA = -1, uCB = -1, uTex = -1, uPos = -1, uGrid = -1, uSub = -1, uRect = -1;
};

// One timeline row's animation: key list (fox::anim flat layout, row-local frames) + the static transform that
// supplies the pivot and is used while the row has no keys. [offset] = timeline frame where the row starts.
struct RowAnim {
    int offset = 0;
    fox::anim::Xf base;
    std::vector<float> keys;
};

// One timeline row's rig: parsed data, evaluated mesh and its GL buffers.
struct RigGL {
    fox::rig::Rig rig;
    fox::rig::Pose pose;
    std::vector<float> chv, verts;   // channel values, x y u v per vertex
    GLuint vao = 0, vbo = 0, ibo = 0;
    GLuint posTex = 0;           // RG32F (grid+1)^2: shown position of every grid vertex (read by the smoothing vertex shader)
    std::vector<float> posTmp;
    bool uploaded = false;
};

struct GLState {
    bool ready = false;
    bool rebuildAll = false;
    int W = 1, H = 1, T = kPaperDefault;
    GLuint strokeProg = 0, uT = 0;
    PaperProg paper, layer, mesh;
    std::vector<RigGL> rigs;   // row r <-> rigs[r] (may be shorter than rows)
    std::vector<int> attach;   // 2 ints per row: parent row, bone (-1 = not attached)
    std::vector<fox::rig::Aff> attM;   // per row, from applyAnim; identity when not attached
    GLuint vao = 0, vbo = 0;
    std::vector<LayerGL> layers;
    std::vector<Stroke> history, redo;
    std::optional<Stroke> active;
    std::unordered_set<int> shown;   // cel ids currently on screen (one per timeline row)
    Emitter emit;

    // keyframe animation: row r drives native layers [r * perRow, (r + 1) * perRow)
    std::vector<RowAnim> rows;
    int perRow = 1;
    float curFrame = 0.f;   // timeline frame (fractional ok)
    bool animDirty = false;
} g;

// ------------------------------------------------------------------------------------------ shaders

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
    o = vec4(vC.rgb * al, al);          // premultiplied
}
)";

// Paper + layer quads. Local space = paper px at scale 1, origin top-left of the paper.
// uMVP (built with GLM on the CPU) maps local paper px straight to clip space:
//   ortho(y-down px) * translate(paper centre) * rotate(clockwise, y down) * scale * translate(-side/2)
const char* kQuadVS = R"(#version 300 es
uniform mat4 uMVP;
uniform float uSide;     // paper side in local px
uniform float uMargin;   // extra local px around the paper (shadow)
out vec2 vLocal;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 local = mix(vec2(-uMargin), vec2(uSide + uMargin), c);
    vLocal = local;
    gl_Position = uMVP * vec4(local, 0.0, 1.0);
}
)";

// Deformed grid mesh. aPos = shown position in PAPER units (0..1), aUV = rest position = texel to sample. vLocal is
// the rest position in layer px, so the layer fragment shader below clips / samples it exactly like the plain quad.

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

const char* kMeshVSHead = R"(#version 300 es
precision highp float;
precision highp int;
uniform mat4 uMVP;
uniform float uSide;     // paper side in local px
uniform float uMargin;
out vec2 vLocal;
)";
const char* kMeshVSMain = R"(
void main() {
    vec2 rest;
    vec2 pos = smoothVertex(rest);
    vLocal = rest * uSide;
    gl_Position = uMVP * vec4(pos * uSide, 0.0, 1.0);
}
)";

const char* kSdf = R"(
uniform float uSide, uScale, uRadius;
float sdRound(vec2 p) {
    vec2 q = abs(p - 0.5 * uSide) - (0.5 * uSide - uRadius);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
}
)";

const char* kPaperFS = R"(#version 300 es
precision highp float;
in vec2 vLocal;
uniform float uTile, uShadow;
uniform vec4 uCA, uCB;
out vec4 o;
)";  // + kSdf + kPaperMain

const char* kPaperMain = R"(
void main() {
    float d = sdRound(vLocal) * uScale;                 // screen px, < 0 inside
    float cov = clamp(0.5 - d, 0.0, 1.0);
    vec2 cell = floor(vLocal / uTile);
    vec4 paper = (mod(cell.x + cell.y, 2.0) < 0.5) ? uCA : uCB;
    float sh = (1.0 - smoothstep(0.0, uShadow, max(d, 0.0))) * 0.28;
    o = vec4(paper.rgb * cov, cov) + vec4(0.0, 0.0, 0.0, sh) * (1.0 - cov);
}
)";

const char* kLayerFS = R"(#version 300 es
precision highp float;
in vec2 vLocal;
uniform sampler2D uTex;
out vec4 o;
)";  // + kSdf + kLayerMain

const char* kLayerMain = R"(
void main() {
    float d = sdRound(vLocal) * uScale;
    float cov = clamp(0.5 - d, 0.0, 1.0);
    o = texture(uTex, vLocal / uSide) * cov;
}
)";

GLuint compile(GLenum type, const std::string& src) {
    GLuint s = glCreateShader(type);
    const char* c = src.c_str();
    glShaderSource(s, 1, &c, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader compile failed: %s", log);
    }
    return s;
}

GLuint link(const std::string& vs, const std::string& fs) {
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        LOGE("program link failed: %s", log);
    }
    glDeleteShader(v);
    glDeleteShader(f);
    return p;
}

void locate(PaperProg& q) {
    auto u = [&](const char* n) { return glGetUniformLocation(q.p, n); };
    q.uMVP = u("uMVP"); q.uScale = u("uScale");
    q.uSide = u("uSide"); q.uMargin = u("uMargin"); q.uRadius = u("uRadius"); q.uTile = u("uTile");
    q.uShadow = u("uShadow"); q.uCA = u("uCA"); q.uCB = u("uCB"); q.uTex = u("uTex");
    q.uPos = u("uPos"); q.uGrid = u("uGrid"); q.uSub = u("uSub"); q.uRect = u("uRect");
}

// 0xAARRGGBB -> (r, g, b, a) in 0..1. Divides by 255 (not * 1/255) to stay bit-identical with the old scalar code.
glm::vec4 unpackArgb(uint32_t c) {
    return glm::vec4((float) ((c >> 16) & 255u), (float) ((c >> 8) & 255u),
                     (float) (c & 255u), (float) (c >> 24)) / 255.f;
}

// ------------------------------------------------------------------------------------------ layers

void createLayerGL(LayerGL& L) {
    glGenTextures(1, &L.tex);
    glBindTexture(GL_TEXTURE_2D, L.tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, g.T, g.T);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &L.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, L.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, L.tex, 0);
    glViewport(0, 0, g.T, g.T);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
}

void destroyLayerGL(LayerGL& L) {
    if (L.fbo) glDeleteFramebuffers(1, &L.fbo);
    if (L.tex) glDeleteTextures(1, &L.tex);
    L.fbo = L.tex = 0;
}

bool isShown(int cel) { return g.shown.count(cel) != 0; }

LayerGL* findLayer(int id) {
    for (auto& L : g.layers) if (L.id == id) return &L;
    return nullptr;
}

LayerGL& getLayer(int id) {
    for (auto& L : g.layers) if (L.id == id) {
        if (!L.tex) createLayerGL(L);
        return L;
    }
    g.layers.push_back(LayerGL{id});
    createLayerGL(g.layers.back());
    return g.layers.back();
}

void bindLayer(LayerGL& L) {
    glBindFramebuffer(GL_FRAMEBUFFER, L.fbo);
    glViewport(0, 0, g.T, g.T);
}

void clearLayer(LayerGL& L) {
    bindLayer(L);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
}

// ------------------------------------------------------------------------------------------ strokes

// Two triangles covering the segment's bounding box grown by the radius (+1.5px AA margin).
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

void flush(Emitter& e, LayerGL& L) {
    if (e.v.empty()) return;
    bindLayer(L);
    glUseProgram(g.strokeProg);
    glUniform1f(g.uT, (float) g.T);
    glEnable(GL_BLEND);
    if (e.erase) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(g.vao);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (e.v.size() * sizeof(Vert)), e.v.data(), GL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, (GLsizei) e.v.size());
    e.v.clear();   // keeps capacity
}

// Uniform Catmull-Rom between p1 and p2 (paper px), subdivided to ~3px steps.
// q(t) = 0.5 * (2 p1 + (p2 - p0) t + (2 p0 - 5 p1 + 4 p2 - p3) t^2 + (3 p1 - p0 - 3 p2 + p3) t^3)
void emitCR(Emitter& e, glm::vec2 p0, glm::vec2 p1, glm::vec2 p2, glm::vec2 p3, float r, const glm::vec4& c) {
    const int n = std::clamp((int) std::ceil(glm::distance(p1, p2) / 3.f), 1, 32);
    const glm::vec2 k1 = -p0 + p2;
    const glm::vec2 k2 = 2.f * p0 - 5.f * p1 + 4.f * p2 - p3;
    const glm::vec2 k3 = -p0 + 3.f * p1 - 3.f * p2 + p3;
    glm::vec2 prev = p1;
    for (int k = 1; k <= n; k++) {
        const float t = (float) k / n, t2 = t * t, t3 = t2 * t;
        const glm::vec2 q = 0.5f * (2.f * p1 + k1 * t + k2 * t2 + k3 * t3);
        pushSeg(e.v, prev, q, r, c);
        prev = q;
    }
}

// Rasterise the not-yet-emitted part of a stroke. `finish` also emits the tail segment / a lone dot.
// Deterministic, so replay after undo/cel switch gives pixel-identical results to live drawing.
void rasterStroke(const Stroke& s, size_t& emitted, bool finish, Emitter& e, LayerGL& L) {
    if (e.erase != s.erase) { flush(e, L); e.erase = s.erase; }
    const size_t n = s.pts.size() / 2;
    if (n == 0) return;
    const float T = (float) g.T;
    const float r = s.size * T / kBrushSpace * 0.5f;
    const glm::vec4 c = unpackArgb(s.argb);
    auto P = [&](size_t i) { return glm::vec2(s.pts[i * 2], s.pts[i * 2 + 1]) * T; };

    if (n == 1) {
        if (finish && emitted == 0) {
            const glm::vec2 p = P(0);
            pushSeg(e.v, p, p, r, c);
            emitted = 1;
        }
    } else {
        while (emitted + 1 < n) {
            const size_t j = emitted;
            if (!finish && j + 2 >= n) break;      // need one look-ahead point for the tangent
            emitCR(e, P(j == 0 ? 0 : j - 1), P(j), P(j + 1), P(std::min(j + 2, n - 1)), r, c);
            emitted++;
            if (e.v.size() >= kFlushVerts) flush(e, L);
        }
    }
    if (e.v.size() >= kFlushVerts) flush(e, L);
}

void drawWhole(const Stroke& s, Emitter& e, LayerGL& L) {
    size_t em = 0;
    rasterStroke(s, em, true, e, L);
}

// Redraw one layer from history: only strokes whose drawing (cel) is on screen. A layer with nothing to show
// gives its texture back, so empty (row, layer) pairs cost no GPU memory.
void rebuildLayer(int id) {
    LayerGL* p = findLayer(id);
    if (!p) return;
    bool any = false;
    for (auto& s : g.history) if (s.layer == id && isShown(s.cel)) { any = true; break; }
    if (!any) { destroyLayerGL(*p); return; }
    LayerGL& L = getLayer(id);
    clearLayer(L);
    Emitter& e = g.emit;
    for (auto& s : g.history) if (s.layer == id && isShown(s.cel)) drawWhole(s, e, L);
    flush(e, L);
}

void rebuildAllLayers() {
    for (auto& L : g.layers) rebuildLayer(L.id);
    g.rebuildAll = false;
}

// ------------------------------------------------------------------------------------------ commands

void applyLayers(const Cmd& c) {
    std::vector<LayerGL> next;
    for (size_t i = 0; i < c.ids.size(); i++) {
        LayerGL L{c.ids[i]};
        for (auto& o : g.layers) if (o.id == L.id) { L = o; break; }
        L.visible = c.vis[i] != 0;
        next.push_back(L);
    }
    for (auto& o : g.layers) {
        bool kept = std::any_of(next.begin(), next.end(), [&](const LayerGL& l) { return l.id == o.id; });
        if (!kept) destroyLayerGL(o);
    }
    g.layers = std::move(next);   // textures are created on demand by getLayer / rebuildLayer
}

// c.ids = native layer ids, c.pts = 7 floats per id: tx, ty, sx, sy, rotDeg, pivotX, pivotY
void applyXf(const Cmd& c) {
    for (size_t i = 0; i < c.ids.size() && (i + 1) * 7 <= c.pts.size(); i++) {
        LayerGL* L = findLayer(c.ids[i]);
        if (!L) continue;
        const float* v = &c.pts[i * 7];
        L->xf = LayerXf{v[0], v[1], v[2], v[3], v[4], v[5], v[6]};
    }
}

// ------------------------------------------------------------------------------------------ rigs

void destroyRigGL(RigGL& R) {
    if (R.vbo) glDeleteBuffers(1, &R.vbo);
    if (R.ibo) glDeleteBuffers(1, &R.ibo);
    if (R.vao) glDeleteVertexArrays(1, &R.vao);
    if (R.posTex) glDeleteTextures(1, &R.posTex);
    R.vbo = R.ibo = R.vao = 0;
    R.posTex = 0;
    R.uploaded = false;
}

void ensureRigGL(RigGL& R) {
    if (R.vao || !R.rig.valid || R.rig.idx.empty()) return;
    glGenVertexArrays(1, &R.vao);
    glGenBuffers(1, &R.vbo);
    glGenBuffers(1, &R.ibo);
    glBindVertexArray(R.vao);
    glBindBuffer(GL_ARRAY_BUFFER, R.vbo);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (R.rig.rest.size() * 4 * sizeof(float)), nullptr, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) 0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*) (2 * sizeof(float)));
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, R.ibo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, (GLsizeiptr) (R.rig.idx.size() * sizeof(uint32_t)), R.rig.idx.data(), GL_STATIC_DRAW);
    glBindVertexArray(0);
    const int W = R.rig.grid + 1;
    glGenTextures(1, &R.posTex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, R.posTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RG32F, W, W, 0, GL_RG, GL_FLOAT, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);   // read with texelFetch: exact values, no filtering
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glActiveTexture(GL_TEXTURE0);
    R.uploaded = false;
}

// Grid positions -> the RG32F texture the mesh shader reads.
void uploadRig(RigGL& R) {
    if (!R.posTex || R.verts.empty()) return;
    const size_t n = R.verts.size() / 4;
    R.posTmp.resize(n * 2);
    for (size_t i = 0; i < n; i++) { R.posTmp[i * 2] = R.verts[i * 4]; R.posTmp[i * 2 + 1] = R.verts[i * 4 + 1]; }
    const int W = R.rig.grid + 1;
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, R.posTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, W, GL_RG, GL_FLOAT, R.posTmp.data());
    glActiveTexture(GL_TEXTURE0);
    R.uploaded = true;
}

// Re-evaluate every rigged row's mesh at the current time and push it to the GPU + the UI snapshot.
void evalRigs() {
    for (size_t r = 0; r < g.rigs.size(); r++) {
        RigGL& R = g.rigs[r];
        if (!R.rig.valid) continue;
        const int off = r < g.rows.size() ? g.rows[r].offset : 0;
        fox::rig::channelValues(R.rig, g.curFrame - (float) off, R.chv);
        fox::rig::buildMesh(R.rig, R.chv.data(), R.verts, R.pose);
        ensureRigGL(R);
        if (R.vao) {
            uploadRig(R);
        }
        std::lock_guard<std::mutex> l(gMu);
        if (gPub.size() <= r) gPub.resize(r + 1);
        gPub[r].valid = true;
        gPub[r].grid = R.rig.grid;
        gPub[r].verts = R.verts;
    }
}

// Evaluate every row's curve at g.curFrame and hand the result to its layers. Runs on the GL thread once per frame,
// only when the time, the key tables or the layer table changed.
void applyAnim() {
    g.animDirty = false;
    std::vector<fox::anim::Xf> rowXfs(g.rows.size());
    for (size_t r = 0; r < g.rows.size(); r++) {
        const RowAnim& R = g.rows[r];
        const int n = (int) (R.keys.size() / fox::anim::kKeyStride);
        const fox::anim::Xf x = fox::anim::evalRow(R.keys.data(), n, g.curFrame - (float) R.offset, R.base);
        rowXfs[r] = x;
        for (int li = 0; li < g.perRow; li++) {
            LayerGL* L = findLayer((int) r * g.perRow + li);
            if (L) L->xf = LayerXf{x.tx, x.ty, x.sx, x.sy, x.rot, x.px, x.py};
        }
    }
    evalRigs();

    // rows attached to a bone of another row follow it (rigid): one paper-space matrix per row
    const size_t N = g.rows.size();
    std::vector<int> par(N, -1), bn(N, -1);
    for (size_t r = 0; r < N && (r + 1) * 2 <= g.attach.size(); r++) { par[r] = g.attach[r * 2]; bn[r] = g.attach[r * 2 + 1]; }
    std::vector<const fox::rig::Pose*> poses(N, nullptr);
    for (size_t r = 0; r < N && r < g.rigs.size(); r++) if (g.rigs[r].rig.valid && !g.rigs[r].verts.empty()) poses[r] = &g.rigs[r].pose;
    fox::rig::attachMatrices(par.data(), bn.data(), N, rowXfs, poses, g.attM);
    {
        std::lock_guard<std::mutex> l(gMu);
        gPubAtt = g.attM;
    }
}

// c.i0 = layers per row; c.ids = [offset x N, keyCount x N]; c.pts = [static xf (7) x N, then all keys (11 floats each)]
void applyAnimCmd(const Cmd& c) {
    const size_t N = c.ids.size() / 2;
    g.perRow = std::max(1, c.i0);
    g.rows.assign(N, RowAnim{});
    size_t ko = N * fox::anim::kXfStride;
    for (size_t r = 0; r < N; r++) {
        RowAnim& R = g.rows[r];
        R.offset = c.ids[r];
        if ((r + 1) * fox::anim::kXfStride <= c.pts.size()) R.base = fox::anim::readXf(&c.pts[r * fox::anim::kXfStride]);
        const size_t cnt = (size_t) std::max(0, c.ids[N + r]);
        const size_t floats = cnt * fox::anim::kKeyStride;
        if (ko + floats <= c.pts.size()) R.keys.assign(c.pts.begin() + (ptrdiff_t) ko, c.pts.begin() + (ptrdiff_t) (ko + floats));
        ko += floats;
    }
    g.animDirty = true;
}

// c.pts = fox::rig set blob: [nRows, (len, floats) per row]. GL objects of rows that keep a rig are reused.
void applyRigCmd(const Cmd& c) {
    std::vector<fox::rig::Rig> next;
    fox::rig::parseSet(c.pts.data(), c.pts.size(), next);
    std::vector<RigGL> rigs(next.size());
    for (size_t r = 0; r < next.size(); r++) {
        const fox::rig::Rig* prev = r < g.rigs.size() && g.rigs[r].rig.valid ? &g.rigs[r].rig : nullptr;
        if (next[r].valid) fox::rig::prepare(next[r], prev, true);
        else fox::rig::prepareBones(next[r]);
        rigs[r].rig = std::move(next[r]);
        if (r < g.rigs.size()) {
            RigGL& o = g.rigs[r];
            const bool sameMesh = o.vao && rigs[r].rig.valid && o.rig.valid && o.rig.rest.size() == rigs[r].rig.rest.size();
            if (sameMesh) {
                // same vertex count: keep the buffers, only the index list may differ (grid is identical => same too)
                rigs[r].vao = o.vao; rigs[r].vbo = o.vbo; rigs[r].ibo = o.ibo; rigs[r].posTex = o.posTex;
                o.vao = o.vbo = o.ibo = 0; o.posTex = 0;
            }
        }
    }
    for (auto& o : g.rigs) destroyRigGL(o);
    g.rigs = std::move(rigs);
    {
        std::lock_guard<std::mutex> l(gMu);
        gPub.assign(g.rigs.size(), PubRig{});
    }
    g.animDirty = true;
}

// c.ids = [parentRow, bone] x N rows
void applyAttachCmd(const Cmd& c) {
    g.attach = c.ids;
    g.animDirty = true;
}

void runCommands() {
    std::vector<Cmd> local;
    {
        std::lock_guard<std::mutex> l(gMu);
        local.swap(gQueue);
    }
    Emitter& e = g.emit;
    for (auto& c : local) {
        switch (c.op) {
            case Op::Begin: {
                if (g.active) rebuildLayer(g.active->layer);
                Stroke s;
                s.cel = c.i0; s.layer = c.i1; s.argb = c.argb; s.size = c.f0; s.erase = c.b;
                g.active = std::move(s);
                break;
            }
            case Op::Point:
                if (g.active) { g.active->pts.push_back(c.f0); g.active->pts.push_back(c.f1); }
                break;
            case Op::End:
                if (g.active) {
                    LayerGL& L = getLayer(g.active->layer);
                    rasterStroke(*g.active, g.active->emitted, true, e, L);
                    flush(e, L);
                    g.history.push_back(std::move(*g.active));
                    g.redo.clear();
                    g.active.reset();
                }
                break;
            case Op::Cancel:
                if (g.active) { int l = g.active->layer; g.active.reset(); rebuildLayer(l); }
                break;
            case Op::Undo:
                if (!g.history.empty()) {
                    Stroke s = std::move(g.history.back());
                    g.history.pop_back();
                    const int layer = s.layer;
                    const bool visible = isShown(s.cel);
                    g.redo.push_back(std::move(s));
                    if (visible) rebuildLayer(layer);
                }
                break;
            case Op::Redo:
                if (!g.redo.empty()) {
                    Stroke s = std::move(g.redo.back());
                    g.redo.pop_back();
                    if (isShown(s.cel)) {
                        LayerGL& L = getLayer(s.layer);
                        drawWhole(s, e, L);
                        flush(e, L);
                    }
                    g.history.push_back(std::move(s));
                }
                break;
            case Op::SetShown: {
                // Only the layers holding strokes of a drawing that appeared / disappeared are redrawn;
                // rows that keep showing the same drawing are not touched.
                std::unordered_set<int> next(c.ids.begin(), c.ids.end());
                std::unordered_set<int> dirty;
                for (auto& s : g.history) {
                    if ((g.shown.count(s.cel) != 0) != (next.count(s.cel) != 0)) dirty.insert(s.layer);
                }
                g.shown = std::move(next);
                for (int id : dirty) rebuildLayer(id);
                break;
            }
            case Op::SetLayers:
                applyLayers(c);
                g.animDirty = true;   // new layers start with the default transform
                break;
            case Op::SetAnim:
                applyAnimCmd(c);
                break;
            case Op::SetRig:
                applyRigCmd(c);
                break;
            case Op::SetAttach:
                applyAttachCmd(c);
                break;
            case Op::SetTime:
                if (c.f0 != g.curFrame) { g.curFrame = c.f0; g.animDirty = true; }
                break;
            case Op::SetXf:
                applyXf(c);
                break;
            case Op::AddStroke: {
                Stroke s;
                s.cel = c.i0; s.layer = c.i1; s.argb = c.argb; s.size = c.f0; s.erase = c.b;
                s.pts = std::move(c.pts);
                if (isShown(s.cel)) {
                    LayerGL& L = getLayer(s.layer);
                    drawWhole(s, e, L);
                    flush(e, L);
                }
                g.history.push_back(std::move(s));
                break;
            }
            case Op::ResetStrokes:
                g.history.clear(); g.redo.clear(); g.active.reset();
                for (auto& L : g.layers) destroyLayerGL(L);
                break;
        }
    }
    // live stroke: emit whatever arrived this frame, in one batch
    if (g.active) {
        LayerGL& L = getLayer(g.active->layer);
        rasterStroke(*g.active, g.active->emitted, false, e, L);
        flush(e, L);
    }
}

void push(Cmd&& c) {
    std::lock_guard<std::mutex> l(gMu);
    gQueue.push_back(std::move(c));
}

// ------------------------------------------------------------------------------------------ frame

// Paper-local px -> clip space. Screen space is y-down px; rotation is clockwise-positive (degrees).
glm::mat4 quadMVP(const ViewState& v, float side) {
    const glm::mat4 proj = glm::ortho(0.f, (float) g.W, (float) g.H, 0.f);
    glm::mat4 m = glm::translate(proj, glm::vec3(g.W * 0.5f + v.off.x, g.H * 0.5f + v.off.y, 0.f));
    m = glm::rotate(m, v.rot * kDeg2Rad, glm::vec3(0.f, 0.f, 1.f));
    m = glm::scale(m, glm::vec3(v.scale, v.scale, 1.f));
    return glm::translate(m, glm::vec3(-0.5f * side, -0.5f * side, 0.f));
}

// Layer-local px -> layer-local px (where the layer is shown). Applied to the quad corners only; vLocal keeps
// pointing at the source texel, so the layer content is simply moved / rotated / scaled as a picture.
glm::mat4 layerMatrix(const LayerXf& x, float side) {
    glm::mat4 m(1.f);
    m = glm::translate(m, glm::vec3((x.px + x.tx) * side, (x.py + x.ty) * side, 0.f));
    m = glm::rotate(m, x.rot * kDeg2Rad, glm::vec3(0.f, 0.f, 1.f));
    m = glm::scale(m, glm::vec3(x.sx, x.sy, 1.f));
    return glm::translate(m, glm::vec3(-x.px * side, -x.py * side, 0.f));
}

void setQuadUniforms(const PaperProg& q, const ViewState& v, const glm::mat4& mvp, float side, float margin) {
    glUniformMatrix4fv(q.uMVP, 1, GL_FALSE, glm::value_ptr(mvp));
    glUniform1f(q.uScale, v.scale);
    glUniform1f(q.uSide, side);
    glUniform1f(q.uMargin, margin);
    glUniform1f(q.uRadius, 0.f);   // square paper corners
}

void drawScreen() {
    ViewState v;
    {
        std::lock_guard<std::mutex> l(gMu);
        v = gView;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, g.W, g.H);
    const glm::vec4 bg = unpackArgb(v.bg);
    glClearColor(bg.x, bg.y, bg.z, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

    const float side = (float) std::min(g.W, g.H);
    const float shadowPx = 10.f * v.density;
    const glm::mat4 mvp = quadMVP(v, side);   // shared by the paper and every layer quad

    glUseProgram(g.paper.p);
    setQuadUniforms(g.paper, v, mvp, side, shadowPx / v.scale + 2.f);
    glUniform1f(g.paper.uTile, 14.f * v.density);
    glUniform1f(g.paper.uShadow, shadowPx);
    const glm::vec4 ca = unpackArgb(v.ca), cb = unpackArgb(v.cb);
    glUniform4fv(g.paper.uCA, 1, glm::value_ptr(ca));
    glUniform4fv(g.paper.uCB, 1, glm::value_ptr(cb));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glUseProgram(g.layer.p);
    setQuadUniforms(g.layer, v, mvp, side, 0.f);
    glUniform1i(g.layer.uTex, 0);
    glActiveTexture(GL_TEXTURE0);
    const bool posed = gRigPosed.load();
    bool meshBound = false;
    for (auto& L : g.layers) {
        if (!L.visible || !L.tex) continue;
        const int row = g.perRow > 0 ? L.id / g.perRow : 0;
        glm::mat4 am(1.f);   // attachment to another row's bone (not while rigs are shown at rest)
        if (posed && row >= 0 && (size_t) row < g.attM.size()) {
            const fox::rig::Aff& a = g.attM[(size_t) row];
            am[0] = glm::vec4(a.a, a.b, 0.f, 0.f);
            am[1] = glm::vec4(a.c, a.d, 0.f, 0.f);
            am[3] = glm::vec4(a.tx * side, a.ty * side, 0.f, 1.f);
        }
        const glm::mat4 lm = mvp * am * layerMatrix(L.xf, side);
        RigGL* rg = nullptr;
        if (posed && row >= 0 && row < (int) g.rigs.size() && g.rigs[(size_t) row].rig.valid) {
            rg = &g.rigs[(size_t) row];
            ensureRigGL(*rg);
            if (!rg->vao) rg = nullptr;
            else if (!rg->uploaded && !rg->verts.empty()) {
                uploadRig(*rg);
            }
            if (rg && rg->verts.empty()) rg = nullptr;   // not evaluated yet: draw flat this frame
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, L.tex);
        if (rg) {
            glUseProgram(g.mesh.p);
            setQuadUniforms(g.mesh, v, lm, side, 0.f);
            glUniform1i(g.mesh.uTex, 0);
            const int sub = fox::rig::meshSub(rg->rig.grid);
            glUniform1i(g.mesh.uPos, 1);
            glUniform1i(g.mesh.uGrid, rg->rig.grid);
            glUniform1i(g.mesh.uSub, sub);
            glUniform4f(g.mesh.uRect, rg->rig.l, rg->rig.t, rg->rig.r, rg->rig.b);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, rg->posTex);
            glActiveTexture(GL_TEXTURE0);
            glBindVertexArray(0);
            glDrawArrays(GL_TRIANGLES, 0, (GLsizei) (rg->rig.grid * rg->rig.grid * sub * sub * 6));
            meshBound = true;
        } else {
            if (meshBound) {   // the previous layer used the mesh program: switch back to the flat-quad one
                glUseProgram(g.layer.p);
                setQuadUniforms(g.layer, v, mvp, side, 0.f);
                glUniform1i(g.layer.uTex, 0);
                meshBound = false;
            }
            glUniformMatrix4fv(g.layer.uMVP, 1, GL_FALSE, glm::value_ptr(lm));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
    }
}

}  // namespace

// ------------------------------------------------------------------------------------------ JNI

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_fox_foxiru_foxcat_fox2d_jnicallers_NativeCanvas_##name

// ---- GL thread ----

FN(void, nativeSurfaceCreated)(JNIEnv*, jclass, jint paperSize) {
    // A new context: every old GL name is gone. Forget handles, keep the stroke history.
    for (auto& L : g.layers) L.tex = L.fbo = 0;
    for (auto& R : g.rigs) { R.vao = R.vbo = R.ibo = 0; R.posTex = 0; R.uploaded = false; }   // old context: names are gone
    g.animDirty = true;

    GLint maxTex = 2048;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    g.T = std::max(256, std::min((int) paperSize, (int) maxTex));

    g.strokeProg = link(kStrokeVS, kStrokeFS);
    g.uT = glGetUniformLocation(g.strokeProg, "uT");
    g.paper.p = link(kQuadVS, std::string(kPaperFS) + kSdf + kPaperMain);
    g.layer.p = link(kQuadVS, std::string(kLayerFS) + kSdf + kLayerMain);
    g.mesh.p = link(std::string(kMeshVSHead) + kSmoothFn + kMeshVSMain, std::string(kLayerFS) + kSdf + kLayerMain);
    locate(g.paper);
    locate(g.layer);
    locate(g.mesh);

    glGenVertexArrays(1, &g.vao);
    glGenBuffers(1, &g.vbo);
    glBindVertexArray(g.vao);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    const GLsizei stride = (GLsizei) sizeof(Vert);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, pos));
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, seg));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, r));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, col));

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    g.emit.v.clear();
    g.ready = true;
    g.rebuildAll = true;   // layers are recreated + replayed on the next frame
}

FN(void, nativeSurfaceChanged)(JNIEnv*, jclass, jint w, jint h) {
    g.W = std::max(1, (int) w);
    g.H = std::max(1, (int) h);
}

FN(void, nativeDrawFrame)(JNIEnv*, jclass) {
    if (!g.ready) return;
    runCommands();
    if (g.animDirty) applyAnim();
    if (g.rebuildAll) rebuildAllLayers();
    drawScreen();
}

// ---- any thread (queued / locked) ----

FN(void, nativeSetView)(JNIEnv*, jclass, jfloat scale, jfloat rot, jfloat offX, jfloat offY) {
    std::lock_guard<std::mutex> l(gMu);
    gView.scale = scale; gView.rot = rot; gView.off = glm::vec2(offX, offY);
}

FN(void, nativeSetTheme)(JNIEnv*, jclass, jint bg, jint ca, jint cb, jfloat density) {
    std::lock_guard<std::mutex> l(gMu);
    gView.bg = (uint32_t) bg; gView.ca = (uint32_t) ca; gView.cb = (uint32_t) cb; gView.density = density;
}

FN(void, nativeBeginStroke)(JNIEnv*, jclass, jint cel, jint layer, jint argb, jfloat size, jboolean erase) {
    Cmd c; c.op = Op::Begin; c.i0 = cel; c.i1 = layer; c.argb = (uint32_t) argb; c.f0 = size; c.b = erase;
    push(std::move(c));
}

FN(void, nativeStrokePoint)(JNIEnv*, jclass, jfloat x, jfloat y) {
    Cmd c; c.op = Op::Point; c.f0 = x; c.f1 = y;
    push(std::move(c));
}

FN(void, nativeEndStroke)(JNIEnv*, jclass) { Cmd c; c.op = Op::End; push(std::move(c)); }
FN(void, nativeCancelStroke)(JNIEnv*, jclass) { Cmd c; c.op = Op::Cancel; push(std::move(c)); }
FN(void, nativeUndo)(JNIEnv*, jclass) { Cmd c; c.op = Op::Undo; push(std::move(c)); }
FN(void, nativeRedo)(JNIEnv*, jclass) { Cmd c; c.op = Op::Redo; push(std::move(c)); }
FN(void, nativeResetStrokes)(JNIEnv*, jclass) { Cmd c; c.op = Op::ResetStrokes; push(std::move(c)); }

FN(void, nativeSetShown)(JNIEnv* env, jclass, jintArray cels) {
    Cmd c; c.op = Op::SetShown;
    const jsize n = cels ? env->GetArrayLength(cels) : 0;
    c.ids.resize(n);
    if (n) env->GetIntArrayRegion(cels, 0, n, c.ids.data());
    push(std::move(c));
}

FN(void, nativeSetXf)(JNIEnv* env, jclass, jintArray ids, jfloatArray vals) {
    Cmd c; c.op = Op::SetXf;
    const jsize n = env->GetArrayLength(ids);
    const jsize m = env->GetArrayLength(vals);
    c.ids.resize(n);
    c.pts.resize(m);
    env->GetIntArrayRegion(ids, 0, n, c.ids.data());
    env->GetFloatArrayRegion(vals, 0, m, c.pts.data());
    push(std::move(c));
}

// Keyframe animation. offsets / counts: one entry per timeline row; statics: 7 floats per row (tx ty sx sy rot px py);
// keys: every row's keys back to back, fox::anim::kKeyStride (11) floats each, sorted by row-local frame.
FN(void, nativeSetAnim)(JNIEnv* env, jclass, jint perRow, jintArray offsets, jintArray counts, jfloatArray statics, jfloatArray keys) {
    Cmd c; c.op = Op::SetAnim; c.i0 = perRow;
    const jsize n = env->GetArrayLength(offsets);
    c.ids.resize((size_t) n * 2);
    env->GetIntArrayRegion(offsets, 0, n, c.ids.data());
    env->GetIntArrayRegion(counts, 0, std::min(n, env->GetArrayLength(counts)), c.ids.data() + n);
    const jsize ns = env->GetArrayLength(statics);
    const jsize nk = keys ? env->GetArrayLength(keys) : 0;
    c.pts.resize((size_t) ns + (size_t) nk);
    env->GetFloatArrayRegion(statics, 0, ns, c.pts.data());
    if (nk) env->GetFloatArrayRegion(keys, 0, nk, c.pts.data() + ns);
    push(std::move(c));
}

FN(void, nativeSetTime)(JNIEnv*, jclass, jfloat frame) {
    Cmd c; c.op = Op::SetTime; c.f0 = frame;
    push(std::move(c));
}

// Stateless (touches no GL state, any thread): one row's transform at row-local frame [local].
// keys = n * 11 floats, base = 7 floats (supplies the pivot / the no-key value). Returns 7 floats.
FN(jfloatArray, nativeEvalXf)(JNIEnv* env, jclass, jfloatArray keys, jint n, jfloat local, jfloatArray base) {
    const jsize nk = keys ? env->GetArrayLength(keys) : 0;
    std::vector<float> k((size_t) nk);
    if (nk) env->GetFloatArrayRegion(keys, 0, nk, k.data());
    float b[fox::anim::kXfStride] = {0.f, 0.f, 1.f, 1.f, 0.f, 0.5f, 0.5f};
    if (base && env->GetArrayLength(base) >= fox::anim::kXfStride) env->GetFloatArrayRegion(base, 0, fox::anim::kXfStride, b);
    const int cnt = std::min((int) n, (int) (k.size() / fox::anim::kKeyStride));
    const fox::anim::Xf x = fox::anim::evalRow(k.data(), cnt, local, fox::anim::readXf(b));
    float out[fox::anim::kXfStride];
    fox::anim::writeXf(x, out);
    jfloatArray r = env->NewFloatArray(fox::anim::kXfStride);
    env->SetFloatArrayRegion(r, 0, fox::anim::kXfStride, out);
    return r;
}

// Samples of an easing curve at t = i / (n - 1), for the UI's curve preview (the maths stays in C++).
FN(jfloatArray, nativeEaseCurve)(JNIEnv* env, jclass, jint id, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jint n) {
    const int m = std::max(2, (int) n);
    std::vector<float> v((size_t) m);
    for (int i = 0; i < m; i++) v[(size_t) i] = fox::anim::ease(id, (float) i / (float) (m - 1), glm::vec4(x1, y1, x2, y2));
    jfloatArray r = env->NewFloatArray(m);
    env->SetFloatArrayRegion(r, 0, m, v.data());
    return r;
}

// ---- rigging (fox_rig.h) ----

// Whole set: [nRows, (len, floats) per row]. Row r <-> timeline row r (same order as nativeSetAnim).
FN(void, nativeSetRig)(JNIEnv* env, jclass, jfloatArray blob) {
    Cmd c; c.op = Op::SetRig;
    const jsize n = blob ? env->GetArrayLength(blob) : 0;
    c.pts.resize((size_t) n);
    if (n) env->GetFloatArrayRegion(blob, 0, n, c.pts.data());
    push(std::move(c));
}

// false: rigged rows are drawn flat (rest pose), the editor shows the unposed picture while bones / curves are built.
// Attachments: [parentRow, bone] per row (-1 = none). Row order = nativeSetAnim order.
FN(void, nativeSetAttach)(JNIEnv* env, jclass, jintArray a) {
    Cmd c; c.op = Op::SetAttach;
    const jsize n = a ? env->GetArrayLength(a) : 0;
    c.ids.resize((size_t) n);
    if (n) env->GetIntArrayRegion(a, 0, n, reinterpret_cast<jint*>(c.ids.data()));
    push(std::move(c));
}

// The attachment matrix of [row] as drawn right now: a b c d tx ty (paper units). Identity when not attached / at rest view.
FN(jfloatArray, nativeAttach)(JNIEnv* env, jclass, jint row) {
    float out[6] = {1.f, 0.f, 0.f, 1.f, 0.f, 0.f};
    if (gRigPosed.load()) {
        std::lock_guard<std::mutex> l(gMu);
        if (row >= 0 && (size_t) row < gPubAtt.size()) {
            const fox::rig::Aff& a = gPubAtt[(size_t) row];
            out[0] = a.a; out[1] = a.b; out[2] = a.c; out[3] = a.d; out[4] = a.tx; out[5] = a.ty;
        }
    }
    jfloatArray r = env->NewFloatArray(6);
    env->SetFloatArrayRegion(r, 0, 6, out);
    return r;
}

FN(void, nativeSetRigPosed)(JNIEnv*, jclass, jboolean on) { gRigPosed.store(on != JNI_FALSE); }

// Stateless: geometry of ONE row's rig at row-local frame [local] for the editor overlay + every channel value.
// Layout in fox_rig.h evalOverlay. [rest] = unposed bones / curves. Empty array = no valid rig.
FN(jfloatArray, nativeEvalRig)(JNIEnv* env, jclass, jfloatArray row, jfloat local, jboolean rest) {
    const jsize n = row ? env->GetArrayLength(row) : 0;
    std::vector<float> d((size_t) n);
    if (n) env->GetFloatArrayRegion(row, 0, n, d.data());
    fox::rig::Rig R;
    std::vector<float> chv, out;
    if (!fox::rig::parseRow(d.data(), d.size(), R) || !R.valid) return env->NewFloatArray(0);
    fox::rig::prepareBones(R);
    fox::rig::channelValues(R, local, chv);
    fox::rig::evalOverlay(R, chv, rest != JNI_FALSE, out);
    jfloatArray r = env->NewFloatArray((jsize) out.size());
    env->SetFloatArrayRegion(r, 0, (jsize) out.size(), out.data());
    return r;
}

// Stateless: final skin weight of [bone] at every vertex of the row's grid (for the weight-paint heat map).
FN(jfloatArray, nativeRigWeights)(JNIEnv* env, jclass, jfloatArray row, jint bone) {
    const jsize n = row ? env->GetArrayLength(row) : 0;
    std::vector<float> d((size_t) n);
    if (n) env->GetFloatArrayRegion(row, 0, n, d.data());
    fox::rig::Rig R;
    std::vector<float> w;
    if (!fox::rig::parseRow(d.data(), d.size(), R) || !R.valid) return env->NewFloatArray(0);
    fox::rig::prepare(R, nullptr, false);
    fox::rig::boneWeights(R, bone, w);
    jfloatArray r = env->NewFloatArray((jsize) w.size());
    env->SetFloatArrayRegion(r, 0, (jsize) w.size(), w.data());
    return r;
}

// Touch -> picture: the REST position (paper units, layer space) under the shown point (x, y) of a rigged row, so a
// stroke drawn on a posed / bent drawing lands where the finger is. Returns {x, y} unchanged when nothing to undo.
FN(jfloatArray, nativeUnwarp)(JNIEnv* env, jclass, jint row, jfloat x, jfloat y) {
    float out[2] = {x, y};
    if (gRigPosed.load()) {
        std::lock_guard<std::mutex> l(gMu);
        if (row >= 0 && (size_t) row < gPub.size() && gPub[(size_t) row].valid) {
            const PubRig& P = gPub[(size_t) row];
            float u, v;
            if (fox::rig::unwarp(P.verts.data(), P.verts.size() / 4, P.grid, x, y, u, v)) { out[0] = u; out[1] = v; }
        }
    }
    jfloatArray r = env->NewFloatArray(2);
    env->SetFloatArrayRegion(r, 0, 2, out);
    return r;
}

FN(void, nativeSetLayers)(JNIEnv* env, jclass, jintArray ids, jbooleanArray vis) {
    Cmd c; c.op = Op::SetLayers;
    const jsize n = env->GetArrayLength(ids);
    c.ids.resize(n);
    c.vis.resize(n);
    env->GetIntArrayRegion(ids, 0, n, c.ids.data());
    env->GetBooleanArrayRegion(vis, 0, n, c.vis.data());
    push(std::move(c));
}

FN(void, nativeAddStroke)(JNIEnv* env, jclass, jint cel, jint layer, jint argb, jfloat size, jboolean erase, jfloatArray xy) {
    Cmd c; c.op = Op::AddStroke; c.i0 = cel; c.i1 = layer; c.argb = (uint32_t) argb; c.f0 = size; c.b = erase;
    const jsize n = env->GetArrayLength(xy);
    c.pts.resize(n);
    env->GetFloatArrayRegion(xy, 0, n, c.pts.data());
    push(std::move(c));
}
