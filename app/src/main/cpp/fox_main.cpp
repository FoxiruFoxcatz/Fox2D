// ============================================================================
// fox_main.cpp  -  FFmpeg + Oboe test harness
//
// Pipeline under test:
//   SAF fd -> custom AVIOContext (read/lseek64) -> libavformat demux
//          -> libavcodec decode -> libswresample (-> float32 stereo @ stream rate)
//          -> SPSC lock-free ring -> Oboe data callback (RT thread)
//
// Requires FFmpeg >= 5.1 (AVChannelLayout API, swr_alloc_set_opts2).
// ============================================================================

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

#include <oboe/Oboe.h>

#include "bpm_detector.h"
#include "lib_config.h"

#if !defined(FOX_HAS_FFMPEG) || !defined(FOX_HAS_OBOE)
#error "ffmpeg and oboe must both be imported (fox_ext_link ffmpeg / oboe)"
#endif

#define LOG_TAG FOXIRU_LIB_NAME
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr double kTwoPi = 6.283185307179586476925286766559;

// ---------------------------------------------------------------- helpers --

std::string vfmt(const char* f, va_list ap) {
    va_list ap2;
    va_copy(ap2, ap);
    const int n = vsnprintf(nullptr, 0, f, ap);
    std::string s;
    if (n > 0) {
        s.resize(static_cast<size_t>(n));
        vsnprintf(s.data(), static_cast<size_t>(n) + 1, f, ap2);
    }
    va_end(ap2);
    return s;
}

std::string fmt(const char* f, ...) {
    va_list ap;
    va_start(ap, f);
    std::string s = vfmt(f, ap);
    va_end(ap);
    return s;
}

// out += formatted line
void line(std::string& out, const char* f, ...) {
    va_list ap;
    va_start(ap, f);
    out += vfmt(f, ap);
    va_end(ap);
    out += '\n';
}

std::string avErr(int rc) {
    char b[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(rc, b, sizeof(b));
    return fmt("%s (%d)", b, rc);
}

// NewStringUTF wants *modified* UTF-8: no NUL, no 4-byte sequences, no
// overlongs. Tags coming out of demuxers are arbitrary bytes, so scrub them.
std::string jsafe(const std::string& s) {
    auto cont = [&](size_t i) { return i < s.size() && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80; };
    std::string o;
    o.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if (c == 0) {
            o += '?'; ++i;
        } else if (c < 0x80) {
            o += static_cast<char>(c); ++i;
        } else if (c >= 0xC2 && (c & 0xE0) == 0xC0 && cont(i + 1)) {
            o.append(s, i, 2); i += 2;
        } else if ((c & 0xF0) == 0xE0 && cont(i + 1) && cont(i + 2)) {
            o.append(s, i, 3); i += 3;
        } else {
            o += '?'; ++i;
            if ((c & 0xF8) == 0xF0) {
                for (int k = 0; k < 3 && cont(i); ++k) ++i;
            }
        }
    }
    return o;
}

jstring toJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(jsafe(s).c_str());
}

void logReport(const std::string& report) {
    size_t pos = 0;
    while (pos < report.size()) {
        size_t nl = report.find('\n', pos);
        if (nl == std::string::npos) nl = report.size();
        LOGI("%s", report.substr(pos, nl - pos).c_str());
        pos = nl + 1;
    }
}

std::string hms(int64_t ms) {
    if (ms < 0) return "?";
    const int64_t s = ms / 1000;
    return fmt("%lld:%02lld.%03lld", static_cast<long long>(s / 60), static_cast<long long>(s % 60),
               static_cast<long long>(ms % 1000));
}

// ------------------------------------------------- fd-backed AVIO source ---

struct FdSource {
    int fd = -1;
    int64_t size = -1;
};

int fdRead(void* opaque, uint8_t* buf, int len) {
    auto* s = static_cast<FdSource*>(opaque);
    ssize_t n;
    do {
        n = ::read(s->fd, buf, static_cast<size_t>(len));
    } while (n < 0 && errno == EINTR);
    if (n < 0) return AVERROR(errno);
    if (n == 0) return AVERROR_EOF;
    return static_cast<int>(n);
}

int64_t fdSeek(void* opaque, int64_t off, int whence) {
    auto* s = static_cast<FdSource*>(opaque);
    if (whence == AVSEEK_SIZE) return s->size;
    whence &= ~AVSEEK_FORCE;  // SEEK_SET/CUR/END values match POSIX
    const off64_t r = ::lseek64(s->fd, static_cast<off64_t>(off), whence);
    return r < 0 ? AVERROR(errno) : static_cast<int64_t>(r);
}

// Owns dup(fd), AVIOContext, AVFormatContext, decoder context.
struct Input {
    FdSource src;
    AVIOContext* io = nullptr;
    AVFormatContext* fmtCtx = nullptr;
    AVCodecContext* dec = nullptr;
    int stream = -1;

    Input() = default;
    Input(const Input&) = delete;
    Input& operator=(const Input&) = delete;
    ~Input() { close(); }

    // Stage 1: container only. Returns "" on success.
    std::string openContainer(int fd) {
        close();
        src.fd = ::dup(fd);
        if (src.fd < 0) return fmt("dup(fd) failed: %s", strerror(errno));

        struct stat st {};
        src.size = (fstat(src.fd, &st) == 0 && S_ISREG(st.st_mode)) ? static_cast<int64_t>(st.st_size) : -1;
        const bool seekable = ::lseek64(src.fd, 0, SEEK_SET) >= 0;

        constexpr int kBuf = 64 * 1024;
        auto* buf = static_cast<uint8_t*>(av_malloc(kBuf));
        if (!buf) return "av_malloc failed";
        io = avio_alloc_context(buf, kBuf, 0, &src, fdRead, nullptr, seekable ? fdSeek : nullptr);
        if (!io) {
            av_free(buf);
            return "avio_alloc_context failed";
        }
        fmtCtx = avformat_alloc_context();
        if (!fmtCtx) return "avformat_alloc_context failed";
        fmtCtx->pb = io;
        fmtCtx->flags |= AVFMT_FLAG_CUSTOM_IO;

        int rc = avformat_open_input(&fmtCtx, "fd-source", nullptr, nullptr);  // frees fmtCtx on failure
        if (rc < 0) return "avformat_open_input: " + avErr(rc);
        rc = avformat_find_stream_info(fmtCtx, nullptr);
        if (rc < 0) return "avformat_find_stream_info: " + avErr(rc);
        return {};
    }

    // Stage 2: pick best audio stream and open its decoder.
    std::string openDecoder() {
        const AVCodec* codec = nullptr;
        stream = av_find_best_stream(fmtCtx, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
        if (stream < 0) return "no audio stream: " + avErr(stream);
        if (!codec) return "no decoder available for this codec (not built into libavcodec)";

        for (unsigned i = 0; i < fmtCtx->nb_streams; ++i) {
            if (static_cast<int>(i) != stream) fmtCtx->streams[i]->discard = AVDISCARD_ALL;
        }
        AVStream* s = fmtCtx->streams[stream];
        dec = avcodec_alloc_context3(codec);
        if (!dec) return "avcodec_alloc_context3 failed";
        int rc = avcodec_parameters_to_context(dec, s->codecpar);
        if (rc < 0) return "avcodec_parameters_to_context: " + avErr(rc);
        dec->pkt_timebase = s->time_base;
        rc = avcodec_open2(dec, codec, nullptr);
        if (rc < 0) return "avcodec_open2: " + avErr(rc);
        return {};
    }

    std::string open(int fd) {
        std::string e = openContainer(fd);
        if (!e.empty()) return e;
        return openDecoder();
    }

    int64_t durationMs() const {
        if (!fmtCtx) return 0;
        if (fmtCtx->duration != AV_NOPTS_VALUE) return fmtCtx->duration / 1000;
        if (stream >= 0) {
            const AVStream* s = fmtCtx->streams[stream];
            if (s->duration != AV_NOPTS_VALUE) {
                return static_cast<int64_t>(static_cast<double>(s->duration) * av_q2d(s->time_base) * 1000.0);
            }
        }
        return 0;
    }

    void close() {
        if (dec) avcodec_free_context(&dec);
        if (fmtCtx) avformat_close_input(&fmtCtx);
        if (io) {  // custom IO: we own buffer + context
            av_freep(&io->buffer);
            avio_context_free(&io);
        }
        if (src.fd >= 0) {
            ::close(src.fd);
            src.fd = -1;
        }
        stream = -1;
    }
};

// ------------------------------------------------------- decode pipeline ---

struct DecodeStats {
    int64_t packets = 0;
    int64_t badPackets = 0;
    int64_t framesOut = 0;
    double peak = 0.0;
    double sumSq = 0.0;
    std::string error;
};

using Sink = std::function<bool(const float* interleavedStereo, int frames)>;  // false = abort

// Demux -> decode -> swr (float32 stereo @ outRate) -> sink.
DecodeStats decodeLoop(Input& in, int outRate, const std::atomic<bool>& stop, const Sink& sink) {
    DecodeStats st;
    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    SwrContext* swr = nullptr;
    std::vector<float> tmp;
    bool aborted = false;

    auto emit = [&](const float* d, int n) {
        for (int i = 0; i < n * 2; ++i) {
            const double v = d[i];
            const double a = std::fabs(v);
            if (a > st.peak) st.peak = a;
            st.sumSq += v * v;
        }
        st.framesOut += n;
        if (!sink(d, n)) aborted = true;
    };

    auto convertFrame = [&](const AVFrame* f) -> bool {
        if (!swr) {
            AVChannelLayout outL {};
            AVChannelLayout inL {};
            av_channel_layout_default(&outL, 2);
            if (f->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC) {
                av_channel_layout_default(&inL, f->ch_layout.nb_channels);
            } else {
                av_channel_layout_copy(&inL, &f->ch_layout);
            }
            int rc = swr_alloc_set_opts2(&swr, &outL, AV_SAMPLE_FMT_FLT, outRate, &inL,
                                         static_cast<AVSampleFormat>(f->format), f->sample_rate, 0, nullptr);
            av_channel_layout_uninit(&inL);
            av_channel_layout_uninit(&outL);
            if (rc >= 0) rc = swr_init(swr);
            if (rc < 0) {
                st.error = "swresample init: " + avErr(rc);
                return false;
            }
        }
        const int maxOut = swr_get_out_samples(swr, f->nb_samples);
        if (maxOut < 0) {
            st.error = "swr_get_out_samples: " + avErr(maxOut);
            return false;
        }
        tmp.resize(static_cast<size_t>(maxOut) * 2);
        uint8_t* op[1] = {reinterpret_cast<uint8_t*>(tmp.data())};
        const int n = swr_convert(swr, op, maxOut, f->extended_data, f->nb_samples);
        if (n < 0) {
            st.error = "swr_convert: " + avErr(n);
            return false;
        }
        if (n > 0) emit(tmp.data(), n);
        return !aborted;
    };

    // true = keep going (EAGAIN / EOF are normal), false = abort or error
    auto drain = [&]() -> bool {
        for (;;) {
            const int rc = avcodec_receive_frame(in.dec, frame);
            if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) return true;
            if (rc < 0) {
                st.error = "avcodec_receive_frame: " + avErr(rc);
                return false;
            }
            const bool ok = convertFrame(frame);
            av_frame_unref(frame);
            if (!ok) return false;
        }
    };

    bool ok = true;
    while (ok && !stop.load(std::memory_order_relaxed)) {
        int rc = av_read_frame(in.fmtCtx, pkt);
        if (rc < 0) {
            if (rc != AVERROR_EOF) st.error = "av_read_frame: " + avErr(rc);
            break;
        }
        if (pkt->stream_index == in.stream) {
            ++st.packets;
            rc = avcodec_send_packet(in.dec, pkt);
            if (rc < 0) ++st.badPackets;  // corrupt packet: skip, keep going
            ok = drain();
        }
        av_packet_unref(pkt);
    }

    if (ok && !stop.load(std::memory_order_relaxed)) {
        avcodec_send_packet(in.dec, nullptr);  // enter draining mode
        ok = drain();
        if (ok && swr) {
            constexpr int kFlush = 8192;
            tmp.resize(static_cast<size_t>(kFlush) * 2);
            for (;;) {
                uint8_t* op[1] = {reinterpret_cast<uint8_t*>(tmp.data())};
                const int n = swr_convert(swr, op, kFlush, nullptr, 0);
                if (n <= 0) break;
                emit(tmp.data(), n);
                if (aborted) break;
            }
        }
    }

    av_frame_free(&frame);
    av_packet_free(&pkt);
    swr_free(&swr);
    return st;
}

// ------------------------------------------------------------ SPSC ring ----

// Single producer (decoder thread), single consumer (Oboe callback).
// Stereo float frames; capacity must be a power of two.
class Ring {
public:
    static constexpr size_t kCap = 1u << 17;  // ~2.7 s @ 48 kHz
    static constexpr size_t kMask = kCap - 1;

    Ring() : buf_(kCap * 2) {}

    void reset() {
        w_.store(0, std::memory_order_relaxed);
        r_.store(0, std::memory_order_relaxed);
    }

    size_t size() const { return static_cast<size_t>(w_.load(std::memory_order_acquire) - r_.load(std::memory_order_acquire)); }

    size_t write(const float* src, size_t frames) {
        const uint64_t w = w_.load(std::memory_order_relaxed);
        const uint64_t r = r_.load(std::memory_order_acquire);
        const size_t n = std::min(frames, kCap - static_cast<size_t>(w - r));
        const size_t idx = static_cast<size_t>(w) & kMask;
        const size_t first = std::min(n, kCap - idx);
        std::memcpy(&buf_[idx * 2], src, first * 2 * sizeof(float));
        if (n > first) std::memcpy(&buf_[0], src + first * 2, (n - first) * 2 * sizeof(float));
        w_.store(w + n, std::memory_order_release);
        return n;
    }

    size_t read(float* dst, size_t frames) {
        const uint64_t r = r_.load(std::memory_order_relaxed);
        const uint64_t w = w_.load(std::memory_order_acquire);
        const size_t n = std::min(frames, static_cast<size_t>(w - r));
        const size_t idx = static_cast<size_t>(r) & kMask;
        const size_t first = std::min(n, kCap - idx);
        std::memcpy(dst, &buf_[idx * 2], first * 2 * sizeof(float));
        if (n > first) std::memcpy(dst + first * 2, &buf_[0], (n - first) * 2 * sizeof(float));
        r_.store(r + n, std::memory_order_release);
        return n;
    }

private:
    std::vector<float> buf_;
    std::atomic<uint64_t> w_{0};
    std::atomic<uint64_t> r_{0};
};

// ------------------------------------------------------------ Oboe player --

enum State : int { kIdle = 0, kPlaying = 1, kPaused = 2, kEnded = 3, kError = 4, kTone = 5 };

class Player : public oboe::AudioStreamDataCallback,
               public oboe::AudioStreamErrorCallback,
               public std::enable_shared_from_this<Player> {
public:
    // Blocks until ~250 ms is prebuffered (or 2 s), then starts the stream.
    std::string play(int fd) {
        std::lock_guard<std::mutex> lk(mtx_);
        stopLocked();

        auto in = std::make_unique<Input>();
        std::string err = in->open(fd);
        if (!err.empty()) return "ERROR: " + err;

        err = openStream();
        if (!err.empty()) return "ERROR: " + err;

        in_ = std::move(in);
        ring_.reset();
        played_ = 0;
        underruns_ = 0;
        paused_ = false;
        decodeDone_ = false;
        tone_ = false;
        stop_ = false;
        setMessage("");
        durMs_ = in_->durationMs();
        const int rate = rate_.load();

        thread_ = std::thread([this, rate] {
            const Sink sink = [this](const float* d, int frames) {
                int done = 0;
                while (done < frames) {
                    if (stop_.load(std::memory_order_relaxed)) return false;
                    const size_t n = ring_.write(d + static_cast<size_t>(done) * 2, static_cast<size_t>(frames - done));
                    done += static_cast<int>(n);
                    if (n == 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));
                }
                return true;
            };
            const DecodeStats st = decodeLoop(*in_, rate, stop_, sink);
            std::string m = fmt("decoder done: %lld pkts (%lld bad), %lld frames out, peak %.1f dBFS",
                                static_cast<long long>(st.packets), static_cast<long long>(st.badPackets),
                                static_cast<long long>(st.framesOut), st.peak > 0 ? 20.0 * std::log10(st.peak) : -999.0);
            if (!st.error.empty()) {
                m += "\nDECODE ERROR: " + st.error;
                state_.store(kError);
            }
            setMessage(m);
            decodeDone_.store(true, std::memory_order_release);
        });

        const auto t0 = std::chrono::steady_clock::now();
        while (ring_.size() < static_cast<size_t>(rate) / 4 && !decodeDone_.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() - t0 < std::chrono::seconds(2)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }

        const oboe::Result r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            const std::string e = fmt("ERROR: requestStart: %s", oboe::convertToText(r));
            stopLocked();
            return e;
        }
        if (state_.load() != kError) state_.store(kPlaying);

        std::string out = "PLAYING\n";
        line(out, "file    : %s  %s", in_->fmtCtx->iformat->name, hms(durMs_.load()).c_str());
        line(out, "decoder : %s  %d Hz  %d ch  -> float stereo @ %d Hz", avcodec_get_name(in_->dec->codec_id),
             in_->dec->sample_rate, in_->dec->ch_layout.nb_channels, rate);
        out += describe();
        return out;
    }

    std::string startTone() {
        std::lock_guard<std::mutex> lk(mtx_);
        stopLocked();
        std::string err = openStream();
        if (!err.empty()) return "ERROR: " + err;
        tone_ = true;
        phase_ = 0.0;
        played_ = 0;
        underruns_ = 0;
        durMs_ = 0;
        paused_ = false;
        stop_ = false;
        setMessage("");
        const oboe::Result r = stream_->requestStart();
        if (r != oboe::Result::OK) {
            const std::string e = fmt("ERROR: requestStart: %s", oboe::convertToText(r));
            stopLocked();
            return e;
        }
        state_.store(kTone);
        return "TONE 440 Hz\n" + describe();
    }

    // Returns and clears the last decoder/stream message.
    std::string stop() {
        std::lock_guard<std::mutex> lk(mtx_);
        stopLocked();
        return takeMessage();
    }

    void setPaused(bool p) {
        paused_.store(p);
        int s = state_.load();
        if (p && s == kPlaying) state_.store(kPaused);
        else if (!p && s == kPaused) state_.store(kPlaying);
    }

    // [state, posMs, durMs, underruns, bufferedMs]
    std::array<int64_t, 5> status() const {
        const int64_t rate = std::max(1, rate_.load());
        return {state_.load(), played_.load() * 1000 / rate, durMs_.load(), underruns_.load(),
                static_cast<int64_t>(ring_.size()) * 1000 / rate};
    }

    // ---- Oboe RT thread. No locks, no allocation, no logging here. ----
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t numFrames) override {
        auto* out = static_cast<float*>(data);

        if (tone_.load(std::memory_order_relaxed)) {
            const double inc = kTwoPi * 440.0 / static_cast<double>(rate_.load(std::memory_order_relaxed));
            for (int32_t i = 0; i < numFrames; ++i) {
                const float s = 0.2f * static_cast<float>(std::sin(phase_));
                out[2 * i] = s;
                out[2 * i + 1] = s;
                phase_ += inc;
                if (phase_ >= kTwoPi) phase_ -= kTwoPi;
            }
            played_.fetch_add(numFrames, std::memory_order_relaxed);
            return oboe::DataCallbackResult::Continue;
        }

        if (paused_.load(std::memory_order_relaxed)) {
            std::memset(out, 0, static_cast<size_t>(numFrames) * 2 * sizeof(float));
            return oboe::DataCallbackResult::Continue;
        }

        // Load "done" BEFORE reading: if it was already set and we still come
        // up short, the ring is genuinely drained (end of file).
        const bool done = decodeDone_.load(std::memory_order_acquire);
        const size_t got = ring_.read(out, static_cast<size_t>(numFrames));
        if (got < static_cast<size_t>(numFrames)) {
            std::memset(out + got * 2, 0, (static_cast<size_t>(numFrames) - got) * 2 * sizeof(float));
            if (done) {
                played_.fetch_add(static_cast<int64_t>(got), std::memory_order_relaxed);
                int expected = kPlaying;
                state_.compare_exchange_strong(expected, kEnded);
                return oboe::DataCallbackResult::Stop;
            }
            underruns_.fetch_add(1, std::memory_order_relaxed);
        }
        played_.fetch_add(static_cast<int64_t>(got), std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

    // Device unplugged / stream torn down by the system. Only touch atomics.
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        setMessage(fmt("STREAM ERROR after close: %s", oboe::convertToText(error)));
        state_.store(kError);
    }

    static std::string probeOutput() {
        std::string out;
        line(out, "oboe %s", oboe::getVersionText());
        line(out, "AAudio supported=%d recommended=%d", oboe::AudioStreamBuilder::isAAudioSupported() ? 1 : 0,
             oboe::AudioStreamBuilder::isAAudioRecommended() ? 1 : 0);
        const oboe::SharingMode modes[] = {oboe::SharingMode::Shared, oboe::SharingMode::Exclusive};
        for (oboe::SharingMode m : modes) {
            oboe::AudioStreamBuilder b;
            b.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(m)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Music);
            std::shared_ptr<oboe::AudioStream> s;
            const oboe::Result r = b.openStream(s);
            if (r != oboe::Result::OK) {
                line(out, "[%s requested] open FAILED: %s", oboe::convertToText(m), oboe::convertToText(r));
                continue;
            }
            line(out, "[%s requested] api=%s share=%s perf=%s", oboe::convertToText(m),
                 oboe::convertToText(s->getAudioApi()), oboe::convertToText(s->getSharingMode()),
                 oboe::convertToText(s->getPerformanceMode()));
            line(out, "   rate=%d ch=%d fmt=%s dev=%d", s->getSampleRate(), s->getChannelCount(),
                 oboe::convertToText(s->getFormat()), s->getDeviceId());
            line(out, "   burst=%d frames  buffer=%d / capacity=%d frames", s->getFramesPerBurst(),
                 s->getBufferSizeInFrames(), s->getBufferCapacityInFrames());
            s->close();
        }
        return out;
    }

private:
    std::string openStream() {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(shared_from_this())
            ->setErrorCallback(shared_from_this());
        const oboe::Result r = b.openStream(stream_);
        if (r != oboe::Result::OK) {
            stream_.reset();
            return fmt("oboe openStream: %s", oboe::convertToText(r));
        }
        rate_.store(stream_->getSampleRate());
        return {};
    }

    std::string describe() const {
        std::string out;
        line(out, "oboe    : %s  api=%s  %d Hz  %d ch  %s", oboe::getVersionText(),
             oboe::convertToText(stream_->getAudioApi()), stream_->getSampleRate(), stream_->getChannelCount(),
             oboe::convertToText(stream_->getFormat()));
        line(out, "stream  : burst=%d  buffer=%d/%d frames  perf=%s  share=%s  dev=%d", stream_->getFramesPerBurst(),
             stream_->getBufferSizeInFrames(), stream_->getBufferCapacityInFrames(),
             oboe::convertToText(stream_->getPerformanceMode()), oboe::convertToText(stream_->getSharingMode()),
             stream_->getDeviceId());
        return out;
    }

    void stopLocked() {
        stop_.store(true);
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
        if (thread_.joinable()) thread_.join();
        in_.reset();
        tone_ = false;
        paused_ = false;
        state_.store(kIdle);
    }

    void setMessage(const std::string& m) {
        std::lock_guard<std::mutex> lk(msgMtx_);
        msg_ = m;
    }
    std::string takeMessage() {
        std::lock_guard<std::mutex> lk(msgMtx_);
        std::string m;
        m.swap(msg_);
        return m;
    }

    std::mutex mtx_;  // serialises play/stop/tone (JNI callers)
    std::mutex msgMtx_;
    std::string msg_;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<Input> in_;
    std::thread thread_;
    Ring ring_;

    std::atomic<bool> stop_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> decodeDone_{false};
    std::atomic<bool> tone_{false};
    std::atomic<int> state_{kIdle};
    std::atomic<int> rate_{48000};
    std::atomic<int64_t> played_{0};
    std::atomic<int64_t> underruns_{0};
    std::atomic<int64_t> durMs_{0};
    double phase_ = 0.0;  // audio thread only
};

std::shared_ptr<Player>& player() {
    static std::shared_ptr<Player> p = std::make_shared<Player>();
    return p;
}

// ------------------------------------------------------------ FFmpeg tests --

std::string ffmpegVersions() {
    std::string out;
    line(out, "ffmpeg %s  (%s)", av_version_info(), avcodec_license());
    auto ver = [&](const char* n, unsigned v) {
        line(out, "  %-12s %u.%u.%u", n, AV_VERSION_MAJOR(v), AV_VERSION_MINOR(v), AV_VERSION_MICRO(v));
    };
    ver("libavutil", avutil_version());
    ver("libavcodec", avcodec_version());
    ver("libavformat", avformat_version());
    ver("libswresample", swresample_version());

    out += "decoders:";
    const char* decs[] = {"mp3float", "mp3", "aac", "aac_fixed", "flac", "vorbis", "opus", "libopus", "alac",
                          "pcm_s16le", "pcm_s24le", "pcm_f32le", "wavpack", "ape", "wmav2", "ac3", "eac3"};
    for (const char* d : decs) out += fmt(" %s=%c", d, avcodec_find_decoder_by_name(d) ? 'Y' : '-');
    out += "\ndemuxers:";
    const char* dems[] = {"mp3", "wav", "flac", "ogg", "mp4", "matroska", "aac", "ac3", "ape", "wv"};
    for (const char* d : dems) out += fmt(" %s=%c", d, av_find_input_format(d) ? 'Y' : '-');
    out += '\n';
    line(out, "oboe %s", oboe::getVersionText());
    return out;
}

std::string ffmpegProbe(int fd) {
    std::string out;
    Input in;
    std::string err = in.openContainer(fd);
    if (!err.empty()) return "ERROR: " + err;

    const AVFormatContext* f = in.fmtCtx;
    line(out, "container: %s (%s)", f->iformat->name, f->iformat->long_name ? f->iformat->long_name : "?");
    line(out, "size     : %lld bytes   seekable=%d", static_cast<long long>(in.src.size),
         (f->pb && (f->pb->seekable & AVIO_SEEKABLE_NORMAL)) ? 1 : 0);
    line(out, "duration : %s   bitrate: %lld kb/s", hms(f->duration != AV_NOPTS_VALUE ? f->duration / 1000 : -1).c_str(),
         static_cast<long long>(f->bit_rate / 1000));

    for (unsigned i = 0; i < f->nb_streams; ++i) {
        const AVStream* s = f->streams[i];
        const AVCodecParameters* p = s->codecpar;
        const char* type = av_get_media_type_string(p->codec_type);
        std::string l = fmt("stream #%u: %s %s", i, type ? type : "?", avcodec_get_name(p->codec_id));
        if (p->codec_type == AVMEDIA_TYPE_AUDIO) {
            char lay[64] = {};
            av_channel_layout_describe(&p->ch_layout, lay, sizeof(lay));
            const char* sf = p->format >= 0 ? av_get_sample_fmt_name(static_cast<AVSampleFormat>(p->format)) : nullptr;
            l += fmt("  %d Hz  %d ch (%s)  fmt=%s  %d bit  %lld kb/s", p->sample_rate, p->ch_layout.nb_channels, lay,
                     sf ? sf : "?", p->bits_per_coded_sample, static_cast<long long>(p->bit_rate / 1000));
        } else if (p->codec_type == AVMEDIA_TYPE_VIDEO) {
            l += fmt("  %dx%d%s", p->width, p->height, (s->disposition & AV_DISPOSITION_ATTACHED_PIC) ? "  [cover art]" : "");
        }
        out += l + '\n';
    }

    int tags = 0;
    const AVDictionaryEntry* e = nullptr;
    while ((e = av_dict_get(f->metadata, "", e, AV_DICT_IGNORE_SUFFIX)) && tags < 24) {
        std::string v = e->value;
        if (v.size() > 120) v = v.substr(0, 120) + "...";
        line(out, "tag %-12s = %s", e->key, v.c_str());
        ++tags;
    }

    err = in.openDecoder();
    if (!err.empty()) {
        line(out, "DECODER: FAIL - %s", err.c_str());
    } else {
        char lay[64] = {};
        av_channel_layout_describe(&in.dec->ch_layout, lay, sizeof(lay));
        line(out, "DECODER: OK  stream #%d  %s (%s)  %d Hz  %s  %s", in.stream, in.dec->codec->name,
             in.dec->codec->long_name ? in.dec->codec->long_name : "", in.dec->sample_rate, lay,
             av_get_sample_fmt_name(in.dec->sample_fmt) ? av_get_sample_fmt_name(in.dec->sample_fmt) : "?");
    }
    return out;
}

std::string ffmpegDecodeTest(int fd) {
    Input in;
    const std::string err = in.open(fd);
    if (!err.empty()) return "ERROR: " + err;

    constexpr int kRate = 48000;
    const std::atomic<bool> never{false};
    const auto t0 = std::chrono::steady_clock::now();
    const DecodeStats st = decodeLoop(in, kRate, never, [](const float*, int) { return true; });
    const double wallMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();

    const double audioSec = static_cast<double>(st.framesOut) / kRate;
    const double rms = st.framesOut > 0 ? std::sqrt(st.sumSq / (static_cast<double>(st.framesOut) * 2.0)) : 0.0;
    std::string out;
    line(out, "%s  %s  %d Hz  %d ch", st.error.empty() ? "DECODE OK" : "DECODE FAILED", in.dec->codec->name,
         in.dec->sample_rate, in.dec->ch_layout.nb_channels);
    line(out, "packets : %lld (bad %lld)", static_cast<long long>(st.packets), static_cast<long long>(st.badPackets));
    line(out, "output  : %lld frames = %.2f s  (float stereo @ %d Hz)", static_cast<long long>(st.framesOut), audioSec, kRate);
    line(out, "time    : %.1f ms  =  %.1fx realtime", wallMs, wallMs > 0 ? audioSec * 1000.0 / wallMs : 0.0);
    line(out, "peak    : %.2f dBFS   rms: %.2f dBFS", st.peak > 0 ? 20.0 * std::log10(st.peak) : -999.0,
         rms > 0 ? 20.0 * std::log10(rms) : -999.0);
    if (!st.error.empty()) line(out, "error   : %s", st.error.c_str());
    if (st.framesOut > 0 && st.peak == 0.0) line(out, "WARNING : output is pure digital silence");
    return out;
}


// ------------------------------------------------------------ BPM detect ---

struct BpmParams {
    double minBpm = 60.0;
    double maxBpm = 200.0;
    double analysisSec = 90.0;  // audio fed to the detector
    double skipSec = 0.0;       // skipped from the start (intros, silence)
    int fftSize = 2048;
    int hop = 512;
};

std::string bpmDetect(int fd, BpmParams p) {
    // Sanitise: the detector assumes a power-of-two FFT and min < max.
    p.fftSize = std::clamp(p.fftSize, 512, 8192);
    if ((p.fftSize & (p.fftSize - 1)) != 0) {
        int v = 512;
        while (v < p.fftSize) v <<= 1;
        p.fftSize = v;
    }
    p.hop = std::clamp(p.hop, 64, p.fftSize);
    p.minBpm = std::clamp(p.minBpm, 30.0, 290.0);
    p.maxBpm = std::clamp(p.maxBpm, p.minBpm + 10.0, 300.0);
    p.analysisSec = std::clamp(p.analysisSec, 10.0, 600.0);
    p.skipSec = std::clamp(p.skipSec, 0.0, 600.0);

    Input in;
    const std::string err = in.open(fd);
    if (!err.empty()) return "ERROR: " + err;

    constexpr int kRate = 22050;  // plenty for onset flux, ~2x cheaper than 44.1k
    fox::BpmConfig cfg;
    cfg.sampleRate = kRate;
    cfg.fftSize = p.fftSize;
    cfg.hop = p.hop;
    cfg.maxSeconds = p.analysisSec;
    cfg.minBpm = p.minBpm;
    cfg.maxBpm = p.maxBpm;
    fox::BpmDetector det(cfg);

    int64_t skipLeft = static_cast<int64_t>(p.skipSec * kRate);
    std::vector<float> mono;
    const std::atomic<bool> never{false};

    const auto t0 = std::chrono::steady_clock::now();
    const DecodeStats st = decodeLoop(in, kRate, never, [&](const float* d, int frames) {
        int start = 0;
        if (skipLeft > 0) {
            start = static_cast<int>(std::min<int64_t>(skipLeft, frames));
            skipLeft -= start;
            if (start == frames) return true;
        }
        const int n = frames - start;
        mono.resize(static_cast<size_t>(n));
        for (int i = 0; i < n; ++i) {
            const float* s = d + static_cast<size_t>(start + i) * 2;
            mono[static_cast<size_t>(i)] = 0.5f * (s[0] + s[1]);
        }
        return det.push(mono.data(), mono.size());  // false at maxSeconds -> decode aborts
    });
    const double decodeMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();

    const auto t1 = std::chrono::steady_clock::now();
    const fox::BpmResult r = det.finish();
    const double detectMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t1).count();

    std::string out;
    if (!st.error.empty()) line(out, "DECODE ERROR: %s", st.error.c_str());
    if (!r.valid) {
        line(out, "BPM FAILED: %s", r.reason.empty() ? "unknown" : r.reason.c_str());
    } else {
        line(out, "BPM %.2f   confidence %.0f%%   stability %.0f%%", r.bpm, r.confidence * 100.0, r.stability * 100.0);
        for (size_t i = 1; i < r.candidates.size(); ++i) {
            line(out, "  alt %.2f  (%.0f%% of best)", r.candidates[i].bpm, r.candidates[i].relScore * 100.0);
        }
    }
    line(out, "analysed %.1f s of max %.0f s (skip %.1f s)  peaks %d", r.seconds, p.analysisSec, p.skipSec, r.peaks);
    line(out, "range %.0f-%.0f BPM  fft %d  hop %d  @ %d Hz", p.minBpm, p.maxBpm, p.fftSize, p.hop, kRate);
    line(out, "decode %.0f ms  detect %.0f ms", decodeMs, detectMs);
    return out;
}

}  // namespace

// ------------------------------------------------------------------- JNI ---

#define FOX_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_fox_foxiru_foxcat_fox2d_jnicallers_AudioNative_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    logReport(ffmpegVersions());
    return JNI_VERSION_1_6;
}

FOX_JNI(jstring, versions)(JNIEnv* env, jobject) {
    const std::string s = ffmpegVersions();
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, probe)(JNIEnv* env, jobject, jint fd) {
    const std::string s = ffmpegProbe(fd);
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, decodeTest)(JNIEnv* env, jobject, jint fd) {
    const std::string s = ffmpegDecodeTest(fd);
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, detectBpm)(JNIEnv* env, jobject, jint fd, jdouble minBpm, jdouble maxBpm, jdouble analysisSec,
                            jdouble skipSec, jint fftSize, jint hop) {
    BpmParams p;
    p.minBpm = minBpm;
    p.maxBpm = maxBpm;
    p.analysisSec = analysisSec;
    p.skipSec = skipSec;
    p.fftSize = fftSize;
    p.hop = hop;
    const std::string s = bpmDetect(fd, p);
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, oboeProbe)(JNIEnv* env, jobject) {
    const std::string s = Player::probeOutput();
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, play)(JNIEnv* env, jobject, jint fd) {
    const std::string s = player()->play(fd);
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, toneStart)(JNIEnv* env, jobject) {
    const std::string s = player()->startTone();
    logReport(s);
    return toJString(env, s);
}

FOX_JNI(jstring, stop)(JNIEnv* env, jobject) {
    const std::string s = player()->stop();
    if (!s.empty()) logReport(s);
    return toJString(env, s);
}

FOX_JNI(void, setPaused)(JNIEnv*, jobject, jboolean paused) {
    player()->setPaused(paused == JNI_TRUE);
}

FOX_JNI(jlongArray, status)(JNIEnv* env, jobject) {
    const auto st = player()->status();
    jlongArray arr = env->NewLongArray(5);
    if (!arr) return nullptr;
    jlong v[5];
    for (int i = 0; i < 5; ++i) v[i] = static_cast<jlong>(st[static_cast<size_t>(i)]);
    env->SetLongArrayRegion(arr, 0, 5, v);
    return arr;
}
