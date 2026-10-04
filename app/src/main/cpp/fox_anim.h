// fox_anim.h - keyframe + easing maths for layer transforms. Header-only, no GL, no JNI: shared by
// fox_canvas.cpp (live canvas) and fox_exporter.cpp (movie export), so both sample the SAME curve.
// Kotlin owns the UI and the key list; every curve is evaluated here, in C++.
//
// Per-channel keys: every animated PROPERTY (position X, position Y, width, height, rotation) has its own lane,
// so a slider can be keyed without touching the others.
// Flat key layout (kKeyStride floats per key, sorted by (channel, frame); one lane = one run of equal channel):
//   [0] frame (row-local, source frames)   [1] channel (0 tx, 1 ty, 2 sx, 3 sy, 4 rotDeg)   [2] value
//   [3] easing id of the segment that STARTS at this key   [4..7] bezier x1 y1 x2 y2 (id == Bezier only)
#pragma once

#include <glm/glm.hpp>

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace fox::anim {

constexpr int kKeyStride = 8;
constexpr int kChanCount = 5;
constexpr int kXfStride = 7;   // tx ty sx sy rotDeg pivotX pivotY

// Easing ids. KEEP IN SYNC with Easing.kt. Families are 3 ids each: In, Out, InOut.
enum Ease : int {
    Hold = 0, Linear = 1,
    Sine = 2, Quad = 5, Cubic = 8, Quart = 11, Expo = 14, Back = 17, Elastic = 20, Bounce = 23,
    Bezier = 26,
    EaseCount = 27,
};

struct Xf {
    float tx = 0.f, ty = 0.f, sx = 1.f, sy = 1.f, rot = 0.f, px = 0.5f, py = 0.5f;
};

namespace detail {

constexpr float kPi = 3.14159265358979f;

inline float outBounce(float t) {
    const float n1 = 7.5625f, d1 = 2.75f;
    if (t < 1.f / d1) return n1 * t * t;
    if (t < 2.f / d1) { t -= 1.5f / d1; return n1 * t * t + 0.75f; }
    if (t < 2.5f / d1) { t -= 2.25f / d1; return n1 * t * t + 0.9375f; }
    t -= 2.625f / d1;
    return n1 * t * t + 0.984375f;
}

// "In" curve of a family at t in [0,1]; Out / InOut are derived from it by symmetry.
inline float inCurve(int fam, float t) {
    switch (fam) {
        case 0: return 1.f - std::cos(t * kPi * 0.5f);                 // sine
        case 1: return t * t;                                          // quad
        case 2: return t * t * t;                                      // cubic
        case 3: return t * t * t * t;                                  // quart
        case 4: return t <= 0.f ? 0.f : std::exp2(10.f * t - 10.f);    // expo
        case 5: { const float c1 = 1.70158f; return (c1 + 1.f) * t * t * t - c1 * t * t; }   // back
        case 6: {                                                      // elastic
            if (t <= 0.f) return 0.f;
            if (t >= 1.f) return 1.f;
            return -std::exp2(10.f * t - 10.f) * std::sin((t * 10.f - 10.75f) * (2.f * kPi / 3.f));
        }
        default: return 1.f - outBounce(1.f - t);                      // bounce
    }
}

// Standard CSS-style cubic-bezier(x1,y1,x2,y2): solve x(s)=t for s (Newton, bisection fallback), return y(s).
inline float cubicBezier(float x1, float y1, float x2, float y2, float t) {
    x1 = std::clamp(x1, 0.f, 1.f);
    x2 = std::clamp(x2, 0.f, 1.f);
    const float cx = 3.f * x1, bx = 3.f * (x2 - x1) - cx, ax = 1.f - cx - bx;
    const float cy = 3.f * y1, by = 3.f * (y2 - y1) - cy, ay = 1.f - cy - by;
    auto X = [&](float s) { return ((ax * s + bx) * s + cx) * s; };
    auto dX = [&](float s) { return (3.f * ax * s + 2.f * bx) * s + cx; };
    float s = t;
    for (int i = 0; i < 8; i++) {
        const float err = X(s) - t;
        if (std::fabs(err) < 1e-6f) return ((ay * s + by) * s + cy) * s;
        const float d = dX(s);
        if (std::fabs(d) < 1e-6f) break;
        s -= err / d;
    }
    float lo = 0.f, hi = 1.f;
    s = t;
    for (int i = 0; i < 24; i++) {
        const float x = X(s);
        if (std::fabs(x - t) < 1e-6f) break;
        if (x < t) lo = s; else hi = s;
        s = 0.5f * (lo + hi);
    }
    return ((ay * s + by) * s + cy) * s;
}

}  // namespace detail

// Eased progress for t in [0,1]. May leave [0,1] (back / elastic overshoot). Hold stays at 0 until the next key.
inline float ease(int id, float t, const glm::vec4& bez = glm::vec4(0.25f, 0.1f, 0.25f, 1.f)) {
    t = std::clamp(t, 0.f, 1.f);
    if (id <= Hold) return 0.f;
    if (id == Linear) return t;
    if (id == Bezier) return detail::cubicBezier(bez.x, bez.y, bez.z, bez.w, t);
    if (id < Sine || id >= Bezier) return t;
    const int fam = (id - Sine) / 3, dir = (id - Sine) % 3;
    if (dir == 0) return detail::inCurve(fam, t);
    if (dir == 1) return 1.f - detail::inCurve(fam, 1.f - t);
    return t < 0.5f ? 0.5f * detail::inCurve(fam, 2.f * t)
                    : 1.f - 0.5f * detail::inCurve(fam, 2.f * (1.f - t));
}

// Value of ONE lane at row-local frame [local]. [k] = n records of one channel, sorted by frame.
inline float evalLane(const float* k, int n, float local) {
    if (n == 1 || local <= k[0]) return k[2];
    const float* last = k + (size_t) (n - 1) * kKeyStride;
    if (local >= last[0]) return last[2];

    int lo = 0, hi = n - 1;   // k[lo].frame <= local < k[hi].frame
    while (hi - lo > 1) {
        const int mid = (lo + hi) / 2;
        if (k[(size_t) mid * kKeyStride] <= local) lo = mid; else hi = mid;
    }
    const float* a = k + (size_t) lo * kKeyStride;
    const float* b = k + (size_t) hi * kKeyStride;
    const float span = b[0] - a[0];
    const float t = span > 1e-6f ? (local - a[0]) / span : 1.f;
    const float e = ease((int) a[3], t, glm::vec4(a[4], a[5], a[6], a[7]));
    return a[2] + (b[2] - a[2]) * e;   // rotation is degrees, NOT shortest-path: 0 -> 720 spins twice
}

// Generic multi-channel evaluation (used by fox_rig.h): [keys] = n records sorted by (channel, frame) with channel ids
// anywhere in [0, nOut). Every channel that HAS keys overwrites out[channel]; channels without keys keep whatever the
// caller put there (their static / base value).
inline void evalChannels(const float* keys, int n, float local, float* out, int nOut) {
    if (n <= 0 || !keys) return;
    int i = 0;
    while (i < n) {
        const int c = (int) keys[(size_t) i * kKeyStride + 1];
        int j = i + 1;
        while (j < n && (int) keys[(size_t) j * kKeyStride + 1] == c) j++;
        if (c >= 0 && c < nOut) out[c] = evalLane(keys + (size_t) i * kKeyStride, j - i, local);
        i = j;
    }
}

// Pose of a row at row-local frame [local] (fractional ok). [keys] = n records, sorted by (channel, frame).
// A channel without keys keeps its [base] value; outside a lane's first / last key the value is held.
// Pivot always comes from [base].
inline Xf evalRow(const float* keys, int n, float local, const Xf& base) {
    Xf r = base;
    if (n <= 0 || !keys) return r;
    float* const out[kChanCount] = {&r.tx, &r.ty, &r.sx, &r.sy, &r.rot};
    int i = 0;
    while (i < n) {
        const int c = (int) keys[(size_t) i * kKeyStride + 1];
        int j = i + 1;
        while (j < n && (int) keys[(size_t) j * kKeyStride + 1] == c) j++;
        if (c >= 0 && c < kChanCount) *out[c] = evalLane(keys + (size_t) i * kKeyStride, j - i, local);
        i = j;
    }
    return r;
}

static_assert(sizeof(Xf) == kXfStride * sizeof(float), "Xf is memcmp'd by the exporter");

inline void writeXf(const Xf& x, float* out) {
    out[0] = x.tx; out[1] = x.ty; out[2] = x.sx; out[3] = x.sy; out[4] = x.rot; out[5] = x.px; out[6] = x.py;
}

inline Xf readXf(const float* v) { return Xf{v[0], v[1], v[2], v[3], v[4], v[5], v[6]}; }

}  // namespace fox::anim
