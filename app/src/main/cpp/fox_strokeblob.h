// fox_strokeblob.h - strokes.bin (gzip, big-endian, see ProjectCodec.writeStrokes) parsed in C++, no JNI in here.
//
//   "F2S1" int32 magic, int32 version (1|2|3), int32 count, then per stroke:
//     int32 cel, int32 layer, int32 argb, float size, u8 erase,
//     [version >= 2] u8 isImage,
//        isImage : u16 len + len bytes (file name), 5 x float (cx cy w h rot)
//        else    : int32 pointCount, pointCount x (float x, float y),
//                  [version >= 3] u8 engine,
//                     engine : u16 paramCount (1..kMaxParams), paramCount x float  (Brush Studio array, layout = BP in BrushUI.kt,
//                              the stroke's random seed included), then pointCount x (float pressure, float ms)
//                              (ms = since the stroke began). engine = 0: classic capsule pen, nothing follows.
//   Version 1 / 2 files load unchanged (every stroke is classic). A v3 file is not readable by builds that stop at v2.
//
// The result is a handful of flat arrays (no per-stroke objects), shared by the JNI readers (fox_project.cpp) and the
// canvas feeder (fox_canvas.cpp: nativeAddStrokesFromBlob), so the points never travel native -> Java -> native.
#pragma once

#include <zlib.h>

#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace fox::proj {

constexpr int32_t kMagic = 0x46325331;        // "F2S1"
constexpr int32_t kVersion = 3;               // newest layout this parser reads
constexpr int kMaxParams = 64;                // engine parameter floats per stroke (the engine uses 35)
constexpr int32_t kMaxStrokes = 50'000'000;   // same limits as the Kotlin reader
constexpr int32_t kMaxPoints = 10'000'000;
constexpr int kMetaStride = 6;                // per stroke: cel, layer, argb, flags, a, b
constexpr int kFlagErase = 1, kFlagImage = 2, kFlagEngine = 4;   // meta[3]; kFlagEngine: Blob::eng describes the stroke
constexpr int kEngStride = 3;                 // per stroke in Blob::eng: index in prm, parameter count, index in aux
//   plain stroke: a = index of its first float in pts, b = point count
//   image stroke: a = index in files, b = index of its first float in img (5 floats)

struct Blob {
    int32_t count = 0;
    std::vector<int32_t> meta;     // count * kMetaStride
    std::vector<float> sizes;      // count (0 for images)
    std::vector<float> pts;        // xy pairs of every plain stroke back to back
    std::vector<std::string> files;   // distinct image file names, first-use order
    std::vector<float> img;        // 5 floats per image stroke, stroke order
    // Brush Studio strokes (kFlagEngine). eng has count * kEngStride entries; {-1, 0, -1} = classic stroke / image.
    std::vector<int32_t> eng;
    std::vector<float> prm;        // parameter arrays of the engine strokes, back to back
    std::vector<float> aux;        // (pressure, ms) per point of every engine stroke, back to back (stroke order == its pts)
};

namespace detail {

struct GzReader {
    gzFile f = nullptr;
    std::vector<uint8_t> buf = std::vector<uint8_t>(256 * 1024);
    size_t pos = 0, len = 0;

    bool fill() {
        const int n = gzread(f, buf.data(), (unsigned) buf.size());
        if (n <= 0) return false;
        pos = 0; len = (size_t) n;
        return true;
    }
    bool read(void* dst, size_t n) {
        auto* d = static_cast<uint8_t*>(dst);
        while (n) {
            if (pos == len && !fill()) return false;
            const size_t c = std::min(n, len - pos);
            std::memcpy(d, buf.data() + pos, c);
            pos += c; d += c; n -= c;
        }
        return true;
    }
    bool u8(uint8_t& v) { return read(&v, 1); }
    bool u16(uint16_t& v) { uint8_t b[2]; if (!read(b, 2)) return false; v = (uint16_t) (b[0] << 8 | b[1]); return true; }
    bool i32(int32_t& v) {
        uint8_t b[4];
        if (!read(b, 4)) return false;
        v = (int32_t) ((uint32_t) b[0] << 24 | (uint32_t) b[1] << 16 | (uint32_t) b[2] << 8 | (uint32_t) b[3]);
        return true;
    }
    bool f32(float& v) { int32_t i; if (!i32(i)) return false; const uint32_t u = (uint32_t) i; std::memcpy(&v, &u, 4); return true; }
};

inline void bswapFloats(float* p, size_t n) {   // big-endian file -> host (all Android ABIs are little-endian)
    auto* u = reinterpret_cast<uint32_t*>(p);
    for (size_t i = 0; i < n; i++) u[i] = __builtin_bswap32(u[i]);
}

}  // namespace detail

// nullptr + [err] on any problem (never throws: the project builds with -fno-exceptions).
inline std::shared_ptr<Blob> parseStrokesFile(const char* path, std::string& err) {
    detail::GzReader r;
    r.f = gzopen(path, "rb");
    if (!r.f) { err = "cannot open strokes file"; return nullptr; }
    struct Close { gzFile f; ~Close() { gzclose(f); } } closer{r.f};

    auto b = std::make_shared<Blob>();
    int32_t magic = 0, ver = 0, n = 0;
    if (!r.i32(magic) || magic != kMagic) { err = "Not a stroke file"; return nullptr; }
    if (!r.i32(ver) || !r.i32(n)) { err = "truncated header"; return nullptr; }
    if (ver < 1 || ver > kVersion) { err = "Unsupported stroke file version"; return nullptr; }
    if (n < 0 || n > kMaxStrokes) { err = "Corrupt stroke count"; return nullptr; }

    const size_t hint = std::min<size_t>((size_t) n, 1u << 20);
    b->meta.reserve(hint * kMetaStride);
    b->sizes.reserve(hint);
    std::unordered_map<std::string, int32_t> fileIdx;
    std::string name;

    for (int32_t i = 0; i < n; i++) {
        int32_t cel, layer, argb;
        float size;
        uint8_t erase;
        if (!r.i32(cel) || !r.i32(layer) || !r.i32(argb) || !r.f32(size) || !r.u8(erase)) { err = "truncated stroke"; return nullptr; }
        uint8_t isImg = 0;
        if (ver >= 2 && !r.u8(isImg)) { err = "truncated stroke"; return nullptr; }

        int32_t flags = erase ? kFlagErase : 0, a = 0, cnt = 0;
        int32_t eng[kEngStride] = {-1, 0, -1};
        if (isImg) {
            uint16_t len;
            if (!r.u16(len)) { err = "truncated image"; return nullptr; }
            name.resize(len);
            if (len && !r.read(&name[0], len)) { err = "truncated image"; return nullptr; }
            auto it = fileIdx.find(name);
            if (it == fileIdx.end()) { it = fileIdx.emplace(name, (int32_t) b->files.size()).first; b->files.push_back(name); }
            a = it->second;
            cnt = (int32_t) b->img.size();
            float v[5];
            for (float& x : v) if (!r.f32(x)) { err = "truncated image"; return nullptr; }
            b->img.insert(b->img.end(), v, v + 5);
            flags |= kFlagImage;
            size = 0.f;
        } else {
            int32_t pc;
            if (!r.i32(pc)) { err = "truncated stroke"; return nullptr; }
            if (pc < 0 || pc > kMaxPoints) { err = "Corrupt point count"; return nullptr; }
            const size_t at = b->pts.size();
            if (at + (size_t) pc * 2 > (size_t) INT32_MAX) { err = "too many points"; return nullptr; }
            b->pts.resize(at + (size_t) pc * 2);
            if (pc && !r.read(b->pts.data() + at, (size_t) pc * 8)) { err = "truncated points"; return nullptr; }
            detail::bswapFloats(b->pts.data() + at, (size_t) pc * 2);
            a = (int32_t) at; cnt = pc;
            if (ver >= 3) {
                uint8_t hasEng = 0;
                if (!r.u8(hasEng)) { err = "truncated stroke"; return nullptr; }
                if (hasEng) {
                    uint16_t np;
                    if (!r.u16(np)) { err = "truncated brush parameters"; return nullptr; }
                    if (np == 0 || np > kMaxParams) { err = "Corrupt brush parameters"; return nullptr; }
                    const size_t pat = b->prm.size();
                    b->prm.resize(pat + np);
                    for (size_t k = 0; k < np; k++) if (!r.f32(b->prm[pat + k])) { err = "truncated brush parameters"; return nullptr; }
                    const size_t aat = b->aux.size();
                    if (aat + (size_t) pc * 2 > (size_t) INT32_MAX) { err = "too many points"; return nullptr; }
                    b->aux.resize(aat + (size_t) pc * 2);
                    if (pc && !r.read(b->aux.data() + aat, (size_t) pc * 8)) { err = "truncated brush samples"; return nullptr; }
                    detail::bswapFloats(b->aux.data() + aat, (size_t) pc * 2);
                    eng[0] = (int32_t) pat; eng[1] = (int32_t) np; eng[2] = (int32_t) aat;
                    flags |= kFlagEngine;
                }
            }
        }
        const int32_t m[kMetaStride] = {cel, layer, argb, flags, a, cnt};
        b->meta.insert(b->meta.end(), m, m + kMetaStride);
        b->sizes.push_back(size);
        b->eng.insert(b->eng.end(), eng, eng + kEngStride);
    }
    b->count = n;
    return b;
}

// ---- handle registry (any thread)
inline std::mutex& regMu() { static std::mutex m; return m; }
inline std::unordered_map<int64_t, std::shared_ptr<Blob>>& reg() { static std::unordered_map<int64_t, std::shared_ptr<Blob>> r; return r; }

inline int64_t put(std::shared_ptr<Blob> b) {
    std::lock_guard<std::mutex> l(regMu());
    static int64_t next = 1;
    const int64_t h = next++;
    reg()[h] = std::move(b);
    return h;
}
inline std::shared_ptr<Blob> get(int64_t h) {
    std::lock_guard<std::mutex> l(regMu());
    auto it = reg().find(h);
    return it == reg().end() ? nullptr : it->second;
}
inline void drop(int64_t h) {
    std::lock_guard<std::mutex> l(regMu());
    reg().erase(h);
}

}  // namespace fox::proj
