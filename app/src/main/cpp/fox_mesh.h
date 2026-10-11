// fox_mesh.h - adaptive TRIANGLE mesh that follows a drawing's silhouette (the Illustrator / After Effects puppet-warp look),
// instead of a square grid over the whole box. Header-only, no GL, no JNI, no glm.
//
//   mask (n x n bytes over paper units 0..1, > 127 = inside)  ->  dilate  ->  boundary points (evenly spaced)
//   + interior points (hex lattice, kept away from the outline)  ->  Delaunay (Bowyer-Watson)  ->  keep triangles inside the shape
//   -> COVERAGE CHECK: rasterise the kept triangles; every ink pixel (+1 px margin) they miss gets a mesh point, re-triangulate (a few
//   passes), then rescue rejected triangles that still cover missed ink. Puppet-warp CUT-OUT draws ONLY the triangles, so ink outside
//   them would vanish: sharp tips (thinning drops the corner pixel, the chord cuts it off), notches (a rejected edge-midpoint leaves a
//   hole on the rim) and soft brush fringes (alpha below the old 127 cut) used to be lost.
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

// Alpha above this counts as ink. Low on purpose: soft brush edges (alpha 20..127) are visible texture and must stay inside the mesh.
constexpr uint8_t kInk = 12;

// Calls f(pixel index) for every mask pixel whose centre lies in triangle (a, b, c) (paper units). Edge-inclusive with a 0.05 px slack,
// so a vertex sitting on a pixel centre always covers that pixel despite float round-off.
template <class F>
inline void rasterTri(int n, const P& a, const P& b, const P& c, F&& f) {
    double ax = (double) a.x * n, ay = (double) a.y * n, bx = (double) b.x * n, by = (double) b.y * n, cx = (double) c.x * n, cy = (double) c.y * n;
    double area = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
    if (std::fabs(area) < 1e-9) return;
    if (area < 0) { std::swap(bx, cx); std::swap(by, cy); }
    const double lab = std::hypot(bx - ax, by - ay), lbc = std::hypot(cx - bx, cy - by), lca = std::hypot(ax - cx, ay - cy);
    if (lab < 1e-9 || lbc < 1e-9 || lca < 1e-9) return;
    const int x0 = std::max(0, (int) std::floor(std::min({ax, bx, cx}) - 0.55)), x1 = std::min(n - 1, (int) std::ceil(std::max({ax, bx, cx}) - 0.45));
    const int y0 = std::max(0, (int) std::floor(std::min({ay, by, cy}) - 0.55)), y1 = std::min(n - 1, (int) std::ceil(std::max({ay, by, cy}) - 0.45));
    const double tol = -0.05;
    for (int y = y0; y <= y1; y++)
        for (int x = x0; x <= x1; x++) {
            const double px = x + 0.5, py = y + 0.5;
            if (((bx - ax) * (py - ay) - (by - ay) * (px - ax)) / lab < tol) continue;
            if (((cx - bx) * (py - by) - (cy - by) * (px - bx)) / lbc < tol) continue;
            if (((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) / lca < tol) continue;
            f((size_t) y * (size_t) n + (size_t) x);
        }
}

// [spacing] = distance between mesh points, paper units (0.02 fine .. 0.08 coarse). [dilatePx] = grow the shape first.
inline std::vector<float> generate(const uint8_t* mask, int n, float spacing, int dilatePx, int maxVerts = 1500) {
    if (n < 16) return {};
    Shape S; S.n = n; S.on.resize((size_t) n * n);
    int cnt = 0;
    for (size_t i = 0; i < S.on.size(); i++) { S.on[i] = mask[i] > kInk; cnt += S.on[i]; }
    if (cnt < 16) return {};
    // what the triangles must cover: the ink plus a 1 px margin (bilinear texel taps at a triangle edge, antialiasing)
    Shape need; need.n = n; need.on = S.on;
    dilate(need, 1);
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

        // 3. triangulate, keep what is inside the shape (centroid and edge midpoints: no triangles bridging a gap).
        //    Triangles that only fail the edge-midpoint test are remembered: they may be needed to cover a notch rim (step 4).
        std::vector<std::array<int, 3>> good, rejected;
        auto triangulate = [&]() {
            good.clear(); rejected.clear();
            for (auto& t : delaunay(pts)) {
                const P &A = pts[(size_t) t[0]], &B = pts[(size_t) t[1]], &C = pts[(size_t) t[2]];
                const float area = 0.5f * std::fabs((B.x - A.x) * (C.y - A.y) - (C.x - A.x) * (B.y - A.y));
                if (area < 1e-7f) continue;
                if (!S.at((A.x + B.x + C.x) / 3.f, (A.y + B.y + C.y) / 3.f)) continue;
                if (!S.at((A.x + B.x) * 0.5f, (A.y + B.y) * 0.5f) || !S.at((B.x + C.x) * 0.5f, (B.y + C.y) * 0.5f) ||
                    !S.at((C.x + A.x) * 0.5f, (C.y + A.y) * 0.5f)) { rejected.push_back(t); continue; }
                good.push_back(t);
            }
        };
        triangulate();
        if (good.empty()) continue;

        // 4. coverage: every ink pixel (+1 px) must lie in a kept triangle, or the cut-out would erase it.
        std::vector<uint8_t> cov((size_t) n * (size_t) n);
        auto paint = [&](const std::array<int, 3>& t) {
            rasterTri(n, pts[(size_t) t[0]], pts[(size_t) t[1]], pts[(size_t) t[2]], [&](size_t i) { cov[i] = 1; });
        };
        auto countMiss = [&](const std::array<int, 3>& t) {
            int c = 0;
            rasterTri(n, pts[(size_t) t[0]], pts[(size_t) t[1]], pts[(size_t) t[2]], [&](size_t i) { if (need.on[i] && !cov[i]) c++; });
            return c;
        };
        auto uncovered = [&]() {
            std::fill(cov.begin(), cov.end(), (uint8_t) 0);
            for (auto& t : good) paint(t);
            size_t m = 0;
            for (size_t i = 0; i < cov.size(); i++) m += need.on[i] && !cov[i];
            return m;
        };
        const int cap = maxVerts + maxVerts / 4;
        size_t miss = uncovered();
        for (int pass = 0; pass < 8 && miss > 0 && (int) pts.size() < cap; pass++) {
            // a point on every missed pixel (thinned): tips and rim pixels become vertices, so the triangles reach them
            const size_t before = pts.size();
            // the thinning distance shrinks every pass, so a pixel hugging an existing vertex still gets its own point eventually
            const float md = std::max(0.3f * h * std::pow(0.5f, (float) pass), 2.5f / (float) n);
            for (int y = 0; y < n && (int) pts.size() < cap; y++)
                for (int x = 0; x < n && (int) pts.size() < cap; x++) {
                    const size_t i = (size_t) y * (size_t) n + (size_t) x;
                    if (!need.on[i] || cov[i]) continue;
                    const float px = ((float) x + 0.5f) / (float) n, py = ((float) y + 0.5f) / (float) n;
                    if (!near(px, py, md)) add(px, py);
                }
            if (pts.size() == before) break;
            triangulate();
            if (good.empty()) break;
            const size_t m2 = uncovered();
            miss = m2;
        }
        if (miss > 0) {
            // last resort: a triangle rejected for a midpoint outside the shape, but which still holds missed ink (a notch rim)
            std::vector<std::pair<int, size_t>> cand;
            for (size_t k = 0; k < rejected.size(); k++) { const int c = countMiss(rejected[k]); if (c >= 1) cand.push_back({c, k}); }
            std::sort(cand.begin(), cand.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
            for (auto& c : cand) if (countMiss(rejected[c.second]) >= 1) { good.push_back(rejected[c.second]); paint(rejected[c.second]); }
        }
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
