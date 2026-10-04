#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace fox {

struct BpmConfig {
    double sampleRate = 22050.0;  // rate of the mono samples passed to push()
    int fftSize = 2048;           // STFT window (rounded up to a power of two, >= 256)
    int hop = 512;                // STFT hop, 0 < hop <= fftSize
    double maxSeconds = 90.0;     // upper bound of audio analysed (measured after leading silence)
    double minBpm = 60.0;
    double maxBpm = 200.0;
    bool trimSilence = true;      // ignore digital/near silence at the start of the stream
    bool earlyStop = true;        // push() returns false as soon as the estimate has converged
};

struct BpmCandidate {
    double bpm = 0.0;
    double relScore = 0.0;  // score / best score, best == 1.0
};

struct BpmResult {
    bool valid = false;
    double bpm = 0.0;
    double confidence = 0.0;  // 0..1, peak sharpness x window agreement
    double stability = 0.0;   // 0..1, fraction of sliding windows agreeing with bpm (+-0.8 %)
    double seconds = 0.0;     // audio actually analysed
    int peaks = 0;            // onset peaks used by the tempogram
    std::string reason;       // set when !valid
    std::vector<BpmCandidate> candidates;  // strongest first, max 4
};

// Streaming: push() mono float PCM until it returns false (or the source ends), then finish().
class BpmDetector {
public:
    explicit BpmDetector(const BpmConfig& cfg);
    ~BpmDetector();
    BpmDetector(const BpmDetector&) = delete;
    BpmDetector& operator=(const BpmDetector&) = delete;

    // Returns false when no more audio is needed (maxSeconds reached or estimate converged).
    bool push(const float* mono, size_t n);
    BpmResult finish();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

}  // namespace fox
