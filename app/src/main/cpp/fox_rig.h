// fox_rig.h - 2D bone rig + curve deformer. Header-only, no GL, no JNI: shared by fox_canvas.cpp (live canvas) and
// fox_exporter.cpp (movie export), so both deform the picture with the SAME code. Kotlin owns the UI and the data
// (Rig.kt); every pose, every skinned vertex and every bent vertex is computed here, in C++.
//
// PIPELINE (per timeline row that has a rig, per frame)
//   1. channelValues : static base values, overridden by keyframed lanes (fox::anim::evalChannels, same easing as
//                      the layer transform keys).
//   2. computePose   : forward kinematics (parent -> child), then IK for every bone with an IK chain.
//   3. buildMesh     : a grid mesh covers the drawing. Every vertex is
//                        a) linear-blend skinned by up to 4 bones (weights = auto falloff + painted bias),
//                        b) blended toward the curve-deformed position of every deform curve that reaches it.
//                      The result is x y u v per vertex; u v is the REST position, i.e. the texel to sample.
//
// BONES  (rest pose is absolute: head, angle in degrees clockwise / y down, length; all in layer paper units 0..1)
//   world_rest[b]  = T(head) * R(angle)
//   local_pose[b]  = T(dx, dy) * local_rest[b] * R(rot) * S(sx, sy)      dx dy: parent space; rot / S: about the bone head
//   world_pose[b]  = world_pose[parent] * local_pose[b]
//   skin[b]        = world_pose[b] * inverse(world_rest[b])
//   Parents MUST come before their children in the bone list (Rig.kt guarantees it).
//
// DEFORM CURVES  (Catmull-Rom through control points; "bend the image along a curve")
//   Each control point is bound to a bone (or free). Posed point = skin[bone] * rest + (dx, dy) in paper space.
//   A vertex stores (s, w, v): curve parameter of its nearest rest-curve point, offset along / across the curve.
//   Posed vertex = C'(s) + w * T'(s) + v * thickness(s) * N'(s).  Pose == rest gives back the vertex exactly.
//
// CHANNEL ids (keys use them, flat key layout of fox_anim.h):
//   bone b, channel c            : b * kBoneChans + c                          c = DX DY ROT SX SY IKX IKY IKMIX
//   curve k  mix                 : kCurveBase + k * kCurveChans
//   curve k, control point i, c  : kCurveBase + k * kCurveChans + 1 + i * 3 + c      c = 0 dx, 1 dy, 2 thickness
//
// PUPPET WARP (pins)  - a third deformation source, applied on top of bones + curves
//   Every pin has a rest position and a keyed offset (dx, dy); posed pin = rest + offset. Mesh vertices are moved by
//   Moving Least Squares, RIGID variant (Schaefer et al. 2006): around each vertex the pins are weighted 1 / d^(2 * falloff),
//   and the vertex follows the weighted rotation + translation of the pins. Pins that are not moved are anchors. The four
//   corners of the mesh rect are implicit anchors, so a single pin bends its neighbourhood instead of dragging everything.
//   channels: kPinBase + pin * kPinChans + (0 dx, 1 dy)
//
// ROW BLOB (float stream, see parseRow):
//   [0] nBones [1] nCurves [2] grid cells per side [3..6] mesh rect l t r b [7] nKeys [8] nPaint [9] nPins
//   bones  : nBones * kBoneStride   parent hx hy angDeg len radius falloff strength base[8] ikChain ikBend 0 0
//   curves : per curve kCurveHead (nCtl reach softness baseMix 0 0 0 0) + nCtl * kCtlStride (rx ry bone bdx bdy bth)
//   pins   : (only when nPins > 0) kPinHead (falloff 1..3, 0) + nPins * kPinStride (rx ry bdx bdy)
//   keys   : nKeys * fox::anim::kKeyStride
//   paint  : nPaint floats (= vertices * bones when valid): weight bias in -1..1
// SET BLOB: [nRows, then per row: len, len floats]   (len 0 = row without rig)
#pragma once

#include "fox_anim.h"

#include <glm/glm.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace fox::rig {

constexpr int kBoneChans = 8;
constexpr int kMaxBones = 64;
constexpr int kMaxCurves = 8;
constexpr int kMaxCtl = 16;
constexpr int kCurveChans = 1 + 3 * kMaxCtl;
constexpr int kCurveBase = kMaxBones * kBoneChans;
constexpr int kMaxPins = 24;
constexpr int kPinChans = 2;
constexpr int kPinBase = kCurveBase + kMaxCurves * kCurveChans;
constexpr int kPinRotBase = kPinBase + kMaxPins * kPinChans;   // pin rotation channels (degrees, clockwise), one per pin
constexpr int kTotalChans = kPinRotBase + kMaxPins;
constexpr int kPinStride = 6;
constexpr int kPinHead = 2;
constexpr int kBoneStride = 20;
constexpr int kCtlStride = 6;
constexpr int kCurveHead = 8;
constexpr int kRowHead = 10;
constexpr int kSamples = 14;   // polyline samples per spline segment (binding + overlay)
constexpr int kMaxGrid = 128;

enum BoneChan : int { DX = 0, DY, ROT, SX, SY, IKX, IKY, IKMIX };

namespace detail {
constexpr float kPi = 3.14159265358979f;
constexpr float kDeg = kPi / 180.f;
inline float wrapPi(float a) {
    while (a > kPi) a -= 2.f * kPi;
    while (a < -kPi) a += 2.f * kPi;
    return a;
}
inline float clamp01(float x) { return std::min(1.f, std::max(0.f, x)); }
}  // namespace detail

// ------------------------------------------------------------------------------------------ 2x3 affine

// x' = a x + c y + tx ;  y' = b x + d y + ty
struct Aff {
    float a = 1.f, b = 0.f, c = 0.f, d = 1.f, tx = 0.f, ty = 0.f;
};

inline glm::vec2 apply(const Aff& m, glm::vec2 p) { return glm::vec2(m.a * p.x + m.c * p.y + m.tx, m.b * p.x + m.d * p.y + m.ty); }

// m * n  (n first)
inline Aff mul(const Aff& m, const Aff& n) {
    Aff r;
    r.a = m.a * n.a + m.c * n.b;
    r.b = m.b * n.a + m.d * n.b;
    r.c = m.a * n.c + m.c * n.d;
    r.d = m.b * n.c + m.d * n.d;
    r.tx = m.a * n.tx + m.c * n.ty + m.tx;
    r.ty = m.b * n.tx + m.d * n.ty + m.ty;
    return r;
}

inline Aff inverse(const Aff& m) {
    const float det = m.a * m.d - m.b * m.c;
    if (std::fabs(det) < 1e-12f) return Aff{};
    Aff r;
    r.a = m.d / det;
    r.b = -m.b / det;
    r.c = -m.c / det;
    r.d = m.a / det;
    r.tx = -(r.a * m.tx + r.c * m.ty);
    r.ty = -(r.b * m.tx + r.d * m.ty);
    return r;
}

// T(tx, ty) * R(rot, clockwise on a y-down screen) * S(sx, sy)
inline Aff trs(float tx, float ty, float rotRad, float sx, float sy) {
    const float c = std::cos(rotRad), s = std::sin(rotRad);
    return Aff{c * sx, s * sx, -s * sy, c * sy, tx, ty};
}

// ------------------------------------------------------------------------------------------ data

struct Bone {
    int parent = -1;
    glm::vec2 head{0.f};
    float ang = 0.f, len = 0.1f, radius = 0.1f, falloff = 2.5f, strength = 1.f;
    float base[kBoneChans] = {0.f, 0.f, 0.f, 1.f, 1.f, 0.f, 0.f, 1.f};
    int ikChain = 0;       // 0 = no IK; n = this bone and its n-1 parents are solved toward (IKX, IKY)
    float ikBend = 1.f;    // two-bone chains: which way the middle joint folds (+1 / -1)
};

struct Ctl {
    glm::vec2 rest{0.f};
    int bone = -1;
    float bdx = 0.f, bdy = 0.f, bth = 1.f;   // static pose values (used while the channel has no keys)
};

struct Pin {
    glm::vec2 rest{0.f};
    float bdx = 0.f, bdy = 0.f;   // static offset (used while the channel has no keys)
    float brot = 0.f;             // static rotation, degrees clockwise (used while the channel has no keys)
    float stiff = 1.f;            // influence multiplier of this pin in the MLS weights (0.2 soft .. 4 dominant)
};

struct Curve {
    float reach = 0.15f, soft = 0.6f, baseMix = 1.f;
    std::vector<Ctl> ctl;
};

struct Bind {   // per vertex, per curve
    float s = 0.f, w = 0.f, v = 0.f, mask = 0.f;
};

struct Rig {
    bool valid = false;
    int grid = 32;
    float l = 0.f, t = 0.f, r = 1.f, b = 1.f;
    std::vector<Bone> bones;
    std::vector<Curve> curves;
    std::vector<Pin> pins;
    int pinFalloff = 2;        // 1 = wide (soft), 2 = medium, 3 = tight
    std::vector<float> keys;
    int nKeys = 0;
    std::vector<float> paint;

    // ---- derived (prepare) ----
    std::vector<Aff> restW, restInv, restLocal;
    std::vector<glm::vec2> rest;          // V rest positions (paper units)
    std::vector<uint32_t> idx;            // triangles
    std::vector<int> bi;                  // V * 4 bone indices (-1 = unused)
    std::vector<float> bw;                // V * 4 weights
    std::vector<float> restWt;            // V weight that stays on the rest position
    std::vector<std::vector<Bind>> bind;  // per curve, per vertex
    std::vector<float> bindKey;           // what bind was computed from (reuse while unchanged)

    int verts() const { return (grid + 1) * (grid + 1); }
};

struct Pose {
    std::vector<std::array<float, kBoneChans>> vals;
    std::vector<Aff> world, skin;
};

// ------------------------------------------------------------------------------------------ parsing

inline bool parseRow(const float* d, size_t n, Rig& R) {
    R = Rig{};
    if (!d || n < (size_t) kRowHead) return false;
    const int nB = (int) d[0], nC = (int) d[1], grid = (int) d[2];
    if (nB < 0 || nB > kMaxBones || nC < 0 || nC > kMaxCurves || grid < 2 || grid > kMaxGrid) return false;
    R.grid = grid;
    R.l = d[3]; R.t = d[4]; R.r = d[5]; R.b = d[6];
    if (!(R.r - R.l > 1e-4f) || !(R.b - R.t > 1e-4f)) return false;
    const int nKeys = std::max(0, (int) d[7]);
    const size_t nPaint = (size_t) std::max(0, (int) d[8]);
    size_t o = kRowHead;

    if (o + (size_t) nB * kBoneStride > n) return false;
    R.bones.resize((size_t) nB);
    for (int i = 0; i < nB; i++, o += kBoneStride) {
        Bone& B = R.bones[(size_t) i];
        const float* p = d + o;
        B.parent = (int) p[0];
        if (B.parent < 0 || B.parent >= i) B.parent = -1;   // parents must precede children
        B.head = glm::vec2(p[1], p[2]);
        B.ang = p[3];
        B.len = std::max(1e-4f, p[4]);
        B.radius = std::max(1e-4f, p[5]);
        B.falloff = p[6];
        B.strength = p[7];
        for (int c = 0; c < kBoneChans; c++) B.base[c] = p[8 + c];
        B.ikChain = std::clamp((int) p[16], 0, 8);
        B.ikBend = p[17] < 0.f ? -1.f : 1.f;
    }

    R.curves.resize((size_t) nC);
    for (int k = 0; k < nC; k++) {
        if (o + (size_t) kCurveHead > n) return false;
        Curve& C = R.curves[(size_t) k];
        const int nCtl = std::clamp((int) d[o], 0, kMaxCtl);
        C.reach = std::max(1e-4f, d[o + 1]);
        C.soft = detail::clamp01(d[o + 2]);
        C.baseMix = d[o + 3];
        o += kCurveHead;
        if (o + (size_t) nCtl * kCtlStride > n) return false;
        C.ctl.resize((size_t) nCtl);
        for (int i = 0; i < nCtl; i++, o += kCtlStride) {
            Ctl& T = C.ctl[(size_t) i];
            const float* p = d + o;
            T.rest = glm::vec2(p[0], p[1]);
            T.bone = (int) p[2];
            if (T.bone < -1 || T.bone >= nB) T.bone = -1;
            T.bdx = p[3]; T.bdy = p[4]; T.bth = p[5];
        }
    }

    const int nPins = std::clamp((int) d[9], 0, kMaxPins);
    if (nPins > 0) {
        if (o + (size_t) kPinHead + (size_t) nPins * kPinStride > n) return false;
        R.pinFalloff = std::clamp((int) std::lround(d[o]), 1, 3);
        o += kPinHead;
        R.pins.resize((size_t) nPins);
        for (int i = 0; i < nPins; i++, o += kPinStride) {
            Pin& P = R.pins[(size_t) i];
            P.rest = glm::vec2(d[o], d[o + 1]);
            P.bdx = d[o + 2]; P.bdy = d[o + 3];
            P.brot = d[o + 4];
            P.stiff = std::clamp(d[o + 5], 0.2f, 4.f);
        }
    }

    if (o + (size_t) nKeys * fox::anim::kKeyStride > n) return false;
    R.keys.assign(d + o, d + o + (size_t) nKeys * fox::anim::kKeyStride);
    R.nKeys = nKeys;
    o += (size_t) nKeys * fox::anim::kKeyStride;

    if (nPaint > 0 && o + nPaint <= n) R.paint.assign(d + o, d + o + nPaint);
    R.valid = nB > 0 || nC > 0 || nPins > 0;
    return true;
}

// [nRows, (len, floats...) * nRows]
inline void parseSet(const float* d, size_t n, std::vector<Rig>& out) {
    out.clear();
    if (!d || n < 1) return;
    const float rf = d[0];
    if (!(rf >= 0.f) || rf > (float) (n - 1) || rf > 4096.f) return;   // each row costs at least its length slot
    const int rows = (int) rf;
    size_t o = 1;
    for (int r = 0; r < rows; r++) {
        Rig R;
        if (o >= n) { out.push_back(std::move(R)); continue; }
        const size_t len = (size_t) std::max(0.f, d[o++]);
        if (len > 0 && o + len <= n) parseRow(d + o, len, R);
        out.push_back(std::move(R));
        o += len;
    }
}

// ------------------------------------------------------------------------------------------ spline

namespace detail {

inline void crNeighbours(const std::vector<glm::vec2>& P, int seg, glm::vec2& p0, glm::vec2& p1, glm::vec2& p2, glm::vec2& p3) {
    const int n = (int) P.size();
    p1 = P[(size_t) seg];
    p2 = P[(size_t) seg + 1];
    p0 = seg > 0 ? P[(size_t) seg - 1] : 2.f * P[0] - P[1];
    p3 = seg + 2 < n ? P[(size_t) seg + 2] : 2.f * P[(size_t) n - 1] - P[(size_t) n - 2];
}

// Uniform Catmull-Rom at parameter s in [0, n-1]. [tan] is dC/ds.
inline void evalSpline(const std::vector<glm::vec2>& P, float s, glm::vec2& pos, glm::vec2& tan) {
    const int n = (int) P.size();
    s = std::clamp(s, 0.f, (float) (n - 1));
    const int seg = std::min((int) s, n - 2);
    const float t = s - (float) seg, t2 = t * t, t3 = t2 * t;
    glm::vec2 p0, p1, p2, p3;
    crNeighbours(P, seg, p0, p1, p2, p3);
    pos = 0.5f * (2.f * p1 + (p2 - p0) * t + (2.f * p0 - 5.f * p1 + 4.f * p2 - p3) * t2 + (3.f * p1 - p0 - 3.f * p2 + p3) * t3);
    tan = 0.5f * ((p2 - p0) + 2.f * (2.f * p0 - 5.f * p1 + 4.f * p2 - p3) * t + 3.f * (3.f * p1 - p0 - 3.f * p2 + p3) * t2);
}

inline glm::vec2 unitOr(glm::vec2 v, glm::vec2 fallback) {
    const float l = std::sqrt(v.x * v.x + v.y * v.y);
    return l < 1e-7f ? fallback : v / l;
}

inline float segDist(glm::vec2 p, glm::vec2 a, glm::vec2 b, float* tOut = nullptr) {
    const glm::vec2 ab = b - a;
    const float l2 = ab.x * ab.x + ab.y * ab.y;
    float t = l2 > 1e-12f ? ((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2 : 0.f;
    t = clamp01(t);
    if (tOut) *tOut = t;
    const glm::vec2 q = a + ab * t - p;
    return std::sqrt(q.x * q.x + q.y * q.y);
}

}  // namespace detail

// ------------------------------------------------------------------------------------------ prepare

inline void prepareBones(Rig& R) {
    const size_t nB = R.bones.size();
    R.restW.resize(nB);
    R.restInv.resize(nB);
    R.restLocal.resize(nB);
    for (size_t b = 0; b < nB; b++) {
        const Bone& B = R.bones[b];
        const Aff w = trs(B.head.x, B.head.y, B.ang * detail::kDeg, 1.f, 1.f);
        R.restW[b] = w;
        R.restInv[b] = inverse(w);
        R.restLocal[b] = B.parent >= 0 ? mul(R.restInv[(size_t) B.parent], w) : w;
    }
}

inline float autoWeight(const Bone& B, glm::vec2 p) {
    const glm::vec2 tail = B.head + glm::vec2(std::cos(B.ang * detail::kDeg), std::sin(B.ang * detail::kDeg)) * B.len;
    const float d = detail::segDist(p, B.head, tail);
    const float t = d / B.radius;
    if (t >= 1.f) return 0.f;
    return std::pow(1.f - t, std::max(0.1f, B.falloff)) * B.strength;
}

// Mesh + skin weights (+ curve binding when [withCurves]). [prev] lets an unchanged curve binding be reused, which
// matters while a weight is being painted (the binding is the expensive part and does not depend on the paint).
inline void prepareMesh(Rig& R, const Rig* prev, bool withCurves) {
    const int N = R.grid, W = N + 1, V = W * W, nB = (int) R.bones.size();
    R.rest.resize((size_t) V);
    for (int j = 0; j <= N; j++)
        for (int i = 0; i <= N; i++)
            R.rest[(size_t) (j * W + i)] = glm::vec2(R.l + (R.r - R.l) * (float) i / (float) N, R.t + (R.b - R.t) * (float) j / (float) N);
    R.idx.clear();
    R.idx.reserve((size_t) N * N * 6);
    for (int j = 0; j < N; j++)
        for (int i = 0; i < N; i++) {
            const uint32_t a = (uint32_t) (j * W + i), b = a + 1, c = a + (uint32_t) W, d = c + 1;
            R.idx.insert(R.idx.end(), {a, b, c, b, d, c});
        }

    // ---- skin weights ----
    R.bi.assign((size_t) V * 4, -1);
    R.bw.assign((size_t) V * 4, 0.f);
    R.restWt.assign((size_t) V, 1.f);
    const bool usePaint = nB > 0 && R.paint.size() == (size_t) V * (size_t) nB;
    for (int v = 0; v < V; v++) {
        int topI[4] = {-1, -1, -1, -1};
        float topW[4] = {0.f, 0.f, 0.f, 0.f};
        for (int b = 0; b < nB; b++) {
            float w = autoWeight(R.bones[(size_t) b], R.rest[(size_t) v]);
            if (usePaint) w += R.paint[(size_t) v * (size_t) nB + (size_t) b];
            w = detail::clamp01(w);
            if (w <= 1e-4f) continue;
            int m = 0;   // slot of the smallest kept weight
            for (int k = 1; k < 4; k++) if (topW[k] < topW[m]) m = k;
            if (w > topW[m]) { topW[m] = w; topI[m] = b; }
        }
        float sum = 0.f;
        for (int k = 0; k < 4; k++) sum += topW[k];
        if (sum > 1.f) {
            for (int k = 0; k < 4; k++) topW[k] /= sum;
            R.restWt[(size_t) v] = 0.f;
        } else {
            R.restWt[(size_t) v] = 1.f - sum;
        }
        for (int k = 0; k < 4; k++) { R.bi[(size_t) v * 4 + (size_t) k] = topI[k]; R.bw[(size_t) v * 4 + (size_t) k] = topW[k]; }
    }

    // ---- curve binding ----
    if (!withCurves) { R.bind.clear(); R.bindKey.clear(); return; }
    std::vector<float> key = {(float) N, R.l, R.t, R.r, R.b};
    for (const Curve& C : R.curves) {
        key.push_back((float) C.ctl.size()); key.push_back(C.reach); key.push_back(C.soft);
        for (const Ctl& T : C.ctl) { key.push_back(T.rest.x); key.push_back(T.rest.y); }
    }
    if (prev && prev->bindKey == key && prev->bind.size() == R.curves.size()) {
        R.bind = prev->bind;
        R.bindKey = std::move(key);
        return;
    }
    R.bindKey = std::move(key);
    R.bind.assign(R.curves.size(), std::vector<Bind>());
    for (size_t k = 0; k < R.curves.size(); k++) {
        const Curve& C = R.curves[k];
        std::vector<Bind>& out = R.bind[k];
        out.assign((size_t) V, Bind{});
        const int n = (int) C.ctl.size();
        if (n < 2) continue;
        std::vector<glm::vec2> P((size_t) n);
        for (int i = 0; i < n; i++) P[(size_t) i] = C.ctl[(size_t) i].rest;
        // rest polyline: sample parameter s = i / kSamples
        const int S = (n - 1) * kSamples;
        std::vector<glm::vec2> poly((size_t) S + 1);
        for (int i = 0; i <= S; i++) {
            glm::vec2 pos, tan;
            detail::evalSpline(P, (float) i / (float) kSamples, pos, tan);
            poly[(size_t) i] = pos;
        }
        const float core = 1.f - C.soft;
        for (int v = 0; v < V; v++) {
            const glm::vec2 p = R.rest[(size_t) v];
            float best = 1e30f, bestS = 0.f;
            for (int i = 0; i < S; i++) {
                float t = 0.f;
                const float d = detail::segDist(p, poly[(size_t) i], poly[(size_t) i + 1], &t);
                if (d < best) { best = d; bestS = ((float) i + t) / (float) kSamples; }
            }
            glm::vec2 pos, tan;
            detail::evalSpline(P, bestS, pos, tan);
            const glm::vec2 T = detail::unitOr(tan, glm::vec2(1.f, 0.f));
            const glm::vec2 N2(-T.y, T.x);
            const glm::vec2 q = p - pos;
            Bind& B = out[(size_t) v];
            B.s = bestS;
            B.w = q.x * T.x + q.y * T.y;
            B.v = q.x * N2.x + q.y * N2.y;
            const float t = best / C.reach;
            float m;
            if (t >= 1.f) m = 0.f;
            else if (t <= core) m = 1.f;
            else { const float x = (t - core) / std::max(1e-4f, 1.f - core); m = 1.f - x * x * (3.f - 2.f * x); }
            B.mask = m;
        }
    }
}

inline void prepare(Rig& R, const Rig* prev = nullptr, bool withCurves = true) {
    prepareBones(R);
    prepareMesh(R, prev, withCurves);
}

// ------------------------------------------------------------------------------------------ pose

// Static values, overridden by keyed lanes. [chv] is resized to kTotalChans.
inline void channelValues(const Rig& R, float local, std::vector<float>& chv) {
    chv.assign((size_t) kTotalChans, 0.f);
    for (size_t b = 0; b < R.bones.size(); b++)
        for (int c = 0; c < kBoneChans; c++) chv[b * kBoneChans + (size_t) c] = R.bones[b].base[c];
    for (size_t k = 0; k < R.curves.size(); k++) {
        const size_t base = (size_t) kCurveBase + k * kCurveChans;
        chv[base] = R.curves[k].baseMix;
        for (size_t i = 0; i < R.curves[k].ctl.size(); i++) {
            chv[base + 1 + i * 3] = R.curves[k].ctl[i].bdx;
            chv[base + 2 + i * 3] = R.curves[k].ctl[i].bdy;
            chv[base + 3 + i * 3] = R.curves[k].ctl[i].bth;
        }
    }
    for (size_t i = 0; i < R.pins.size(); i++) {
        chv[(size_t) kPinBase + i * kPinChans] = R.pins[i].bdx;
        chv[(size_t) kPinBase + i * kPinChans + 1] = R.pins[i].bdy;
        chv[(size_t) kPinRotBase + i] = R.pins[i].brot;
    }
    fox::anim::evalChannels(R.keys.data(), R.nKeys, local, chv.data(), kTotalChans);
}

namespace detail {

inline void fk(const Rig& R, Pose& P) {
    const size_t nB = R.bones.size();
    for (size_t b = 0; b < nB; b++) {
        const auto& v = P.vals[b];
        const Aff loc = mul(trs(v[DX], v[DY], 0.f, 1.f, 1.f), mul(R.restLocal[b], trs(0.f, 0.f, v[ROT] * kDeg, v[SX], v[SY])));
        const int par = R.bones[b].parent;
        P.world[b] = par >= 0 ? mul(P.world[(size_t) par], loc) : loc;
    }
}

inline glm::vec2 tailOf(const Rig& R, const Pose& P, int b) { return apply(P.world[(size_t) b], glm::vec2(R.bones[(size_t) b].len, 0.f)); }

inline void solveIk(const Rig& R, Pose& P, int tip) {
    const Bone& TB = R.bones[(size_t) tip];
    if (TB.ikChain <= 0) return;
    const float mix = clamp01(P.vals[(size_t) tip][IKMIX]);
    if (mix <= 0.f) return;
    int chain[8];
    int n = 0;
    for (int b = tip; b >= 0 && n < TB.ikChain && n < 8; b = R.bones[(size_t) b].parent) chain[n++] = b;
    const glm::vec2 T(P.vals[(size_t) tip][IKX], P.vals[(size_t) tip][IKY]);
    const float toDeg = 1.f / kDeg;

    if (n == 2) {   // analytic two-bone
        const int lo = chain[0], up = chain[1];
        const glm::vec2 S = apply(P.world[(size_t) up], glm::vec2(0.f));
        glm::vec2 E = apply(P.world[(size_t) lo], glm::vec2(0.f));
        const glm::vec2 H = tailOf(R, P, tip);
        const float l1 = glm::length(E - S), l2 = glm::length(H - E);
        if (l1 < 1e-6f || l2 < 1e-6f) return;
        const glm::vec2 dT = T - S;
        const float d = std::clamp(glm::length(dT), std::fabs(l1 - l2) + 1e-5f, l1 + l2 - 1e-5f);
        const float phi = std::atan2(dT.y, dT.x);
        const float cosA = std::clamp((l1 * l1 + d * d - l2 * l2) / (2.f * l1 * d), -1.f, 1.f);
        const float psi1 = phi - TB.ikBend * std::acos(cosA);
        const float cur1 = std::atan2(P.world[(size_t) up].b, P.world[(size_t) up].a);
        P.vals[(size_t) up][ROT] += wrapPi(psi1 - cur1) * mix * toDeg;
        fk(R, P);
        E = apply(P.world[(size_t) lo], glm::vec2(0.f));
        const glm::vec2 aim = T - E;
        if (glm::length(aim) < 1e-6f) return;
        const float cur2 = std::atan2(P.world[(size_t) lo].b, P.world[(size_t) lo].a);
        P.vals[(size_t) lo][ROT] += wrapPi(std::atan2(aim.y, aim.x) - cur2) * mix * toDeg;
        fk(R, P);
        return;
    }

    // CCD for 1 or 3+ bones
    float fkRot[8];
    for (int i = 0; i < n; i++) fkRot[i] = P.vals[(size_t) chain[i]][ROT];
    for (int it = 0; it < 20; it++) {
        for (int i = 0; i < n; i++) {
            const int x = chain[i];
            const glm::vec2 J = apply(P.world[(size_t) x], glm::vec2(0.f));
            const glm::vec2 v1 = tailOf(R, P, tip) - J, v2 = T - J;
            if (glm::dot(v1, v1) < 1e-10f || glm::dot(v2, v2) < 1e-10f) continue;
            const float a = std::atan2(v1.x * v2.y - v1.y * v2.x, glm::dot(v1, v2));
            P.vals[(size_t) x][ROT] += a * toDeg;
            fk(R, P);
        }
        if (glm::length(tailOf(R, P, tip) - T) < 1e-4f) break;
    }
    for (int i = 0; i < n; i++) {
        float& r = P.vals[(size_t) chain[i]][ROT];
        r = fkRot[i] + (r - fkRot[i]) * mix;
    }
    fk(R, P);
}

}  // namespace detail

inline void computePose(const Rig& R, const float* chv, Pose& P) {
    const size_t nB = R.bones.size();
    P.vals.resize(nB);
    P.world.resize(nB);
    P.skin.resize(nB);
    for (size_t b = 0; b < nB; b++)
        for (int c = 0; c < kBoneChans; c++) P.vals[b][(size_t) c] = chv[b * kBoneChans + (size_t) c];
    detail::fk(R, P);
    for (size_t b = 0; b < nB; b++) detail::solveIk(R, P, (int) b);
    for (size_t b = 0; b < nB; b++) P.skin[b] = mul(P.world[b], R.restInv[b]);
}

// Posed control points of curve [k] (skin + keyed offset) and thickness per point.
inline void posedCurve(const Rig& R, const Pose& P, const float* chv, size_t k, std::vector<glm::vec2>& pts, std::vector<float>& thick) {
    const Curve& C = R.curves[k];
    const size_t base = (size_t) kCurveBase + k * kCurveChans;
    pts.resize(C.ctl.size());
    thick.resize(C.ctl.size());
    for (size_t i = 0; i < C.ctl.size(); i++) {
        const Ctl& T = C.ctl[i];
        glm::vec2 p = T.rest;
        if (T.bone >= 0 && (size_t) T.bone < P.skin.size()) p = apply(P.skin[(size_t) T.bone], p);
        pts[i] = p + glm::vec2(chv[base + 1 + i * 3], chv[base + 2 + i * 3]);
        thick[i] = chv[base + 3 + i * 3];
    }
}

// Sub-quads per grid cell edge used by the smoothing vertex shader (about 160 sub-quads across the whole mesh).
inline int meshSub(int grid) { return std::clamp((160 + grid - 1) / std::max(1, grid), 1, 6); }

// One 1-2-1 pass over the DISPLACEMENT (shown - rest) of the grid, blended [kSmoothAmount]: hard weight edges and folded
// cells relax, while the rest pose (displacement 0) stays exact.
constexpr float kSmoothAmount = 0.25f;
inline void smoothDisplacement(int grid, std::vector<float>& out) {
    const int W = grid + 1;
    if ((size_t) W * (size_t) W * 4 != out.size()) return;
    std::vector<float> d((size_t) W * W * 2), t(d.size());
    for (int i = 0; i < W * W; i++) { d[(size_t) i * 2] = out[(size_t) i * 4] - out[(size_t) i * 4 + 2]; d[(size_t) i * 2 + 1] = out[(size_t) i * 4 + 1] - out[(size_t) i * 4 + 3]; }
    auto at = [&](int x, int y, int c) { return d[((size_t) std::clamp(y, 0, W - 1) * W + (size_t) std::clamp(x, 0, W - 1)) * 2 + (size_t) c]; };
    for (int y = 0; y < W; y++)
        for (int x = 0; x < W; x++)
            for (int c = 0; c < 2; c++) {
                const float s = 4.f * at(x, y, c) + 2.f * (at(x - 1, y, c) + at(x + 1, y, c) + at(x, y - 1, c) + at(x, y + 1, c))
                              + at(x - 1, y - 1, c) + at(x + 1, y - 1, c) + at(x - 1, y + 1, c) + at(x + 1, y + 1, c);
                t[((size_t) y * W + (size_t) x) * 2 + (size_t) c] = at(x, y, c) + (s / 16.f - at(x, y, c)) * kSmoothAmount;
            }
    for (int i = 0; i < W * W; i++) { out[(size_t) i * 4] = out[(size_t) i * 4 + 2] + t[(size_t) i * 2]; out[(size_t) i * 4 + 1] = out[(size_t) i * 4 + 3] + t[(size_t) i * 2 + 1]; }
}

// ------------------------------------------------------------------------------------------ puppet warp (pins)

struct PinPt { glm::vec2 p, q; float k = 1.f; };   // rest, posed, influence

// Rigid Moving Least Squares at [v]: weights w_i = 1 / |p_i - v|^(2 falloff); the weighted centroids give the translation and
// the weighted Procrustes angle the rotation (angle from p-hat to q-hat). Interpolates: v on a pin returns that pin's q.
inline glm::vec2 mlsRigid(const PinPt* pp, size_t n, int falloff, glm::vec2 v) {
    float w[kMaxPins * 4 + 8];   // pins + 3 rotation helpers per pin + corners
    float sw = 0.f;
    // Weights 1/d^(2f) reach 1e27+ next to a pin: squaring them overflows float. So work with the nearest pin's distance as unit:
    // w_i = (dmin2 / d2_i)^f in 0..1, which is the same field up to one constant factor.
    float dmin2 = 1e30f;
    for (size_t i = 0; i < n; i++) {
        const glm::vec2 dl = pp[i].p - v;
        const float d2 = dl.x * dl.x + dl.y * dl.y;
        if (d2 < 1e-8f) return pp[i].q;
        dmin2 = std::min(dmin2, d2);
    }
    glm::vec2 ps(0.f), qs(0.f);
    for (size_t i = 0; i < n; i++) {
        const glm::vec2 dl = pp[i].p - v;
        const float d2 = dl.x * dl.x + dl.y * dl.y;
        const float r = dmin2 / d2;
        float wi = r;
        for (int k = 1; k < falloff; k++) wi *= r;
        wi *= pp[i].k;
        w[i] = wi; sw += wi;
    }
    if (sw < 1e-20f) return v;
    const float inv = 1.f / sw;
    for (size_t i = 0; i < n; i++) { w[i] *= inv; ps += w[i] * pp[i].p; qs += w[i] * pp[i].q; }
    float c = 0.f, s = 0.f;
    for (size_t i = 0; i < n; i++) {
        const glm::vec2 ph = pp[i].p - ps, qh = pp[i].q - qs;
        c += w[i] * (ph.x * qh.x + ph.y * qh.y);
        s += w[i] * (ph.x * qh.y - ph.y * qh.x);
    }
    const glm::vec2 a = v - ps;
    const float nrm = std::sqrt(c * c + s * s);
    glm::vec2 res = qs + a;
    if (nrm > 1e-12f) { c /= nrm; s /= nrm; res = qs + glm::vec2(c * a.x - s * a.y, s * a.x + c * a.y); }
    return (std::isfinite(res.x) && std::isfinite(res.y)) ? res : v;
}

// Pins + the four mesh-rect corners as fixed anchors. false = nothing moves (every pin at its rest position, no rotation).
// A ROTATED pin adds three helper points on a small circle around it, rotated with the pin: MLS only knows points, so this is
// what makes the picture turn around the pin instead of only sliding.
constexpr float kPinSatRadius = 0.035f;
inline bool buildPinPts(const Rig& R, const float* chv, std::vector<PinPt>& out) {
    out.clear();
    if (R.pins.empty()) return false;
    bool moved = false;
    for (size_t i = 0; i < R.pins.size(); i++) {
        const glm::vec2 d(chv[(size_t) kPinBase + i * kPinChans], chv[(size_t) kPinBase + i * kPinChans + 1]);
        const float rotDeg = chv[(size_t) kPinRotBase + i];
        const float k = R.pins[i].stiff;
        const glm::vec2 p = R.pins[i].rest, q = p + d;
        if (std::fabs(d.x) > 1e-7f || std::fabs(d.y) > 1e-7f) moved = true;
        out.push_back({p, q, k});
        if (std::fabs(rotDeg) > 1e-4f) {
            moved = true;
            const float a = rotDeg * 0.017453292519943295f, c = std::cos(a), s = std::sin(a);
            for (int j = 0; j < 3; j++) {
                const float ang = 1.5707963f + 2.0943951f * (float) j;
                const glm::vec2 u(std::cos(ang) * kPinSatRadius, std::sin(ang) * kPinSatRadius);
                out.push_back({p + u, q + glm::vec2(c * u.x - s * u.y, s * u.x + c * u.y), k});
            }
        }
    }
    if (!moved) { out.clear(); return false; }
    const glm::vec2 corners[4] = {{R.l, R.t}, {R.r, R.t}, {R.l, R.b}, {R.r, R.b}};
    for (const glm::vec2& c : corners) out.push_back({c, c, 1.f});
    return true;
}

// out = x y u v per vertex (paper units). x y = shown position, u v = rest position = texel to sample.
inline void buildMesh(const Rig& R, const float* chv, std::vector<float>& out, Pose& P) {
    computePose(R, chv, P);
    const size_t V = R.rest.size();
    out.resize(V * 4);

    struct PC { std::vector<glm::vec2> pts; std::vector<float> thick; float mix = 0.f; bool on = false; };
    std::vector<PC> pcs(R.curves.size());
    for (size_t k = 0; k < R.curves.size(); k++) {
        PC& c = pcs[k];
        c.mix = detail::clamp01(chv[(size_t) kCurveBase + k * kCurveChans]);
        c.on = R.curves[k].ctl.size() >= 2 && c.mix > 1e-4f && k < R.bind.size();
        if (c.on) posedCurve(R, P, chv, k, c.pts, c.thick);
    }

    std::vector<PinPt> pinPts;
    const bool warp = buildPinPts(R, chv, pinPts);

    for (size_t v = 0; v < V; v++) {
        const glm::vec2 p = R.rest[v];
        glm::vec2 pos = p * R.restWt[v];
        for (size_t k = 0; k < 4; k++) {
            const int b = R.bi[v * 4 + k];
            if (b >= 0) pos += apply(P.skin[(size_t) b], p) * R.bw[v * 4 + k];
        }
        for (size_t k = 0; k < pcs.size(); k++) {
            const PC& c = pcs[k];
            if (!c.on) continue;
            const Bind& B = R.bind[k][v];
            if (B.mask <= 1e-4f) continue;
            glm::vec2 C, tan;
            detail::evalSpline(c.pts, B.s, C, tan);
            const glm::vec2 T = detail::unitOr(tan, glm::vec2(1.f, 0.f));
            const glm::vec2 N(-T.y, T.x);
            const float si = std::clamp(B.s, 0.f, (float) (c.pts.size() - 1));
            const int seg = std::min((int) si, (int) c.pts.size() - 2);
            const float th = c.thick[(size_t) seg] + (c.thick[(size_t) seg + 1] - c.thick[(size_t) seg]) * (si - (float) seg);
            const glm::vec2 q = C + T * B.w + N * (B.v * th);
            pos += (q - pos) * (B.mask * c.mix);
        }
        if (warp) pos += mlsRigid(pinPts.data(), pinPts.size(), R.pinFalloff, p) - p;   // pins move the REST position; add the offset
        // never hand the GPU a NaN / runaway vertex (it would smear the whole canvas): fall back to the rest position
        if (!std::isfinite(pos.x) || !std::isfinite(pos.y) || std::fabs(pos.x - p.x) > 8.f || std::fabs(pos.y - p.y) > 8.f) pos = p;
        out[v * 4] = pos.x; out[v * 4 + 1] = pos.y; out[v * 4 + 2] = p.x; out[v * 4 + 3] = p.y;
    }
    smoothDisplacement(R.grid, out);
}

// ------------------------------------------------------------------------------------------ UI helpers

// Overlay data for the editor (stateless). [rest] = unposed geometry. Layout:
//   [nB, nC]  nB * (headX headY tailX tailY)  per curve: nCtl, nCtl*(x y), nSamp, nSamp*(x y)   nPins, nPins*(x y)   then kTotalChans channel values
inline void evalOverlay(const Rig& R, const std::vector<float>& chv, bool rest, std::vector<float>& out) {
    Pose P;
    computePose(R, chv.data(), P);
    out.clear();
    out.push_back((float) R.bones.size());
    out.push_back((float) R.curves.size());
    for (size_t b = 0; b < R.bones.size(); b++) {
        const Bone& B = R.bones[b];
        glm::vec2 h, t;
        if (rest) {
            h = B.head;
            t = B.head + glm::vec2(std::cos(B.ang * detail::kDeg), std::sin(B.ang * detail::kDeg)) * B.len;
        } else {
            h = apply(P.world[b], glm::vec2(0.f));
            t = detail::tailOf(R, P, (int) b);
        }
        out.push_back(h.x); out.push_back(h.y); out.push_back(t.x); out.push_back(t.y);
    }
    for (size_t k = 0; k < R.curves.size(); k++) {
        const Curve& C = R.curves[k];
        std::vector<glm::vec2> pts;
        std::vector<float> thick;
        if (rest) { pts.resize(C.ctl.size()); for (size_t i = 0; i < pts.size(); i++) pts[i] = C.ctl[i].rest; }
        else posedCurve(R, P, chv.data(), k, pts, thick);
        out.push_back((float) pts.size());
        for (const glm::vec2& p : pts) { out.push_back(p.x); out.push_back(p.y); }
        if (pts.size() >= 2) {
            const int S = ((int) pts.size() - 1) * kSamples;
            out.push_back((float) (S + 1));
            for (int i = 0; i <= S; i++) {
                glm::vec2 pos, tan;
                detail::evalSpline(pts, (float) i / (float) kSamples, pos, tan);
                out.push_back(pos.x); out.push_back(pos.y);
            }
        } else {
            out.push_back(0.f);
        }
    }
    // pins: count, then shown (or rest) positions
    out.push_back((float) R.pins.size());
    for (size_t i = 0; i < R.pins.size(); i++) {
        glm::vec2 p = R.pins[i].rest;
        if (!rest) p += glm::vec2(chv[(size_t) kPinBase + i * kPinChans], chv[(size_t) kPinBase + i * kPinChans + 1]);
        out.push_back(p.x); out.push_back(p.y);
    }
    out.insert(out.end(), chv.begin(), chv.end());
}

// Final (normalised) skin weight of [bone] at every mesh vertex.
inline void boneWeights(const Rig& R, int bone, std::vector<float>& out) {
    const size_t V = R.rest.size();
    out.assign(V, 0.f);
    for (size_t v = 0; v < V; v++)
        for (size_t k = 0; k < 4; k++)
            if (R.bi[v * 4 + k] == bone) out[v] += R.bw[v * 4 + k];
}

// Inverse of the deformation for ONE point: the rest position (u v) of the shown point (x y), or false when no
// triangle of the deformed mesh covers it. [verts] = buildMesh output. Later triangles win (they are drawn on top).
inline bool unwarp(const float* verts, size_t nVerts, int grid, float x, float y, float& u, float& v) {
    const int W = grid + 1;
    if (!verts || (size_t) W * (size_t) W != nVerts) return false;
    auto P = [&](int i, int j) { return verts + ((size_t) j * (size_t) W + (size_t) i) * 4; };
    auto tri = [&](const float* a, const float* b, const float* c) -> bool {
        const float d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (std::fabs(d) < 1e-12f) return false;
        const float l1 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / d;
        const float l2 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / d;
        const float l3 = 1.f - l1 - l2;
        const float e = -1e-4f;
        if (l1 < e || l2 < e || l3 < e) return false;
        u = l1 * a[2] + l2 * b[2] + l3 * c[2];
        v = l1 * a[3] + l2 * b[3] + l3 * c[3];
        return true;
    };
    for (int j = grid - 1; j >= 0; j--)
        for (int i = grid - 1; i >= 0; i--) {
            const float* a = P(i, j); const float* b = P(i + 1, j); const float* c = P(i, j + 1); const float* d = P(i + 1, j + 1);
            if (tri(b, d, c)) return true;
            if (tri(a, b, c)) return true;
        }
    return false;
}

// ------------------------------------------------------------------------------------------ attachment
// A drawing row can be ATTACHED to a bone of another row: it then follows that bone's motion rigidly. For row r with
// parent row p and bone b, in PAPER units:
//   A_r = A_p * PX_p * Skin_p[b] * PX_p^-1        (PX = the parent's own row transform, Skin = world_pose * inverse(world_rest))
// and the row is drawn with  A_r * ChildXf. [parent][r] < 0 = not attached. [pose][p] = null when row p has no rig.
// Chains work (A_p is applied first); a cycle is cut after kMaxChain passes.
constexpr int kMaxChain = 16;

inline Aff rowAff(const fox::anim::Xf& x) {
    return mul(trs(x.px + x.tx, x.py + x.ty, x.rot * detail::kDeg, x.sx, x.sy), trs(-x.px, -x.py, 0.f, 1.f, 1.f));
}

inline void attachMatrices(const int* parent, const int* bone, size_t n, const std::vector<fox::anim::Xf>& xf,
                           const std::vector<const Pose*>& pose, std::vector<Aff>& out) {
    out.assign(n, Aff{});
    bool any = false;
    for (size_t r = 0; r < n; r++) if (parent[r] >= 0) any = true;
    if (!any) return;
    for (int pass = 0; pass < kMaxChain; pass++) {
        bool changed = false;
        for (size_t r = 0; r < n; r++) {
            const int p = parent[r];
            if (p < 0 || (size_t) p >= n || (size_t) p == r) continue;
            Aff a = out[(size_t) p];
            const Pose* P = (size_t) p < pose.size() ? pose[(size_t) p] : nullptr;
            const int b = bone[r];
            if (P && b >= 0 && (size_t) b < P->skin.size() && (size_t) p < xf.size()) {
                const Aff px = rowAff(xf[(size_t) p]);
                const float det = px.a * px.d - px.b * px.c;
                if (std::fabs(det) > 1e-8f) a = mul(a, mul(px, mul(P->skin[(size_t) b], inverse(px))));
            }
            if (a.a != out[r].a || a.b != out[r].b || a.c != out[r].c || a.d != out[r].d || a.tx != out[r].tx || a.ty != out[r].ty) changed = true;
            out[r] = a;
        }
        if (!changed) break;
    }
}

}  // namespace fox::rig
