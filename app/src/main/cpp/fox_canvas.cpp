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
// The mesh covers the drawing's box only, but the surface CONTINUES outside it (a ring of quads that extrapolates the
// nearest edge / corner tangent up to the paper border), so ink added after rigging is drawn, bent and unwarped too.
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
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <optional>
#include <string>
#include <type_traits>
#include <unordered_set>
#include <utility>
#include <vector>
#include <unordered_map>

#include "fox_imagelayer.h"
#include "fox_strokeblob.h"
#include "fox_blend.h"
#include "fox_anim.h"
#include "fox_rig.h"
#include "fox_fit.h"
#include "fox_mesh.h"

#define TAG "FoxCanvas"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Per-call-site error probe: reports the first few errors of THIS site only, so one noisy site cannot hide the others.
#define GLC(label) do { static int b_ = 4; GLenum e_; while ((e_ = glGetError()) != GL_NO_ERROR) { if (b_-- > 0) LOGE("GL error 0x%x at %s", (unsigned) e_, label); } } while (0)


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
    
    bool isImg = false;
    int img = -1;
    float ix = 0, iy = 0, iw = 0, ih = 0, irot = 0;
};

enum class Op { Begin, Point, End, Cancel, Undo, Redo, SetShown, SetLayers, SetFx, SetGroupFx, SetXf, AddStroke, ResetStrokes, SetAnim, SetTime, SetRig, SetAttach, AddImage, FreeImage };

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
    float l = 0.f, t = 0.f, r = 1.f, b = 1.f;   // rest rect of the mesh (paper units)
    std::vector<float> verts;   // x y u v per vertex (fox::rig::buildMesh)
};
std::vector<PubRig> gPub;                 // guarded by gMu
std::vector<fox::rig::Aff> gPubAtt;       // guarded by gMu: per-row attachment matrix (paper units) as drawn
std::atomic<bool> gMeshLines{false};      // draw the rig mesh as GLSL lines (no Compose lines)
std::atomic<int> gMeshRow{-1};            // row whose mesh is drawn, -1 = every rigged row
// Triangle meshes that follow a drawing's outline (fox_mesh.h), per timeline row. Written by the UI thread under gMu,
// uploaded by the GL thread when gTriVer changes. Rest pose only for now (shown as lines).
struct TriData { std::vector<float> v; std::vector<int> t; };
std::unordered_map<int, TriData> gTri;
uint32_t gTriVer = 1;
std::atomic<bool> gRigPosed{true};        // false: rigged rows are shown in their REST pose (rig editing)

// ---- CPU mirror of the mesh vertex shader's surface (kSmoothFn): used to invert a touch back to the picture ----
// Inside the mesh box: bicubic Catmull-Rom through the grid points (exactly what the GPU draws). Outside the box:
// the surface continues first-order (tangent plane of the nearest edge / corner point), also exactly like the GPU ring.
struct SV2 { float x, y; };

struct SurfRef {
    const float* v;   // x y u v per grid vertex, row-major ((grid+1)^2 entries)
    int grid;
    SV2 gp(int i, int j) const {
        i = std::clamp(i, 0, grid); j = std::clamp(j, 0, grid);
        const float* q = v + ((size_t) j * (size_t) (grid + 1) + (size_t) i) * 4;
        return {q[0], q[1]};
    }
};

inline void crWeights(float t, float w[4]) {
    const float t2 = t * t, t3 = t2 * t;
    w[0] = -0.5f * t3 + t2 - 0.5f * t;
    w[1] = 1.5f * t3 - 2.5f * t2 + 1.f;
    w[2] = -1.5f * t3 + 2.f * t2 + 0.5f * t;
    w[3] = 0.5f * t3 - 0.5f * t2;
}

inline SV2 surfIn(const SurfRef& S, float fx, float fy) {
    const float G = (float) S.grid;
    fx = std::clamp(fx, 0.f, G); fy = std::clamp(fy, 0.f, G);
    const int i = std::min((int) std::floor(fx), S.grid - 1), j = std::min((int) std::floor(fy), S.grid - 1);
    float wx[4], wy[4];
    crWeights(fx - (float) i, wx);
    crWeights(fy - (float) j, wy);
    SV2 p{0.f, 0.f};
    for (int b = 0; b < 4; b++) {
        SV2 row{0.f, 0.f};
        for (int a = 0; a < 4; a++) {
            const SV2 q = S.gp(i + a - 1, j + b - 1);
            row.x += q.x * wx[a]; row.y += q.y * wx[a];
        }
        p.x += row.x * wy[b]; p.y += row.y * wy[b];
    }
    return p;
}

inline SV2 surfExt(const SurfRef& S, float fx, float fy) {
    const float G = (float) S.grid;
    const float cx = std::clamp(fx, 0.f, G), cy = std::clamp(fy, 0.f, G);
    const SV2 p0 = surfIn(S, cx, cy);
    SV2 p = p0;
    const float dx = fx - cx, dy = fy - cy;
    if (dx != 0.f) {
        const float s = dx < 0.f ? 1.f : -1.f;
        const SV2 q = surfIn(S, cx + s, cy);
        p.x += (q.x - p0.x) * (dx * s); p.y += (q.y - p0.y) * (dx * s);
    }
    if (dy != 0.f) {
        const float s = dy < 0.f ? 1.f : -1.f;
        const SV2 q = surfIn(S, cx, cy + s);
        p.x += (q.x - p0.x) * (dy * s); p.y += (q.y - p0.y) * (dy * s);
    }
    return p;
}

// Shown point (x, y) -> rest point (ou, ov) in paper units, Newton on the surface above (valid inside AND outside the mesh box).
inline bool unwarpSurface(const float* verts, size_t nVerts, int grid, float l, float t, float r, float b,
                          float x, float y, float& ou, float& ov) {
    if (grid < 1 || nVerts < (size_t) (grid + 1) * (size_t) (grid + 1)) return false;
    const SurfRef S{verts, grid};
    const float w = std::max(r - l, 1e-4f), h = std::max(b - t, 1e-4f), G = (float) grid;
    float bd = 1e30f, fx = 0.f, fy = 0.f;
    for (int j = 0; j <= grid; j++) for (int i = 0; i <= grid; i++) {   // start at the nearest grid vertex
        const SV2 q = S.gp(i, j);
        const float d = (q.x - x) * (q.x - x) + (q.y - y) * (q.y - y);
        if (d < bd) { bd = d; fx = (float) i; fy = (float) j; }
    }
    float res = 1e30f;
    for (int it = 0; it < 20; it++) {
        const SV2 p = surfExt(S, fx, fy);
        const float ex = x - p.x, ey = y - p.y;
        res = ex * ex + ey * ey;
        if (res < 1e-11f) break;
        const float e = 0.02f;
        const SV2 a = surfExt(S, fx + e, fy), c = surfExt(S, fx - e, fy);
        const SV2 d = surfExt(S, fx, fy + e), f = surfExt(S, fx, fy - e);
        const float jxx = (a.x - c.x) / (2.f * e), jyx = (a.y - c.y) / (2.f * e);
        const float jxy = (d.x - f.x) / (2.f * e), jyy = (d.y - f.y) / (2.f * e);
        const float det = jxx * jyy - jxy * jyx;
        if (std::fabs(det) < 1e-12f) break;
        float dfx = (jyy * ex - jxy * ey) / det, dfy = (-jyx * ex + jxx * ey) / det;
        const float m = std::max(std::fabs(dfx), std::fabs(dfy));
        if (m > 2.f) { dfx *= 2.f / m; dfy *= 2.f / m; }   // damp wild steps (folded meshes)
        fx += dfx; fy += dfy;
    }
    const SV2 p = surfExt(S, fx, fy);
    res = (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y);
    if (!(res < 1e-6f) || !std::isfinite(fx) || !std::isfinite(fy)) return false;   // > 1e-3 paper units off: let the caller fall back
    ou = l + w * fx / G;
    ov = t + h * fy / G;
    return true;
}

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
    fox::blend::Fx fx;   // blend mode / opacity / clipping of this layer inside its row
};

struct PaperProg {
    GLuint p = 0;
    GLint uMVP = -1, uModel = -1, uScale = -1, uSide = -1, uMargin = -1, uRadius = -1, uTile = -1, uShadow = -1,
          uCA = -1, uCB = -1, uTex = -1, uPos = -1, uGrid = -1, uSub = -1, uRect = -1,
          uView = -1, uWidth = -1, uColor = -1, uFlat = -1;   // wire program only
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

struct TriGL {
    GLuint vao = 0, vbo = 0; GLsizei verts = 0;
    std::vector<float> rest;        // x y per vertex (rest pose)
    std::vector<int> ea, eb;        // unique edges
    std::vector<float> border;      // 10 = outline edge (used by one triangle), 0 = inner edge
    std::vector<float> sig;         // pin state the buffer was filled for (empty = rest pose)
    std::vector<float> buf;
    bool filled = false;
};

struct GLState {
    GLuint imgProg = 0;
    std::unordered_map<int, GLuint> imgTex;
    bool ready = false;
    bool rebuildAll = false;
    int W = 1, H = 1, T = kPaperDefault;
    GLuint strokeProg = 0; GLint uT = -1;
    PaperProg paper, layer, mesh, wire, triwire;
    std::unordered_map<int, TriGL> triGL;   // row -> GPU edge quads of its triangle mesh
    uint32_t triVer = 0;
    std::vector<RigGL> rigs;   // row r <-> rigs[r] (may be shorter than rows)
    std::vector<int> attach;   // 2 ints per row: parent row, bone (-1 = not attached)
    std::vector<fox::rig::Aff> attM;   // per row, from applyAnim; identity when not attached
    GLuint vao = 0, vbo = 0;
    std::vector<LayerGL> layers;
    std::vector<fox::blend::Fx> rowFx;   // per timeline row: blend mode / opacity / clipping of the whole row
    std::vector<int> rowGroup;           // per timeline row: id of the group folder it sits in (-1 = none)
    std::vector<std::pair<int, fox::blend::Fx>> groupFx;   // group id -> blend / opacity / clipping of the whole folder
    fox::blend::Ctx fxc;                 // intermediate pictures for blend + clipping
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

// Layer quad: same corners as the paper, but uModel (attachment * row transform) is applied separately from uMVP so the
// fragment shader also knows where the pixel is SHOWN (vShown, paper px) and can clip it to the paper.
const char* kLayerVS = R"(#version 300 es
uniform mat4 uMVP;
uniform mat4 uModel;
uniform float uSide;
uniform float uMargin;
out vec2 vLocal;
out vec2 vShown;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 local = mix(vec2(-uMargin), vec2(uSide + uMargin), c);
    vLocal = local;
    vec4 w = uModel * vec4(local, 0.0, 1.0);
    vShown = w.xy;
    gl_Position = uMVP * w;
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
// Bicubic surface through the grid points, parameter f in grid cells (clamped to the grid).
vec2 surfIn(vec2 f) {
    f = clamp(f, vec2(0.0), vec2(float(uGrid)));
    int i = min(int(floor(f.x)), uGrid - 1), j = min(int(floor(f.y)), uGrid - 1);
    vec4 wx = crW(f.x - float(i)), wy = crW(f.y - float(j));
    vec2 pos = vec2(0.0);
    for (int b = 0; b < 4; b++) {
        int jj = j + b - 1;
        vec2 row = gridP(i - 1, jj) * wx.x + gridP(i, jj) * wx.y + gridP(i + 1, jj) * wx.z + gridP(i + 2, jj) * wx.w;
        pos += row * wy[b];
    }
    return pos;
}
// Same surface, continued first-order beyond the grid (tangent of the nearest edge / corner point), so the picture
// keeps following the bend outside the mesh box. Identical to surfExt in the CPU mirror (unwarpSurface).
vec2 surfExt(vec2 f) {
    vec2 fc = clamp(f, vec2(0.0), vec2(float(uGrid)));
    vec2 p0 = surfIn(fc);
    vec2 p = p0;
    vec2 d = f - fc;
    if (d.x != 0.0) { float s = d.x < 0.0 ? 1.0 : -1.0; p += (surfIn(fc + vec2(s, 0.0)) - p0) * (d.x * s); }
    if (d.y != 0.0) { float s = d.y < 0.0 ? 1.0 : -1.0; p += (surfIn(fc + vec2(0.0, s)) - p0) * (d.y * s); }
    return p;
}
// vertex gl_VertexID of the attribute-less draw: 6 per quad of an (n+2) x (n+2) lattice, n = uGrid * uSub.
// Lattice rows / columns 1..n are the smooth mesh; row / column 0 and n+1 are single-quad rings that reach out to the
// paper border (rest 0 / 1). Returns shown position, sets rest (paper units).
vec2 smoothVertex(out vec2 rest) {
    int q = gl_VertexID / 6;
    int k = gl_VertexID - q * 6;
    int m = uGrid * uSub + 2;
    int qx = q % m, qy = q / m;
    int lx = qx + ((k == 1 || k == 3 || k == 4) ? 1 : 0);
    int ly = qy + ((k == 2 || k == 4 || k == 5) ? 1 : 0);
    float G = float(uGrid);
    vec2 w = max(uRect.zw - uRect.xy, vec2(1e-4));
    vec2 e0 = max(uRect.xy, vec2(0.0)) / w * G;                 // grid cells from the box edge back to the paper border
    vec2 e1 = max(vec2(1.0) - uRect.zw, vec2(0.0)) / w * G;
    float fx = lx == 0 ? -e0.x : (lx == m ? G + e1.x : float(lx - 1) / float(uSub));
    float fy = ly == 0 ? -e0.y : (ly == m ? G + e1.y : float(ly - 1) / float(uSub));
    rest = uRect.xy + w * vec2(fx, fy) / G;
    return surfExt(vec2(fx, fy));
}
)";

const char* kMeshVSHead = R"(#version 300 es
precision highp float;
precision highp int;
uniform mat4 uMVP;
uniform mat4 uModel;     // attachment * row transform (paper px -> paper px)
uniform float uSide;     // paper side in local px
uniform float uMargin;
out vec2 vLocal;
out vec2 vShown;
)";
const char* kMeshVSMain = R"(
void main() {
    vec2 rest;
    vec2 pos = smoothVertex(rest);
    vLocal = rest * uSide;
    vec4 w = uModel * vec4(pos * uSide, 0.0, 1.0);
    vShown = w.xy;
    gl_Position = uMVP * w;
}
)";

// ---- Mesh lines (Puppet Warp "Show mesh lines"): drawn on the GPU from the SAME smooth surface as the picture ----
// Attribute-less: 6 vertices per line segment (a screen-space quad of uWidth px, so no 1-px GL_LINES limit). The lines are the
// grid cells' edges, tessellated uSub times per cell so they bend exactly like the picture. uFlat = 1: rest grid (not posed).
const char* kWireVSHead = R"(#version 300 es
precision highp float;
precision highp int;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform float uSide;
uniform vec2 uView;      // viewport px
uniform float uWidth;    // line width px
uniform int uFlat;
out float vEdge;         // signed distance from the line centre, px
out float vHalf;         // half width incl. border boost, px
out float vBorder;
)";
const char* kWireVSMain = R"(
vec2 wirePt(int dir, int line, int stp) {
    vec2 f = dir == 0 ? vec2(float(stp) / float(uSub), float(line)) : vec2(float(line), float(stp) / float(uSub));
    if (uFlat == 1) return uRect.xy + (uRect.zw - uRect.xy) * f / float(uGrid);
    return surfIn(f);
}
vec2 toNdc(vec2 pos, out float w) {
    vec4 c = uMVP * (uModel * vec4(pos * uSide, 0.0, 1.0));
    w = c.w;
    return c.xy / c.w;
}
void main() {
    int seg = gl_VertexID / 6;
    int k = gl_VertexID - seg * 6;
    int n = uGrid * uSub;
    int perDir = (uGrid + 1) * n;
    int dir = seg / perDir;
    int rem = seg - dir * perDir;
    int line = rem / n;
    int st = rem - line * n;
    float wa, wb;
    vec2 na = toNdc(wirePt(dir, line, st), wa), nb = toNdc(wirePt(dir, line, st + 1), wb);
    bool endB = (k == 1 || k == 4 || k == 5);
    float sgn = (k == 2 || k == 3 || k == 5) ? 1.0 : -1.0;
    float border = (line == 0 || line == uGrid) ? 1.0 : 0.0;
    float hw = 0.5 * uWidth * (1.0 + 0.7 * border) + 0.75;   // + 0.75 px of anti-alias skirt
    vec2 d = (nb - na) * 0.5 * uView;                             // segment direction in px
    float len = length(d);
    d = len > 1e-5 ? d / len : vec2(1.0, 0.0);
    vec2 nrm = vec2(-d.y, d.x);
    vec2 base = endB ? nb : na;
    vec2 px = (nrm * sgn + d * (endB ? 1.0 : -1.0)) * hw;      // along-line extension closes the gaps at bends
    vec2 ndc = base + px / (0.5 * uView);
    vEdge = sgn * hw;
    vHalf = hw;
    vBorder = border;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
)";
const char* kWireFS = R"(#version 300 es
precision highp float;
uniform vec4 uColor;     // premultiplied-ready rgba
uniform float uWidth;
in float vEdge;
in float vHalf;
in float vBorder;
out vec4 o;
void main() {
    float core = 0.5 * uWidth * (1.0 + 0.7 * vBorder);
    float a = 1.0 - smoothstep(core - 0.5, core + 0.75, abs(vEdge));
    float al = uColor.a * a * (1.0 + 0.35 * vBorder);
    o = vec4(uColor.rgb * al, al);
}
)";

// Triangle-mesh lines: one screen-space quad per unique triangle edge (built on the CPU once per mesh, see uploadTri).
// aSeg = edge endpoints (paper units), aK = corner 0..5 (+10 = outline edge, drawn heavier).
const char* kTriWireVS = R"(#version 300 es
precision highp float;
precision highp int;
layout(location = 0) in vec4 aSeg;
layout(location = 1) in float aK;
uniform mat4 uMVP;
uniform mat4 uModel;
uniform float uSide;
uniform vec2 uView;
uniform float uWidth;
out float vEdge;
out float vHalf;
out float vBorder;
vec2 toNdc(vec2 pos) {
    vec4 c = uMVP * (uModel * vec4(pos * uSide, 0.0, 1.0));
    return c.xy / c.w;
}
void main() {
    float border = aK >= 10.0 ? 1.0 : 0.0;
    int k = int(aK - 10.0 * border + 0.5);
    vec2 na = toNdc(aSeg.xy), nb = toNdc(aSeg.zw);
    bool endB = (k == 1 || k == 4 || k == 5);
    float sgn = (k == 2 || k == 3 || k == 5) ? 1.0 : -1.0;
    float hw = 0.5 * uWidth * (1.0 + 0.7 * border) + 0.75;
    vec2 d = (nb - na) * 0.5 * uView;
    float len = length(d);
    d = len > 1e-5 ? d / len : vec2(1.0, 0.0);
    vec2 nrm = vec2(-d.y, d.x);
    vec2 base = endB ? nb : na;
    vec2 px = (nrm * sgn + d * (endB ? 1.0 : -1.0)) * hw;
    vEdge = sgn * hw;
    vHalf = hw;
    vBorder = border;
    gl_Position = vec4(base + px / (0.5 * uView), 0.0, 1.0);
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
in vec2 vShown;
uniform sampler2D uTex;
out vec4 o;
)";  // + kSdf + kLayerMain

const char* kLayerMain = R"(
void main() {
    // clip where the pixel is SHOWN (after rig bend / attachment / row transform), never where it came from:
    // a bent or moved drawing must not leave the paper. Sampling still uses the rest position.
    float d = sdRound(vShown) * uScale;
    float cov = clamp(0.5 - d, 0.0, 1.0);
    o = texture(uTex, vLocal / uSide) * cov;
}
)";

GLuint compile(GLenum type, const std::string& src) {
    GLuint s = glCreateShader(type);
    GLC("compile: glCreateShader");
    const char* c = src.c_str();
    glShaderSource(s, 1, &c, nullptr);
    GLC("compile: glShaderSource");
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok || s == 0) {
        char log[1024] = {0};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        LOGE("shader compile failed (name %u, type 0x%x): %s", s, (unsigned) type, log);
    }
    return s;
}

GLuint linkProgram(const std::string& vs, const std::string& fs) {
    GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    GLuint p = glCreateProgram();
    GLC("link: glCreateProgram");
    glAttachShader(p, v);
    GLC("link: glAttachShader(vs)");
    glAttachShader(p, f);
    GLC("link: glAttachShader(fs)");
    glLinkProgram(p);
    GLC("link: glLinkProgram");
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        LOGE("program link failed (program %u, vs %u, fs %u): %s", p, v, f, log);
    }
    static int linkLogs = 12;
    if (linkLogs-- > 0) LOGE("link: program %u (vs %u fs %u) linked=%d isProgram=%d", p, v, f, (int) ok, (int) glIsProgram(p));
    glDeleteShader(v);
    glDeleteShader(f);
    GLC("link: glDeleteShader");
    return p;
}

void locate(PaperProg& q) {
    auto u = [&](const char* n) { return glGetUniformLocation(q.p, n); };
    q.uMVP = u("uMVP"); q.uModel = u("uModel"); q.uScale = u("uScale");
    q.uSide = u("uSide"); q.uMargin = u("uMargin"); q.uRadius = u("uRadius"); q.uTile = u("uTile");
    q.uShadow = u("uShadow"); q.uCA = u("uCA"); q.uCB = u("uCB"); q.uTex = u("uTex");
    q.uPos = u("uPos"); q.uGrid = u("uGrid"); q.uSub = u("uSub"); q.uRect = u("uRect");
    if (!q.p) LOGE("program %s: not linked (0)", "paper/layer/mesh");
    else if (q.uMVP < 0 || q.uSide < 0) LOGE("program %u: missing uniforms uMVP=%d uSide=%d uModel=%d uTex=%d", q.p, q.uMVP, q.uSide, q.uModel, q.uTex);
}

// 0xAARRGGBB -> (r, g, b, a) in 0..1. Divides by 255 (not * 1/255) to stay bit-identical with the old scalar code.
glm::vec4 unpackArgb(uint32_t c) {
    return glm::vec4((float) ((c >> 16) & 255u), (float) ((c >> 8) & 255u),
                     (float) (c & 255u), (float) (c >> 24)) / 255.f;
}

// ------------------------------------------------------------------------------------------ layers

// Diagnostics: logs the first few GL errors per site (Logcat tag FoxCanvas). GL never throws, so without this a failed
// texture allocation / incomplete framebuffer only shows up as a garbled canvas.
void glChk(const char* where) {
    static int budget = 40;
    for (int i = 0; i < 4; i++) {
        const GLenum err = glGetError();
        if (err == GL_NO_ERROR) return;
        if (budget > 0) { budget--; LOGE("GL error 0x%x at %s", (unsigned) err, where); }
    }
}

void glChkOp(int op) {
    char buf[40];
    snprintf(buf, sizeof buf, "after Op #%d", op);
    glChk(buf);
}

void createLayerGL(LayerGL& L) {
    glChk("before createLayerGL");
    glGenTextures(1, &L.tex);
    glBindTexture(GL_TEXTURE_2D, L.tex);
    GLC("createLayerGL: genTextures/bindTexture");
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, g.T, g.T);
    GLC("createLayerGL: texStorage2D");
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &L.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, L.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, L.tex, 0);
    const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (st != GL_FRAMEBUFFER_COMPLETE) LOGE("layer %d framebuffer incomplete: 0x%x (layers alive: %zu)", L.id, (unsigned) st, g.layers.size());
    glViewport(0, 0, g.T, g.T);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    glChk("createLayerGL");
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
    glActiveTexture(GL_TEXTURE0);
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, L.fbo);
    GLC("bindLayer: bindFramebuffer");
    glViewport(0, 0, g.T, g.T);
    GLC("bindLayer: viewport");
    static int fboBudget = 20;
    if (fboBudget > 0) {
        const GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) { fboBudget--; LOGE("bindLayer %d (fbo %u tex %u): framebuffer status 0x%x", L.id, L.fbo, L.tex, (unsigned) st); }
    }
}

void clearLayer(LayerGL& L) {
    bindLayer(L);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
}

// ------------------------------------------------------------------------------------------ strokes

// Two triangles covering the segment's bounding box grown by the radius (+1.5px AA margin).
void pushSeg(std::vector<Vert>& v, glm::vec2 a, glm::vec2 b, float r, const glm::vec4& c) {
    if (!std::isfinite(a.x + a.y + b.x + b.y + r)) {
        static int nanBudget = 10;
        if (nanBudget-- > 0) LOGE("stroke segment with non-finite data dropped: a=(%f,%f) b=(%f,%f) r=%f", a.x, a.y, b.x, b.y, r);
        return;
    }
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
    GLC("flush: error pending on entry (from an earlier call)");
    bindLayer(L);
    GLC("flush: bindLayer");
    {
        static int pl = 6;
        if (pl-- > 0) {
            GLint cur = -1;
            glGetIntegerv(GL_CURRENT_PROGRAM, &cur);
            LOGE("flush: strokeProg=%u isProgram=%d uT=%d currentProgram=%d g@%p", g.strokeProg, (int) glIsProgram(g.strokeProg), (int) g.uT, (int) cur, (void*) &g);
        }
    }
    glUseProgram(g.strokeProg);
    GLC("flush: useProgram(strokeProg)");
    glUniform1f(g.uT, (float) g.T);
    GLC("flush: uniform uT");
    glEnable(GL_BLEND);
    if (e.erase) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    GLC("flush: blend");
    glBindVertexArray(g.vao);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    GLC("flush: bind vao/vbo");
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (e.v.size() * sizeof(Vert)), e.v.data(), GL_STREAM_DRAW);
    GLC("flush: bufferData");
    glDrawArrays(GL_TRIANGLES, 0, (GLsizei) e.v.size());
    GLC("flush: drawArrays");
    e.v.clear();   // keeps capacity
}

GLuint imageTex(int h) {
    auto it = g.imgTex.find(h);
    if (it != g.imgTex.end()) return it->second;
    auto im = fox::img::get(h);
    if (!im || im->w <= 0 || im->h <= 0) { LOGE("image handle %d: missing / empty", h); return 0; }
    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    glChk("before makeTexture");
    const GLuint t = fox::img::makeTexture(*im);
    LOGE("image handle %d: %dx%d (GL_MAX_TEXTURE_SIZE %d) -> tex %u", h, im->w, im->h, (int) maxTex, (unsigned) t);
    glChk("makeTexture");
    g.imgTex[h] = t;
    return t;
}

// Keeps draw order: pending ink is flushed first, then the image goes into the same layer FBO (so erasers cut it).
void drawImageStroke(const Stroke& s, Emitter& e, LayerGL& L) {
    flush(e, L);
    const GLuint tex = s.img >= 0 ? imageTex(s.img) : 0;
    if (!tex) return;
    bindLayer(L);
    {
        static int pl = 6;
        if (pl-- > 0) LOGE("drawImageStroke: imgProg=%u isProgram=%d tex=%u isTexture=%d", g.imgProg, (int) glIsProgram(g.imgProg), tex, (int) glIsTexture(tex));
    }
    GLC("drawImageStroke: before draw");
    fox::img::draw(g.imgProg, tex, s.ix, s.iy, s.iw, s.ih, s.irot, 0);
    // the image helper owns its own program / blend / texture unit: put the stroke pipeline's assumptions back
    glActiveTexture(GL_TEXTURE0);
    glBindVertexArray(0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glChk("drawImageStroke");
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
    if (s.isImg) {
        if (finish && emitted == 0) { drawImageStroke(s, e, L); emitted = 1; }
        return;
    }
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

// c.ids = [nLayers, layer modes..., row modes...], c.pts = [layer opacities..., row opacities...],
// c.vis = [layer clip flags..., row clip flags...]. Layer k <-> native layer id k.
void applyFx(const Cmd& c) {
    if (c.ids.empty()) return;
    const size_t nL = (size_t) std::max(0, c.ids[0]);
    const size_t nR = c.ids.size() > 1 + nL ? c.ids.size() - 1 - nL : 0;
    if (c.pts.size() < nL + nR || c.vis.size() < nL + nR) return;
    for (size_t k = 0; k < nL; k++) {
        LayerGL* L = findLayer((int) k);
        if (L) L->fx = fox::blend::makeFx(c.ids[1 + k], c.pts[k], c.vis[k] != 0);
    }
    g.rowFx.assign(nR, fox::blend::Fx{});
    for (size_t r = 0; r < nR; r++) g.rowFx[r] = fox::blend::makeFx(c.ids[1 + nL + r], c.pts[nL + r], c.vis[nL + r] != 0);
}

// c.ids = [nRows, row group ids..., group ids..., group modes...], c.pts = group opacities, c.vis = group clip flags.
void applyGroupFx(const Cmd& c) {
    if (c.ids.empty()) return;
    const size_t nR = (size_t) std::max(0, c.ids[0]);
    if (c.ids.size() < 1 + nR) return;
    const size_t nG = (c.ids.size() - 1 - nR) / 2;
    if (c.pts.size() < nG || c.vis.size() < nG) return;
    g.rowGroup.assign(c.ids.begin() + 1, c.ids.begin() + 1 + (std::ptrdiff_t) nR);
    g.groupFx.clear();
    for (size_t k = 0; k < nG; k++)
        g.groupFx.emplace_back(c.ids[1 + nR + k], fox::blend::makeFx(c.ids[1 + nR + nG + k], c.pts[k], c.vis[k] != 0));
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
        // PERF: the playhead moves every frame, but a rig whose channel values did not change (no keys, a Freeze / Loop that
        // lands on the same pose, a hold between keys) has the same mesh: skip buildMesh + the GPU upload + the UI snapshot copy.
        // A fresh RigGL (rig replaced) starts with empty chv / uploaded=false / gPub reset, so it always takes the full path.
        std::vector<float> nv;
        fox::rig::channelValues(R.rig, g.curFrame - (float) off, nv);
        bool published = false;
        {
            std::lock_guard<std::mutex> l(gMu);
            published = r < gPub.size() && gPub[r].valid;
        }
        if (published && R.uploaded && R.vao && !R.verts.empty() && nv == R.chv) continue;
        R.chv = std::move(nv);
        fox::rig::buildMesh(R.rig, R.chv.data(), R.verts, R.pose);
        ensureRigGL(R);
        if (R.vao) {
            uploadRig(R);
        }
        std::lock_guard<std::mutex> l(gMu);
        if (gPub.size() <= r) gPub.resize(r + 1);
        gPub[r].valid = true;
        gPub[r].grid = R.rig.grid;
        gPub[r].l = R.rig.l; gPub[r].t = R.rig.t; gPub[r].r = R.rig.r; gPub[r].b = R.rig.b;
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
        static int xfBudget = 20;
        const float sum = x.tx + x.ty + x.sx + x.sy + x.rot + x.px + x.py;
        if (xfBudget > 0 && (!std::isfinite(sum) || std::fabs(x.sx) > 50.f || std::fabs(x.sy) > 50.f || std::fabs(x.tx) > 50.f || std::fabs(x.ty) > 50.f)) {
            xfBudget--;
            LOGE("row %zu transform out of range: t=(%f,%f) s=(%f,%f) rot=%f pivot=(%f,%f) frame=%f keys=%d", r, x.tx, x.ty, x.sx, x.sy, x.rot, x.px, x.py,
                 g.curFrame, n);
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
    int lastOp = -1;
    for (auto& c : local) {
        if (lastOp >= 0) glChkOp(lastOp);   // attribute any GL error to the command that caused it
        lastOp = (int) c.op;
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
            case Op::SetFx:
                applyFx(c);
                break;
            case Op::SetGroupFx:
                applyGroupFx(c);
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
            case Op::AddImage: {
                if (c.ids.empty() || c.pts.size() < 5) break;
                Stroke s;
                s.cel = c.i0; s.layer = c.i1; s.isImg = true; s.img = c.ids[0];
                s.ix = c.pts[0]; s.iy = c.pts[1]; s.iw = c.pts[2]; s.ih = c.pts[3]; s.irot = c.pts[4];
                g.redo.clear();
                if (isShown(s.cel)) {
                    LayerGL& L = getLayer(s.layer);
                    drawWhole(s, e, L);
                    flush(e, L);
                }
                g.history.push_back(std::move(s));
                break;
            }
            case Op::FreeImage:
                for (int h : c.ids) {
                    auto it = g.imgTex.find(h);
                    if (it != g.imgTex.end()) { glDeleteTextures(1, &it->second); g.imgTex.erase(it); }
                }
                break;
        }
    }
    if (lastOp >= 0) glChkOp(lastOp);
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

// Triangle mesh -> GPU. Topology once (unique edges, outline edges = used by one triangle); positions every time the pins move.
// Every edge becomes 6 vertices (a, b, corner) for the screen-space line quads of kTriWireVS.
void fillTri(TriGL& G, const std::vector<float>& pos) {
    G.buf.resize(G.ea.size() * 30);
    size_t o = 0;
    for (size_t e = 0; e < G.ea.size(); e++) {
        const size_t a = (size_t) G.ea[e] * 2, b = (size_t) G.eb[e] * 2;
        for (int c = 0; c < 6; c++) {
            G.buf[o++] = pos[a]; G.buf[o++] = pos[a + 1]; G.buf[o++] = pos[b]; G.buf[o++] = pos[b + 1];
            G.buf[o++] = (float) c + G.border[e];
        }
    }
    glBindBuffer(GL_ARRAY_BUFFER, G.vbo);
    glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (G.buf.size() * sizeof(float)), G.buf.data(), GL_DYNAMIC_DRAW);
    G.verts = (GLsizei) (G.buf.size() / 5);
}

void uploadTri(TriGL& G, const TriData& D) {
    std::unordered_map<uint64_t, int> uses;
    auto ek = [](int a, int b) { return ((uint64_t) (uint32_t) std::min(a, b) << 32) | (uint32_t) std::max(a, b); };
    for (size_t i = 0; i + 2 < D.t.size(); i += 3) for (int k = 0; k < 3; k++) uses[ek(D.t[i + (size_t) k], D.t[i + (size_t) ((k + 1) % 3)])]++;
    const int nv = (int) (D.v.size() / 2);
    G.rest = D.v;
    G.ea.clear(); G.eb.clear(); G.border.clear();
    for (auto& e : uses) {
        const int a = (int) (e.first >> 32), b = (int) (e.first & 0xffffffffu);
        if (a >= nv || b >= nv) continue;
        G.ea.push_back(a); G.eb.push_back(b); G.border.push_back(e.second == 1 ? 10.f : 0.f);
    }
    if (!G.vao) { glGenVertexArrays(1, &G.vao); glGenBuffers(1, &G.vbo); }
    glBindVertexArray(G.vao);
    glBindBuffer(GL_ARRAY_BUFFER, G.vbo);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void*) 0);
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 1, GL_FLOAT, GL_FALSE, 5 * sizeof(float), (void*) (4 * sizeof(float)));
    G.sig.clear();
    fillTri(G, G.rest);
    G.filled = true;
    glBindVertexArray(0);
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

    const bool posed = gRigPosed.load();

    // One layer (flat quad, or the deformed mesh of a rigged row) rendered into [fbo] with the CURRENT blend state.
    auto drawLayer = [&](const LayerGL& L, GLuint fbo, bool atop) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glEnable(GL_BLEND);
        glBlendFunc(atop ? GL_DST_ALPHA : GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glViewport(0, 0, g.W, g.H);
        const int row = g.perRow > 0 ? L.id / g.perRow : 0;
        glm::mat4 am(1.f);   // attachment to another row's bone (not while rigs are shown at rest)
        if (posed && row >= 0 && (size_t) row < g.attM.size()) {
            const fox::rig::Aff& a = g.attM[(size_t) row];
            am[0] = glm::vec4(a.a, a.b, 0.f, 0.f);
            am[1] = glm::vec4(a.c, a.d, 0.f, 0.f);
            am[3] = glm::vec4(a.tx * side, a.ty * side, 0.f, 1.f);
        }
        const glm::mat4 lm = am * layerMatrix(L.xf, side);   // model: layer px -> shown paper px (clip happens in that space)
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
        {
            // log whenever what a layer is drawn with changes (never per frame): which texture, transform, mesh or flat
            static std::unordered_map<int, std::array<float, 10>> last;
            const std::array<float, 10> sig = {(float) L.tex, L.xf.tx, L.xf.ty, L.xf.sx, L.xf.sy, L.xf.rot, rg ? 1.f : 0.f, (float) L.fbo,
                                               am[3].x, am[3].y};
            auto it = last.find(L.id);
            if (it == last.end() || it->second != sig) {
                last[L.id] = sig;
                static int stBudget = 60;
                if (stBudget-- > 0)
                    LOGE("draw layer %d row %d: tex %u(%s) fbo %u xf t=(%.3f,%.3f) s=(%.3f,%.3f) rot=%.1f %s", L.id, row, L.tex,
                         glIsTexture(L.tex) ? "ok" : "NOT A TEXTURE", L.fbo, L.xf.tx, L.xf.ty, L.xf.sx, L.xf.sy, L.xf.rot, rg ? "MESH" : "flat");
            }
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, L.tex);
        if (rg) {
            glUseProgram(g.mesh.p);
            setQuadUniforms(g.mesh, v, mvp, side, 0.f);
            glUniformMatrix4fv(g.mesh.uModel, 1, GL_FALSE, glm::value_ptr(lm));
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
            const int lat = rg->rig.grid * sub + 2;   // smooth mesh + one ring of extension quads on every side
            glDrawArrays(GL_TRIANGLES, 0, (GLsizei) (lat * lat * 6));
        } else {
            glUseProgram(g.layer.p);
            setQuadUniforms(g.layer, v, mvp, side, 0.f);
            glUniform1i(g.layer.uTex, 0);
            glUniformMatrix4fv(g.layer.uModel, 1, GL_FALSE, glm::value_ptr(lm));
            glBindVertexArray(0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
    };

    // layers of every timeline row (g.layers is bottom -> top; native layer id = row * perRow + slot)
    std::vector<std::vector<size_t>> byRow;
    bool anyFx = false;
    for (size_t k = 0; k < g.layers.size(); k++) {
        const int row = g.perRow > 0 ? g.layers[k].id / g.perRow : 0;
        if (row < 0) continue;
        if ((size_t) row >= byRow.size()) byRow.resize((size_t) row + 1);
        byRow[(size_t) row].push_back(k);
        if (!g.layers[k].fx.none()) anyFx = true;
    }
    for (auto& f : g.rowFx) if (!f.none()) anyFx = true;
    for (auto& p : g.groupFx) if (!p.second.none()) anyFx = true;
    const bool useFx = anyFx && g.fxc.ok;
    auto layerOn = [&](size_t k) { return g.layers[k].visible && g.layers[k].tex; };
    auto rowOn = [&](int r) {
        for (size_t k : byRow[(size_t) r]) if (layerOn(k)) return true;
        return false;
    };
    auto rowFxOf = [&](int r) { return useFx && (size_t) r < g.rowFx.size() ? g.rowFx[(size_t) r] : fox::blend::Fx{}; };

    // one timeline row = its layers composited bottom -> top (layer blend / opacity / clipping)
    auto drawRow = [&](int r, GLuint fbo, bool) {
        const std::vector<size_t>& idx = byRow[(size_t) r];
        fox::blend::stack(g.fxc, (int) idx.size(), fbo, true,
            [&](int i) { return !layerOn(idx[(size_t) i]); },
            [&](int i) { return useFx ? g.layers[idx[(size_t) i]].fx : fox::blend::Fx{}; },
            [&](int i, GLuint f, bool atop) { drawLayer(g.layers[idx[(size_t) i]], f, atop); });
    };
    auto groupOf = [&](int r) { return (size_t) r < g.rowGroup.size() ? g.rowGroup[(size_t) r] : -1; };
    auto groupFxOf = [&](int gid) -> fox::blend::Fx {
        if (!useFx) return fox::blend::Fx{};
        for (auto& p : g.groupFx) if (p.first == gid) return p.second;
        return fox::blend::Fx{};
    };
    // rows of a group folder with an effect are composited as one picture (fox_blend.h stackRows)
    auto drawRows = [&](GLuint dst) {
        fox::blend::stackRows(g.fxc, (int) byRow.size(), dst,
            [&](int r) { return !rowOn(r); },
            rowFxOf, groupOf, groupFxOf,
            drawRow);
    };

    if (useFx) {
        // everything is composited into a transparent scene picture first (blend modes need a backdrop), then laid over the paper
        g.fxc.resize(g.W, g.H);
        fox::blend::Target* S = g.fxc.acquire();
        if (S) {
            drawRows(S->fbo);
            fox::blend::composite(g.fxc, S->tex, 0, fox::blend::Fx{}, false);
            g.fxc.giveBack();
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            drawRows(0);
        }
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        drawRows(0);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, g.W, g.H);

    // ---- Show mesh lines: GPU only, after the picture, straight onto the screen ----
    if (gMeshLines.load() && g.wire.p) {
        const int only = gMeshRow.load();
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(g.wire.p);
        glUniformMatrix4fv(g.wire.uMVP, 1, GL_FALSE, glm::value_ptr(mvp));
        glUniform1f(g.wire.uSide, side);
        glUniform2f(g.wire.uView, (float) g.W, (float) g.H);
        glBindVertexArray(0);
        {   // triangle meshes (outline-following) replace the square grid of their row
            std::unordered_map<int, TriData> snap;
            bool changed = false;
            {
                std::lock_guard<std::mutex> l(gMu);
                if (g.triVer != gTriVer) { snap = gTri; g.triVer = gTriVer; changed = true; }
            }
            if (changed) {
                for (auto& kv : g.triGL) { if (kv.second.vbo) glDeleteBuffers(1, &kv.second.vbo); if (kv.second.vao) glDeleteVertexArrays(1, &kv.second.vao); }
                g.triGL.clear();
                for (auto& kv : snap) uploadTri(g.triGL[kv.first], kv.second);
            }
            glUseProgram(g.triwire.p);
            glUniformMatrix4fv(g.triwire.uMVP, 1, GL_FALSE, glm::value_ptr(mvp));
            glUniform1f(g.triwire.uSide, side);
            glUniform2f(g.triwire.uView, (float) g.W, (float) g.H);
            for (auto& kv : g.triGL) {
                const size_t r = (size_t) kv.first;
                if ((only >= 0 && kv.first != only) || r >= byRow.size() || byRow[r].empty() || !rowOn(kv.first) || !kv.second.vao) continue;
                const LayerGL& L = g.layers[byRow[r][0]];
                glm::mat4 am(1.f);
                if (posed && r < g.attM.size()) {
                    const fox::rig::Aff& a = g.attM[r];
                    am[0] = glm::vec4(a.a, a.b, 0.f, 0.f);
                    am[1] = glm::vec4(a.c, a.d, 0.f, 0.f);
                    am[3] = glm::vec4(a.tx * side, a.ty * side, 0.f, 1.f);
                }
                const glm::mat4 lm = am * layerMatrix(L.xf, side);
                glUniformMatrix4fv(g.triwire.uModel, 1, GL_FALSE, glm::value_ptr(lm));
                const float px = std::max(1.f, v.density);
                {   // puppet-warp pins move the mesh: same rigid MLS as the picture (fox::rig::mlsRigid), refilled only when a pin moved
                    TriGL& G = kv.second;
                    std::vector<fox::rig::PinPt> pp;
                    bool warp = false;
                    if (posed && r < g.rigs.size() && g.rigs[r].rig.valid &&
                        g.rigs[r].chv.size() >= (size_t) fox::rig::kPinBase + g.rigs[r].rig.pins.size() * (size_t) fox::rig::kPinChans)
                        warp = fox::rig::buildPinPts(g.rigs[r].rig, g.rigs[r].chv.data(), pp);
                    std::vector<float> sig;
                    if (warp) for (const auto& p : pp) { sig.push_back(p.p.x); sig.push_back(p.p.y); sig.push_back(p.q.x); sig.push_back(p.q.y); }
                    sig.push_back((float) (warp ? g.rigs[r].rig.pinFalloff : 0));
                    if (!G.filled || sig != G.sig) {
                        std::vector<float> pos = G.rest;
                        if (warp) {
                            for (size_t i = 0; i + 1 < pos.size(); i += 2) {
                                const glm::vec2 q = fox::rig::mlsRigid(pp.data(), pp.size(), g.rigs[r].rig.pinFalloff, glm::vec2(G.rest[i], G.rest[i + 1]));
                                pos[i] = q.x; pos[i + 1] = q.y;
                            }
                        }
                        fillTri(G, pos);
                        G.sig = sig;
                        G.filled = true;
                    }
                }
                glBindVertexArray(kv.second.vao);
                glUniform1f(g.triwire.uWidth, 3.2f * px);
                glUniform4f(g.triwire.uColor, 0.f, 0.f, 0.f, 0.35f);
                glDrawArrays(GL_TRIANGLES, 0, kv.second.verts);
                glUniform1f(g.triwire.uWidth, 1.2f * px);
                glUniform4f(g.triwire.uColor, 0.35f, 0.9f, 1.f, 0.9f);
                glDrawArrays(GL_TRIANGLES, 0, kv.second.verts);
                glBindVertexArray(0);
            }
            glUseProgram(g.wire.p);
        }
        for (size_t r = 0; r < byRow.size() && r < g.rigs.size(); r++) {
            if (only >= 0 && (int) r != only) continue;
            if (g.triGL.count((int) r)) continue;
            const RigGL& R = g.rigs[r];
            if (!R.rig.valid || !R.posTex || !R.uploaded || R.verts.empty() || byRow[r].empty() || !rowOn((int) r)) continue;
            const LayerGL& L = g.layers[byRow[r][0]];   // every layer of a row shares its transform
            glm::mat4 am(1.f);
            if (posed && r < g.attM.size()) {
                const fox::rig::Aff& a = g.attM[r];
                am[0] = glm::vec4(a.a, a.b, 0.f, 0.f);
                am[1] = glm::vec4(a.c, a.d, 0.f, 0.f);
                am[3] = glm::vec4(a.tx * side, a.ty * side, 0.f, 1.f);
            }
            const glm::mat4 lm = am * layerMatrix(L.xf, side);
            const int sub = fox::rig::meshSub(R.rig.grid);
            glUniformMatrix4fv(g.wire.uModel, 1, GL_FALSE, glm::value_ptr(lm));
            glUniform1i(g.wire.uPos, 1);
            glUniform1i(g.wire.uGrid, R.rig.grid);
            glUniform1i(g.wire.uSub, sub);
            glUniform4f(g.wire.uRect, R.rig.l, R.rig.t, R.rig.r, R.rig.b);
            glUniform1i(g.wire.uFlat, posed ? 0 : 1);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, R.posTex);
            glActiveTexture(GL_TEXTURE0);
            const GLsizei verts = (GLsizei) (2 * (R.rig.grid + 1) * R.rig.grid * sub * 6);
            const float px = std::max(1.f, v.density);   // 1 dp
            glUniform1f(g.wire.uWidth, 3.2f * px);       // dark underlay, then the bright line on top
            glUniform4f(g.wire.uColor, 0.f, 0.f, 0.f, 0.35f);
            glDrawArrays(GL_TRIANGLES, 0, verts);
            glUniform1f(g.wire.uWidth, 1.4f * px);
            glUniform4f(g.wire.uColor, 0.35f, 0.9f, 1.f, 0.9f);
            glDrawArrays(GL_TRIANGLES, 0, verts);
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
    LOGE("surface created: %s | %s | %s | maxTex %d paper %d", (const char*) glGetString(GL_RENDERER), (const char*) glGetString(GL_VERSION),
         (const char*) glGetString(GL_SHADING_LANGUAGE_VERSION), (int) maxTex, (int) paperSize);
    g.T = std::max(256, std::min((int) paperSize, (int) maxTex));

    g.strokeProg = linkProgram(kStrokeVS, kStrokeFS);
    g.uT = glGetUniformLocation(g.strokeProg, "uT");
    LOGE("surface: strokeProg=%u uT=%d g@%p", g.strokeProg, (int) g.uT, (void*) &g);
    g.paper.p = linkProgram(kQuadVS, std::string(kPaperFS) + kSdf + kPaperMain);
    g.layer.p = linkProgram(kLayerVS, std::string(kLayerFS) + kSdf + kLayerMain);
    g.mesh.p = linkProgram(std::string(kMeshVSHead) + kSmoothFn + kMeshVSMain, std::string(kLayerFS) + kSdf + kLayerMain);
    GLC("surfaceCreated: programs linked");
    locate(g.paper);
    locate(g.layer);
    GLC("surfaceCreated: locate paper/layer");
    locate(g.mesh);
    g.wire.p = linkProgram(std::string(kWireVSHead) + kSmoothFn + kWireVSMain, kWireFS);
    locate(g.wire);
    g.wire.uView = glGetUniformLocation(g.wire.p, "uView");
    g.wire.uWidth = glGetUniformLocation(g.wire.p, "uWidth");
    g.wire.uColor = glGetUniformLocation(g.wire.p, "uColor");
    g.wire.uFlat = glGetUniformLocation(g.wire.p, "uFlat");
    g.triwire.p = linkProgram(kTriWireVS, kWireFS);
    locate(g.triwire);
    g.triwire.uView = glGetUniformLocation(g.triwire.p, "uView");
    g.triwire.uWidth = glGetUniformLocation(g.triwire.p, "uWidth");
    g.triwire.uColor = glGetUniformLocation(g.triwire.p, "uColor");
    g.triGL.clear();   // old context: names are gone
    g.triVer = 0;
    
    g.imgTex.clear();
    g.imgProg = linkProgram(fox::img::kImageVS, fox::img::kImageFS);
    GLC("surfaceCreated: image program");
    g.fxc.init(0);   // blend / clipping compositor (new context: old names are gone)
    GLC("surfaceCreated: blend compositor init (fxc)");

    glGenVertexArrays(1, &g.vao);
    glGenBuffers(1, &g.vbo);
    glBindVertexArray(g.vao);
    glBindBuffer(GL_ARRAY_BUFFER, g.vbo);
    const GLsizei stride = (GLsizei) sizeof(Vert);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, pos));
    glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, seg));
    glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, r));
    glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, GL_FALSE, stride, (void*) offsetof(Vert, col));

    GLC("surfaceCreated: vao/vbo + attributes");
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
    glChk("commands");
    if (g.rebuildAll) rebuildAllLayers();
    glChk("rebuild");
    drawScreen();
    glChk("drawScreen");
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

// Show mesh lines: [row] = timeline row whose mesh is drawn, -1 = every rigged row. Rendered in GLSL on the GL thread.
FN(void, nativeSetMeshLines)(JNIEnv*, jclass, jboolean on, jint row) {
    gMeshLines.store(on != JNI_FALSE);
    gMeshRow.store((int) row);
}

// Outline-following triangle mesh from a drawing mask (fox_mesh.h). Stateless. Result: [nV, nT, x y * nV, i j k * nT]; empty = no shape.
FN(jfloatArray, nativeGenMesh)(JNIEnv* env, jclass, jbyteArray mask, jint n, jfloat spacing, jint dilatePx) {
    const jsize want = (jsize) n * (jsize) n;
    if (!mask || n < 16 || env->GetArrayLength(mask) < want) return env->NewFloatArray(0);
    std::vector<uint8_t> m((size_t) want);
    env->GetByteArrayRegion(mask, 0, want, reinterpret_cast<jbyte*>(m.data()));
    const std::vector<float> out = fox::mesh::generate(m.data(), (int) n, spacing, (int) dilatePx);
    jfloatArray r = env->NewFloatArray((jsize) out.size());
    if (!out.empty()) env->SetFloatArrayRegion(r, 0, (jsize) out.size(), out.data());
    return r;
}

// Show [row]'s outline-following mesh (replaces its square grid). [verts] = x y per vertex, [tris] = i j k per triangle (as floats).
FN(void, nativeSetTriMesh)(JNIEnv* env, jclass, jint row, jfloatArray verts, jfloatArray tris) {
    TriData d;
    const jsize nv = verts ? env->GetArrayLength(verts) : 0, nt = tris ? env->GetArrayLength(tris) : 0;
    d.v.resize((size_t) nv); d.t.resize((size_t) nt);
    std::vector<float> tf((size_t) nt);
    if (nv) env->GetFloatArrayRegion(verts, 0, nv, d.v.data());
    if (nt) env->GetFloatArrayRegion(tris, 0, nt, tf.data());
    for (jsize i = 0; i < nt; i++) d.t[(size_t) i] = (int) tf[(size_t) i];
    std::lock_guard<std::mutex> l(gMu);
    gTri[(int) row] = std::move(d);
    gTriVer++;
}

FN(void, nativeClearTriMeshes)(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> l(gMu);
    gTri.clear();
    gTriVer++;
}

// Stateless (any thread): puppet-warp pins that fit a drawing's silhouette to a picked image (fox_fit.h).
// [src] / [tgt] = n * n bytes over the layer's paper units. Result: l t r b of the mapped target, pin count, then rx ry dx dy per pin.
FN(jfloatArray, nativeFitPins)(JNIEnv* env, jclass, jbyteArray src, jbyteArray tgt, jint n, jint mode, jboolean uniform, jint maxPins) {
    const jsize want = (jsize) n * (jsize) n;
    if (!src || !tgt || n < 16 || env->GetArrayLength(src) < want || env->GetArrayLength(tgt) < want) return env->NewFloatArray(0);
    std::vector<uint8_t> a((size_t) want), b((size_t) want);
    env->GetByteArrayRegion(src, 0, want, reinterpret_cast<jbyte*>(a.data()));
    env->GetByteArrayRegion(tgt, 0, want, reinterpret_cast<jbyte*>(b.data()));
    const std::vector<float> out = fox::fit::solve(a.data(), b.data(), (int) n, (int) mode, uniform != JNI_FALSE, (int) maxPins);
    jfloatArray r = env->NewFloatArray((jsize) out.size());
    if (!out.empty()) env->SetFloatArrayRegion(r, 0, (jsize) out.size(), out.data());
    return r;
}

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
// stroke drawn on a posed / bent drawing lands where the finger is - also outside the mesh box (extension ring).
// Returns {x, y} unchanged when nothing to undo.
FN(jfloatArray, nativeUnwarp)(JNIEnv* env, jclass, jint row, jfloat x, jfloat y) {
    float out[2] = {x, y};
    if (gRigPosed.load()) {
        std::lock_guard<std::mutex> l(gMu);
        if (row >= 0 && (size_t) row < gPub.size() && gPub[(size_t) row].valid) {
            const PubRig& P = gPub[(size_t) row];
            float u, v;
            // exact inverse of what the GPU draws (smooth surface + extension ring); old bilinear inverse as a fallback
            if (unwarpSurface(P.verts.data(), P.verts.size() / 4, P.grid, P.l, P.t, P.r, P.b, x, y, u, v) ||
                fox::rig::unwarp(P.verts.data(), P.verts.size() / 4, P.grid, x, y, u, v)) { out[0] = u; out[1] = v; }
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

// Blend / opacity / clipping. Layer arrays are indexed by native layer id (same order as nativeSetLayers), row arrays by
// timeline row (bottom -> top).
FN(void, nativeSetFx)(JNIEnv* env, jclass, jintArray lMode, jfloatArray lOpacity, jbooleanArray lClip,
                      jintArray rMode, jfloatArray rOpacity, jbooleanArray rClip) {
    const jsize nL = env->GetArrayLength(lMode), nR = env->GetArrayLength(rMode);
    if (env->GetArrayLength(lOpacity) < nL || env->GetArrayLength(lClip) < nL ||
        env->GetArrayLength(rOpacity) < nR || env->GetArrayLength(rClip) < nR) return;
    Cmd c; c.op = Op::SetFx;
    c.ids.resize((size_t) (1 + nL + nR));
    c.pts.resize((size_t) (nL + nR));
    c.vis.resize((size_t) (nL + nR));
    c.ids[0] = nL;
    if (nL) {
        env->GetIntArrayRegion(lMode, 0, nL, c.ids.data() + 1);
        env->GetFloatArrayRegion(lOpacity, 0, nL, c.pts.data());
        env->GetBooleanArrayRegion(lClip, 0, nL, c.vis.data());
    }
    if (nR) {
        env->GetIntArrayRegion(rMode, 0, nR, c.ids.data() + 1 + nL);
        env->GetFloatArrayRegion(rOpacity, 0, nR, c.pts.data() + nL);
        env->GetBooleanArrayRegion(rClip, 0, nR, c.vis.data() + nL);
    }
    push(std::move(c));
}

// Blend / opacity / clipping of group folders. rowGroup = group id of every timeline row (bottom -> top, -1 = none).
FN(void, nativeSetGroupFx)(JNIEnv* env, jclass, jintArray rowGroup, jintArray gIds, jintArray gMode,
                           jfloatArray gOpacity, jbooleanArray gClip) {
    const jsize nR = env->GetArrayLength(rowGroup), nG = env->GetArrayLength(gIds);
    if (env->GetArrayLength(gMode) < nG || env->GetArrayLength(gOpacity) < nG || env->GetArrayLength(gClip) < nG) return;
    Cmd c; c.op = Op::SetGroupFx;
    c.ids.resize((size_t) (1 + nR + 2 * nG));
    c.pts.resize((size_t) nG);
    c.vis.resize((size_t) nG);
    c.ids[0] = nR;
    if (nR) env->GetIntArrayRegion(rowGroup, 0, nR, c.ids.data() + 1);
    if (nG) {
        env->GetIntArrayRegion(gIds, 0, nG, c.ids.data() + 1 + nR);
        env->GetIntArrayRegion(gMode, 0, nG, c.ids.data() + 1 + nR + nG);
        env->GetFloatArrayRegion(gOpacity, 0, nG, c.pts.data());
        env->GetBooleanArrayRegion(gClip, 0, nG, c.vis.data());
    }
    push(std::move(c));
}

FN(void, nativeAddStroke)(JNIEnv* env, jclass, jint cel, jint layer, jint argb, jfloat size, jboolean erase, jfloatArray xy) {
    Cmd c; c.op = Op::AddStroke; c.i0 = cel; c.i1 = layer; c.argb = (uint32_t) argb; c.f0 = size; c.b = erase;
    const jsize n = env->GetArrayLength(xy);
    c.pts.resize(n);
    env->GetFloatArrayRegion(xy, 0, n, c.pts.data());
    push(std::move(c));
}

// Project load: queue every stroke of a parsed strokes.bin (fox_strokeblob.h) straight from native memory, same result as one
// nativeAddStroke / nativeAddImage per stroke from Kotlin, in order, without the points ever touching the Java heap.
//   celRow    : pairs (celId, timeline row index); a stroke whose cel is not listed is skipped (Kotlin did the same)
//   layerTab  : rows * slots layer ids (Int.MIN_VALUE = empty slot); native layer = row * slots + slot of the stroke's layer id (0 if absent)
//   imgHandles: native image handle per blob image file (-1 = file missing: the stroke stays in history but draws nothing)
FN(jboolean, nativeAddStrokesFromBlob)(JNIEnv* env, jclass, jlong h, jintArray jCelRow, jintArray jLayerTab, jint slots,
                                       jintArray jImgHandles) {
    auto b = fox::proj::get(h);
    if (!b || slots <= 0) return JNI_FALSE;
    auto ints = [&](jintArray a) {
        std::vector<jint> v((size_t) (a ? env->GetArrayLength(a) : 0));
        if (!v.empty()) env->GetIntArrayRegion(a, 0, (jsize) v.size(), v.data());
        return v;
    };
    const std::vector<jint> celRow = ints(jCelRow), tab = ints(jLayerTab), imgH = ints(jImgHandles);
    std::unordered_map<int, int> row;
    row.reserve(celRow.size());
    for (size_t i = 0; i + 1 < celRow.size(); i += 2) row[celRow[i]] = celRow[i + 1];
    const size_t rows = tab.size() / (size_t) slots;

    for (int32_t i = 0; i < b->count; i++) {
        const int32_t* m = &b->meta[(size_t) i * fox::proj::kMetaStride];
        auto it = row.find(m[0]);
        if (it == row.end()) continue;
        const int r = it->second;
        int slot = 0;
        if ((size_t) r < rows) {
            for (int k = 0; k < slots; k++) if (tab[(size_t) r * slots + k] == m[1]) { slot = k; break; }
        }
        Cmd c;
        c.i0 = m[0];
        c.i1 = r * slots + slot;
        if (m[3] & fox::proj::kFlagImage) {
            c.op = Op::AddImage;
            c.ids = {(size_t) m[4] < imgH.size() ? imgH[(size_t) m[4]] : -1};
            c.pts.assign(b->img.begin() + m[5], b->img.begin() + m[5] + 5);
        } else {
            c.op = Op::AddStroke;
            c.argb = (uint32_t) m[2];
            c.f0 = b->sizes[(size_t) i];
            c.b = (m[3] & fox::proj::kFlagErase) != 0;
            c.pts.assign(b->pts.begin() + m[4], b->pts.begin() + m[4] + (size_t) m[5] * 2);
        }
        push(std::move(c));
    }
    return JNI_TRUE;
}

// Any thread: only stores pixels. The GL texture is created lazily on the GL thread.
FN(jint, nativeImageLoad)(JNIEnv* env, jclass, jint w, jint h, jobject buf) {
    if (w <= 0 || h <= 0 || !buf) return -1;
    const void* p = env->GetDirectBufferAddress(buf);
    const size_t n = (size_t) w * (size_t) h * 4;
    if (!p || (size_t) env->GetDirectBufferCapacity(buf) < n) return -1;
    auto im = std::make_shared<fox::img::Image>();
    im->w = w; im->h = h;
    im->px.assign(static_cast<const uint8_t*>(p), static_cast<const uint8_t*>(p) + n);
    return fox::img::put(std::move(im));
}

FN(void, nativeImageRelease)(JNIEnv*, jclass, jint h) {
    fox::img::drop(h);
    Cmd c; c.op = Op::FreeImage; c.ids = {h};
    push(std::move(c));
}

FN(void, nativeAddImage)(JNIEnv*, jclass, jint cel, jint layer, jint handle, jfloat cx, jfloat cy, jfloat w, jfloat h, jfloat rot) {
    Cmd c; c.op = Op::AddImage; c.i0 = cel; c.i1 = layer; c.ids = {handle};
    c.pts = {cx, cy, w, h, rot};
    push(std::move(c));
}