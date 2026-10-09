// fox_mesh.h - adaptive TRIANGLE mesh that follows a drawing's silhouette (the Illustrator / After Effects puppet-warp look),
// instead of a square grid over the whole box. Header-only, no GL, no JNI, no glm.
//
//   mask (n x n bytes over paper units 0..1, > 127 = inside)  ->  dilate  ->  boundary points (evenly spaced)
//   + interior points (hex lattice, kept away from the outline)  ->  Delaunay (Bowyer-Watson)  ->  keep triangles inside the shape.
//
// OUT (floats): [nV, nT, x y * nV, i j k * nT]   (indices as floats, exact below 2^24). Empty = no shape.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace fox::mesh {

struct P { float x, y; };

struct Shape {
    int n = 0;
    std::vector<uint8_t> on;
    bool at(float x, float y) const {
        const int ix = (int) std::floor(x * (float) n), iy = (int) std::floor(y * (float) n);
        return ix >= 0 && iy >= 0 && ix < n && iy < n && on[(size_t) iy * (size_t) n + (size_t) ix];
    }
};

// Grow the shape by [r] pixels (circle), so the mesh reaches a little past the ink and soft edges still bend.
inline void dilate(Shape& s, int r) {
    if (r <= 0) return;
    const int n = s.n;
    std::vector<uint8_t> out = s.on;
    for (int y = 0; y < n; y++)
        for (int x = 0; x < n; x++) {
            if (!s.on[(size_t) y * n + x]) continue;
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dy * dy > r * r) continue;
                    const int xx = x + dx, yy = y + dy;
                    if (xx >= 0 && yy >= 0 && xx < n && yy < n) out[(size_t) yy * n + xx] = 1;
                }
        }
    s.on.swap(out);
}

// Distance (px) of every inside pixel to the nearest outside pixel / border: two-pass chamfer 3-4.
inline std::vector<float> insideDistance(const Shape& s) {
    const int n = s.n;
    const float INF = 1e9f;
    std::vector<float> d((size_t) n * n);
    for (size_t i = 0; i < d.size(); i++) d[i] = s.on[i] ? INF : 0.f;
    auto at = [&](int x, int y) { return (x < 0 || y < 0 || x >= n || y >= n) ? 0.f : d[(size_t) y * n + x]; };
    for (int y = 0; y < n; y++)
        for (int x = 0; x < n; x++) {
            float& v = d[(size_t) y * n + x];
            if (v == 0.f) continue;
            v = std::min({v, at(x - 1, y) + 1.f, at(x, y - 1) + 1.f, at(x - 1, y - 1) + 1.414f, at(x + 1, y - 1) + 1.414f});
        }
    for (int y = n - 1; y >= 0; y--)
        for (int x = n - 1; x >= 0; x--) {
            float& v = d[(size_t) y * n + x];
            if (v == 0.f) continue;
            v = std::min({v, at(x + 1, y) + 1.f, at(x, y + 1) + 1.f, at(x + 1, y + 1) + 1.414f, at(x - 1, y + 1) + 1.414f});
        }
    return d;
}

struct Tri { int a, b, c; float cx, cy, r2; };

inline bool circum(const std::vector<P>& p, Tri& t) {
    const P &A = p[(size_t) t.a], &B = p[(size_t) t.b], &C = p[(size_t) t.c];
    const double d = 2.0 * ((double) A.x * (B.y - C.y) + (double) B.x * (C.y - A.y) + (double) C.x * (A.y - B.y));
    if (std::fabs(d) < 1e-14) return false;
    const double a2 = (double) A.x * A.x + (double) A.y * A.y, b2 = (double) B.x * B.x + (double) B.y * B.y, c2 = (double) C.x * C.x + (double) C.y * C.y;
    const double ux = (a2 * (B.y - C.y) + b2 * (C.y - A.y) + c2 * (A.y - B.y)) / d;
    const double uy = (a2 * (C.x - B.x) + b2 * (A.x - C.x) + c2 * (B.x - A.x)) / d;
    t.cx = (float) ux; t.cy = (float) uy;
    t.r2 = (float) ((ux - A.x) * (ux - A.x) + (uy - A.y) * (uy - A.y));
    return true;
}

// Bowyer-Watson. O(n^2): fine for the ~1500 points the generator allows.
inline std::vector<std::array<int, 3>> delaunay(std::vector<P> pts) {
    const int n = (int) pts.size();
    pts.push_back({-10.f, -10.f}); pts.push_back({12.f, -10.f}); pts.push_back({1.f, 14.f});   // super triangle
    std::vector<Tri> tris;
    Tri s{n, n + 1, n + 2, 0, 0, 0};
    circum(pts, s);
    tris.push_back(s);
    for (int i = 0; i < n; i++) {
        std::vector<Tri> keep;
        std::vector<std::pair<int, int>> edges;
        for (const Tri& t : tris) {
            const float dx = pts[(size_t) i].x - t.cx, dy = pts[(size_t) i].y - t.cy;
            if (dx * dx + dy * dy <= t.r2) {
                edges.push_back({t.a, t.b}); edges.push_back({t.b, t.c}); edges.push_back({t.c, t.a});
            } else keep.push_back(t);
        }
        std::vector<char> shared(edges.size(), 0);
        for (size_t a = 0; a < edges.size(); a++)
            for (size_t b = a + 1; b < edges.size(); b++)
                if ((edges[a].first == edges[b].second && edges[a].second == edges[b].first) ||
                    (edges[a].first == edges[b].first && edges[a].second == edges[b].second)) shared[a] = shared[b] = 1;
        for (size_t a = 0; a < edges.size(); a++) {
            if (shared[a]) continue;
            Tri t{edges[a].first, edges[a].second, i, 0, 0, 0};
            if (circum(pts, t)) keep.push_back(t);
        }
        tris.swap(keep);
    }
    std::vector<std::array<int, 3>> out;
    for (const Tri& t : tris) if (t.a < n && t.b < n && t.c < n) out.push_back({t.a, t.b, t.c});
    return out;
}

// [spacing] = distance between mesh points, paper units (0.02 fine .. 0.08 coarse). [dilatePx] = grow the shape first.
inline std::vector<float> generate(const uint8_t* mask, int n, float spacing, int dilatePx, int maxVerts = 1500) {
    if (n < 16) return {};
    Shape S; S.n = n; S.on.resize((size_t) n * n);
    int cnt = 0;
    for (size_t i = 0; i < S.on.size(); i++) { S.on[i] = mask[i] > 127; cnt += S.on[i]; }
    if (cnt < 16) return {};
    dilate(S, dilatePx);
    const std::vector<float> dist = insideDistance(S);

    for (int attempt = 0; attempt < 8; attempt++) {
        const float h = std::max(spacing, 0.01f) * (1.f + 0.25f * (float) attempt);
        std::vector<P> pts;
        // 1. outline: boundary pixels (inside, with an outside 4-neighbour), thinned so neighbours are >= 0.85 h apart
        const float minD = 0.85f * h, cell = minD;
        const int gw = (int) std::ceil(1.f / cell) + 1;
        std::unordered_map<int, std::vector<int>> hash;
        auto key = [&](float x, float y) { return (int) (y / cell) * gw + (int) (x / cell); };
        auto near = [&](float x, float y, float md) {
            const int cx = (int) (x / cell), cy = (int) (y / cell);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    auto it = hash.find((cy + dy) * gw + (cx + dx));
                    if (it == hash.end()) continue;
                    for (int id : it->second) {
                        const float ex = pts[(size_t) id].x - x, ey = pts[(size_t) id].y - y;
                        if (ex * ex + ey * ey < md * md) return true;
                    }
                }
            return false;
        };
        auto add = [&](float x, float y) { hash[key(x, y)].push_back((int) pts.size()); pts.push_back({x, y}); };
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                if (!S.on[(size_t) y * n + x]) continue;
                const bool edge = x == 0 || y == 0 || x == n - 1 || y == n - 1 || !S.on[(size_t) y * n + x - 1] || !S.on[(size_t) y * n + x + 1] ||
                                  !S.on[(size_t) (y - 1) * n + x] || !S.on[(size_t) (y + 1) * n + x];
                if (!edge) continue;
                const float px = ((float) x + 0.5f) / (float) n, py = ((float) y + 0.5f) / (float) n;
                if (!near(px, py, minD)) add(px, py);
            }
        const int nBoundary = (int) pts.size();
        // 2. inside: hex lattice, away from the outline so triangles stay well shaped
        const float rowH = h * 0.8660254f;
        const float away = 0.6f * h * (float) n;   // px
        int row = 0;
        for (float y = h * 0.5f; y < 1.f; y += rowH, row++)
            for (float x = (row & 1) ? h : h * 0.5f; x < 1.f; x += h) {
                const int ix = (int) (x * n), iy = (int) (y * n);
                if (ix < 0 || iy < 0 || ix >= n || iy >= n) continue;
                if (dist[(size_t) iy * n + ix] < away) continue;
                add(x, y);
            }
        if ((int) pts.size() > maxVerts) continue;   // too dense: coarser spacing
        if (nBoundary < 3 || pts.size() < 4) continue;

        // 3. triangulate, keep what is inside the shape (centroid and edge midpoints: no triangles bridging a gap)
        auto tris = delaunay(pts);
        std::vector<std::array<int, 3>> good;
        for (auto& t : tris) {
            const P &A = pts[(size_t) t[0]], &B = pts[(size_t) t[1]], &C = pts[(size_t) t[2]];
            const float area = 0.5f * std::fabs((B.x - A.x) * (C.y - A.y) - (C.x - A.x) * (B.y - A.y));
            if (area < 1e-7f) continue;
            if (!S.at((A.x + B.x + C.x) / 3.f, (A.y + B.y + C.y) / 3.f)) continue;
            if (!S.at((A.x + B.x) * 0.5f, (A.y + B.y) * 0.5f) || !S.at((B.x + C.x) * 0.5f, (B.y + C.y) * 0.5f) ||
                !S.at((C.x + A.x) * 0.5f, (C.y + A.y) * 0.5f)) continue;
            good.push_back(t);
        }
        if (good.empty()) continue;
        // drop points no triangle uses, renumber
        std::vector<int> remap(pts.size(), -1);
        std::vector<float> out(2);
        int nv = 0;
        for (auto& t : good) for (int k = 0; k < 3; k++) if (remap[(size_t) t[(size_t) k]] < 0) {
            remap[(size_t) t[(size_t) k]] = nv++;
            out.push_back(pts[(size_t) t[(size_t) k]].x); out.push_back(pts[(size_t) t[(size_t) k]].y);
        }
        out[0] = (float) nv; out[1] = (float) good.size();
        for (auto& t : good) for (int k = 0; k < 3; k++) out.push_back((float) remap[(size_t) t[(size_t) k]]);
        return out;
    }
    return {};
}

}  // namespace fox::mesh
