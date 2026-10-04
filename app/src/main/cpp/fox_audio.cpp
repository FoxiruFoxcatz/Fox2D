// fox_audio.cpp - native audio for the fox2d timeline.
//
//  * Decode : FFmpeg (libavformat/libavcodec) -> 48 kHz stereo int16, linear resampler (no swresample needed).
//  * Peaks  : one min/max block per 256 frames; waveform queries use blocks when zoomed out, raw PCM when zoomed in.
//  * Playback: Oboe float/stereo/48k output. The data callback only touches atomics + immutable PCM -> no locks,
//              no allocation. Clips live in fixed slots written by setClips().
//
// Math: Eigen (header-only, Eigen/Core only).
//  * Decode  : each AVFrame channel is converted to float with ONE Eigen::Map expression (sample format is
//              dispatched once per frame, not once per sample like before).
//  * Peaks   : min/max reductions over Eigen maps (strided over the interleaved PCM, contiguous over blocks).
//  * Mixer   : per clip, one contiguous `out += pcm.cast<float>() * gain` over the overlap range; the
//              per-sample bounds checks are hoisted into the range. Maps only -> no heap allocation in the callback.
//
// Requires Oboe >= 1.7 (shared_ptr callbacks) and FFmpeg headers on the include path (fox_ext_link does that).

#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/samplefmt.h>
}

#include <Eigen/Core>
#include <glm/vec2.hpp>

#include "fox_audio_mix.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#define TAG "FoxAudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

constexpr int kRate = 48000;
constexpr int kBlock = 256;
constexpr int kMaxSlots = 64;
constexpr int kMaxMid = 2 * kBlock;      // longest raw run midMinMax() is asked to reduce

struct Pcm {
    std::vector<int16_t> d;              // interleaved stereo
    int64_t frames = 0;
    std::vector<int16_t> bmin, bmax;     // mono peaks per kBlock frames
};

std::mutex gMu;                          // guards gPcm / gRetired / gNext (never taken by the audio callback)
std::unordered_map<int, std::shared_ptr<Pcm>> gPcm;
std::vector<std::shared_ptr<Pcm>> gRetired;
int gNext = 1;

// --------------------------------------------------------------------------------------------- decode

int chOfPar(const AVCodecParameters* p) {
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 24, 100)
    return p->ch_layout.nb_channels;
#else
    return p->channels;
#endif
}

int chOfFrame(const AVFrame* f) {
#if LIBAVUTIL_VERSION_INT >= AV_VERSION_INT(57, 24, 100)
    return f->ch_layout.nb_channels;
#else
    return f->channels;
#endif
}

// Strided read-only view over one channel of an AVFrame (stride 1 when planar, nch when packed).
template <class T>
using ChanMap = Eigen::Map<const Eigen::Array<T, Eigen::Dynamic, 1>, Eigen::Unaligned, Eigen::InnerStride<>>;

// out[i] = (float(sample_i) + bias) * scale. All scales used are powers of two -> exact, same as the old divide.
template <class T>
void loadCh(const AVFrame* f, int ch, int nch, bool planar, float scale, float bias, Eigen::ArrayXf& out) {
    const T* base = planar ? reinterpret_cast<const T*>(f->data[ch])
                           : reinterpret_cast<const T*>(f->data[0]) + ch;
    const ChanMap<T> src(base, f->nb_samples, Eigen::InnerStride<>(planar ? 1 : nch));
    out = (src.template cast<float>() + bias) * scale;
}

// Streaming linear resampler to kRate, stereo in / int16 stereo out.
struct Sink {
    std::shared_ptr<Pcm> out = std::make_shared<Pcm>();
    Eigen::ArrayXf L, R;                 // per-frame planar scratch (capacity is reused)
    bool ready = false, first = true;
    double step = 1.0, pos = 0.0;
    int64_t n = 0;
    glm::vec2 prev{0.f};                 // previous input sample (l, r)

    void init(int srcRate) {
        step = (double) std::max(srcRate, 1) / kRate;
        ready = true;
    }

    static int16_t q(float v) { return (int16_t) lrintf(std::clamp(v, -1.f, 1.f) * 32767.f); }

    void pushBlock(const float* l, const float* r, int m) {
        if (m <= 0) return;
        if (first) { prev = glm::vec2(l[0], r[0]); first = false; }
        auto& d = out->d;
        for (int i = 0; i < m; i++) {
            const glm::vec2 cur(l[i], r[i]);
            const glm::vec2 delta = cur - prev;
            while (pos <= (double) n) {
                const float f = (float) (pos - (double) (n - 1));   // 0..1 between previous and current sample
                const glm::vec2 o = prev + delta * f;
                d.push_back(q(o.x));
                d.push_back(q(o.y));
                pos += step;
            }
            prev = cur;
            n++;
        }
    }
};

void consume(const AVFrame* fr, Sink& s) {
    if (!s.ready) s.init(fr->sample_rate);
    const int nch = std::max(1, chOfFrame(fr));
    const auto fmt = (AVSampleFormat) fr->format;
    const auto packed = av_get_packed_sample_fmt(fmt);
    const bool planar = av_sample_fmt_is_planar(fmt);

    auto load = [&](int ch, Eigen::ArrayXf& out) {
        switch (packed) {
            case AV_SAMPLE_FMT_U8:  loadCh<uint8_t>(fr, ch, nch, planar, 1.f / 128.f, -128.f, out); break;
            case AV_SAMPLE_FMT_S16: loadCh<int16_t>(fr, ch, nch, planar, 1.f / 32768.f, 0.f, out); break;
            case AV_SAMPLE_FMT_S32: loadCh<int32_t>(fr, ch, nch, planar, 1.f / 2147483648.f, 0.f, out); break;
            case AV_SAMPLE_FMT_FLT: loadCh<float>(fr, ch, nch, planar, 1.f, 0.f, out); break;
            case AV_SAMPLE_FMT_DBL: loadCh<double>(fr, ch, nch, planar, 1.f, 0.f, out); break;
            default: out.setZero(fr->nb_samples); break;
        }
    };

    load(0, s.L);
    if (nch > 1) load(1, s.R);
    const Eigen::ArrayXf& right = nch > 1 ? s.R : s.L;    // mono -> duplicate left
    s.pushBlock(s.L.data(), right.data(), fr->nb_samples);
}

// Min/max of the mono mix m = (L + R) / 2 (C++ truncating division) over frames [s, e) of interleaved PCM.
// x/2 is monotonic, so reduce the int sums first and halve the two results: identical to halving every sample.
// Requires 0 < e - s <= kMaxMid (fixed max-size array -> no heap allocation).
inline void midMinMax(const int16_t* d, int64_t s, int64_t e, int& mn, int& mx) {
    using Ch = Eigen::Map<const Eigen::Array<int16_t, Eigen::Dynamic, 1>, Eigen::Unaligned, Eigen::InnerStride<2>>;
    using Mid = Eigen::Array<int, Eigen::Dynamic, 1, 0, kMaxMid, 1>;
    const Eigen::Index len = (Eigen::Index) (e - s);
    const int16_t* p = d + s * 2;
    const Mid sum = Ch(p, len).cast<int>() + Ch(p + 1, len).cast<int>();
    mn = sum.minCoeff() / 2;
    mx = sum.maxCoeff() / 2;
}

void buildPeaks(Pcm& p) {
    p.frames = (int64_t) (p.d.size() / 2);
    const int64_t blocks = (p.frames + kBlock - 1) / kBlock;
    p.bmin.assign((size_t) blocks, 0);
    p.bmax.assign((size_t) blocks, 0);
    for (int64_t b = 0; b < blocks; b++) {
        const int64_t a = b * kBlock, e = std::min(p.frames, a + kBlock);
        int mn, mx;
        midMinMax(p.d.data(), a, e, mn, mx);
        p.bmin[b] = (int16_t) mn;
        p.bmax[b] = (int16_t) mx;
    }
}

std::shared_ptr<Pcm> decodeFile(const char* path) {
    AVFormatContext* fmt = nullptr;
    if (avformat_open_input(&fmt, path, nullptr, nullptr) < 0) { LOGE("open failed"); return nullptr; }
    std::shared_ptr<Pcm> result;
    AVCodecContext* ctx = nullptr;
    AVPacket* pkt = av_packet_alloc();
    AVFrame* fr = av_frame_alloc();
    do {
        if (avformat_find_stream_info(fmt, nullptr) < 0) break;
        const int si = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (si < 0) { LOGE("no audio stream"); break; }
        AVStream* st = fmt->streams[si];
        const AVCodec* codec = avcodec_find_decoder(st->codecpar->codec_id);
        if (!codec) { LOGE("no decoder"); break; }
        ctx = avcodec_alloc_context3(codec);
        if (avcodec_parameters_to_context(ctx, st->codecpar) < 0) break;
        if (avcodec_open2(ctx, codec, nullptr) < 0) break;
        (void) chOfPar;

        Sink sink;
        if (fmt->duration > 0) {
            sink.out->d.reserve((size_t) ((double) fmt->duration / AV_TIME_BASE * kRate * 2 + 4096));
        }
        while (av_read_frame(fmt, pkt) >= 0) {
            if (pkt->stream_index == si && avcodec_send_packet(ctx, pkt) >= 0) {
                while (avcodec_receive_frame(ctx, fr) == 0) { consume(fr, sink); av_frame_unref(fr); }
            }
            av_packet_unref(pkt);
        }
        avcodec_send_packet(ctx, nullptr);
        while (avcodec_receive_frame(ctx, fr) == 0) { consume(fr, sink); av_frame_unref(fr); }

        if (sink.out->d.empty()) break;
        buildPeaks(*sink.out);
        result = sink.out;
    } while (false);
    av_frame_free(&fr);
    av_packet_free(&pkt);
    if (ctx) avcodec_free_context(&ctx);
    avformat_close_input(&fmt);
    return result;
}

void waveform(const Pcm& p, double startMs, double endMs, int buckets, float* out) {
    using PeakMap = Eigen::Map<const Eigen::Array<int16_t, Eigen::Dynamic, 1>>;
    const double a = startMs * kRate / 1000.0, b = endMs * kRate / 1000.0;
    const double w = (b - a) / std::max(1, buckets);
    for (int i = 0; i < buckets; i++) {
        int64_t s = (int64_t) std::floor(a + i * w), e = (int64_t) std::floor(a + (i + 1) * w);
        if (e <= s) e = s + 1;
        if (e <= 0 || s >= p.frames) { out[i * 2] = out[i * 2 + 1] = 0.f; continue; }
        s = std::max<int64_t>(s, 0);
        e = std::min(e, p.frames);
        int mn, mx;
        if (e - s >= 2 * kBlock) {
            const int64_t b0 = s / kBlock, b1 = std::min<int64_t>((e + kBlock - 1) / kBlock, (int64_t) p.bmin.size());
            const Eigen::Index cnt = (Eigen::Index) (b1 - b0);
            mn = PeakMap(p.bmin.data() + b0, cnt).minCoeff();
            mx = PeakMap(p.bmax.data() + b0, cnt).maxCoeff();
        } else {
            midMinMax(p.d.data(), s, e, mn, mx);      // e - s < 2 * kBlock == kMaxMid
        }
        out[i * 2] = mn / 32768.f;
        out[i * 2 + 1] = mx / 32768.f;
    }
}

// --------------------------------------------------------------------------------------------- volume envelope
// Per-clip volume keys. The line through them is a MONOTONE CUBIC HERMITE spline (Fritsch-Carlson): smooth (C1, no
// polygon corners), never overshoots a key (no ringing above / below the keyed volumes). Keys are in SOURCE frames
// (0 = start of the file), so trimming / moving a clip keeps them on the sound. The UI graph asks nativeEnvelope()
// for samples of this exact curve, so what is drawn is what is mixed.

constexpr int kMaxKeys = 32;

struct EnvData {
    int n = 0;
    float t[kMaxKeys], v[kMaxKeys], m[kMaxKeys];   // time (source frames), volume, tangent
};

void envBuild(EnvData& e, const float* t, const float* v, int n) {
    e.n = std::clamp(n, 0, kMaxKeys);
    for (int i = 0; i < e.n; i++) { e.t[i] = t[i]; e.v[i] = v[i]; e.m[i] = 0.f; }
    if (e.n < 2) return;
    float d[kMaxKeys];
    for (int i = 0; i < e.n - 1; i++) d[i] = (e.v[i + 1] - e.v[i]) / std::max(e.t[i + 1] - e.t[i], 1.f);
    e.m[0] = d[0];
    e.m[e.n - 1] = d[e.n - 2];
    for (int i = 1; i < e.n - 1; i++) e.m[i] = d[i - 1] * d[i] <= 0.f ? 0.f : 0.5f * (d[i - 1] + d[i]);
    for (int i = 0; i < e.n - 1; i++) {
        if (d[i] == 0.f) { e.m[i] = 0.f; e.m[i + 1] = 0.f; continue; }
        const float a = e.m[i] / d[i], b = e.m[i + 1] / d[i];
        const float h = a * a + b * b;
        if (h > 9.f) { const float tau = 3.f / std::sqrt(h); e.m[i] = tau * a * d[i]; e.m[i + 1] = tau * b * d[i]; }
    }
}

inline float envEval(const float* t, const float* v, const float* m, int n, float x) {
    if (x <= t[0]) return v[0];
    if (x >= t[n - 1]) return v[n - 1];
    int lo = 0, hi = n - 1;
    while (hi - lo > 1) { const int mid = (lo + hi) / 2; if (t[mid] <= x) lo = mid; else hi = mid; }
    const float h = std::max(t[hi] - t[lo], 1.f), s = (x - t[lo]) / h, s2 = s * s, s3 = s2 * s;
    const float r = (2.f * s3 - 3.f * s2 + 1.f) * v[lo] + (s3 - 2.f * s2 + s) * h * m[lo] +
                    (-2.f * s3 + 3.f * s2) * v[hi] + (s3 - s2) * h * m[hi];
    return std::max(r, 0.f);
}

// out += pcm * envelope * master for output frames [a, b). Gain is evaluated every kStep frames and ramped linearly in
// between: no zipper noise and no per-sample spline maths. Source frame of output frame i is (i - off).
void mixEnv(float* out, const int16_t* pcm, int64_t a, int64_t b, int64_t pos, int64_t off, float master,
            const float* t, const float* v, const float* m, int n) {
    constexpr int64_t kStep = 64;
    for (int64_t s0 = a; s0 < b; s0 += kStep) {
        const int64_t s1 = std::min<int64_t>(s0 + kStep, b);
        const float g0 = envEval(t, v, m, n, (float) (s0 - off)) * master;
        const float g1 = envEval(t, v, m, n, (float) (s1 - off)) * master;
        const float dg = (g1 - g0) / (float) (s1 - s0);
        float g = g0;
        for (int64_t i = s0; i < s1; i++, g += dg) {
            float* o = out + (i - pos) * 2;
            const int16_t* p = pcm + (i - off) * 2;
            o[0] += (float) p[0] * g;
            o[1] += (float) p[1] * g;
        }
    }
}

// --------------------------------------------------------------------------------------------- engine

struct Slot {
    std::atomic<const int16_t*> data{nullptr};
    std::atomic<int64_t> frames{0}, start{0}, in{0}, len{0};   // all in output frames
    std::atomic<float> gain{1.f};                               // master (0 = muted track); the only volume when there are no keys
    std::atomic<int> nkeys{0};
    std::atomic<float> kt[kMaxKeys], kv[kMaxKeys], km[kMaxKeys];
};

struct Engine : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
    Slot slots[kMaxSlots];
    std::atomic<bool> playing{false}, inCb{false};
    std::atomic<int64_t> playFrame{0};
    std::shared_ptr<oboe::AudioStream> stream;
    std::mutex ctl;

    // Real-time: only Eigen::Map views over the Oboe buffer and immutable PCM. No allocation, no locks.
    // (Debug aid: build with -DEIGEN_RUNTIME_NO_MALLOC and call Eigen::internal::set_is_malloc_allowed(false)
    //  around this body to assert that no Eigen expression ever allocates here.)
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        using OutMap = Eigen::Map<Eigen::ArrayXf>;
        using PcmMap = Eigen::Map<const Eigen::Array<int16_t, Eigen::Dynamic, 1>>;

        inCb.store(true, std::memory_order_seq_cst);
        float* out = static_cast<float*>(audioData);
        OutMap mix(out, (Eigen::Index) numFrames * 2);
        mix.setZero();
        if (playing.load(std::memory_order_acquire)) {
            const int64_t pos = playFrame.load(std::memory_order_relaxed);
            for (auto& sl : slots) {
                const int16_t* d = sl.data.load(std::memory_order_acquire);
                if (!d) continue;
                const float g = sl.gain.load(std::memory_order_relaxed) / 32768.f;
                if (g == 0.f) continue;
                const int64_t start = sl.start.load(std::memory_order_relaxed);
                const int64_t len = sl.len.load(std::memory_order_relaxed);
                const int64_t in = sl.in.load(std::memory_order_relaxed);
                const int64_t total = sl.frames.load(std::memory_order_relaxed);

                // source frame for output frame t is  src = in + (t - start) = t - off.
                // Keep t inside this block, inside the clip, and with 0 <= src < total -> one contiguous run.
                const int64_t off = start - in;
                const int64_t a = std::max({pos, start, off});
                const int64_t b = std::min({pos + numFrames, start + len, off + total});
                if (b <= a) continue;

                const int nk = sl.nkeys.load(std::memory_order_relaxed);
                if (nk > 0) {
                    float t[kMaxKeys], v[kMaxKeys], m[kMaxKeys];
                    for (int k = 0; k < nk; k++) {
                        t[k] = sl.kt[k].load(std::memory_order_relaxed);
                        v[k] = sl.kv[k].load(std::memory_order_relaxed);
                        m[k] = sl.km[k].load(std::memory_order_relaxed);
                    }
                    mixEnv(out, d, a, b, pos, off, g, t, v, m, nk);
                    continue;
                }
                const Eigen::Index cnt = (Eigen::Index) (b - a) * 2;     // interleaved stereo
                OutMap(out + (a - pos) * 2, cnt) += PcmMap(d + (a - off) * 2, cnt).cast<float>() * g;
            }
            mix = mix.max(-1.f).min(1.f);
            playFrame.store(pos + numFrames, std::memory_order_relaxed);
        }
        inCb.store(false, std::memory_order_seq_cst);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override {   // e.g. headphones unplugged
        std::lock_guard<std::mutex> l(ctl);
        stream.reset();
        if (playing.load() && open()) stream->requestStart();
    }

    bool open();
};

std::shared_ptr<Engine> gEng = std::make_shared<Engine>();

bool Engine::open() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::None)
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(2)
        ->setSampleRate(kRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(gEng)
        ->setErrorCallback(gEng);
    const oboe::Result r = b.openStream(stream);
    if (r != oboe::Result::OK) { LOGE("oboe open failed: %s", oboe::convertToText(r)); stream.reset(); return false; }
    return true;
}

// Free PCM that was released while the mixer might still be reading it.
void freeRetired() {
    if (gEng->playing.load()) return;
    for (int i = 0; i < 100 && gEng->inCb.load(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    if (gEng->inCb.load()) return;
    std::lock_guard<std::mutex> l(gMu);
    gRetired.clear();
}

}  // namespace

// --------------------------------------------------------------------------------------------- offline mix

struct FoxMixSnapshot {
    struct Clip {
        std::shared_ptr<Pcm> pcm;
        int64_t start = 0, in = 0, len = 0, total = 0;
        float gain = 1.f;
        EnvData env;   // env.n == 0: plain gain
    };
    std::vector<Clip> clips;
};

std::shared_ptr<FoxMixSnapshot> foxAudioSnapshot() {
    auto snap = std::make_shared<FoxMixSnapshot>();
    std::lock_guard<std::mutex> l(gMu);
    for (auto& sl : gEng->slots) {
        const int16_t* d = sl.data.load(std::memory_order_acquire);
        if (!d) continue;
        std::shared_ptr<Pcm> pcm;
        for (auto& kv : gPcm) if (kv.second->d.data() == d) { pcm = kv.second; break; }
        if (!pcm) continue;
        FoxMixSnapshot::Clip c;
        c.pcm = std::move(pcm);
        c.start = sl.start.load(std::memory_order_relaxed);
        c.in = sl.in.load(std::memory_order_relaxed);
        c.len = sl.len.load(std::memory_order_relaxed);
        c.total = sl.frames.load(std::memory_order_relaxed);
        c.gain = sl.gain.load(std::memory_order_relaxed);
        c.env.n = std::clamp(sl.nkeys.load(std::memory_order_relaxed), 0, kMaxKeys);
        for (int k = 0; k < c.env.n; k++) {
            c.env.t[k] = sl.kt[k].load(std::memory_order_relaxed);
            c.env.v[k] = sl.kv[k].load(std::memory_order_relaxed);
            c.env.m[k] = sl.km[k].load(std::memory_order_relaxed);
        }
        snap->clips.push_back(std::move(c));
    }
    return snap;
}

bool foxAudioHasClips(const FoxMixSnapshot& s) { return !s.clips.empty(); }

void foxAudioMix(const FoxMixSnapshot& s, int64_t pos, int frames, float* out) {
    using OutMap = Eigen::Map<Eigen::ArrayXf>;
    using PcmMap = Eigen::Map<const Eigen::Array<int16_t, Eigen::Dynamic, 1>>;
    OutMap mix(out, (Eigen::Index) frames * 2);
    mix.setZero();
    for (const auto& c : s.clips) {
        const float g = c.gain / 32768.f;
        if (g == 0.f) continue;
        const int64_t off = c.start - c.in;
        const int64_t a = std::max({pos, c.start, off});
        const int64_t b = std::min({pos + frames, c.start + c.len, off + c.total});
        if (b <= a) continue;
        if (c.env.n > 0) {
            mixEnv(out, c.pcm->d.data(), a, b, pos, off, g, c.env.t, c.env.v, c.env.m, c.env.n);
            continue;
        }
        const Eigen::Index cnt = (Eigen::Index) (b - a) * 2;
        OutMap(out + (a - pos) * 2, cnt) += PcmMap(c.pcm->d.data() + (a - off) * 2, cnt).cast<float>() * g;
    }
    mix = mix.max(-1.f).min(1.f);
}

// --------------------------------------------------------------------------------------------- JNI

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_fox_foxiru_foxcat_fox2d_jnicallers_AudioHandlerNative_##name

FN(jint, nativeLoad)(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    auto pcm = decodeFile(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!pcm) return -1;
    std::lock_guard<std::mutex> l(gMu);
    const int h = gNext++;
    gPcm[h] = std::move(pcm);
    return h;
}

FN(jlong, nativeDurationMs)(JNIEnv*, jclass, jint h) {
    std::lock_guard<std::mutex> l(gMu);
    auto it = gPcm.find(h);
    return it == gPcm.end() ? 0 : (jlong) (it->second->frames * 1000 / kRate);
}

FN(void, nativeRelease)(JNIEnv*, jclass, jint h) {
    {
        std::lock_guard<std::mutex> l(gMu);
        auto it = gPcm.find(h);
        if (it == gPcm.end()) return;
        gRetired.push_back(std::move(it->second));
        gPcm.erase(it);
    }
    freeRetired();
}

FN(void, nativeWaveform)(JNIEnv* env, jclass, jint h, jdouble startMs, jdouble endMs, jint buckets, jfloatArray out) {
    std::shared_ptr<Pcm> p;
    {
        std::lock_guard<std::mutex> l(gMu);
        auto it = gPcm.find(h);
        if (it != gPcm.end()) p = it->second;
    }
    const jsize cap = env->GetArrayLength(out) / 2;
    const int n = std::min<int>(buckets, cap);
    if (!p || n <= 0) return;
    std::vector<float> tmp((size_t) n * 2);
    waveform(*p, startMs, endMs, n, tmp.data());
    env->SetFloatArrayRegion(out, 0, n * 2, tmp.data());
}

// keyCounts[i] = volume keys of clip i; keys = those keys back to back as (sourceMs, gain) pairs, sorted by time.
FN(void, nativeSetClips)(JNIEnv* env, jclass, jintArray jh, jlongArray jstart, jlongArray jin, jlongArray jlen, jfloatArray jgain,
                         jintArray jkc, jfloatArray jkeys) {
    const int n = std::min<int>(env->GetArrayLength(jh), kMaxSlots);
    std::vector<jint> h(n), kc(n, 0);
    std::vector<jlong> st(n), in(n), len(n);
    std::vector<jfloat> g(n);
    env->GetIntArrayRegion(jh, 0, n, h.data());
    env->GetLongArrayRegion(jstart, 0, n, st.data());
    env->GetLongArrayRegion(jin, 0, n, in.data());
    env->GetLongArrayRegion(jlen, 0, n, len.data());
    env->GetFloatArrayRegion(jgain, 0, n, g.data());
    if (jkc) env->GetIntArrayRegion(jkc, 0, std::min<int>(n, env->GetArrayLength(jkc)), kc.data());
    const jsize nk = jkeys ? env->GetArrayLength(jkeys) : 0;
    std::vector<jfloat> keys(nk);
    if (nk) env->GetFloatArrayRegion(jkeys, 0, nk, keys.data());

    std::lock_guard<std::mutex> l(gMu);
    size_t ko = 0;   // running offset (in keys) into [keys]
    for (int i = 0; i < kMaxSlots; i++) {
        Slot& s = gEng->slots[i];
        s.data.store(nullptr, std::memory_order_release);          // silence the slot while it is rewritten
        if (i >= n) continue;
        const size_t cnt = (size_t) std::max(kc[i], 0);
        const size_t mine = ko;
        ko += cnt;
        auto it = gPcm.find(h[i]);
        if (it == gPcm.end()) continue;
        s.frames.store(it->second->frames, std::memory_order_relaxed);
        s.start.store(st[i] * kRate / 1000, std::memory_order_relaxed);
        s.in.store(in[i] * kRate / 1000, std::memory_order_relaxed);
        s.len.store(len[i] * kRate / 1000, std::memory_order_relaxed);
        s.gain.store(g[i], std::memory_order_relaxed);

        EnvData e;
        if (cnt > 0 && (mine + cnt) * 2 <= (size_t) nk) {
            const int m = (int) std::min<size_t>(cnt, kMaxKeys);
            float t[kMaxKeys], v[kMaxKeys];
            for (int k = 0; k < m; k++) {
                t[k] = keys[(mine + k) * 2] * (float) kRate / 1000.f;   // source ms -> source frames
                v[k] = std::max(keys[(mine + k) * 2 + 1], 0.f);
            }
            envBuild(e, t, v, m);
        }
        s.nkeys.store(0, std::memory_order_relaxed);
        for (int k = 0; k < e.n; k++) {
            s.kt[k].store(e.t[k], std::memory_order_relaxed);
            s.kv[k].store(e.v[k], std::memory_order_relaxed);
            s.km[k].store(e.m[k], std::memory_order_relaxed);
        }
        s.nkeys.store(e.n, std::memory_order_relaxed);
        s.data.store(it->second->d.data(), std::memory_order_release);
    }
}

// Samples of the volume curve for the UI graph: out.size points evenly spread over [fromMs, toMs] (SOURCE ms).
// out.size == 1 (or from == to) = the value at fromMs. n == 0 keys -> 1.0 (the caller uses the static gain instead).
FN(void, nativeEnvelope)(JNIEnv* env, jclass, jfloatArray jkeys, jint n, jdouble fromMs, jdouble toMs, jfloatArray jout) {
    const jsize cnt = env->GetArrayLength(jout);
    if (cnt <= 0) return;
    std::vector<jfloat> keys((size_t) env->GetArrayLength(jkeys));
    if (!keys.empty()) env->GetFloatArrayRegion(jkeys, 0, (jsize) keys.size(), keys.data());
    const int m = std::min<int>(std::min<int>(n, kMaxKeys), (int) (keys.size() / 2));
    std::vector<jfloat> out((size_t) cnt, 1.f);
    if (m > 0) {
        float t[kMaxKeys], v[kMaxKeys];
        for (int k = 0; k < m; k++) { t[k] = keys[(size_t) k * 2] * (float) kRate / 1000.f; v[k] = std::max(keys[(size_t) k * 2 + 1], 0.f); }
        EnvData e;
        envBuild(e, t, v, m);
        for (int i = 0; i < cnt; i++) {
            const double ms = cnt == 1 ? fromMs : fromMs + (toMs - fromMs) * i / (double) (cnt - 1);
            out[(size_t) i] = envEval(e.t, e.v, e.m, e.n, (float) (ms * kRate / 1000.0));
        }
    }
    env->SetFloatArrayRegion(jout, 0, cnt, out.data());
}

FN(void, nativePlay)(JNIEnv*, jclass, jlong posMs) {
    std::lock_guard<std::mutex> l(gEng->ctl);
    if (!gEng->stream && !gEng->open()) return;
    gEng->playFrame.store((int64_t) posMs * kRate / 1000);
    gEng->playing.store(true, std::memory_order_release);
    gEng->stream->requestStart();
}

FN(void, nativePause)(JNIEnv*, jclass) {
    {
        std::lock_guard<std::mutex> l(gEng->ctl);
        gEng->playing.store(false, std::memory_order_release);
        if (gEng->stream) gEng->stream->requestPause();
    }
    freeRetired();
}

FN(jlong, nativePositionMs)(JNIEnv*, jclass) {
    return (jlong) (gEng->playFrame.load(std::memory_order_relaxed) * 1000 / kRate);
}
