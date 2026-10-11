// fox_celcache.h : per-drawing raster cache ("FlipaClip style"), no GL, no JNI, header only.
//
// A cel's finished picture is kept as a premultiplied RGBA8 bitmap cropped to its content box and RLE-compressed (transparent paper
// costs ~nothing). Three tiers, all handled on a worker thread so the GL thread never compresses or waits for the disk:
//   RAM raw   : just read back from the GPU, waiting for the worker to compress it   (usable immediately)
//   RAM comp  : compressed bitmap in memory                                          (the hot set, bounded by the RAM budget)
//   disk      : spilled least-recently-used bitmaps, one file each in the cache dir  (the "ROM", thousands of frames)
// take() hands the GL thread decoded pixels for glTexSubImage2D; prefetch() pulls disk entries back into RAM ahead of playback.
// The cache is only a speed-up: the stroke history stays the source of truth, so a missing entry just means "replay the strokes".
#pragma once

#include <algorithm>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

namespace fox::cache {

// ------------------------------------------------------------------------------------------------ codec
// Stream of control bytes:  0x00-0x7F literal (c+1) pixels follow | 0x80-0xFE repeat next pixel (c&0x7F)+2 times | 0xFF u32 n transparent pixels

inline void rleEncode(const uint32_t* px, size_t n, std::vector<uint8_t>& o) {
    o.clear();
    o.reserve(n / 16 + 64);
    auto put32 = [&](uint32_t v) { uint8_t b[4]; std::memcpy(b, &v, 4); o.insert(o.end(), b, b + 4); };
    size_t i = 0;
    while (i < n) {
        size_t r = 1;
        while (i + r < n && px[i + r] == px[i]) r++;
        if (r >= 2) {
            if (px[i] == 0) {
                o.push_back(0xFF);
                put32((uint32_t) r);
            } else {
                size_t left = r;
                while (left > 0) {
                    const size_t k = std::min<size_t>(left, 128);
                    if (k >= 2) { o.push_back((uint8_t) (0x80 | (k - 2))); put32(px[i]); }
                    else { o.push_back(0); put32(px[i]); }
                    left -= k;
                }
            }
            i += r;
        } else {
            size_t j = i + 1;
            while (j < n && j - i < 128 && !(j + 1 < n && px[j] == px[j + 1])) j++;
            o.push_back((uint8_t) (j - i - 1));
            for (size_t k = i; k < j; k++) put32(px[k]);
            i = j;
        }
    }
}

inline bool rleDecode(const uint8_t* in, size_t len, uint32_t* px, size_t n) {
    size_t i = 0, o = 0;
    while (i < len) {
        const uint8_t c = in[i++];
        if (c == 0xFF) {
            if (i + 4 > len) return false;
            uint32_t cnt; std::memcpy(&cnt, in + i, 4); i += 4;
            if (cnt > n - o) return false;
            std::memset(px + o, 0, (size_t) cnt * 4); o += cnt;
        } else if (c & 0x80) {
            const size_t k = (size_t) (c & 0x7F) + 2;
            if (i + 4 > len || k > n - o) return false;
            uint32_t v; std::memcpy(&v, in + i, 4); i += 4;
            for (size_t q = 0; q < k; q++) px[o++] = v;
        } else {
            const size_t k = (size_t) c + 1;
            if (i + 4 * k > len || k > n - o) return false;
            std::memcpy(px + o, in + i, 4 * k); i += 4 * k; o += k;
        }
    }
    return o == n;
}

// ------------------------------------------------------------------------------------------------ cache

using Pixels = std::shared_ptr<const std::vector<uint32_t>>;
using Bytes = std::shared_ptr<const std::vector<uint8_t>>;

struct Raster {
    int x = 0, y = 0, w = 0, h = 0;   // content box in layer pixels; w == 0: the drawing is empty
    Pixels px;                        // w * h pixels, rows bottom-up like the GL texture
};

class Cache {
public:
    static Cache& get() { static Cache* c = new Cache(); return *c; }   // never destroyed: no thread-join at process exit

    /** [dir] = where spilled bitmaps go (wiped now: a previous run's files are never trusted); [ramBytes] = budget for the in-RAM bitmaps. */
    void configure(const std::string& dir, size_t ramBytes) {
        std::lock_guard<std::mutex> l(mu_);
        budget_ = ramBytes;
        if (dir != dir_) {
            dir_ = dir;
            if (!dir_.empty()) { ::mkdir(dir_.c_str(), 0700); wipeFiles_(); }
        }
        if (!started_) { started_ = true; std::thread([this] { worker_(); }).detach(); }
        cv_.notify_all();
    }

    /** Store a freshly read-back picture (cropped to [x,y,w,h], layer size [T]); compressed + spilled in the background. */
    void put(uint64_t key, int T, int x, int y, int w, int h, std::vector<uint32_t>&& px) {
        std::lock_guard<std::mutex> l(mu_);
        eraseLocked_(key);
        Entry e;
        e.serial = ++serial_; e.T = T; e.x = x; e.y = y; e.w = w; e.h = h; e.tick = ++tick_;
        e.raw = std::make_shared<std::vector<uint32_t>>(std::move(px));
        ram_ += e.raw->size() * 4;
        const uint64_t s = e.serial;
        map_[key] = std::move(e);
        jobs_.push_back({Op::Compress, key, s});
        cv_.notify_all();
    }

    void putEmpty(uint64_t key, int T) {
        std::lock_guard<std::mutex> l(mu_);
        eraseLocked_(key);
        Entry e;
        e.serial = ++serial_; e.T = T; e.tick = ++tick_;
        map_[key] = std::move(e);
    }

    /** Decoded pixels of a cached drawing. false = not cached (replay the strokes instead). May read the disk file (small). */
    bool take(uint64_t key, int T, Raster& out) {
        Pixels raw; Bytes comp; std::string path; uint64_t serial = 0;
        {
            std::lock_guard<std::mutex> l(mu_);
            auto it = map_.find(key);
            if (it == map_.end() || it->second.T != T) return false;
            Entry& e = it->second;
            e.tick = ++tick_;
            out.x = e.x; out.y = e.y; out.w = e.w; out.h = e.h;
            if (e.w <= 0 || e.h <= 0) { out.px.reset(); return true; }
            if (e.raw) raw = e.raw;
            else if (e.comp) comp = e.comp;
            else if (e.onDisk) { path = pathOf_(key); serial = e.serial; }
            else return false;
        }
        if (!raw) {
            if (!comp) {
                auto b = std::make_shared<std::vector<uint8_t>>();
                if (!readFile_(path, *b)) { dropIf_(key, serial); return false; }
                comp = b;
                std::lock_guard<std::mutex> l(mu_);
                auto it = map_.find(key);
                if (it != map_.end() && it->second.serial == serial && !it->second.comp && !it->second.raw) {
                    it->second.comp = comp; ram_ += comp->size();
                    jobs_.push_back({Op::Trim, 0, 0}); cv_.notify_all();
                }
            }
            auto px = std::make_shared<std::vector<uint32_t>>((size_t) out.w * (size_t) out.h);
            if (!rleDecode(comp->data(), comp->size(), px->data(), px->size())) { drop(key); return false; }
            raw = px;
        }
        out.px = raw;
        return true;
    }

    bool has(uint64_t key, int T) {
        std::lock_guard<std::mutex> l(mu_);
        auto it = map_.find(key);
        return it != map_.end() && it->second.T == T;
    }

    void drop(uint64_t key) { std::lock_guard<std::mutex> l(mu_); eraseLocked_(key); }

    void clear() {
        std::lock_guard<std::mutex> l(mu_);
        map_.clear(); ram_ = 0;
        jobs_.clear();
        wipeFiles_();
    }

    /** Pull a spilled drawing back into RAM (worker thread) so take() finds it there. */
    void prefetch(uint64_t key) {
        std::lock_guard<std::mutex> l(mu_);
        auto it = map_.find(key);
        if (it == map_.end() || !it->second.onDisk || it->second.comp || it->second.raw) return;
        it->second.tick = ++tick_;
        jobs_.push_back({Op::Load, key, it->second.serial});
        cv_.notify_all();
    }

    size_t ramBytes() { std::lock_guard<std::mutex> l(mu_); return ram_; }

private:
    enum class Op { Compress, Load, Trim };
    struct Job { Op op; uint64_t key; uint64_t serial; };
    struct Entry {
        uint64_t serial = 0, tick = 0;
        int T = 0, x = 0, y = 0, w = 0, h = 0;
        Pixels raw;      // uncompressed, until the worker has compressed it
        Bytes comp;      // compressed, in RAM
        bool onDisk = false;
    };

    std::mutex mu_;
    std::condition_variable cv_;
    std::unordered_map<uint64_t, Entry> map_;
    std::deque<Job> jobs_;
    std::string dir_;
    size_t budget_ = (size_t) 192 << 20, ram_ = 0;
    uint64_t serial_ = 0, tick_ = 0;
    bool started_ = false;

    std::string pathOf_(uint64_t key) const {
        char b[40];
        std::snprintf(b, sizeof b, "/%016llx.fxr", (unsigned long long) key);
        return dir_ + b;
    }

    static size_t bytesOf_(const Entry& e) { return (e.raw ? e.raw->size() * 4 : 0) + (e.comp ? e.comp->size() : 0); }

    void eraseLocked_(uint64_t key) {
        auto it = map_.find(key);
        if (it == map_.end()) return;
        ram_ -= std::min(ram_, bytesOf_(it->second));
        if (it->second.onDisk && !dir_.empty()) ::unlink(pathOf_(key).c_str());
        map_.erase(it);
    }

    void dropIf_(uint64_t key, uint64_t serial) {
        std::lock_guard<std::mutex> l(mu_);
        auto it = map_.find(key);
        if (it != map_.end() && it->second.serial == serial) eraseLocked_(key);
    }

    void wipeFiles_() {
        if (dir_.empty()) return;
        if (DIR* d = ::opendir(dir_.c_str())) {
            while (dirent* de = ::readdir(d)) {
                const std::string n = de->d_name;
                if (n.size() > 4 && n.compare(n.size() - 4, 4, ".fxr") == 0) ::unlink((dir_ + "/" + n).c_str());
            }
            ::closedir(d);
        }
    }

    static bool writeFile_(const std::string& path, const std::vector<uint8_t>& b) {
        const std::string tmp = path + ".tmp";
        FILE* f = std::fopen(tmp.c_str(), "wb");
        if (!f) return false;
        const uint32_t magic = 0x31525846u;   // "FXR1"
        const uint64_t n = b.size();
        bool ok = std::fwrite(&magic, 4, 1, f) == 1 && std::fwrite(&n, 8, 1, f) == 1 && (b.empty() || std::fwrite(b.data(), 1, b.size(), f) == b.size());
        ok = (std::fclose(f) == 0) && ok;
        if (!ok) { ::unlink(tmp.c_str()); return false; }
        if (std::rename(tmp.c_str(), path.c_str()) != 0) { ::unlink(tmp.c_str()); return false; }
        return true;
    }

    static bool readFile_(const std::string& path, std::vector<uint8_t>& b) {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) return false;
        uint32_t magic = 0; uint64_t n = 0;
        bool ok = std::fread(&magic, 4, 1, f) == 1 && magic == 0x31525846u && std::fread(&n, 8, 1, f) == 1 && n < ((uint64_t) 1 << 31);
        if (ok) { b.resize((size_t) n); ok = n == 0 || std::fread(b.data(), 1, (size_t) n, f) == (size_t) n; }
        std::fclose(f);
        return ok;
    }

    // Move the least-recently-used compressed bitmaps to disk until the RAM budget holds. Called with the lock held; unlocks around file I/O.
    void spill_(std::unique_lock<std::mutex>& lk) {
        while (ram_ > budget_ && !dir_.empty()) {
            uint64_t vk = 0, best = ~0ull; bool found = false;
            for (auto& kv : map_) {
                const Entry& e = kv.second;
                if (e.comp && !e.raw && e.tick < best) { best = e.tick; vk = kv.first; found = true; }
            }
            if (!found) break;
            Entry& v = map_[vk];
            if (v.onDisk) { ram_ -= v.comp->size(); v.comp.reset(); continue; }   // a copy already lives on disk
            Bytes comp = v.comp; const uint64_t serial = v.serial; const std::string path = pathOf_(vk);
            lk.unlock();
            const bool ok = writeFile_(path, *comp);
            lk.lock();
            auto it = map_.find(vk);
            if (it == map_.end() || it->second.serial != serial) { if (ok) ::unlink(path.c_str()); continue; }
            if (!ok) break;   // disk full / unwritable: stay over budget rather than loop
            it->second.onDisk = true;
            ram_ -= std::min(ram_, it->second.comp ? it->second.comp->size() : 0);
            it->second.comp.reset();
        }
    }

    void worker_() {
        std::unique_lock<std::mutex> lk(mu_);
        for (;;) {
            cv_.wait(lk, [&] { return !jobs_.empty(); });
            const Job j = jobs_.front();
            jobs_.pop_front();
            if (j.op == Op::Compress) {
                auto it = map_.find(j.key);
                if (it == map_.end() || it->second.serial != j.serial || !it->second.raw) continue;
                Pixels raw = it->second.raw;
                lk.unlock();
                auto out = std::make_shared<std::vector<uint8_t>>();
                rleEncode(raw->data(), raw->size(), *out);
                out->shrink_to_fit();
                lk.lock();
                it = map_.find(j.key);
                if (it != map_.end() && it->second.serial == j.serial) {
                    ram_ -= std::min(ram_, raw->size() * 4);
                    ram_ += out->size();
                    it->second.raw.reset();
                    it->second.comp = out;
                }
                spill_(lk);
            } else if (j.op == Op::Load) {
                auto it = map_.find(j.key);
                if (it == map_.end() || it->second.serial != j.serial || it->second.comp || it->second.raw || !it->second.onDisk) continue;
                const std::string path = pathOf_(j.key);
                lk.unlock();
                auto b = std::make_shared<std::vector<uint8_t>>();
                const bool ok = readFile_(path, *b);
                lk.lock();
                it = map_.find(j.key);
                if (it == map_.end() || it->second.serial != j.serial) continue;
                if (!ok) { eraseLocked_(j.key); continue; }
                if (!it->second.comp && !it->second.raw) { it->second.comp = b; ram_ += b->size(); }
                spill_(lk);
            } else {
                spill_(lk);
            }
        }
    }
};

}  // namespace fox::cache
