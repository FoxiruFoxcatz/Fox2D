// fox_arap.h - As-Rigid-As-Possible solver for Puppet Warp pins. Header-only, no GL, no JNI, no glm.
//
// WHAT IT SOLVES
//   Outline triangle mesh (fox_mesh.h) with rest vertices p_i. Find positions x_i that minimise
//
//       E(x, R) = sum_t w_t A_t || J_t(x) - R_t ||_F^2      J_t = sum_i x_i g_i^T   (g_i = gradient of the linear hat function)
//               + sum_c lambda_c | sum_k b_ck x_ck - q_c |^2                          (pin constraints, barycentric)
//               + eps sum_i |x_i - p_i|^2                                              (keeps pin-less pieces at rest)
//
//   with R_t a rotation per triangle (2D "triangle ARAP", Liu et al. 2008 / Sorkine-Alexa 2007 in its Dirichlet form).
//   The matrix is the assembled Dirichlet stiffness, so it is PSD for ANY triangle shape (no cotangent clamping needed).
//
// ALGORITHM (what makes it fast + robust enough for a touch-driven editor)
//   1. Local-global iteration, local step in closed form (2x2 polar: R = atan2(J10 - J01, J00 + J11)).
//   2. ANDERSON ACCELERATION of the fixed-point map x -> G(x) (Peng et al., SIGGRAPH Asia 2018), depth m = 5, with the energy
//      safeguard of that paper: an accelerated iterate that does not lower the ARAP energy is discarded (history reset, plain step).
//   3. The global matrix does NOT depend on where the pins are dragged to, only on where they sit at rest: one sparse Cholesky
//      (reverse Cuthill-McKee order + envelope storage, double precision) per pin layout, reused by every frame / every iteration.
//   4. Pins are SOFT barycentric point constraints (lambda = 1e5 ~ exact): a pin can sit anywhere inside a triangle, no snapping,
//      and a pin's rotation is carried by three satellite constraints on a small circle (same trick as the MLS path).
//   5. Pin "Influence" (Pin::stiff) becomes a per-triangle rigidity weight around the pin (in-shape distance), so a stiff pin
//      moves its neighbourhood as one piece and a soft pin lets it bend. Part of the factorisation key.
//   6. The caller supplies the initial guess (fox_rig.h uses the old in-shape MLS), so the result is a pure function of the pose:
//      scrubbing, the live canvas and the movie export all land on the same mesh.
//   7. Displacements are carried from the outline mesh to the rig grid by barycentric lookup, faded to the MLS field outside the shape.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <numeric>
#include <utility>
#include <vector>

namespace fox::arap {

constexpr double kPinLambda = 1e5;      // main pin constraint weight (Dirichlet stiffness entries are ~1..10)
constexpr double kSatLambda = 1e3;      // rotation satellites: strong, but they never fight the pin itself
constexpr double kRegEps = 1e-7;        // x kept near rest, relative to the mean stiffness diagonal
constexpr float kBlendDist = 0.04f;     // grid vertex this far outside the mesh is fully back on the MLS field
constexpr float kSatRadius = 0.035f;    // == fox::rig::kPinSatRadius
constexpr int kDefaultIters = 24;
constexpr int kDefaultHist = 5;         // Anderson depth
constexpr int kMaxHist = 8;
constexpr double kPi = 3.14159265358979323846;

// ------------------------------------------------------------------------------------------ envelope Cholesky

// Lower triangle L (K = L L^T) stored row by row from the first non-zero column of the row to the diagonal.
struct Skyline {
    int n = 0;
    std::vector<int> first;
    std::vector<size_t> off;
    std::vector<double> a;

    void init(int n_, const std::vector<int>& firstCol) {
        n = n_;
        first = firstCol;
        off.assign((size_t) n + 1, 0);
        size_t s = 0;
        for (int i = 0; i < n; i++) { off[(size_t) i] = s; s += (size_t) (i - first[(size_t) i] + 1); }
        off[(size_t) n] = s;
        a.assign(s, 0.0);
    }
    double& at(int i, int j) { return a[off[(size_t) i] + (size_t) (j - first[(size_t) i])]; }

    bool factor() {
        for (int i = 0; i < n; i++) {
            const int fi = first[(size_t) i];
            double* Li = &a[off[(size_t) i]];
            for (int j = fi; j < i; j++) {
                const int fj = first[(size_t) j];
                const double* Lj = &a[off[(size_t) j]];
                double s = Li[j - fi];
                for (int k = std::max(fi, fj); k < j; k++) s -= Li[k - fi] * Lj[k - fj];
                Li[j - fi] = s / Lj[j - fj];
            }
            double d = Li[i - fi];
            for (int k = fi; k < i; k++) d -= Li[k - fi] * Li[k - fi];
            if (!(d > 1e-300) || !std::isfinite(d)) return false;
            Li[i - fi] = std::sqrt(d);
        }
        return true;
    }

    // x: right-hand side in, solution out (permuted space).
    void solve(double* x) const {
        for (int i = 0; i < n; i++) {
            const int fi = first[(size_t) i];
            const double* Li = &a[off[(size_t) i]];
            double s = x[i];
            for (int k = fi; k < i; k++) s -= Li[k - fi] * x[k];
            x[i] = s / Li[i - fi];
        }
        for (int i = n - 1; i >= 0; i--) {
            const int fi = first[(size_t) i];
            const double* Li = &a[off[(size_t) i]];
            const double xi = x[i] / Li[i - fi];
            x[i] = xi;
            for (int k = fi; k < i; k++) x[k] -= Li[k - fi] * xi;
        }
    }
};

// Reverse Cuthill-McKee: perm[new] = old. Handles disconnected pieces.
inline void rcm(int n, const std::vector<int>& off, const std::vector<int>& adj, std::vector<int>& perm) {
    std::vector<int> deg((size_t) n), order((size_t) n), mark((size_t) n, -1);
    std::vector<char> seen((size_t) n, 0);
    for (int i = 0; i < n; i++) deg[(size_t) i] = off[(size_t) i + 1] - off[(size_t) i];
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](int x, int y) { return deg[(size_t) x] < deg[(size_t) y]; });
    perm.clear();
    perm.reserve((size_t) n);
    std::vector<int> q;
    int stamp = 0;
    for (int s : order) {
        if (seen[(size_t) s]) continue;
        // one BFS to find a far, low-degree node = pseudo-peripheral start
        int start = s;
        {
            ++stamp; q.clear(); q.push_back(s); mark[(size_t) s] = stamp;
            size_t head = 0, levelEnd = 1;
            int cand = s, candDeg = deg[(size_t) s];
            while (head < q.size()) {
                const int u = q[head++];
                for (int k = off[(size_t) u]; k < off[(size_t) u + 1]; k++) {
                    const int w = adj[(size_t) k];
                    if (mark[(size_t) w] == stamp) continue;
                    mark[(size_t) w] = stamp; q.push_back(w);
                }
                if (head == levelEnd) {   // finished a level: its min-degree node is the candidate
                    if (levelEnd < q.size()) {
                        cand = q[levelEnd]; candDeg = deg[(size_t) cand];
                        for (size_t z = levelEnd; z < q.size(); z++) if (deg[(size_t) q[z]] < candDeg) { cand = q[z]; candDeg = deg[(size_t) cand]; }
                    }
                    levelEnd = q.size();
                }
            }
            start = cand;
        }
        q.clear(); q.push_back(start); seen[(size_t) start] = 1;
        size_t head = 0;
        std::vector<int> nb;
        while (head < q.size()) {
            const int u = q[head++];
            perm.push_back(u);
            nb.clear();
            for (int k = off[(size_t) u]; k < off[(size_t) u + 1]; k++) if (!seen[(size_t) adj[(size_t) k]]) nb.push_back(adj[(size_t) k]);
            std::sort(nb.begin(), nb.end(), [&](int x, int y) { return deg[(size_t) x] < deg[(size_t) y]; });
            for (int w : nb) { seen[(size_t) w] = 1; q.push_back(w); }
        }
    }
    std::reverse(perm.begin(), perm.end());
}

// ------------------------------------------------------------------------------------------ point location on the mesh

// Closest point of triangle (a, b, c) to (px, py), as barycentric coordinates (Ericson, Real-Time Collision Detection 5.1.5).
inline void closestBary(double px, double py, double ax, double ay, double bx, double by, double cx, double cy, double out[3]) {
    const double abx = bx - ax, aby = by - ay, acx = cx - ax, acy = cy - ay;
    const double apx = px - ax, apy = py - ay;
    const double d1 = abx * apx + aby * apy, d2 = acx * apx + acy * apy;
    if (d1 <= 0 && d2 <= 0) { out[0] = 1; out[1] = 0; out[2] = 0; return; }
    const double bpx = px - bx, bpy = py - by;
    const double d3 = abx * bpx + aby * bpy, d4 = acx * bpx + acy * bpy;
    if (d3 >= 0 && d4 <= d3) { out[0] = 0; out[1] = 1; out[2] = 0; return; }
    const double vc = d1 * d4 - d3 * d2;
    if (vc <= 0 && d1 >= 0 && d3 <= 0) { const double v = d1 / (d1 - d3); out[0] = 1 - v; out[1] = v; out[2] = 0; return; }
    const double cpx = px - cx, cpy = py - cy;
    const double d5 = abx * cpx + aby * cpy, d6 = acx * cpx + acy * cpy;
    if (d6 >= 0 && d5 <= d6) { out[0] = 0; out[1] = 0; out[2] = 1; return; }
    const double vb = d5 * d2 - d1 * d6;
    if (vb <= 0 && d2 >= 0 && d6 <= 0) { const double w = d2 / (d2 - d6); out[0] = 1 - w; out[1] = 0; out[2] = w; return; }
    const double va = d3 * d6 - d5 * d4;
    if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) { const double w = (d4 - d3) / ((d4 - d3) + (d5 - d6)); out[0] = 0; out[1] = 1 - w; out[2] = w; return; }
    const double den = 1.0 / (va + vb + vc), v = vb * den, w = vc * den;
    out[0] = 1 - v - w; out[1] = v; out[2] = w;
}

struct Locator {
    const double* P = nullptr;
    const int* T = nullptr;
    int nv = 0, nt = 0, nx = 1, ny = 1;
    double x0 = 0, y0 = 0, cs = 1;
    std::vector<int> start, list;

    void build(const double* p, int nv_, const int* tri, int nt_) {
        P = p; T = tri; nv = nv_; nt = nt_;
        double x1 = -1e30, y1 = -1e30;
        x0 = 1e30; y0 = 1e30;
        for (int i = 0; i < nv; i++) { x0 = std::min(x0, p[i]); x1 = std::max(x1, p[i]); y0 = std::min(y0, p[nv + i]); y1 = std::max(y1, p[nv + i]); }
        const double W = std::max(x1 - x0, 1e-6), H = std::max(y1 - y0, 1e-6);
        cs = std::max(W, H) / 32.0;
        nx = (int) std::ceil(W / cs) + 1; ny = (int) std::ceil(H / cs) + 1;
        start.assign((size_t) nx * (size_t) ny + 1, 0);
        auto range = [&](int t, int& i0, int& i1, int& j0, int& j1) {
            double lx = 1e30, hx = -1e30, ly = 1e30, hy = -1e30;
            for (int k = 0; k < 3; k++) { const int v = T[t * 3 + k]; lx = std::min(lx, p[v]); hx = std::max(hx, p[v]); ly = std::min(ly, p[nv + v]); hy = std::max(hy, p[nv + v]); }
            i0 = std::clamp((int) std::floor((lx - x0) / cs), 0, nx - 1); i1 = std::clamp((int) std::floor((hx - x0) / cs), 0, nx - 1);
            j0 = std::clamp((int) std::floor((ly - y0) / cs), 0, ny - 1); j1 = std::clamp((int) std::floor((hy - y0) / cs), 0, ny - 1);
        };
        for (int t = 0; t < nt; t++) { int i0, i1, j0, j1; range(t, i0, i1, j0, j1); for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) start[(size_t) (j * nx + i) + 1]++; }
        for (size_t i = 0; i + 1 < start.size(); i++) start[i + 1] += start[i];
        list.resize((size_t) start.back());
        std::vector<int> fill(start.begin(), start.end() - 1);
        for (int t = 0; t < nt; t++) { int i0, i1, j0, j1; range(t, i0, i1, j0, j1); for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) list[(size_t) fill[(size_t) (j * nx + i)]++] = t; }
    }

    // Nearest point of the mesh to (qx, qy) within maxD: triangle, barycentric coordinates, squared distance.
    bool closest(double qx, double qy, double maxD, int& bt, double bb[3], double& bd2) const {
        const double ex = std::max({x0 - qx, 0.0, qx - (x0 + nx * cs)}), ey = std::max({y0 - qy, 0.0, qy - (y0 + ny * cs)});
        if (ex * ex + ey * ey > maxD * maxD) return false;
        const int cx = std::clamp((int) std::floor((qx - x0) / cs), 0, nx - 1), cy = std::clamp((int) std::floor((qy - y0) / cs), 0, ny - 1);
        bt = -1; bd2 = 1e300;
        const int maxRing = std::max(nx, ny);
        for (int r = 0; r <= maxRing; r++) {
            for (int yy = cy - r; yy <= cy + r; yy++) {
                if (yy < 0 || yy >= ny) continue;
                const bool edgeRow = (yy == cy - r || yy == cy + r);
                const int step = edgeRow ? 1 : std::max(1, 2 * r);
                for (int xx = cx - r; xx <= cx + r; xx += step) {
                    if (xx < 0 || xx >= nx) continue;
                    for (int k = start[(size_t) (yy * nx + xx)]; k < start[(size_t) (yy * nx + xx) + 1]; k++) {
                        const int t = list[(size_t) k];
                        const int a = T[t * 3], b = T[t * 3 + 1], c = T[t * 3 + 2];
                        double w[3];
                        closestBary(qx, qy, P[a], P[nv + a], P[b], P[nv + b], P[c], P[nv + c], w);
                        const double sx = w[0] * P[a] + w[1] * P[b] + w[2] * P[c] - qx, sy = w[0] * P[nv + a] + w[1] * P[nv + b] + w[2] * P[nv + c] - qy;
                        const double d2 = sx * sx + sy * sy;
                        if (d2 < bd2) { bd2 = d2; bt = t; bb[0] = w[0]; bb[1] = w[1]; bb[2] = w[2]; }
                    }
                }
            }
            if (bt >= 0) { const double lim = r * cs; if (bd2 <= lim * lim) break; }
            if (r * cs > maxD) break;
        }
        return bt >= 0 && bd2 <= maxD * maxD;
    }
};

// ------------------------------------------------------------------------------------------ system (everything that does not change per frame)

struct Con {          // one barycentric point constraint
    int v[3];
    double b[3];
    double lam;
    int pin;
    double cx, cy;    // the constrained REST point (inside the mesh)
};

struct System {
    bool ok = false;
    int nv = 0, nt = 0, nPins = 0, nGrid = 0;
    uint32_t stamp = 0;
    std::vector<float> key;
    std::vector<double> p;            // rest positions: X block [0, nv), Y block [nv, 2 nv)
    std::vector<int> tri;             // 3 per triangle (degenerate ones removed)
    std::vector<double> tg;           // 6 per triangle: g0x g0y g1x g1y g2x g2y
    std::vector<double> tw;           // rigidity weight * area
    std::vector<int> perm, iperm;     // fill-reducing order
    Skyline K;
    double eps = 0;
    std::vector<Con> cons;
    std::vector<double> pinRest;      // 2 per pin
    std::vector<int> gtri;            // per rig-grid vertex: triangle of its nearest mesh point (-1 = none close enough)
    std::vector<float> gb;            // 3 barycentric coordinates
    std::vector<float> gw;            // 1 inside the shape .. 0 beyond kBlendDist
};

struct BuildIn {
    int nv = 0;
    const float* v = nullptr;         // outline vertices, xy
    int nt = 0;
    const int* tri = nullptr;         // 3 per triangle
    int nPins = 0;
    const float* pinRest = nullptr;   // xy per pin
    const float* pinStiff = nullptr;  // per pin (1 = neutral)
    const float* pinDist = nullptr;   // nPins * nv in-shape distances (>= 1e29 = unreachable), may be null
    float reach = 0.18f;              // radius of a pin's rigidity influence (paper units)
    int nGrid = 0;
    const float* gridRest = nullptr;  // xy per rig grid vertex
};

inline std::shared_ptr<System> build(const BuildIn& in) {
    auto S = std::make_shared<System>();
    const int nv = in.nv;
    if (nv < 3 || in.nt < 1 || !in.v || !in.tri) return S;
    S->nv = nv; S->nPins = in.nPins; S->nGrid = in.nGrid;
    S->p.resize((size_t) nv * 2);
    for (int i = 0; i < nv; i++) { S->p[(size_t) i] = in.v[i * 2]; S->p[(size_t) nv + (size_t) i] = in.v[i * 2 + 1]; }

    // ---- triangles: hat-function gradients, rigidity weights
    std::vector<double> diag((size_t) nv, 0.0);
    const double r2 = std::max(1e-6, (double) in.reach * (double) in.reach);
    for (int t = 0; t < in.nt; t++) {
        const int a = in.tri[t * 3], b = in.tri[t * 3 + 1], c = in.tri[t * 3 + 2];
        if (a < 0 || b < 0 || c < 0 || a >= nv || b >= nv || c >= nv || a == b || b == c || a == c) continue;
        const double x0 = S->p[(size_t) a], y0 = S->p[(size_t) nv + (size_t) a], x1 = S->p[(size_t) b], y1 = S->p[(size_t) nv + (size_t) b];
        const double x2 = S->p[(size_t) c], y2 = S->p[(size_t) nv + (size_t) c];
        const double a2 = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);   // signed 2A
        const double area = 0.5 * std::fabs(a2);
        if (!(area > 1e-12)) continue;
        const double g[6] = {(y1 - y2) / a2, (x2 - x1) / a2, (y2 - y0) / a2, (x0 - x2) / a2, (y0 - y1) / a2, (x1 - x0) / a2};
        double w = 1.0;
        if (in.pinStiff && in.pinDist && in.nPins > 0) {
            for (int i = 0; i < in.nPins; i++) {
                const double k = in.pinStiff[i];
                if (std::fabs(k - 1.0) < 1e-6) continue;
                const double da = in.pinDist[(size_t) i * (size_t) nv + (size_t) a], db = in.pinDist[(size_t) i * (size_t) nv + (size_t) b], dc = in.pinDist[(size_t) i * (size_t) nv + (size_t) c];
                if (da >= 1e29 || db >= 1e29 || dc >= 1e29) continue;
                const double d = (da + db + dc) / 3.0;
                w += (k - 1.0) * std::exp(-d * d / r2);
            }
            w = std::clamp(w, 0.1, 16.0);
        }
        S->tri.push_back(a); S->tri.push_back(b); S->tri.push_back(c);
        for (int k = 0; k < 6; k++) S->tg.push_back(g[k]);
        S->tw.push_back(w * area);
        const double tw = w * area;
        diag[(size_t) a] += tw * (g[0] * g[0] + g[1] * g[1]);
        diag[(size_t) b] += tw * (g[2] * g[2] + g[3] * g[3]);
        diag[(size_t) c] += tw * (g[4] * g[4] + g[5] * g[5]);
    }
    S->nt = (int) S->tw.size();
    if (S->nt < 1) return S;
    {
        double s = 0; int n = 0;
        for (double d : diag) if (d > 0) { s += d; n++; }
        S->eps = kRegEps * std::max(n ? s / n : 1.0, 1e-9);
    }

    // ---- adjacency -> RCM order -> envelope
    std::vector<std::pair<int, int>> e;
    e.reserve((size_t) S->nt * 3);
    for (int t = 0; t < S->nt; t++)
        for (int k = 0; k < 3; k++) { const int a = S->tri[(size_t) t * 3 + (size_t) k], b = S->tri[(size_t) t * 3 + (size_t) (k + 1) % 3]; e.push_back({std::min(a, b), std::max(a, b)}); }
    std::sort(e.begin(), e.end());
    e.erase(std::unique(e.begin(), e.end()), e.end());
    std::vector<int> off((size_t) nv + 1, 0), adj;
    for (const auto& pr : e) { off[(size_t) pr.first + 1]++; off[(size_t) pr.second + 1]++; }
    for (int i = 0; i < nv; i++) off[(size_t) i + 1] += off[(size_t) i];
    adj.resize((size_t) off[(size_t) nv]);
    {
        std::vector<int> fill(off.begin(), off.end() - 1);
        for (const auto& pr : e) { adj[(size_t) fill[(size_t) pr.first]++] = pr.second; adj[(size_t) fill[(size_t) pr.second]++] = pr.first; }
    }
    rcm(nv, off, adj, S->perm);
    S->iperm.assign((size_t) nv, 0);
    for (int i = 0; i < nv; i++) S->iperm[(size_t) S->perm[(size_t) i]] = i;
    std::vector<int> first((size_t) nv);
    for (int i = 0; i < nv; i++) {
        const int old = S->perm[(size_t) i];
        int f = i;
        for (int k = off[(size_t) old]; k < off[(size_t) old + 1]; k++) f = std::min(f, S->iperm[(size_t) adj[(size_t) k]]);
        first[(size_t) i] = f;
    }
    S->K.init(nv, first);

    // ---- pin constraints: the pin point and 3 satellites on a small circle, each located in the mesh (clamped when off it)
    Locator loc;
    loc.build(S->p.data(), nv, S->tri.data(), S->nt);
    S->pinRest.assign((size_t) in.nPins * 2, 0.0);
    for (int i = 0; i < in.nPins; i++) {
        const double px = in.pinRest[i * 2], py = in.pinRest[i * 2 + 1];
        S->pinRest[(size_t) i * 2] = px; S->pinRest[(size_t) i * 2 + 1] = py;
        for (int j = -1; j < 3; j++) {
            double qx = px, qy = py;
            if (j >= 0) { const double ang = 1.5707963267948966 + 2.0943951023931953 * j; qx += std::cos(ang) * kSatRadius; qy += std::sin(ang) * kSatRadius; }
            int t; double w[3], d2;
            if (!loc.closest(qx, qy, 1e9, t, w, d2)) continue;
            if (j >= 0 && in.pinDist) {
                // a satellite only carries the pin's ROTATION: drop it when its mesh point is not close to the pin ALONG the drawing
                // (e.g. it landed on the neighbouring leg, a glued-on web away), where it would just fight that piece's own pins
                double dg = 0;
                for (int k = 0; k < 3; k++) dg += w[k] * std::min(1e3, (double) in.pinDist[(size_t) i * (size_t) nv + (size_t) S->tri[(size_t) t * 3 + (size_t) k]]);
                if (dg > 3.0 * kSatRadius) continue;
            }
            Con c;
            for (int k = 0; k < 3; k++) { c.v[k] = S->tri[(size_t) t * 3 + (size_t) k]; c.b[k] = w[k]; }
            c.lam = j < 0 ? kPinLambda : kSatLambda;
            c.pin = i;
            c.cx = w[0] * S->p[(size_t) c.v[0]] + w[1] * S->p[(size_t) c.v[1]] + w[2] * S->p[(size_t) c.v[2]];
            c.cy = w[0] * S->p[(size_t) nv + (size_t) c.v[0]] + w[1] * S->p[(size_t) nv + (size_t) c.v[1]] + w[2] * S->p[(size_t) nv + (size_t) c.v[2]];
            S->cons.push_back(c);
        }
    }

    // ---- assemble K = sum_t tw g g^T  +  eps I  +  sum_c lam b b^T   (lower triangle, permuted)
    auto add = [&](int va, int vb, double val) {
        const int ia = S->iperm[(size_t) va], ib = S->iperm[(size_t) vb];
        if (ia >= ib) S->K.at(ia, ib) += val;
    };
    for (int t = 0; t < S->nt; t++) {
        const int* v = &S->tri[(size_t) t * 3];
        const double* g = &S->tg[(size_t) t * 6];
        for (int a = 0; a < 3; a++)
            for (int b = 0; b < 3; b++) add(v[a], v[b], S->tw[(size_t) t] * (g[a * 2] * g[b * 2] + g[a * 2 + 1] * g[b * 2 + 1]));
    }
    for (int i = 0; i < nv; i++) add(i, i, S->eps);
    for (const Con& c : S->cons)
        for (int a = 0; a < 3; a++)
            for (int b = 0; b < 3; b++) add(c.v[a], c.v[b], c.lam * c.b[a] * c.b[b]);
    if (!S->K.factor()) return S;

    // ---- rig grid -> outline mesh lookup
    S->gtri.assign((size_t) in.nGrid, -1);
    S->gb.assign((size_t) in.nGrid * 3, 0.f);
    S->gw.assign((size_t) in.nGrid, 0.f);
    for (int v = 0; v < in.nGrid; v++) {
        int t; double w[3], d2;
        if (!loc.closest(in.gridRest[v * 2], in.gridRest[v * 2 + 1], kBlendDist, t, w, d2)) continue;
        const float x = 1.f - (float) std::sqrt(d2) / kBlendDist;
        S->gtri[(size_t) v] = t;
        S->gb[(size_t) v * 3] = (float) w[0]; S->gb[(size_t) v * 3 + 1] = (float) w[1]; S->gb[(size_t) v * 3 + 2] = (float) w[2];
        S->gw[(size_t) v] = x * x * (3.f - 2.f * x);
    }
    S->ok = true;
    return S;
}

// ------------------------------------------------------------------------------------------ per-frame solve

struct Opts {
    int maxIter = kDefaultIters;    // iterations of the final stage
    int hist = kDefaultHist;        // 0 = plain local-global (no acceleration)
    double tol = 1e-6;              // relative energy change that ends the iteration
    int maxSteps = 1;               // OPT-IN continuation: up to this many stages ramping the pins from rest (1 = off, one stage from [init]).
                                    // Measured on 300 random stress poses it matched the single stage in ~95% and cost 1.5-2.4x the iterations, so it is off.
    float stepSize = 0.05f;         // paper units of pin motion per stage when continuation is on
};

struct Stats {
    int iters = 0, rejected = 0, steps = 1;
    double e0 = 0, e = 0;
    std::vector<double> trace;      // energy after every iteration (tests / tuning)
};

namespace detail {

struct Work {
    std::vector<double> x, xn, g, f, fp, gp, bBase, b, tmp, rot, rotN;
    std::vector<std::vector<double>> dF, dG;
};

// Local step: best rotation per triangle (c, s), returns the ARAP energy of [x] with those rotations.
inline double local(const System& S, const double* x, double* rot) {
    const int nv = S.nv;
    double E = 0;
    for (int t = 0; t < S.nt; t++) {
        const int* v = &S.tri[(size_t) t * 3];
        const double* g = &S.tg[(size_t) t * 6];
        double j00 = 0, j01 = 0, j10 = 0, j11 = 0;
        for (int k = 0; k < 3; k++) {
            const double xx = x[v[k]], yy = x[nv + v[k]];
            j00 += xx * g[k * 2]; j01 += xx * g[k * 2 + 1]; j10 += yy * g[k * 2]; j11 += yy * g[k * 2 + 1];
        }
        const double cc = j00 + j11, ss = j10 - j01, rho = std::sqrt(cc * cc + ss * ss);
        if (rho > 1e-14) { rot[t * 2] = cc / rho; rot[t * 2 + 1] = ss / rho; } else { rot[t * 2] = 1; rot[t * 2 + 1] = 0; }
        E += S.tw[(size_t) t] * (j00 * j00 + j01 * j01 + j10 * j10 + j11 * j11 - 2.0 * rho + 2.0);
    }
    return E;
}

// Global step: K x = bBase + sum_t tw g_j . R_t rows
inline void global(const System& S, const double* rot, Work& W, double* xOut) {
    const int nv = S.nv;
    std::copy(W.bBase.begin(), W.bBase.end(), W.b.begin());
    for (int t = 0; t < S.nt; t++) {
        const int* v = &S.tri[(size_t) t * 3];
        const double* g = &S.tg[(size_t) t * 6];
        const double c = rot[t * 2], s = rot[t * 2 + 1], w = S.tw[(size_t) t];
        for (int k = 0; k < 3; k++) {
            W.b[(size_t) v[k]] += w * (g[k * 2] * c - g[k * 2 + 1] * s);
            W.b[(size_t) nv + (size_t) v[k]] += w * (g[k * 2] * s + g[k * 2 + 1] * c);
        }
    }
    for (int comp = 0; comp < 2; comp++) {
        const double* b = &W.b[(size_t) comp * (size_t) nv];
        for (int i = 0; i < nv; i++) W.tmp[(size_t) i] = b[S.perm[(size_t) i]];
        S.K.solve(W.tmp.data());
        double* x = xOut + (size_t) comp * (size_t) nv;
        for (int i = 0; i < nv; i++) x[S.perm[(size_t) i]] = W.tmp[(size_t) i];
    }
}

// Small dense least squares  min |f - dF gamma|  through the normal equations (m <= kMaxHist), Tikhonov-regularised.
inline bool anderson(const std::vector<std::vector<double>>& dF, int m, const std::vector<double>& f, double* gamma) {
    double A[kMaxHist][kMaxHist + 1];
    for (int i = 0; i < m; i++) {
        for (int j = i; j < m; j++) {
            double s = 0;
            for (size_t k = 0; k < f.size(); k++) s += dF[(size_t) i][k] * dF[(size_t) j][k];
            A[i][j] = A[j][i] = s;
        }
        double s = 0;
        for (size_t k = 0; k < f.size(); k++) s += dF[(size_t) i][k] * f[k];
        A[i][m] = s;
    }
    double tr = 0;
    for (int i = 0; i < m; i++) tr += A[i][i];
    if (!(tr > 1e-300)) return false;
    for (int i = 0; i < m; i++) A[i][i] += 1e-10 * tr;
    for (int c = 0; c < m; c++) {   // Gaussian elimination, partial pivoting
        int piv = c;
        for (int r = c + 1; r < m; r++) if (std::fabs(A[r][c]) > std::fabs(A[piv][c])) piv = r;
        if (std::fabs(A[piv][c]) < 1e-300) return false;
        if (piv != c) for (int k = 0; k <= m; k++) std::swap(A[c][k], A[piv][k]);
        for (int r = c + 1; r < m; r++) {
            const double fct = A[r][c] / A[c][c];
            for (int k = c; k <= m; k++) A[r][k] -= fct * A[c][k];
        }
    }
    for (int r = m - 1; r >= 0; r--) {
        double s = A[r][m];
        for (int k = r + 1; k < m; k++) s -= A[r][k] * gamma[k];
        gamma[r] = s / A[r][r];
        if (!std::isfinite(gamma[r])) return false;
    }
    return true;
}

}  // namespace detail

// Number of continuation stages for this pose: one per [stepSize] of the largest pin motion (translation + the arc a satellite sweeps).
inline int stepCount(const System& S, const float* pin, const Opts& o) {
    double m = 0;
    for (int i = 0; i < S.nPins; i++) m = std::max(m, std::hypot((double) pin[i * 3], (double) pin[i * 3 + 1]) + (double) kSatRadius * std::fabs((double) pin[i * 3 + 2]) * (kPi / 180.0));
    return std::clamp((int) std::ceil(m / std::max(1e-4, (double) o.stepSize)), 1, std::max(1, o.maxSteps));
}

// [pin] = 3 floats per pin: dx dy rotDeg (clockwise on screen, y down). [init] = starting positions xy per outline vertex, used when the
// pose needs a single stage (null = rest). With Opts::maxSteps > 1 bigger moves are CONTINUED from rest instead: the pin offsets ramp
// 1/K .. 1 and every stage starts from the previous one.
// [out] = DISPLACEMENT xy per outline vertex. false = nothing usable (caller falls back to MLS).
inline bool solve(const System& S, const float* pin, const float* init, std::vector<float>& out, const Opts& o = Opts(), Stats* st = nullptr) {
    if (!S.ok) return false;
    const int nv = S.nv, n2 = nv * 2;
    static thread_local detail::Work W;
    W.x.resize((size_t) n2); W.xn.resize((size_t) n2); W.g.resize((size_t) n2); W.f.resize((size_t) n2);
    W.fp.resize((size_t) n2); W.gp.resize((size_t) n2); W.bBase.resize((size_t) n2); W.b.resize((size_t) n2); W.tmp.resize((size_t) nv);
    W.rot.resize((size_t) S.nt * 2); W.rotN.resize((size_t) S.nt * 2);
    const int hist = std::clamp(o.hist, 0, kMaxHist);
    if ((int) W.dF.size() < hist) { W.dF.resize((size_t) hist); W.dG.resize((size_t) hist); }
    for (int i = 0; i < hist; i++) { W.dF[(size_t) i].resize((size_t) n2); W.dG[(size_t) i].resize((size_t) n2); }

    const int K = stepCount(S, pin, o);
    const bool useInit = init && K == 1;
    for (int i = 0; i < nv; i++) {
        W.x[(size_t) i] = useInit ? init[i * 2] : S.p[(size_t) i];
        W.x[(size_t) nv + (size_t) i] = useInit ? init[i * 2 + 1] : S.p[(size_t) nv + (size_t) i];
    }
    if (st) { st->iters = 0; st->rejected = 0; st->trace.clear(); st->steps = K; }

    double gamma[kMaxHist];
    for (int stage = 1; stage <= K; stage++) {
        const double sc = (double) stage / (double) K;
        // constant part of the right-hand side: rest anchor + pin constraints at their (scaled) targets
        for (int i = 0; i < n2; i++) W.bBase[(size_t) i] = S.eps * S.p[(size_t) i];
        for (const Con& c : S.cons) {
            const double rx = S.pinRest[(size_t) c.pin * 2], ry = S.pinRest[(size_t) c.pin * 2 + 1];
            const double a = sc * (double) pin[c.pin * 3 + 2] * (kPi / 180.0), cs = std::cos(a), sn = std::sin(a);
            const double ux = c.cx - rx, uy = c.cy - ry;
            const double qx = rx + sc * pin[c.pin * 3] + cs * ux - sn * uy, qy = ry + sc * pin[c.pin * 3 + 1] + sn * ux + cs * uy;
            for (int k = 0; k < 3; k++) {
                W.bBase[(size_t) c.v[k]] += c.lam * c.b[k] * qx;
                W.bBase[(size_t) nv + (size_t) c.v[k]] += c.lam * c.b[k] * qy;
            }
        }
        const bool last = stage == K;
        const int maxIt = last ? o.maxIter : std::min(o.maxIter, 8);
        const double tol = last ? o.tol : std::max(o.tol, 1e-4);

        double curE = detail::local(S, W.x.data(), W.rot.data());
        if (st && stage == 1) st->e0 = curE;
        int hc = 0, head = 0;
        bool havePrev = false;
        for (int it = 0; it < maxIt; it++) {
            detail::global(S, W.rot.data(), W, W.g.data());
            for (int i = 0; i < n2; i++) W.f[(size_t) i] = W.g[(size_t) i] - W.x[(size_t) i];
            if (hist > 0 && havePrev) {   // ring buffer of residual / image differences
                std::vector<double>& a = W.dF[(size_t) head];
                std::vector<double>& b = W.dG[(size_t) head];
                for (int i = 0; i < n2; i++) { a[(size_t) i] = W.f[(size_t) i] - W.fp[(size_t) i]; b[(size_t) i] = W.g[(size_t) i] - W.gp[(size_t) i]; }
                head = (head + 1) % hist;
                hc = std::min(hc + 1, hist);
            }
            W.fp = W.f; W.gp = W.g; havePrev = hist > 0;
            bool acc = false;
            if (hc > 0 && detail::anderson(W.dF, hc, W.f, gamma)) {
                // ring order is irrelevant for the least squares: the first hc slots are the filled ones
                for (int i = 0; i < n2; i++) {
                    double v = W.g[(size_t) i];
                    for (int j = 0; j < hc; j++) v -= gamma[j] * W.dG[(size_t) j][(size_t) i];
                    W.xn[(size_t) i] = v;
                }
                acc = true;
            } else {
                W.xn = W.g;
            }
            double candE = detail::local(S, W.xn.data(), W.rotN.data());
            if (acc && !(candE <= curE * (1.0 + 1e-9))) {   // safeguard: the accelerated step must not raise the energy
                W.xn = W.g;
                candE = detail::local(S, W.xn.data(), W.rotN.data());
                hc = 0; head = 0; havePrev = false;
                if (st) st->rejected++;
            }
            const bool done = std::fabs(curE - candE) <= tol * std::max(candE, 1e-12);
            W.x.swap(W.xn);
            W.rot.swap(W.rotN);
            curE = candE;
            if (st) { st->iters++; st->trace.push_back(curE); st->e = curE; }
            if (done) break;
        }
        if (st) st->e = curE;
    }
    out.resize((size_t) n2);
    for (int i = 0; i < nv; i++) {
        const double dx = W.x[(size_t) i] - S.p[(size_t) i], dy = W.x[(size_t) nv + (size_t) i] - S.p[(size_t) nv + (size_t) i];
        if (!std::isfinite(dx) || !std::isfinite(dy)) return false;
        out[(size_t) i * 2] = (float) dx; out[(size_t) i * 2 + 1] = (float) dy;
    }
    return true;
}

// Displacement of rig grid vertex [v] carried over from the outline displacements [D]; returns its ARAP weight (0 = use MLS only).
inline float sample(const System& S, const float* D, int v, float& dx, float& dy) {
    dx = dy = 0.f;
    if (v < 0 || v >= S.nGrid) return 0.f;
    const int t = S.gtri[(size_t) v];
    if (t < 0) return 0.f;
    const float* b = &S.gb[(size_t) v * 3];
    for (int k = 0; k < 3; k++) { const int i = S.tri[(size_t) t * 3 + (size_t) k]; dx += b[k] * D[i * 2]; dy += b[k] * D[i * 2 + 1]; }
    return S.gw[(size_t) v];
}

}  // namespace fox::arap
