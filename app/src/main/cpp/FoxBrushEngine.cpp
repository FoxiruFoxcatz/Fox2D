// FoxBrushEngine.cpp - see FoxBrushEngine.h for the design and the determinism rules.
#include "FoxBrushEngine.h"

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#if defined(__ANDROID__)
#include <android/log.h>
#define BLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FoxBrush", __VA_ARGS__)
#else
#define BLOGE(...) std::fprintf(stderr, __VA_ARGS__)
#endif

namespace fox {
namespace brush {

namespace {

constexpr float kDeg2Rad = 0.017453292f;
constexpr float kPi = 3.14159265f;

inline float clampf(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }
inline float fractf(float x) { return x - std::floor(x); }
inline float smooth01(float t) { t = clampf(t, 0.f, 1.f); return t * t * (3.f - 2.f * t); }
inline float smoothstepf(float e0, float e1, float x) { return smooth01((x - e0) / (e1 - e0)); }

inline uint32_t mix32(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352dU;
    x ^= x >> 15; x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

struct Range { float lo, hi, def; };

// Order == enum P. One table drives defaults and clamping.
const Range kRange[kParamCount] = {
    /* SIZE        */ {1.f, 256.f, 12.f},
    /* TIP         */ {0.f, (float) (TipCount - 1), 0.f},
    /* HARDNESS    */ {0.f, 1.f, 0.85f},
    /* ROUNDNESS   */ {0.05f, 1.f, 1.f},
    /* ANGLE       */ {-360.f, 360.f, 0.f},
    /* ANGLE_FOLLOW*/ {0.f, 1.f, 0.f},
    /* SPACING     */ {0.02f, 2.f, 0.12f},
    /* FLOW        */ {0.01f, 1.f, 1.f},
    /* OPACITY     */ {0.01f, 1.f, 1.f},
    /* BUILDUP     */ {0.f, 1.f, 1.f},
    /* SIZE_PRESS  */ {0.f, 1.f, 0.6f},
    /* OPAC_PRESS  */ {0.f, 1.f, 0.f},
    /* PRESS_GAMMA */ {0.3f, 3.f, 1.f},
    /* SIZE_SPEED  */ {-1.f, 1.f, 0.f},
    /* OPAC_SPEED  */ {-1.f, 1.f, 0.f},
    /* MIN_SIZE    */ {0.f, 1.f, 0.15f},
    /* TAPER_START */ {0.f, 128.f, 0.f},
    /* TAPER_END   */ {0.f, 128.f, 0.f},
    /* SCATTER     */ {0.f, 3.f, 0.f},
    /* SIZE_JIT    */ {0.f, 1.f, 0.f},
    /* OPAC_JIT    */ {0.f, 1.f, 0.f},
    /* ANGLE_JIT   */ {0.f, 180.f, 0.f},
    /* HUE_JIT     */ {0.f, 1.f, 0.f},
    /* SAT_JIT     */ {0.f, 1.f, 0.f},
    /* BRI_JIT     */ {0.f, 1.f, 0.f},
    /* COUNT       */ {1.f, 8.f, 1.f},
    /* GRAIN       */ {0.f, 1.f, 0.f},
    /* GRAIN_SCALE */ {1.f, 64.f, 6.f},
    /* GRAIN_PAPER */ {0.f, 1.f, 1.f},
    /* WET_EDGE    */ {0.f, 1.f, 0.f},
    /* STABILIZER  */ {0.f, 64.f, 0.f},
    /* SMOOTHING   */ {0.f, 1.f, 0.f},
    /* BRISTLES    */ {2.f, 32.f, 12.f},
    /* SEED        */ {0.f, 16777215.f, 1.f},
    /* SIM_PRESSURE*/ {0.f, 1.f, 0.f},
};

void rgb2hsv(const float* c, float& h, float& s, float& v) {
    const float mx = std::max(c[0], std::max(c[1], c[2])), mn = std::min(c[0], std::min(c[1], c[2]));
    const float d = mx - mn;
    v = mx;
    s = mx > 1e-6f ? d / mx : 0.f;
    if (d < 1e-6f) { h = 0.f; return; }
    if (mx == c[0]) h = (c[1] - c[2]) / d + (c[1] < c[2] ? 6.f : 0.f);
    else if (mx == c[1]) h = (c[2] - c[0]) / d + 2.f;
    else h = (c[0] - c[1]) / d + 4.f;
    h /= 6.f;
}

void hsv2rgb(float h, float s, float v, float* c) {
    h = (h - std::floor(h)) * 6.f;
    const int i = (int) h;
    const float f = h - (float) i, p = v * (1.f - s), q = v * (1.f - s * f), t = v * (1.f - s * (1.f - f));
    switch (i % 6) {
        case 0: c[0] = v; c[1] = t; c[2] = p; break;
        case 1: c[0] = q; c[1] = v; c[2] = p; break;
        case 2: c[0] = p; c[1] = v; c[2] = t; break;
        case 3: c[0] = p; c[1] = q; c[2] = v; break;
        case 4: c[0] = t; c[1] = p; c[2] = v; break;
        default: c[0] = v; c[1] = p; c[2] = q; break;
    }
}

}  // namespace

// ================================================================================================ Params / Rect

Params Params::defaults() {
    Params p;
    for (int i = 0; i < kParamCount; i++) p.v[i] = kRange[i].def;
    return p;
}

bool Params::fromArray(const float* a, int n, Params& out) {
    out = defaults();
    if (!a || n <= 0) return false;
    for (int i = 0; i < kParamCount && i < n; i++) {
        float x = a[i];
        if (!std::isfinite(x)) x = kRange[i].def;
        out.v[i] = clampf(x, kRange[i].lo, kRange[i].hi);
    }
    out.v[P_TIP] = std::floor(out.v[P_TIP] + 0.5f);
    out.v[P_COUNT] = std::floor(out.v[P_COUNT] + 0.5f);
    out.v[P_BRISTLES] = std::floor(out.v[P_BRISTLES] + 0.5f);
    out.v[P_SEED] = std::floor(out.v[P_SEED]);
    return true;
}

void Rect::merge(const Rect& o) {
    if (o.empty()) return;
    if (empty()) { *this = o; return; }
    x0 = std::min(x0, o.x0); y0 = std::min(y0, o.y0);
    x1 = std::max(x1, o.x1); y1 = std::max(y1, o.y1);
}

// ================================================================================================ Builder

void Builder::begin(const Params& p, uint32_t argb, float pxPerUnit) {
    p_ = p;
    k_ = std::max(pxPerUnit, 1e-3f);
    T_ = k_ * kBrushSpace;
    baseR_ = p_.v[P_SIZE] * k_ * 0.5f;
    rgb_[0] = (float) ((argb >> 16) & 255u) / 255.f;
    rgb_[1] = (float) ((argb >> 8) & 255u) / 255.f;
    rgb_[2] = (float) (argb & 255u) / 255.f;
    seed_ = mix32((uint32_t) p_.v[P_SEED] * 2654435761u + 0x1234567u);
    haveRaw_ = false; nRaw_ = 0;
    bx_ = by_ = sx_ = sy_ = 0.f; sp_ = 1.f;
    lastRaw_ = Node{};
    pts_.clear(); pts_.reserve(256);
    seg_ = 0; started_ = finished_ = single_ = false;
    arc_ = 0.f; toNext_ = 0.f; speed_ = 0.3f; dabN_ = 0;
}

float Builder::R(uint32_t sub, uint32_t ch) const {
    const uint32_t h = mix32(seed_ ^ mix32(dabN_ * 0x9E3779B1u + sub * 0x85EBCA6Bu + ch * 0xC2B2AE35u + 1u));
    return (float) (h >> 8) * (1.f / 16777216.f);
}

void Builder::add(const Sample& s) { addPx(s.x * T_, s.y * T_, s.p, s.t); }

void Builder::addPx(float x, float y, float p, float t) {
    if (!std::isfinite(x + y + p + t)) return;
    nRaw_++;
    p = clampf(p, 0.f, 1.f);
    lastRaw_ = Node{x, y, p, t};
    if (!haveRaw_) {
        haveRaw_ = true;
        bx_ = sx_ = x; by_ = sy_ = y; sp_ = p;
        pushNode(x, y, p, t);
        return;
    }
    stabilise(x, y, p, t);
}

// Lazy rope: the brush only moves once the pointer is a full rope length away, then trails behind it. The EMA on top
// rounds off what is left of hand jitter. Both are pure functions of the sample sequence.
void Builder::stabilise(float x, float y, float p, float t) {
    const float rope = p_.v[P_STABILIZER] * k_;
    const float dx = x - bx_, dy = y - by_;
    const float d = std::sqrt(dx * dx + dy * dy);
    if (d > rope && d > 1e-6f) {
        const float m = (d - rope) / d;
        bx_ += dx * m; by_ += dy * m;
    }
    const float a = 1.f - 0.93f * p_.v[P_SMOOTHING];
    sx_ += (bx_ - sx_) * a;
    sy_ += (by_ - sy_) * a;
    sp_ += (p - sp_) * 0.55f;
    pushNode(sx_, sy_, sp_, t);
}

void Builder::pushNode(float x, float y, float p, float t) {
    if (!pts_.empty()) {
        const Node& l = pts_.back();
        const float dx = x - l.x, dy = y - l.y;
        if (dx * dx + dy * dy < 0.0625f) return;   // < 0.25 px: a duplicate, would only make a zero-length segment
    }
    pts_.push_back(Node{x, y, p, t});
}

// At the end of the stroke the stabilised brush glides to where the finger actually lifted.
void Builder::catchUp() {
    if (!haveRaw_ || pts_.empty()) return;
    const Node& l = pts_.back();
    const float dx = lastRaw_.x - l.x, dy = lastRaw_.y - l.y;
    const float d = std::sqrt(dx * dx + dy * dy);
    if (d < 0.5f) return;
    const int n = std::min(48, (int) std::ceil(d / 4.f));
    const float x0 = l.x, y0 = l.y, p0 = l.p, t0 = l.t;
    for (int i = 1; i <= n; i++) {
        const float u = (float) i / (float) n;
        pushNode(lerpf(x0, lastRaw_.x, u), lerpf(y0, lastRaw_.y, u), lerpf(p0, lastRaw_.p, u), lerpf(t0, lastRaw_.t, u));
    }
}

void Builder::place(float x, float y, float pressure, float dx, float dy, float tailU, std::vector<Dab>& out) {
    const Params& P = p_;
    const float pr = P.v[P_SIM_PRESSURE] > 0.5f ? clampf(1.f - 0.85f * speed_, 0.12f, 1.f) : clampf(pressure, 0.f, 1.f);
    const float g = std::pow(pr, P.v[P_PRESS_GAMMA]);

    float sizeF = lerpf(1.f, lerpf(P.v[P_MIN_SIZE], 1.f, g), P.v[P_SIZE_PRESS]);
    sizeF *= clampf(1.f + P.v[P_SIZE_SPEED] * (speed_ - 0.3f) * 1.2f, 0.1f, 2.5f);
    const float ts = P.v[P_TAPER_START] * k_;
    if (ts > 0.f && !single_) sizeF *= lerpf(0.08f, 1.f, smooth01(arc_ / ts));
    if (tailU >= 0.f) sizeF *= lerpf(1.f, 0.04f, smooth01(tailU));
    const float rDab = std::max(0.3f, baseR_ * sizeF);

    float alpha = P.v[P_FLOW] * lerpf(1.f, g, P.v[P_OPAC_PRESS]);
    alpha *= clampf(1.f + P.v[P_OPAC_SPEED] * (speed_ - 0.3f) * 1.2f, 0.05f, 2.f);
    if (P.v[P_BUILDUP] > 0.5f) alpha *= P.v[P_OPACITY];   // capped strokes apply the opacity once, at composite time

    const float dirA = std::atan2(dy, dx);
    const int cnt = std::max(1, (int) (P.v[P_COUNT] + 0.5f));
    float hh, ss, vv;
    rgb2hsv(rgb_, hh, ss, vv);
    for (int c = 0; c < cnt; c++) {
        const uint32_t sub = (uint32_t) c;
        Dab d;
        d.r = std::max(0.3f, rDab * (1.f - P.v[P_SIZE_JIT] * R(sub, 0)));
        d.a = clampf(alpha * (1.f - P.v[P_OPAC_JIT] * R(sub, 1)), 0.f, 1.f);
        d.ang = (P.v[P_ANGLE] + P.v[P_ANGLE_JIT] * (R(sub, 2) * 2.f - 1.f)) * kDeg2Rad + P.v[P_ANGLE_FOLLOW] * dirA;
        float ox = 0.f, oy = 0.f;
        if (P.v[P_SCATTER] > 0.f) {
            const float sc = P.v[P_SCATTER] * 2.f * rDab;
            ox = (R(sub, 3) * 2.f - 1.f) * sc;
            oy = (R(sub, 4) * 2.f - 1.f) * sc;
        }
        d.x = x + ox; d.y = y + oy;
        d.seed = R(sub, 7);
        float col[3] = {rgb_[0], rgb_[1], rgb_[2]};
        if (P.v[P_HUE_JIT] > 0.f || P.v[P_SAT_JIT] > 0.f || P.v[P_BRI_JIT] > 0.f) {
            const float h2 = hh + (R(sub, 5) * 2.f - 1.f) * P.v[P_HUE_JIT] * 0.5f;
            const float s2 = clampf(ss + (R(sub, 6) * 2.f - 1.f) * P.v[P_SAT_JIT], 0.f, 1.f);
            const float v2 = clampf(vv + (R(sub, 8) * 2.f - 1.f) * P.v[P_BRI_JIT] * 0.5f, 0.f, 1.f);
            hsv2rgb(h2, s2, v2, col);
        }
        d.cr = col[0]; d.cg = col[1]; d.cb = col[2];
        if (!std::isfinite(d.x + d.y + d.r + d.ang + d.a)) continue;
        out.push_back(d);
    }
    dabN_++;
    toNext_ = std::max(0.5f, P.v[P_SPACING] * 2.f * rDab);
}

// Walk a straight piece, dropping a dab every toNext_ px. [tailLen] > 0 marks the end-taper tail (tailStart = where on
// it this piece begins).
void Builder::walkLine(float x0, float y0, float x1, float y1, float p0, float p1, float tailLen, float tailStart, std::vector<Dab>& out) {
    const float dx = x1 - x0, dy = y1 - y0;
    float remain = std::sqrt(dx * dx + dy * dy);
    if (remain < 1e-6f) return;
    const float L0 = remain, ux = dx / L0, uy = dy / L0;
    float cx = x0, cy = y0, along = 0.f;
    while (remain >= toNext_) {
        const float step = toNext_;
        cx += ux * step; cy += uy * step;
        along += step; remain -= step; arc_ += step;
        place(cx, cy, lerpf(p0, p1, along / L0), ux, uy, tailLen > 0.f ? (tailStart + along) / tailLen : -1.f, out);
    }
    toNext_ -= remain;
    arc_ += remain;
}

void Builder::walkSegment(size_t j, std::vector<Dab>& out) {
    const size_t n = pts_.size();
    const Node& p0 = pts_[j == 0 ? 0 : j - 1];
    const Node& p1 = pts_[j];
    const Node& p2 = pts_[j + 1];
    const Node& p3 = pts_[std::min(j + 2, n - 1)];

    const float len = std::sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y));
    const float dt = std::max(p2.t - p1.t, 1.f);
    speed_ += (clampf((len / dt) / (0.004f * T_), 0.f, 1.f) - speed_) * 0.25f;

    if (!started_) {
        started_ = true;
        float tx = p2.x - p0.x, ty = p2.y - p0.y;
        if (tx * tx + ty * ty < 1e-6f) { tx = p2.x - p1.x; ty = p2.y - p1.y; }
        place(p1.x, p1.y, p1.p, tx, ty, -1.f, out);
    }
    // uniform Catmull-Rom p1 -> p2 (same basis as emitCR in fox_canvas.cpp)
    const float k1x = -p0.x + p2.x, k1y = -p0.y + p2.y;
    const float k2x = 2.f * p0.x - 5.f * p1.x + 4.f * p2.x - p3.x, k2y = 2.f * p0.y - 5.f * p1.y + 4.f * p2.y - p3.y;
    const float k3x = -p0.x + 3.f * p1.x - 3.f * p2.x + p3.x, k3y = -p0.y + 3.f * p1.y - 3.f * p2.y + p3.y;
    const int nsub = std::clamp((int) std::ceil(len / 1.5f), 1, 96);
    float px = p1.x, py = p1.y, pp = p1.p;
    for (int k = 1; k <= nsub; k++) {
        const float t = (float) k / (float) nsub, t2 = t * t, t3 = t2 * t;
        const float qx = 0.5f * (2.f * p1.x + k1x * t + k2x * t2 + k3x * t3);
        const float qy = 0.5f * (2.f * p1.y + k1y * t + k2y * t2 + k3y * t3);
        const float qp = lerpf(p1.p, p2.p, t);
        walkLine(px, py, qx, qy, pp, qp, 0.f, 0.f, out);
        px = qx; py = qy; pp = qp;
    }
}

// End taper: extra dabs AFTER the last point, along the exit direction, shrinking to a point. Nothing already emitted changes.
void Builder::tail(std::vector<Dab>& out) {
    const float L = p_.v[P_TAPER_END] * k_;
    const size_t n = pts_.size();
    if (L < 0.5f || n < 2) return;
    const Node& e = pts_[n - 1];
    for (size_t i = n - 1; i-- > 0 && n - 1 - i <= 8;) {
        const float dx = e.x - pts_[i].x, dy = e.y - pts_[i].y;
        const float d = std::sqrt(dx * dx + dy * dy);
        if (d >= 3.f) {
            walkLine(e.x, e.y, e.x + dx / d * L, e.y + dy / d * L, e.p, e.p, L, 0.f, out);
            return;
        }
    }
}

void Builder::emit(std::vector<Dab>& out, bool finish) {
    if (finish && !finished_) { finished_ = true; catchUp(); }
    const size_t n = pts_.size();
    if (n == 0) return;
    if (n == 1) {
        if (finish && !started_) {   // a lone tap: one full-size dab, no taper
            started_ = single_ = true;
            place(pts_[0].x, pts_[0].y, pts_[0].p, 1.f, 0.f, -1.f, out);
        }
        return;
    }
    while (seg_ + 1 < n) {
        if (!finish && seg_ + 2 >= n) break;   // Catmull-Rom needs one point of look-ahead; the same tangent as when finishing
        walkSegment(seg_, out);
        seg_++;
    }
    if (finish) { tail(out); }
}

// ================================================================================================ tip maths (CPU twin of kDabFS)
// Keep in sync with the fragment shader below: same constants, same order of operations.

namespace {

struct TipU {
    int tip; float hard, asp, grain, gscale, wet, bris, seed; bool gpaper;
};

float hash21(float px, float py) {
    px = fractf(px * 123.34f); py = fractf(py * 456.21f);
    const float d = px * (px + 45.32f) + py * (py + 45.32f);
    px += d; py += d;
    return fractf(px * py);
}

float vnoise(float px, float py) {
    const float ix = std::floor(px), iy = std::floor(py);
    float fx = px - ix, fy = py - iy;
    fx = fx * fx * (3.f - 2.f * fx); fy = fy * fy * (3.f - 2.f * fy);
    const float a = hash21(ix, iy), b = hash21(ix + 1.f, iy), c = hash21(ix, iy + 1.f), d = hash21(ix + 1.f, iy + 1.f);
    return lerpf(lerpf(a, b, fx), lerpf(c, d, fx), fy);
}

// qx, qy = tip space (1 = dab radius), pX, pY = paper px, r = dab radius px, seed = per-dab random 0..1
float shade(const TipU& u, float qx0, float qy0, float pX, float pY, float r, float seed) {
    const float qx = qx0, qy = qy0 / u.asp;
    float d;
    switch (u.tip) {
        case 0: d = std::sqrt(qx * qx + qy * qy); break;
        case 1: d = std::max(std::fabs(qx), std::fabs(qy)); break;
        case 2: d = std::fabs(qx) + std::fabs(qy); break;
        case 3: {
            const float rr = std::sqrt(qx * qx + qy * qy), a = std::atan2(qy, qx + 1e-6f);
            d = rr / (0.62f + 0.38f * std::cos(5.f * a));
            break;
        }
        case 4: d = std::sqrt(qx * qx + qy * qy) + (vnoise(qx * 9.f + seed * 31.f, qy * 9.f + seed * 31.f) - 0.5f) * 0.55f; break;
        default: {
            float sx = std::fabs(qx), sy = std::fabs(qy);
            sx *= sx; sx *= sx; sy *= sy; sy *= sy;
            d = std::pow(sx + sy, 0.25f);
            break;
        }
    }
    const float aa = 1.f / std::max(r, 1.f);
    const float soft = std::max(1.f - u.hard, aa);
    const float e = clampf((1.f - d) / soft, 0.f, 1.f);
    float a = e * e * (3.f - 2.f * e);
    if (u.tip == 4) a *= lerpf(1.f, smoothstepf(0.15f, 0.55f, vnoise(pX * 0.8f, pY * 0.8f)), 0.5f);
    if (u.tip == 5) {
        const float uu = qx * 0.5f + 0.5f, n = u.bris;
        const float idx = std::floor(uu * n), fr = fractf(uu * n);
        const float h = lerpf(0.45f, 1.f, hash21(idx, u.seed * 91.7f));
        const float gap = smoothstepf(0.f, 0.2f, fr) * (1.f - smoothstepf(0.8f, 1.f, fr));
        a *= h * lerpf(1.f, gap, 0.85f);
    }
    if (u.grain > 0.f) {
        float gx, gy;
        if (u.gpaper) { gx = pX / u.gscale; gy = pY / u.gscale; }
        else { gx = (qx0 * r + seed * 97.f) / u.gscale; gy = (qy0 * r + seed * 53.f) / u.gscale; }
        const float n = vnoise(gx, gy) * 0.65f + vnoise(gx * 2.3f + 7.1f, gy * 2.3f + 7.1f) * 0.35f;
        a *= lerpf(1.f, smoothstepf(0.3f, 0.7f, n), u.grain);
    }
    a *= lerpf(1.f, 0.45f + 1.1f * smoothstepf(0.35f, 1.f, d), u.wet);
    return clampf(a, 0.f, 1.f);
}

TipU tipUniforms(const Params& p, float k) {
    TipU u;
    u.tip = (int) (p.v[P_TIP] + 0.5f);
    u.hard = p.v[P_HARDNESS];
    u.asp = p.v[P_ROUNDNESS];
    u.grain = p.v[P_GRAIN];
    u.gscale = std::max(1.f, p.v[P_GRAIN_SCALE] * k);
    u.wet = p.v[P_WET_EDGE];
    u.bris = p.v[P_BRISTLES];
    u.seed = (float) (mix32((uint32_t) p.v[P_SEED] * 40503u + 7u) >> 8) * (1.f / 16777216.f);
    u.gpaper = p.v[P_GRAIN_PAPER] > 0.5f;
    return u;
}

}  // namespace

// ================================================================================================ preview (CPU)

// samples = nSamples quadruples (x px, y px, pressure, ms) from the Studio's scribble pad; none = the built-in test stroke.
void renderPreview(const Params& P, uint32_t argb, int w, int h, float unit, const float* samples, int nSamples, std::vector<uint32_t>& outArgb) {
    outArgb.assign((size_t) w * h, 0u);
    if (w < 4 || h < 4) return;

    Builder b;
    b.begin(P, argb, unit);
    if (samples && nSamples > 0) {
        for (int i = 0; i < nSamples; i++) b.addPx(samples[i * 4], samples[i * 4 + 1], samples[i * 4 + 2], samples[i * 4 + 3]);
    } else {
        const int N = 44;
        for (int i = 0; i < N; i++) {
            const float u = (float) i / (float) (N - 1);
            const float x = w * (0.09f + 0.82f * u);
            const float y = h * 0.5f + std::sin(u * 2.f * kPi) * h * 0.24f;
            const float p = 0.15f + 0.85f * std::pow(std::sin(u * kPi), 0.8f);
            b.addPx(x, y, p, (float) i * 9.f);
        }
    }
    std::vector<Dab> dabs;
    b.emit(dabs, true);

    const bool capped = P.v[P_BUILDUP] < 0.5f;
    const TipU tu = tipUniforms(P, unit);
    std::vector<float> acc((size_t) w * h * 4, 0.f);   // premultiplied RGBA
    for (const Dab& d : dabs) {
        const float m = d.r + 1.5f;
        const int x0 = std::max(0, (int) std::floor(d.x - m)), x1 = std::min(w, (int) std::ceil(d.x + m));
        const int y0 = std::max(0, (int) std::floor(d.y - m)), y1 = std::min(h, (int) std::ceil(d.y + m));
        const float cs = std::cos(d.ang), sn = std::sin(d.ang);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                const float rx = (float) x + 0.5f - d.x, ry = (float) y + 0.5f - d.y;
                const float lx = cs * rx + sn * ry, ly = -sn * rx + cs * ry;   // inverse of the quad rotation
                if (std::fabs(lx) > m || std::fabs(ly) > m) continue;
                const float a = shade(tu, lx / d.r, ly / d.r, (float) x + 0.5f, (float) y + 0.5f, d.r, d.seed) * d.a;
                if (a <= 0.f) continue;
                float* o = &acc[((size_t) y * w + x) * 4];
                const float sr = d.cr * a, sg = d.cg * a, sb = d.cb * a;
                if (capped) {
                    o[0] = std::max(o[0], sr); o[1] = std::max(o[1], sg); o[2] = std::max(o[2], sb); o[3] = std::max(o[3], a);
                } else {
                    const float k = 1.f - a;
                    o[0] = sr + o[0] * k; o[1] = sg + o[1] * k; o[2] = sb + o[2] * k; o[3] = a + o[3] * k;
                }
            }
        }
    }
    const float op = capped ? P.v[P_OPACITY] : 1.f;
    for (size_t i = 0; i < (size_t) w * h; i++) {
        const float a = acc[i * 4 + 3] * op;
        if (a <= 0.002f) continue;
        const float inv = op / a;   // unpremultiply (rgb carries the same factor as alpha)
        const auto ch = [&](float c) { return (uint32_t) (clampf(c * inv, 0.f, 1.f) * 255.f + 0.5f); };
        outArgb[i] = ((uint32_t) (clampf(a, 0.f, 1.f) * 255.f + 0.5f) << 24) | (ch(acc[i * 4]) << 16) | (ch(acc[i * 4 + 1]) << 8) | ch(acc[i * 4 + 2]);
    }
}

// the declaration in FoxBrushEngine.h
void renderPreview(const Params& P, uint32_t argb, int w, int h, float unit, std::vector<uint32_t>& outArgb) {
    renderPreview(P, argb, w, h, unit, nullptr, 0, outArgb);
}

// ================================================================================================ GPU

namespace {

const char* kDabVS = R"(#version 300 es
layout(location=0) in vec4 aA;   // x, y, radius, angle   (paper px)
layout(location=1) in vec2 aB;   // alpha, seed
layout(location=2) in vec3 aC;   // straight rgb
uniform float uT;
out vec2 vQ;
out vec2 vP;
flat out float vR;
flat out float vA;
flat out float vS;
flat out vec3 vC;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    float m = aA.z + 1.5;                      // radius + AA margin
    vec2 l = c * m;
    float cs = cos(aA.w), sn = sin(aA.w);
    vec2 p = aA.xy + vec2(cs * l.x - sn * l.y, sn * l.x + cs * l.y);
    vQ = l / aA.z;
    vP = p;
    vR = aA.z; vA = aB.x; vS = aB.y; vC = aC;
    gl_Position = vec4(p / uT * 2.0 - 1.0, 0.0, 1.0);
}
)";

// CPU twin: shade() above. Keep both in sync.
const char* kDabFS = R"(#version 300 es
precision highp float;
precision highp int;
in vec2 vQ;
in vec2 vP;
flat in float vR;
flat in float vA;
flat in float vS;
flat in vec3 vC;
uniform int uTip;
uniform float uHard;
uniform float uAsp;
uniform float uGrain;
uniform float uGScale;
uniform int uGPaper;
uniform float uWet;
uniform float uBris;
uniform float uSeed;
out vec4 o;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i), b = hash21(i + vec2(1.0, 0.0)), c = hash21(i + vec2(0.0, 1.0)), d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
void main() {
    vec2 q = vec2(vQ.x, vQ.y / uAsp);
    float d;
    if (uTip == 0) d = length(q);
    else if (uTip == 1) d = max(abs(q.x), abs(q.y));
    else if (uTip == 2) d = abs(q.x) + abs(q.y);
    else if (uTip == 3) d = length(q) / (0.62 + 0.38 * cos(5.0 * atan(q.y, q.x + 1e-6)));
    else if (uTip == 4) d = length(q) + (vnoise(q * 9.0 + vS * 31.0) - 0.5) * 0.55;
    else { vec2 s = abs(q); s *= s; s *= s; d = pow(s.x + s.y, 0.25); }

    float aa = 1.0 / max(vR, 1.0);
    float soft = max(1.0 - uHard, aa);
    float e = clamp((1.0 - d) / soft, 0.0, 1.0);
    float a = e * e * (3.0 - 2.0 * e);

    if (uTip == 4) a *= mix(1.0, smoothstep(0.15, 0.55, vnoise(vP * 0.8)), 0.5);
    if (uTip == 5) {
        float u = q.x * 0.5 + 0.5;
        float idx = floor(u * uBris), fr = fract(u * uBris);
        float h = mix(0.45, 1.0, hash21(vec2(idx, uSeed * 91.7)));
        float gap = smoothstep(0.0, 0.2, fr) * (1.0 - smoothstep(0.8, 1.0, fr));
        a *= h * mix(1.0, gap, 0.85);
    }
    if (uGrain > 0.0) {
        vec2 gp = (uGPaper == 1 ? vP : vQ * vR + vec2(vS * 97.0, vS * 53.0)) / uGScale;
        float n = vnoise(gp) * 0.65 + vnoise(gp * 2.3 + 7.1) * 0.35;
        a *= mix(1.0, smoothstep(0.3, 0.7, n), uGrain);
    }
    a *= mix(1.0, 0.45 + 1.1 * smoothstep(0.35, 1.0, d), uWet);
    a = clamp(a, 0.0, 1.0) * vA;
    o = vec4(vC * a, a);          // premultiplied
}
)";

const char* kCompVS = R"(#version 300 es
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kCompFS = R"(#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform float uOp;
out vec4 o;
void main() {
    o = texelFetch(uTex, ivec2(gl_FragCoord.xy), 0) * uOp;
}
)";

GLuint compile(GLenum type, const char* src) {
    const GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        BLOGE("shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

GLuint link(const char* vs, const char* fs) {
    const GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
    if (!v || !f) { if (v) glDeleteShader(v); if (f) glDeleteShader(f); return 0; }
    const GLuint p = glCreateProgram();
    glAttachShader(p, v); glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v); glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        BLOGE("program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

constexpr size_t kBatch = 4096;   // dabs per upload (147 KB)
constexpr int kTile = 64;

Rect dabBounds(const Dab* d, size_t n, int T) {
    float x0 = 1e30f, y0 = 1e30f, x1 = -1e30f, y1 = -1e30f;
    for (size_t i = 0; i < n; i++) {
        const float m = d[i].r + 2.f;
        x0 = std::min(x0, d[i].x - m); y0 = std::min(y0, d[i].y - m);
        x1 = std::max(x1, d[i].x + m); y1 = std::max(y1, d[i].y + m);
    }
    Rect r;
    r.x0 = std::clamp((int) std::floor(x0), 0, T); r.y0 = std::clamp((int) std::floor(y0), 0, T);
    r.x1 = std::clamp((int) std::ceil(x1), 0, T); r.y1 = std::clamp((int) std::ceil(y1), 0, T);
    return r;
}

}  // namespace

bool Renderer::init(int paperPx) {
    shutdown();
    T_ = paperPx;
    prog_ = link(kDabVS, kDabFS);
    comp_ = link(kCompVS, kCompFS);
    if (!prog_ || !comp_) { shutdown(); return false; }
    uT_ = glGetUniformLocation(prog_, "uT");
    uTip_ = glGetUniformLocation(prog_, "uTip");
    uHard_ = glGetUniformLocation(prog_, "uHard");
    uAsp_ = glGetUniformLocation(prog_, "uAsp");
    uGrain_ = glGetUniformLocation(prog_, "uGrain");
    uGScale_ = glGetUniformLocation(prog_, "uGScale");
    uGPaper_ = glGetUniformLocation(prog_, "uGPaper");
    uWet_ = glGetUniformLocation(prog_, "uWet");
    uBris_ = glGetUniformLocation(prog_, "uBris");
    uSeed_ = glGetUniformLocation(prog_, "uSeed");
    cTex_ = glGetUniformLocation(comp_, "uTex");
    cOp_ = glGetUniformLocation(comp_, "uOp");

    glGenVertexArrays(1, &vao_);
    glGenBuffers(1, &vbo_);
    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    const GLsizei stride = (GLsizei) sizeof(Dab);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, stride, (const void*) 0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, stride, (const void*) (4 * sizeof(float)));
    glEnableVertexAttribArray(2);
    glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, stride, (const void*) (6 * sizeof(float)));
    for (GLuint i = 0; i < 3; i++) glVertexAttribDivisor(i, 1);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    tilesX_ = (T_ + kTile - 1) / kTile;
    tilesY_ = tilesX_;
    tiles_.assign((size_t) tilesX_ * tilesY_, 0);
    touched_ = Rect{};
    return true;
}

void Renderer::forget() {
    prog_ = comp_ = vao_ = vbo_ = 0;
    scratchTex_ = scratchFbo_ = backupTex_ = backupFbo_ = 0;
    touched_ = Rect{};
    std::fill(tiles_.begin(), tiles_.end(), (uint8_t) 0);
}

void Renderer::shutdown() {
    if (prog_) glDeleteProgram(prog_);
    if (comp_) glDeleteProgram(comp_);
    if (vbo_) glDeleteBuffers(1, &vbo_);
    if (vao_) glDeleteVertexArrays(1, &vao_);
    if (scratchFbo_) glDeleteFramebuffers(1, &scratchFbo_);
    if (scratchTex_) glDeleteTextures(1, &scratchTex_);
    if (backupFbo_) glDeleteFramebuffers(1, &backupFbo_);
    if (backupTex_) glDeleteTextures(1, &backupTex_);
    forget();
}

// Scratch + backup pictures (2 x T*T*4 bytes) exist only once an opacity-capped brush is used.
bool Renderer::capBuffers() {
    if (scratchFbo_ && backupFbo_) return true;
    auto make = [&](GLuint& tex, GLuint& fbo) {
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, T_, T_, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        const bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.f, 0.f, 0.f, 0.f);
        glClear(GL_COLOR_BUFFER_BIT);   // fresh texture memory is undefined
        return ok;
    };
    const bool a = make(scratchTex_, scratchFbo_);
    const bool b = make(backupTex_, backupFbo_);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (!a || !b) {
        BLOGE("opacity-cap buffers incomplete (%dx%d); falling back to build-up", T_, T_);
        if (scratchFbo_) glDeleteFramebuffers(1, &scratchFbo_);
        if (scratchTex_) glDeleteTextures(1, &scratchTex_);
        if (backupFbo_) glDeleteFramebuffers(1, &backupFbo_);
        if (backupTex_) glDeleteTextures(1, &backupTex_);
        scratchTex_ = scratchFbo_ = backupTex_ = backupFbo_ = 0;
        return false;
    }
    return true;
}

// Copies the tiles of [r] that the stroke has not touched yet from the layer into the backup. Because the layer is only
// written (restore + composite) AFTER its tile is here, the backup always holds the picture as it was before the stroke.
void Renderer::backupTiles(GLuint layerFbo, const Rect& r) {
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, layerFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, backupFbo_);
    const int tx0 = r.x0 / kTile, tx1 = (r.x1 - 1) / kTile, ty0 = r.y0 / kTile, ty1 = (r.y1 - 1) / kTile;
    for (int ty = ty0; ty <= ty1 && ty < tilesY_; ty++) {
        int tx = tx0;
        while (tx <= tx1 && tx < tilesX_) {
            if (tiles_[(size_t) ty * tilesX_ + tx]) { tx++; continue; }
            int run = tx;   // longest run of untouched tiles in this row -> one blit
            while (run + 1 <= tx1 && run + 1 < tilesX_ && !tiles_[(size_t) ty * tilesX_ + run + 1]) run++;
            const int x0 = tx * kTile, x1 = std::min(T_, (run + 1) * kTile), y0 = ty * kTile, y1 = std::min(T_, (ty + 1) * kTile);
            glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            for (int i = tx; i <= run; i++) tiles_[(size_t) ty * tilesX_ + i] = 1;
            tx = run + 1;
        }
    }
}

Rect Renderer::draw(GLuint layerFbo, const Params& P, uint32_t /*argb*/, bool erase, const Dab* d, size_t n) {
    Rect dirty;
    if (!prog_ || !d || n == 0) return dirty;
    const bool capped = P.v[P_BUILDUP] < 0.5f && capBuffers();
    const TipU tu = tipUniforms(P, (float) T_ / kBrushSpace);

    glBindVertexArray(vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glViewport(0, 0, T_, T_);

    auto dabState = [&]() {
        glUseProgram(prog_);
        glUniform1f(uT_, (float) T_);
        glUniform1i(uTip_, tu.tip);
        glUniform1f(uHard_, tu.hard);
        glUniform1f(uAsp_, tu.asp);
        glUniform1f(uGrain_, tu.grain);
        glUniform1f(uGScale_, tu.gscale);
        glUniform1i(uGPaper_, tu.gpaper ? 1 : 0);
        glUniform1f(uWet_, tu.wet);
        glUniform1f(uBris_, tu.bris);
        glUniform1f(uSeed_, tu.seed);
    };
    auto blendLayer = [&]() {
        glEnable(GL_BLEND);
        if (erase) glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
        else glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    };

    for (size_t off = 0; off < n; off += kBatch) {
        const size_t m = std::min(kBatch, n - off);
        const Rect bb = dabBounds(d + off, m, T_);
        if (bb.empty()) continue;
        glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr) (m * sizeof(Dab)), d + off, GL_STREAM_DRAW);
        glEnable(GL_SCISSOR_TEST);
        glScissor(bb.x0, bb.y0, bb.x1 - bb.x0, bb.y1 - bb.y0);

        if (!capped) {
            glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
            dabState();
            blendLayer();
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, (GLsizei) m);
        } else {
            backupTiles(layerFbo, bb);
            // 1. dabs -> scratch, per-pixel max: overlap inside one stroke never gets denser
            glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo_);
            glEnable(GL_SCISSOR_TEST);
            glScissor(bb.x0, bb.y0, bb.x1 - bb.x0, bb.y1 - bb.y0);
            dabState();
            glEnable(GL_BLEND);
            glBlendEquation(GL_MAX);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, (GLsizei) m);
            glBlendEquation(GL_FUNC_ADD);
            // 2. layer := backup in the box
            glDisable(GL_SCISSOR_TEST);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, backupFbo_);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, layerFbo);
            glBlitFramebuffer(bb.x0, bb.y0, bb.x1, bb.y1, bb.x0, bb.y0, bb.x1, bb.y1, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            // 3. scratch * opacity over it (erase: cuts)
            glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
            glEnable(GL_SCISSOR_TEST);
            glScissor(bb.x0, bb.y0, bb.x1 - bb.x0, bb.y1 - bb.y0);
            glUseProgram(comp_);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, scratchTex_);
            glUniform1i(cTex_, 0);
            glUniform1f(cOp_, P.v[P_OPACITY]);
            blendLayer();
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glBindTexture(GL_TEXTURE_2D, 0);
            touched_.merge(bb);
        }
        dirty.merge(bb);
    }

    // hand the GL state back the way fox_canvas.cpp expects it
    glDisable(GL_SCISSOR_TEST);
    glBlendEquation(GL_FUNC_ADD);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glUseProgram(0);
    glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
    return dirty;
}

void Renderer::endStroke(GLuint layerFbo) {
    if (!touched_.empty() && scratchFbo_) {
        glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo_);
        glEnable(GL_SCISSOR_TEST);
        glScissor(touched_.x0, touched_.y0, touched_.x1 - touched_.x0, touched_.y1 - touched_.y0);
        glClearColor(0.f, 0.f, 0.f, 0.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDisable(GL_SCISSOR_TEST);
        if (layerFbo) glBindFramebuffer(GL_FRAMEBUFFER, layerFbo);
    }
    touched_ = Rect{};
    std::fill(tiles_.begin(), tiles_.end(), (uint8_t) 0);
}

}  // namespace brush
}  // namespace fox

// ================================================================================================ JNI (stateless)

extern "C" JNIEXPORT jintArray JNICALL
Java_fox_foxiru_foxcat_fox2d_jnicallers_NativeCanvas_nativeBrushPreview(JNIEnv* env, jclass, jfloatArray jp, jint argb, jint w, jint h, jfloat unit, jfloatArray js) {
    using namespace fox::brush;
    if (w < 4 || h < 4 || w > 2048 || h > 1024) return env->NewIntArray(0);
    float tmp[64] = {0};
    const jsize len = jp ? std::min<jsize>(env->GetArrayLength(jp), 64) : 0;
    if (len > 0) env->GetFloatArrayRegion(jp, 0, len, tmp);
    Params P;
    Params::fromArray(tmp, (int) len, P);
    std::vector<float> samples;
    if (js) {
        const jsize n = std::min<jsize>(env->GetArrayLength(js), 4 * 4096);   // 4096 pad samples is plenty
        samples.resize((size_t) n);
        if (n > 0) env->GetFloatArrayRegion(js, 0, n, samples.data());
    }
    std::vector<uint32_t> px;
    renderPreview(P, (uint32_t) argb, w, h, unit > 0.f ? unit : 1.f, samples.data(), (int) (samples.size() / 4), px);
    jintArray out = env->NewIntArray((jsize) px.size());
    if (out) env->SetIntArrayRegion(out, 0, (jsize) px.size(), reinterpret_cast<const jint*>(px.data()));
    return out;
}
