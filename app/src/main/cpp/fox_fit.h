// fox_fit.h - "Fit to image" for Puppet Warp. Header-only, no GL, no JNI, no glm.
//
// Given the drawing's silhouette (source mask) and the silhouette of a picked image (target mask), both N x N bytes over the
// layer's paper units 0..1, it proposes puppet-warp pins: the target is mapped onto the drawing's bounding box, K rays leave
// the centre of each shape, and every ray gives one pin: rest = where the drawing's outline is, offset = where the target's
// outline is. Pins are plain puppet-warp pins, so the usual MLS deformation (fox_rig.h) does the bending.
//
// Target silhouette ("mode"; the input byte is already background-corrected by Kotlin):
//   0 = byte is ALPHA, shape = byte > 127
//   1 = byte is LUMA, shape = DARK pixels  (Otsu threshold)
//   2 = byte is LUMA, shape = LIGHT pixels (Otsu threshold)
//
// OUT: [0..3] mapped target box l t r b, [4] pin count, then per pin: rx ry dx dy (offset = target - rest). Empty = failed.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace fox::fit {

struct Mask {
    int n = 0;
    std::vector<uint8_t> on;
    int count = 0;
    float cx = 0.f, cy = 0.f, l = 1.f, t = 1.f, r = 0.f, b = 0.f;   // centroid + box, paper units
    bool at(float x, float y) const {   // paper units
        const int ix = (int) std::floor(x * (float) n), iy = (int) std::floor(y * (float) n);
        return ix >= 0 && iy >= 0 && ix < n && iy < n && on[(size_t) iy * (size_t) n + (size_t) ix];
    }
};

inline int otsu(const uint8_t* v, size_t cnt) {
    double hist[256] = {0};
    for (size_t i = 0; i < cnt; i++) hist[v[i]] += 1.0;
    double sum = 0;
    for (int i = 0; i < 256; i++) sum += i * hist[i];
    double wB = 0, sB = 0, best = -1;
    int th = 127;
    for (int i = 0; i < 256; i++) {
        wB += hist[i];
        if (wB <= 0) continue;
        const double wF = (double) cnt - wB;
        if (wF <= 0) break;
        sB += i * hist[i];
        const double d = sB / wB - (sum - sB) / wF;
        const double between = wB * wF * d * d;
        if (between > best) { best = between; th = i; }
    }
    return th;
}

inline void finish(Mask& m) {
    double sx = 0, sy = 0;
    const float inv = 1.f / (float) m.n;
    for (int y = 0; y < m.n; y++)
        for (int x = 0; x < m.n; x++) {
            if (!m.on[(size_t) y * (size_t) m.n + (size_t) x]) continue;
            m.count++; sx += (x + 0.5f) * inv; sy += (y + 0.5f) * inv;
            m.l = std::min(m.l, x * inv); m.r = std::max(m.r, (x + 1) * inv);
            m.t = std::min(m.t, y * inv); m.b = std::max(m.b, (y + 1) * inv);
        }
    if (m.count > 0) { m.cx = (float) (sx / m.count); m.cy = (float) (sy / m.count); }
}

inline Mask fromAlpha(const uint8_t* v, int n) {
    Mask m; m.n = n; m.on.resize((size_t) n * (size_t) n);
    for (size_t i = 0; i < m.on.size(); i++) m.on[i] = v[i] > 127;
    finish(m);
    return m;
}

inline Mask fromTarget(const uint8_t* v, int n, int mode) {
    if (mode == 0) return fromAlpha(v, n);
    Mask m; m.n = n; m.on.resize((size_t) n * (size_t) n);
    const int th = otsu(v, m.on.size());
    for (size_t i = 0; i < m.on.size(); i++) m.on[i] = mode == 1 ? v[i] <= th : v[i] > th;
    finish(m);
    return m;
}

// Farthest shape pixel along the ray (c + s * dir), in paper units. 0 = the ray never touches the shape.
inline float rayRadius(const Mask& m, float cx, float cy, float dx, float dy) {
    const float step = 0.5f / (float) m.n, maxR = 1.5f;
    float best = 0.f;
    for (float s = 0.f; s < maxR; s += step) if (m.at(cx + dx * s, cy + dy * s)) best = s;
    return best;
}

inline std::vector<float> solve(const uint8_t* src, const uint8_t* tgt, int n, int mode, bool uniform, int maxPins) {
    if (n < 16 || maxPins < 3) return {};
    const Mask S = fromAlpha(src, n), T = fromTarget(tgt, n, mode);
    if (S.count < 8 || T.count < 8) return {};
    const float sw = S.r - S.l, sh = S.b - S.t, tw = T.r - T.l, th = T.b - T.t;
    if (sw < 1e-4f || sh < 1e-4f || tw < 1e-4f || th < 1e-4f) return {};
    float kx = sw / tw, ky = sh / th;
    if (uniform) kx = ky = std::min(kx, ky);
    const float scx = 0.5f * (S.l + S.r), scy = 0.5f * (S.t + S.b), tcx = 0.5f * (T.l + T.r), tcy = 0.5f * (T.t + T.b);
    auto map = [&](float x, float y, float& ox, float& oy) { ox = scx + (x - tcx) * kx; oy = scy + (y - tcy) * ky; };

    std::vector<float> out(5);
    {
        float a, b, c, d;
        map(T.l, T.t, a, b); map(T.r, T.b, c, d);
        out[0] = a; out[1] = b; out[2] = c; out[3] = d;
    }
    int pins = 0;
    auto add = [&](float px, float py, float qx, float qy) {
        out.push_back(px); out.push_back(py); out.push_back(qx - px); out.push_back(qy - py);
        pins++;
    };
    {   // centre pin: the shapes' centres of mass
        float qx, qy; map(T.cx, T.cy, qx, qy);
        add(S.cx, S.cy, qx, qy);
    }
    const int K = std::clamp(maxPins - 1, 3, 23);
    const float twoPi = 6.28318530718f;
    for (int k = 0; k < K; k++) {
        const float a = twoPi * (float) k / (float) K, dx = std::cos(a), dy = std::sin(a);
        const float rs = rayRadius(S, S.cx, S.cy, dx, dy), rt = rayRadius(T, T.cx, T.cy, dx, dy);
        if (rs < 2.f / (float) n || rt < 2.f / (float) n) continue;
        float qx, qy; map(T.cx + dx * rt, T.cy + dy * rt, qx, qy);
        add(S.cx + dx * rs, S.cy + dy * rs, qx, qy);
    }
    if (pins < 3) return {};
    out[4] = (float) pins;
    return out;
}

}  // namespace fox::fit
