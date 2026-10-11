// fox_graph.h - curve smoothing + sampling for the KeyGraph editor. Header-only, no GL, no JNI.
// Works on the SAME flat key layout as fox_anim.h (kKeyStride floats per key, sorted by (channel, frame)), and writes its
// result back as ordinary per-segment Bezier easings. So whatever the graph shows is exactly what fox_anim.h::evalRow
// plays on the canvas and in the exported movie: there is no second curve type to keep in sync.
//
// How smoothing works: for every key we pick a tangent (value change per frame). A segment a -> b with tangents ma, mb is
// a cubic Hermite curve; written as the segment's cubic-bezier(1/3, ya, 2/3, yb) easing it is IDENTICAL, with
//     ya = ma * span / (3 * dv)        yb = 1 - mb * span / (3 * dv)        (span = frames, dv = value change)
// Because both neighbouring segments share the tangent at their common key, the curve has no corner there (C1).
#pragma once

#include "fox_anim.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace fox::graph {

enum SmoothMode : int {
    Monotone = 0,   // never overshoots a key, flat at peaks / valleys (PCHIP): safe for opacity, volume-like values
    Flow = 1,       // Catmull-Rom: rounder, flowing, may overshoot a little between keys (good for swings / rotation)
};

// Vertical range a bezier handle may have (normalised to the segment's value change). Wider than the old easing UI's
// [-0.5, 1.5] so pen-style handles on short / flat segments are not squashed.
constexpr float kYMin = -3.f, kYMax = 4.f;

namespace detail {

// Tangents (value per frame) of one lane: [k] = n records of ONE channel, sorted by frame.
inline void laneTangents(const float* k, int n, int mode, float strength, bool flatEnds, std::vector<float>& m) {
    using fox::anim::kKeyStride;
    m.assign((size_t) n, 0.f);
    if (n < 2) return;
    auto F = [&](int i) { return k[(size_t) i * kKeyStride]; };
    auto V = [&](int i) { return k[(size_t) i * kKeyStride + 2]; };
    std::vector<float> d((size_t) n - 1), h((size_t) n - 1);
    for (int i = 0; i + 1 < n; i++) {
        h[(size_t) i] = std::max(F(i + 1) - F(i), 1e-6f);
        d[(size_t) i] = (V(i + 1) - V(i)) / h[(size_t) i];
    }
    for (int i = 1; i + 1 < n; i++) {
        if (mode == Flow) {
            m[(size_t) i] = (V(i + 1) - V(i - 1)) / std::max(F(i + 1) - F(i - 1), 1e-6f);
        } else {
            const float d0 = d[(size_t) i - 1], d1 = d[(size_t) i];
            if (d0 * d1 <= 0.f) { m[(size_t) i] = 0.f; continue; }          // peak / valley / plateau: flat
            const float h0 = h[(size_t) i - 1], h1 = h[(size_t) i];
            const float w1 = 2.f * h1 + h0, w2 = h1 + 2.f * h0;             // weighted harmonic mean (Fritsch-Butland)
            m[(size_t) i] = (w1 + w2) / (w1 / d0 + w2 / d1);
        }
    }
    m[0] = flatEnds ? 0.f : d[0];
    m[(size_t) n - 1] = flatEnds ? 0.f : d[(size_t) n - 2];
    for (float& t : m) t *= strength;
}

}  // namespace detail

// Smooth the lanes of [keys] IN PLACE (rewrites only easing id + bezier handles, never frame / value).
//   chan        channel to smooth, -1 = every channel
//   mode        SmoothMode
//   strength    0..1: 0 = every key eases flat (classic ease-in/out), 1 = full tangents
//   flatEnds    first / last key get a zero tangent (ease in at the start, ease out at the end)
//   frameLo/Hi  only segments overlapping [frameLo, frameHi] change; use +-1e9 for the whole lane
// Segments that are Hold stay Hold (a deliberate step is never "smoothed" away). Returns the number of segments rewritten.
inline int smoothKeys(float* keys, int n, int chan, int mode, float strength, bool flatEnds, float frameLo, float frameHi) {
    using namespace fox::anim;
    if (!keys || n < 2) return 0;
    strength = std::clamp(strength, 0.f, 1.5f);
    int changed = 0;
    std::vector<float> m;
    int i = 0;
    while (i < n) {
        const int c = (int) keys[(size_t) i * kKeyStride + 1];
        int j = i + 1;
        while (j < n && (int) keys[(size_t) j * kKeyStride + 1] == c) j++;
        const int cnt = j - i;
        if ((chan < 0 || chan == c) && cnt >= 2) {
            float* lane = keys + (size_t) i * kKeyStride;
            detail::laneTangents(lane, cnt, mode, strength, flatEnds, m);
            for (int s = 0; s + 1 < cnt; s++) {
                float* a = lane + (size_t) s * kKeyStride;
                const float* b = lane + (size_t) (s + 1) * kKeyStride;
                if (a[0] > frameHi || b[0] < frameLo) continue;
                if ((int) a[3] == Hold) continue;
                const float span = b[0] - a[0], dv = b[2] - a[2];
                if (span <= 1e-6f) continue;
                if (std::fabs(dv) < 1e-6f) {                     // flat segment: nothing to ease
                    a[3] = (float) Linear; changed++;
                    continue;
                }
                const float ya = m[(size_t) s] * span / (3.f * dv);
                const float yb = 1.f - m[(size_t) s + 1] * span / (3.f * dv);
                a[3] = (float) Bezier;
                a[4] = 1.f / 3.f; a[5] = std::clamp(ya, kYMin, kYMax);      // the graph editor's handle range
                a[6] = 2.f / 3.f; a[7] = std::clamp(yb, kYMin, kYMax);
                changed++;
            }
        }
        i = j;
    }
    return changed;
}

// [m] samples of ONE lane over row-local frames [f0, f1] (inclusive), exactly what the canvas will play.
inline void sampleLane(const float* lane, int n, float f0, float f1, float* out, int m) {
    if (!lane || n <= 0 || m <= 0) return;
    for (int i = 0; i < m; i++) {
        const float t = m > 1 ? (float) i / (float) (m - 1) : 0.f;
        out[i] = fox::anim::evalLane(lane, n, f0 + (f1 - f0) * t);
    }
}

// ---------------------------------------------------------------------------------------------- pen-tool style editing

// Split the cubic-bezier easing (x1,y1,x2,y2) at time fraction [t] (0..1) WITHOUT changing the curve (de Casteljau).
// o[0..3] = left half (x1 y1 x2 y2), o[4..7] = right half, o[8] = y of the curve at t (the new anchor's normalised value).
// Each half is re-normalised to its own 0..1 box, so it is again an ordinary easing of a segment. false = degenerate
// (the curve passes through the start / end level at t, nothing to normalise against): the caller falls back to Linear.
inline bool splitBezier(float x1, float y1, float x2, float y2, float t, float* o) {
    x1 = std::clamp(x1, 0.f, 1.f); x2 = std::clamp(x2, 0.f, 1.f);
    t = std::clamp(t, 0.f, 1.f);
    auto X = [&](float s) { const float u = 1.f - s; return 3.f * u * u * s * x1 + 3.f * u * s * s * x2 + s * s * s; };
    float lo = 0.f, hi = 1.f, s = t;
    for (int i = 0; i < 48; i++) { s = 0.5f * (lo + hi); if (X(s) < t) lo = s; else hi = s; }
    struct P { float x, y; };
    auto L = [](P a, P b, float k) { return P{a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k}; };
    const P p0{0.f, 0.f}, p1{x1, y1}, p2{x2, y2}, p3{1.f, 1.f};
    const P a = L(p0, p1, s), b = L(p1, p2, s), c = L(p2, p3, s), d = L(a, b, s), e = L(b, c, s), m = L(d, e, s);
    const float xs = m.x, ys = m.y;
    if (xs < 1e-4f || xs > 1.f - 1e-4f || std::fabs(ys) < 1e-4f || std::fabs(1.f - ys) < 1e-4f) return false;
    o[0] = a.x / xs;  o[1] = a.y / ys;  o[2] = d.x / xs;  o[3] = d.y / ys;
    o[4] = (e.x - xs) / (1.f - xs);  o[5] = (e.y - ys) / (1.f - ys);
    o[6] = (c.x - xs) / (1.f - xs);  o[7] = (c.y - ys) / (1.f - ys);
    o[8] = ys;
    for (int i : {0, 2, 4, 6}) o[i] = std::clamp(o[i], 0.f, 1.f);        // the evaluator clamps x the same way
    for (int i : {1, 3, 5, 7}) o[i] = std::clamp(o[i], kYMin, kYMax);
    return true;
}

// Closest cubic-bezier (x1,y1,x2,y2) to ANY preset easing id (Sine .. Bounce, Linear): so a preset segment can be turned
// into editable handles. Exact for Linear / the standard Sine-Cubic-Quad shapes, a close approximation for Back / Elastic /
// Bounce. Coordinate descent from four classic starting shapes, o[0..3] out.
inline void fitBezier(int id, float* o) {
    using namespace fox::anim;
    constexpr int N = 48;
    float target[N + 1];
    for (int i = 0; i <= N; i++) target[i] = ease(id, (float) i / (float) N);
    auto err = [&](const float* p) {
        double e = 0.0;
        for (int i = 0; i <= N; i++) {
            const double dd = (double) fox::anim::detail::cubicBezier(p[0], p[1], p[2], p[3], (float) i / (float) N) - (double) target[i];
            e += dd * dd;
        }
        return e;
    };
    const float starts[4][4] = {{1.f/3.f, 1.f/3.f, 2.f/3.f, 2.f/3.f}, {0.42f, 0.f, 1.f, 1.f}, {0.f, 0.f, 0.58f, 1.f}, {0.42f, 0.f, 0.58f, 1.f}};
    double bestE = 1e30;
    for (const auto& st : starts) {
        float p[4] = {st[0], st[1], st[2], st[3]};
        double e = err(p);
        float step = 0.2f;
        for (int it = 0; it < 80 && step > 1e-4f; it++) {
            bool better = false;
            for (int c = 0; c < 4; c++) {
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float q[4] = {p[0], p[1], p[2], p[3]};
                    q[c] += (float) sgn * step;
                    if (c == 0 || c == 2) q[c] = std::clamp(q[c], 0.f, 1.f); else q[c] = std::clamp(q[c], kYMin, kYMax);
                    const double e2 = err(q);
                    if (e2 < e) { e = e2; p[c] = q[c]; better = true; }
                }
            }
            if (!better) step *= 0.5f;
        }
        if (e < bestE) { bestE = e; for (int c = 0; c < 4; c++) o[c] = p[c]; }
    }
}

}  // namespace fox::graph
