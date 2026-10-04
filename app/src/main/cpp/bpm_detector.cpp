#include "bpm_detector.h"

#include <Eigen/Core>
#include <algorithm>
#include <cstdint>
#include <utility>

// Math policy: this file never calls <cmath>/<math.h>. Scalar math goes through Eigen::numext (nx::),
// bulk math (trig tables, windows, tempogram scan, band log) goes through Eigen array expressions,
// which use Eigen's own SIMD packet kernels (psin/pcos/plog/pexp/psqrt) where the target has them.

namespace fox {

namespace nx = Eigen::numext;

namespace {

using ArrD = Eigen::ArrayXd;
using ArrF = Eigen::ArrayXf;

constexpr double kPi = 3.14159265358979323846;
constexpr double kLn10 = 2.30258509299404568402;
constexpr double kInvLn2 = 1.44269504088896340736;

// ---- front end (STFT -> mel log-flux) -------------------------------------
constexpr int kBands = 24;
constexpr double kBandLoHz = 30.0;
constexpr double kBandHiHz = 10000.0;
constexpr double kLogCompress = 100.0;  // log1p(kLogCompress * bandAmplitude)
constexpr double kSilenceRms = 1e-3;    // ~ -60 dBFS
constexpr double kSilenceGuardSec = 30.0;

// ---- envelope / peaks --------------------------------------------------------
constexpr double kMinAnalyzeSec = 8.0;
constexpr double kMinOnsetRms = 1e-3;
constexpr double kMeanWindowSec = 0.5;
constexpr double kNormWindowSec = 3.0;
constexpr double kNormFloor = 0.25;
constexpr double kMinPeakSepSec = 0.06;
constexpr double kPeakThresholdStd = 0.5;

// ---- tempo search ------------------------------------------------------------
constexpr double kCoarseStep = 0.10;
constexpr double kFineStep = 0.01;
constexpr double kFineSpan = 0.60;
constexpr double kPriorCenterBpm = 120.0;
constexpr double kPriorSigmaOct = 1.0;
constexpr size_t kMaxSeeds = 6;

// ---- stability / early stop --------------------------------------------------
constexpr double kStableTol = 0.008;
constexpr double kFirstCheckSec = 20.0;
constexpr double kCheckEverySec = 10.0;
constexpr double kStopConfidence = 0.80;
constexpr double kStopAgreeBpm = 0.10;

struct Peak {
    size_t idx;
    double timeSec;
    double amp;
};

inline long iround(double x) { return static_cast<long>(nx::round(x)); }

double hz2mel(double f) { return 2595.0 * nx::log(1.0 + f / 700.0) / kLn10; }
double mel2hz(double m) { return 700.0 * (nx::exp(m / 2595.0 * kLn10) - 1.0); }

// Real-input FFT through one complex FFT of half size. Outputs |X[k]|^2, k = 0..N/2.
struct RealFft {
    int N = 0, M = 0;
    std::vector<int> rev;
    std::vector<float> twr, twi, ptr, pti, zr, zi;

    void init(int n) {
        N = n;
        M = n / 2;
        int bits = 0;
        while ((1 << bits) < M) ++bits;
        rev.resize(static_cast<size_t>(M));
        for (int i = 0; i < M; ++i) {
            int r = 0;
            for (int b = 0; b < bits; ++b)
                if (i & (1 << b)) r |= 1 << (bits - 1 - b);
            rev[static_cast<size_t>(i)] = r;
        }

        // Twiddles via Eigen array trig (double precision, then narrowed).
        const int H = M / 2;
        twr.resize(static_cast<size_t>(H));
        twi.resize(static_cast<size_t>(H));
        const ArrD ph = ArrD::LinSpaced(H, 0.0, static_cast<double>(H - 1)) * (2.0 * kPi / M);
        Eigen::Map<ArrF>(twr.data(), H) = ph.cos().cast<float>();
        Eigen::Map<ArrF>(twi.data(), H) = (-ph.sin()).cast<float>();

        ptr.resize(static_cast<size_t>(M + 1));
        pti.resize(static_cast<size_t>(M + 1));
        const ArrD pp = ArrD::LinSpaced(M + 1, 0.0, static_cast<double>(M)) * (2.0 * kPi / N);
        Eigen::Map<ArrF>(ptr.data(), M + 1) = pp.cos().cast<float>();
        Eigen::Map<ArrF>(pti.data(), M + 1) = (-pp.sin()).cast<float>();

        zr.assign(static_cast<size_t>(M), 0.f);
        zi.assign(static_cast<size_t>(M), 0.f);
    }

    void power(const float* x, float* out) {
        for (int n = 0; n < M; ++n) {
            const size_t j = static_cast<size_t>(rev[static_cast<size_t>(n)]);
            zr[j] = x[2 * n];
            zi[j] = x[2 * n + 1];
        }
        for (int len = 2; len <= M; len <<= 1) {
            const int half = len >> 1, step = M / len;
            for (int i = 0; i < M; i += len) {
                for (int k = 0; k < half; ++k) {
                    const float wr = twr[static_cast<size_t>(k * step)], wi = twi[static_cast<size_t>(k * step)];
                    const size_t a = static_cast<size_t>(i + k), b = a + static_cast<size_t>(half);
                    const float vr = zr[b] * wr - zi[b] * wi;
                    const float vi = zr[b] * wi + zi[b] * wr;
                    zr[b] = zr[a] - vr;
                    zi[b] = zi[a] - vi;
                    zr[a] += vr;
                    zi[a] += vi;
                }
            }
        }
        for (int k = 0; k <= M; ++k) {
            const size_t k1 = static_cast<size_t>(k == M ? 0 : k);
            const size_t k2 = static_cast<size_t>(k == 0 ? 0 : M - k);
            const float ar = zr[k1] - zr[k2], ai = zi[k1] + zi[k2];
            const float er = 0.5f * (zr[k1] + zr[k2]), ei = 0.5f * (zi[k1] - zi[k2]);
            const float or_ = 0.5f * ai, oi = -0.5f * ar;
            const float wr = ptr[static_cast<size_t>(k)], wi = pti[static_cast<size_t>(k)];
            const float xr = er + wr * or_ - wi * oi;
            const float xi = ei + wr * oi + wi * or_;
            out[k] = xr * xr + xi * xi;
        }
    }
};

// |sum_i w_i e^{-j 2 pi f h t_i}| / (h sumW) summed over h = 1..4, for `count` BPM values
// bpm0 + g * stepBpm. Phases advance by complex rotation (no trig in the g-loop); the per-onset
// work is Eigen array expressions, so it vectorises across onsets.
void scanGrid(const double* t, const double* w, size_t cnt, double bpm0, double stepBpm, size_t count, double* out) {
    const Eigen::Map<const ArrD> T(t, static_cast<Eigen::Index>(cnt));
    const Eigen::Map<const ArrD> Wt(w, static_cast<Eigen::Index>(cnt));
    const double sumW = cnt ? Wt.sum() : 0.0;
    if (cnt == 0 || sumW <= 0.0) {
        std::fill(out, out + count, 0.0);
        return;
    }
    const double f0 = bpm0 / 60.0, df = stepBpm / 60.0;
    const ArrD pa = T * (2.0 * kPi * f0);
    const ArrD pb = T * (2.0 * kPi * df);
    ArrD cr = pa.cos(), ci = -pa.sin();
    const ArrD dr = pb.cos(), di = -pb.sin();

    const Eigen::Index n = static_cast<Eigen::Index>(cnt);
    ArrD x2r(n), x2i(n), x3r(n), x3i(n), x4r(n), x4i(n), nr(n);
    for (size_t g = 0; g < count; ++g) {
        x2r = cr.square() - ci.square();
        x2i = 2.0 * cr * ci;
        x3r = x2r * cr - x2i * ci;
        x3i = x2r * ci + x2i * cr;
        x4r = x2r.square() - x2i.square();
        x4i = 2.0 * x2r * x2i;

        const double a1r = (Wt * cr).sum(), a1i = (Wt * ci).sum();
        const double a2r = (Wt * x2r).sum(), a2i = (Wt * x2i).sum();
        const double a3r = (Wt * x3r).sum(), a3i = (Wt * x3i).sum();
        const double a4r = (Wt * x4r).sum(), a4i = (Wt * x4i).sum();
        out[g] = (nx::sqrt(a1r * a1r + a1i * a1i) + nx::sqrt(a2r * a2r + a2i * a2i) / 2.0 +
                  nx::sqrt(a3r * a3r + a3i * a3i) / 3.0 + nx::sqrt(a4r * a4r + a4i * a4i) / 4.0) /
                 sumW;

        nr = cr * dr - ci * di;
        ci = cr * di + ci * dr;
        cr = nr;
    }
}

double prior(double bpm) {
    const double o = nx::log(bpm / kPriorCenterBpm) * kInvLn2 / kPriorSigmaOct;  // log2 via ln
    return nx::exp(-0.5 * o * o);
}

// Onset strength envelope -> tempo. Pure function of its inputs (used for early-stop checks and finish()).
BpmResult analyze(const BpmConfig& cfg, const std::vector<float>& onset, double seconds) {
    BpmResult r;
    r.seconds = seconds;
    const size_t n = onset.size();
    const double frameRate = cfg.sampleRate / cfg.hop;
    if (seconds < kMinAnalyzeSec || n < 64) {
        r.reason = "audio too short (need at least ~8 s)";
        return r;
    }

    // ---- 1. Detrend (centred moving mean), clip at 0 ---------------------------
    const size_t W = static_cast<size_t>(iround(kMeanWindowSec * frameRate)) | 1u;
    const size_t halfW = W / 2;
    std::vector<double> prefix(n + 1, 0.0);
    for (size_t i = 0; i < n; ++i) prefix[i + 1] = prefix[i] + onset[i];
    ArrD env = ArrD::Zero(static_cast<Eigen::Index>(n));
    for (size_t i = 0; i < n; ++i) {
        const size_t lo = i > halfW ? i - halfW : 0;
        const size_t hi = std::min(n, i + halfW + 1);
        const double mean = (prefix[hi] - prefix[lo]) / static_cast<double>(hi - lo);
        env[static_cast<Eigen::Index>(i)] = std::max(0.0, onset[i] - mean);
    }

    // ---- 1b. Equalise dynamics: divide by a slow local RMS (floored) -----------
    std::vector<double> p2(n + 1, 0.0);
    for (size_t i = 0; i < n; ++i) p2[i + 1] = p2[i] + env[static_cast<Eigen::Index>(i)] * env[static_cast<Eigen::Index>(i)];
    const double gRms = nx::sqrt(p2[n] / static_cast<double>(n));
    if (gRms < kMinOnsetRms) {
        r.reason = "no rhythmic content (silence or constant tone)";
        return r;
    }
    const size_t Wn = static_cast<size_t>(iround(kNormWindowSec * frameRate)) | 1u;
    const size_t halfN = Wn / 2;
    for (size_t i = 0; i < n; ++i) {
        const size_t lo = i > halfN ? i - halfN : 0;
        const size_t hi = std::min(n, i + halfN + 1);
        const double loc = nx::sqrt((p2[hi] - p2[lo]) / static_cast<double>(hi - lo));
        env[static_cast<Eigen::Index>(i)] /= std::max(loc, kNormFloor * gRms);
    }
    const double m = env.mean();
    const double sd = nx::sqrt(std::max(0.0, env.square().mean() - m * m));
    if (sd < kMinOnsetRms) {
        r.reason = "no rhythmic content (silence or constant tone)";
        return r;
    }
    const double thr = kPeakThresholdStd * sd;

    // ---- 2. Peak picking with sub-frame (parabolic) timing ----------------------
    const size_t minSep = std::max<size_t>(1, static_cast<size_t>(iround(kMinPeakSepSec * frameRate)));
    auto makePeak = [&](size_t t) {
        const double a = env[static_cast<Eigen::Index>(t - 1)], b = env[static_cast<Eigen::Index>(t)],
                     c = env[static_cast<Eigen::Index>(t + 1)];
        const double den = a - 2.0 * b + c;
        double d = den < 0.0 ? 0.5 * (a - c) / den : 0.0;
        d = std::max(-0.5, std::min(0.5, d));
        return Peak{t, (static_cast<double>(t) + d) / frameRate, b};
    };
    std::vector<Peak> peaks;
    for (size_t t = 1; t + 1 < n; ++t) {
        const double e0 = env[static_cast<Eigen::Index>(t)];
        if (e0 > thr && e0 > env[static_cast<Eigen::Index>(t - 1)] && e0 >= env[static_cast<Eigen::Index>(t + 1)]) {
            if (!peaks.empty() && t - peaks.back().idx < minSep) {
                if (e0 > peaks.back().amp) peaks.back() = makePeak(t);
            } else {
                peaks.push_back(makePeak(t));
            }
        }
    }
    r.peaks = static_cast<int>(peaks.size());
    if (peaks.size() < 16) {
        r.reason = "not enough onset peaks (" + std::to_string(peaks.size()) + ")";
        return r;
    }
    const size_t P = peaks.size();
    std::vector<double> pt(P), pw(P);
    for (size_t i = 0; i < P; ++i) {
        pt[i] = peaks[i].timeSec;
        pw[i] = nx::sqrt(peaks[i].amp);  // compress: a few loud hits must not dominate
    }

    // ---- 3. Coarse tempogram over the whole BPM range ---------------------------
    const double minB = cfg.minBpm, maxB = cfg.maxBpm;
    const size_t nc = static_cast<size_t>((maxB - minB) / kCoarseStep) + 1;
    std::vector<double> cs(nc);
    scanGrid(pt.data(), pw.data(), P, minB, kCoarseStep, nc, cs.data());
    for (size_t i = 0; i < nc; ++i) cs[i] *= prior(minB + static_cast<double>(i) * kCoarseStep);
    const Eigen::Map<const ArrD> csv(cs.data(), static_cast<Eigen::Index>(nc));
    const double csMean = csv.mean();
    const double csSd = nx::sqrt((csv - csMean).square().mean());

    std::vector<std::pair<double, size_t>> maxima;
    for (size_t i = 1; i + 1 < nc; ++i)
        if (cs[i] > cs[i - 1] && cs[i] >= cs[i + 1]) maxima.emplace_back(cs[i], i);
    std::sort(maxima.begin(), maxima.end(), [](auto& a, auto& b) { return a.first > b.first; });
    std::vector<size_t> seeds;
    for (auto& mx : maxima) {
        bool near = false;
        for (size_t sIdx : seeds)
            if (nx::abs(static_cast<double>(sIdx) - static_cast<double>(mx.second)) * kCoarseStep < 2.0) near = true;
        if (!near) seeds.push_back(mx.second);
        if (seeds.size() >= kMaxSeeds) break;
    }
    if (seeds.empty()) {
        r.reason = "no tempo lobe found";
        return r;
    }

    // ---- 4. Fine refine (0.01 BPM grid + parabolic peak), un-priored score -------
    struct Cand {
        double bpm;
        double raw;   // Fourier harmonic comb score (peak sharpness)
        double rank;  // raw * prior: decides the winner
    };
    auto refine = [&](double center) {
        const double lo = std::max(minB, center - kFineSpan), hi = std::min(maxB, center + kFineSpan);
        const size_t cnt = static_cast<size_t>((hi - lo) / kFineStep) + 1;
        std::vector<double> sc(cnt);
        scanGrid(pt.data(), pw.data(), P, lo, kFineStep, cnt, sc.data());
        size_t bi = 0;
        for (size_t i = 1; i < cnt; ++i)
            if (sc[i] > sc[bi]) bi = i;
        double b = lo + static_cast<double>(bi) * kFineStep;
        if (bi > 0 && bi + 1 < cnt) {
            const double a = sc[bi - 1], c0 = sc[bi], d = sc[bi + 1];
            const double den = a - 2.0 * c0 + d;
            if (den < 0.0) b += 0.5 * kFineStep * (a - d) / den;
        }
        b = std::max(minB, std::min(maxB, b));
        double raw = 0.0;
        scanGrid(pt.data(), pw.data(), P, b, kFineStep, 1, &raw);
        return Cand{nx::round(b * 100.0) / 100.0, raw, raw * prior(b)};
    };

    std::vector<Cand> cands;
    auto addCand = [&](const Cand& c) {
        for (auto& e : cands)
            if (nx::abs(e.bpm - c.bpm) < 0.5) {
                if (c.rank > e.rank) e = c;
                return;
            }
        cands.push_back(c);
    };
    for (size_t idx : seeds) addCand(refine(minB + static_cast<double>(idx) * kCoarseStep));

    // Octave siblings of the leader always take part in the decision (and the alt list).
    {
        std::sort(cands.begin(), cands.end(), [](auto& a, auto& b) { return a.rank > b.rank; });
        const double lead = cands.front().bpm;
        if (lead * 2.0 <= maxB) addCand(refine(lead * 2.0));
        if (lead * 0.5 >= minB) addCand(refine(lead * 0.5));
    }
    std::sort(cands.begin(), cands.end(), [](auto& a, auto& b) { return a.rank > b.rank; });
    const Cand best = cands.front();

    // ---- 5. Sliding-window agreement --------------------------------------------
    const double winLen = std::max(8.0, std::min(20.0, seconds / 2.0));
    const double winHop = winLen / 2.0;
    int stableWin = 0, totalWin = 0;
    const size_t lcount = static_cast<size_t>(0.06 * best.bpm / 0.1) + 1;
    std::vector<double> lsc(lcount);
    for (double w0 = 0.0; w0 + winLen <= seconds + 1e-9; w0 += winHop) {
        const size_t i0 = static_cast<size_t>(std::lower_bound(pt.begin(), pt.end(), w0) - pt.begin());
        const size_t i1 = static_cast<size_t>(std::lower_bound(pt.begin(), pt.end(), w0 + winLen) - pt.begin());
        if (i1 <= i0 || i1 - i0 < 8) continue;
        scanGrid(pt.data() + i0, pw.data() + i0, i1 - i0, best.bpm * 0.97, 0.1, lcount, lsc.data());
        size_t bi = 0;
        for (size_t i = 1; i < lcount; ++i)
            if (lsc[i] > lsc[bi]) bi = i;
        const double local = best.bpm * 0.97 + static_cast<double>(bi) * 0.1;
        ++totalWin;
        if (nx::abs(local - best.bpm) <= kStableTol * best.bpm) ++stableWin;
    }
    r.stability = totalWin > 0 ? static_cast<double>(stableWin) / totalWin : 0.5;

    // ---- 6. Result ----------------------------------------------------------------
    std::vector<BpmCandidate> out;
    for (auto& c : cands) out.push_back({c.bpm, c.rank / best.rank});
    if (out.size() > 4) out.resize(4);
    const double z = csSd > 0.0 ? (best.rank - csMean) / csSd : 0.0;
    const double sharp = std::max(0.0, std::min(1.0, 1.0 - nx::exp(-z / 4.0)));
    r.valid = true;
    r.bpm = best.bpm;
    r.confidence = std::max(0.0, std::min(1.0, sharp * (0.4 + 0.6 * r.stability)));
    r.candidates = std::move(out);
    return r;
}

}  // namespace

struct BpmDetector::Impl {
    BpmConfig cfg;
    int N, M, hop;
    RealFft fft;
    std::vector<float> win, wx, pw;
    struct Band {
        int lo, cnt;
        size_t wOff;
    };
    std::vector<Band> bands;
    std::vector<float> bw;
    std::vector<float> prevL, curL;
    std::vector<float> buf;
    std::vector<float> onset;
    bool havePrev = false, started = false, full = false;
    uint64_t totalSamples = 0, framesSinceStart = 0;

    // early-stop bookkeeping
    double nextCheck = kFirstCheckSec;
    bool lastValid = false;
    double lastBpm = 0.0;
    BpmResult cached;
    size_t cachedN = 0;

    explicit Impl(const BpmConfig& c) : cfg(c) {
        N = 256;
        while (N < cfg.fftSize) N <<= 1;
        hop = std::max(1, std::min(cfg.hop, N / 2));  // keep >= 50 % frame overlap
        if (cfg.sampleRate < 1.0) cfg.sampleRate = 22050.0;
        if (cfg.minBpm < 20.0) cfg.minBpm = 20.0;
        if (cfg.maxBpm < cfg.minBpm + 5.0) cfg.maxBpm = cfg.minBpm + 5.0;
        cfg.fftSize = N;
        cfg.hop = hop;
        M = N / 2;
        fft.init(N);

        // Periodic Hann, normalised so |X| ~ sine amplitude.
        const ArrD h = 0.5 - 0.5 * (ArrD::LinSpaced(N, 0.0, static_cast<double>(N - 1)) * (2.0 * kPi / N)).cos();
        const double s = h.sum();
        win.resize(static_cast<size_t>(N));
        Eigen::Map<ArrF>(win.data(), N) = (h * (2.0 / s)).cast<float>();
        wx.assign(static_cast<size_t>(N), 0.f);
        pw.assign(static_cast<size_t>(M + 1), 0.f);
        buildBands();
        prevL.assign(kBands, 0.f);
        curL.assign(kBands, 0.f);
    }

    void buildBands() {
        const double sr = cfg.sampleRate;
        const double hi = std::min(kBandHiHz, 0.45 * sr);
        const double mlo = hz2mel(kBandLoHz), mhi = hz2mel(hi);
        std::vector<double> edge(kBands + 2);
        for (int i = 0; i < kBands + 2; ++i) edge[static_cast<size_t>(i)] = mel2hz(mlo + (mhi - mlo) * i / (kBands + 1));
        const double binHz = sr / N;
        for (int b = 0; b < kBands; ++b) {
            const double fl = edge[static_cast<size_t>(b)], fc = edge[static_cast<size_t>(b + 1)],
                         fh = edge[static_cast<size_t>(b + 2)];
            int kLo = std::max(1, static_cast<int>(nx::ceil(fl / binHz)));
            int kHi = std::min(M - 1, static_cast<int>(nx::floor(fh / binHz)));
            std::vector<float> w;
            double sum = 0.0;
            for (int k = kLo; k <= kHi; ++k) {
                const double f = k * binHz;
                const double v = f <= fc ? (f - fl) / (fc - fl) : (fh - f) / (fh - fc);
                w.push_back(static_cast<float>(std::max(0.0, v)));
                sum += std::max(0.0, v);
            }
            if (sum <= 0.0) {  // band narrower than a bin: take the nearest bin
                kLo = kHi = std::max(1, std::min(M - 1, static_cast<int>(iround(fc / binHz))));
                w.assign(1, 1.0f);
                sum = 1.0;
            }
            Band bd{kLo, static_cast<int>(w.size()), bw.size()};
            for (float v : w) bw.push_back(static_cast<float>(v / sum));
            bands.push_back(bd);
        }
    }

    double analysedSec() const {
        if (framesSinceStart == 0) return 0.0;
        return (static_cast<double>(framesSinceStart - 1) * hop + N) / cfg.sampleRate;
    }

    // One STFT frame -> one onset-strength value (half-wave rectified, max-filtered mel log-flux).
    void frame(const float* x) {
        const Eigen::Map<const ArrF> xv(x, N);
        if (!started) {
            if (cfg.trimSilence) {
                const double e = xv.cast<double>().square().sum();
                if (nx::sqrt(e / N) < kSilenceRms) return;
            }
            started = true;
        }
        ++framesSinceStart;
        Eigen::Map<ArrF>(wx.data(), N) = xv * Eigen::Map<const ArrF>(win.data(), N);
        fft.power(wx.data(), pw.data());

        Eigen::Array<float, kBands, 1> e;
        for (int b = 0; b < kBands; ++b) {
            const Band& bd = bands[static_cast<size_t>(b)];
            e[b] = Eigen::Map<const Eigen::VectorXf>(&bw[bd.wOff], bd.cnt)
                       .dot(Eigen::Map<const Eigen::VectorXf>(&pw[static_cast<size_t>(bd.lo)], bd.cnt));
        }
        Eigen::Map<Eigen::Array<float, kBands, 1>>(curL.data()) = (static_cast<float>(kLogCompress) * e.sqrt()).log1p();

        if (havePrev) {
            double flux = 0.0;
            for (int b = 0; b < kBands; ++b) {
                const float pm = std::max(prevL[static_cast<size_t>(std::max(b - 1, 0))],
                                          std::max(prevL[static_cast<size_t>(b)],
                                                   prevL[static_cast<size_t>(std::min(b + 1, kBands - 1))]));
                const float d = curL[static_cast<size_t>(b)] - pm;
                if (d > 0.f) flux += d;
            }
            onset.push_back(static_cast<float>(flux));
        }
        havePrev = true;
        std::swap(prevL, curL);
    }

    void earlyCheck() {
        const double sec = analysedSec();
        if (sec < nextCheck) return;
        nextCheck = sec + kCheckEverySec;
        cached = analyze(cfg, onset, sec);
        cachedN = onset.size();
        const bool agree = lastValid && cached.valid && nx::abs(cached.bpm - lastBpm) <= kStopAgreeBpm;
        if (agree && cached.confidence >= kStopConfidence) full = true;
        lastValid = cached.valid;
        lastBpm = cached.bpm;
    }
};

BpmDetector::BpmDetector(const BpmConfig& cfg) : p_(new Impl(cfg)) {}
BpmDetector::~BpmDetector() = default;

bool BpmDetector::push(const float* mono, size_t n) {
    Impl& s = *p_;
    if (s.full) return false;
    s.buf.insert(s.buf.end(), mono, mono + n);
    s.totalSamples += n;

    size_t pos = 0;
    const size_t N = static_cast<size_t>(s.N), hop = static_cast<size_t>(s.hop);
    while (s.buf.size() - pos >= N) {
        s.frame(&s.buf[pos]);
        pos += hop;
    }
    s.buf.erase(s.buf.begin(), s.buf.begin() + static_cast<std::ptrdiff_t>(pos));

    if (s.analysedSec() >= s.cfg.maxSeconds ||
        static_cast<double>(s.totalSamples) >= (s.cfg.maxSeconds + kSilenceGuardSec) * s.cfg.sampleRate) {
        s.full = true;
    } else if (s.cfg.earlyStop) {
        s.earlyCheck();
    }
    return !s.full;
}

BpmResult BpmDetector::finish() {
    Impl& s = *p_;
    if (!s.started) {
        BpmResult r;
        r.reason = "silence (nothing above -60 dBFS)";
        return r;
    }
    if (s.cachedN != 0 && s.cachedN == s.onset.size()) return s.cached;
    return analyze(s.cfg, s.onset, s.analysedSec());
}

}  // namespace fox
