// fox_loopgen.cpp - live Loop / Freeze generation for Track Groups, OFF the UI thread.
//
//   UI thread --nativeSubmit--> [moodycamel BlockingConcurrentQueue<Job>] --> worker thread "fox-loopgen"
//   UI thread <--nativePoll---- [moodycamel ConcurrentQueue<Done>]        <--
//
// The worker never touches Kotlin objects: a request is ONE packed int array, the answer is ONE packed int array.
//   * Requests are coalesced: when several are queued only the newest ticket of each group is computed, and a request
//     that is overtaken while it runs is abandoned at the next check (every ~1k repeats).
//   * Generated drawings carry NO strokes (they point at their source drawing, see Cel.gen), so what comes back is a
//     tiny diff: which existing copies stay, which are resized, which new (length, source) entries are appended.
//   * Adjacent copies of the same drawing are merged into ONE longer hold (same picture, far fewer cels in RAM).
//   * A hard cap (kMaxEntries) bounds the memory a runaway end frame can ask for.
//
// Needs the moodycamel queue headers in ExternalLibraries/concurrent_queue/moodycamel/ (CMakeLists adds the include root).
//
// Request (all int32):
//   [mode 1=loop 2=freeze, start, period, end, minTail, nRows, nAudioSrc, nAudioHave,
//    periodMs, startMs, endMs, tailMs, minClipMs]
//   per row   : rowId, offset, nCels, nKeys, nRigKeys, nCels*(id, gen, len), nKeys*frame, nRigKeys*frame
//   audio src : nAudioSrc *(startMs, lenMs, idKey)
//   audio have: nAudioHave*(startMs, lenMs, idKey)
// Answer (after the 2 ints [group, ticket] that nativePoll prepends):
//   [status 0 ok / 1 truncated / -1 failed, nRows,
//    per row : rowId, srcLen, dropAll, matched(-1 = row untouched), nResize, nTail, nKeyCopy, nRigCopy,
//              nResize*(celId, newLen), nTail*(len, from), nKeyCopy*(keyIdx, k), nRigCopy*(keyIdx, k),
//    audio   : matched, nLenFix, nTail, nLenFix*(haveIdx, newLen), nTail*(srcIdx, startMs, lenMs)]

#include <jni.h>
#include <android/log.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include <moodycamel/concurrentqueue.h>
#include <moodycamel/blockingconcurrentqueue.h>

#define LG_TAG "fox_loopgen"
#define LG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kMaxEntries = 2000000;   // generated entries (cels + key copies + audio copies) per request

struct Job {
    int group = 0;
    int ticket = 0;
    std::vector<int> req;
    std::shared_ptr<std::atomic<int>> latest;   // newest ticket of this group; != ticket -> this job is obsolete
};

struct Done {
    int group = 0;
    int ticket = 0;
    std::vector<int> out;
};

moodycamel::BlockingConcurrentQueue<Job> gJobs;
moodycamel::ConcurrentQueue<Done> gDone;
std::mutex gMapMu;
std::unordered_map<int, std::shared_ptr<std::atomic<int>>> gLatest;
std::once_flag gOnce;

// ------------------------------------------------------------------------------------------------ request reader
struct Reader {
    const int* p;
    const int* e;
    bool ok = true;
    int next() {
        if (p >= e) { ok = false; return 0; }
        return *p++;
    }
    const int* take(int n) {
        if (n < 0 || (int64_t)(e - p) < (int64_t)n) { ok = false; return nullptr; }
        const int* r = p;
        p += n;
        return r;
    }
};

struct SrcCel { int id; int len; };
struct HaveCel { int id; int gen; int len; };

// What a row should hold after its source, streamed against what it holds now (the Kotlin GenPlan, in C++).
// Adjacent copies of the same drawing become one longer hold.
struct Plan {
    const std::vector<HaveCel>& have;
    size_t matched = 0;
    int64_t total = 0;
    std::vector<int> resize;   // (celId, newLen)
    std::vector<int> tail;     // (len, from)
    size_t entries = 0;

    explicit Plan(const std::vector<HaveCel>& h) : have(h) {}

    void hold(int64_t frames) { pend += frames; }

    void cel(int64_t len, int from) {
        if (pend > 0) { put(pend, -2); pend = 0; }
        put(len, from);
    }

    void finish() { flush(); }

private:
    int64_t pend = 0;
    bool has = false;
    int64_t curLen = 0;
    int curFrom = 0;

    void put(int64_t len, int from) {
        if (has && from >= 0 && from == curFrom) { curLen += len; return; }
        flush();
        has = true; curLen = len; curFrom = from;
    }

    void flush() {
        if (!has) return;
        has = false;
        const int len = (int) std::min<int64_t>(curLen, INT32_MAX);
        total += len;
        if (tail.empty() && matched < have.size() && have[matched].gen == curFrom) {
            const HaveCel& h = have[matched++];
            if (h.len != len) { resize.push_back(h.id); resize.push_back(len); }
        } else {
            tail.push_back(len);
            tail.push_back(curFrom);
            entries++;
        }
    }
};

struct AudioEntry { int64_t start; int64_t len; int key; };
struct WantClip { int src; int64_t start; int64_t len; };

// ------------------------------------------------------------------------------------------------ the generator
// false = abandoned (a newer request of the group exists) or malformed.
bool compute(const Job& j, std::vector<int>& out) {
    Reader r{j.req.data(), j.req.data() + j.req.size()};
    const int mode = r.next();
    const int start = r.next();
    const int p = r.next();
    const int end = r.next();
    const int minTail = r.next();
    const int nRows = r.next();
    const int nAs = r.next();
    const int nAh = r.next();
    const int64_t pMs = r.next();
    const int64_t startMs = r.next();
    const int64_t endMs = r.next();
    const int64_t tailMs = r.next();
    const int64_t minClipMs = r.next();
    if (!r.ok || nRows < 0 || nAs < 0 || nAh < 0) return false;
    (void) start;
    const bool loop = mode == 1;
    bool truncated = false;
    size_t budget = 0;   // entries produced so far, over rows + key copies + audio
    auto obsolete = [&]() { return j.latest->load(std::memory_order_relaxed) != j.ticket; };

    out.clear();
    out.push_back(0);
    out.push_back(nRows);

    std::vector<SrcCel> src;
    std::vector<HaveCel> have;
    std::vector<int> kc, rc;

    for (int row = 0; row < nRows; row++) {
        const int rowId = r.next();
        const int offset = r.next();
        const int nCels = r.next();
        const int nKeys = r.next();
        const int nRig = r.next();
        if (!r.ok || nCels < 0) return false;
        const int* cels = r.take(nCels * 3);
        const int* keys = r.take(nKeys);
        const int* rig = r.take(nRig);
        if (!r.ok) return false;

        src.clear(); have.clear(); kc.clear(); rc.clear();
        int64_t l0 = 0;
        bool badOrder = false;
        for (int i = 0; i < nCels; i++) {
            const int id = cels[i * 3], gen = cels[i * 3 + 1], len = cels[i * 3 + 2];
            if (gen == -1) {
                if (!have.empty()) badOrder = true;   // a source drawing behind a copy: rebuild this row's copies
                src.push_back({id, len});
                l0 += len;
            } else {
                have.push_back({id, gen, len});
            }
        }
        const int dropAll = badOrder ? 1 : 0;
        if (badOrder) have.clear();

        if (src.empty()) {   // nothing to repeat: leave the row alone
            const int hdr[8] = {rowId, 0, 0, -1, 0, 0, 0, 0};
            out.insert(out.end(), hdr, hdr + 8);
            continue;
        }

        const int64_t rowEnd = (int64_t) offset + l0;
        Plan plan(have);
        if (loop) {
            if (p > 0) {
                int64_t cur = rowEnd;
                for (int64_t k = 1; (int64_t) offset + k * p < end; k++) {
                    if ((k & 1023) == 0 && obsolete()) return false;
                    const int64_t repStart = (int64_t) offset + k * p;
                    if (repStart > cur) { plan.hold(repStart - cur); cur = repStart; }
                    for (const SrcCel& c : src) {
                        if (cur >= end) break;
                        const int64_t len = std::min<int64_t>(c.len, (int64_t) end - cur);
                        if (len < c.len && len < minTail) break;   // cut the 1-3 frame sliver
                        plan.cel(len, c.id);
                        cur += len;
                    }
                    if (plan.entries + budget > kMaxEntries) { truncated = true; break; }
                }
            }
        } else if ((int64_t) end - rowEnd >= minTail) {
            plan.cel((int64_t) end - rowEnd, src.back().id);
        }
        plan.finish();
        budget += plan.entries;

        // keys: the source keys (row-local frame < srcLen), repeated for a loop; a freeze just holds the last value
        const int64_t finalEnd = rowEnd + plan.total;
        if (loop && p > 0) {
            for (int pass = 0; pass < 2; pass++) {
                const int* kf = pass == 0 ? keys : rig;
                const int nk = pass == 0 ? nKeys : nRig;
                std::vector<int>& dst = pass == 0 ? kc : rc;
                if (nk == 0) continue;
                for (int64_t k = 1; (int64_t) offset + k * p < finalEnd; k++) {
                    if ((k & 1023) == 0 && obsolete()) return false;
                    for (int i = 0; i < nk; i++) {
                        if (kf[i] >= l0) continue;
                        if ((int64_t) offset + kf[i] + k * p < finalEnd) { dst.push_back(i); dst.push_back((int) k); }
                    }
                    if (budget + (kc.size() + rc.size()) / 2 > kMaxEntries) { truncated = true; break; }
                }
            }
            budget += (kc.size() + rc.size()) / 2;
        }

        const int hdr[8] = {rowId, (int) std::min<int64_t>(l0, INT32_MAX), dropAll, (int) plan.matched,
                            (int) (plan.resize.size() / 2), (int) (plan.tail.size() / 2),
                            (int) (kc.size() / 2), (int) (rc.size() / 2)};
        out.insert(out.end(), hdr, hdr + 8);
        out.insert(out.end(), plan.resize.begin(), plan.resize.end());
        out.insert(out.end(), plan.tail.begin(), plan.tail.end());
        out.insert(out.end(), kc.begin(), kc.end());
        out.insert(out.end(), rc.begin(), rc.end());
    }

    // ---- audio: copies of the source clips for every repeat (freeze = silence), diffed against the copies we hold
    std::vector<AudioEntry> aSrc((size_t) nAs), aHave((size_t) nAh);
    {
        const int* a = r.take(nAs * 3);
        const int* h = r.take(nAh * 3);
        if (!r.ok) return false;
        for (int i = 0; i < nAs; i++) aSrc[(size_t) i] = {a[i * 3], a[i * 3 + 1], a[i * 3 + 2]};
        for (int i = 0; i < nAh; i++) aHave[(size_t) i] = {h[i * 3], h[i * 3 + 1], h[i * 3 + 2]};
    }
    std::vector<WantClip> want;
    if (loop && p > 0 && pMs > 0 && nAs > 0) {
        for (int64_t k = 1; startMs + k * pMs < endMs; k++) {
            if ((k & 1023) == 0 && obsolete()) return false;
            for (int i = 0; i < nAs; i++) {
                const AudioEntry& c = aSrc[(size_t) i];
                const int64_t s = c.start + k * pMs;
                const int64_t len = std::min<int64_t>(c.len, endMs - s);
                if (s >= endMs || len < minClipMs || (len < c.len && len < tailMs)) continue;
                want.push_back({i, s, len});
            }
            if (budget + want.size() > kMaxEntries) { truncated = true; break; }
        }
    }
    size_t m = 0;
    std::vector<int> lenFix;
    while (m < aHave.size() && m < want.size() &&
           aHave[m].key == aSrc[(size_t) want[m].src].key && aHave[m].start == want[m].start) {
        if (aHave[m].len != want[m].len) { lenFix.push_back((int) m); lenFix.push_back((int) want[m].len); }
        m++;
    }
    out.push_back((int) m);
    out.push_back((int) (lenFix.size() / 2));
    out.push_back((int) (want.size() - m));
    out.insert(out.end(), lenFix.begin(), lenFix.end());
    for (size_t i = m; i < want.size(); i++) {
        out.push_back(want[i].src);
        out.push_back((int) std::min<int64_t>(want[i].start, INT32_MAX));
        out.push_back((int) std::min<int64_t>(want[i].len, INT32_MAX));
    }
    if (truncated) out[0] = 1;
    return true;
}

// ------------------------------------------------------------------------------------------------ worker
void workerMain() {
    prctl(PR_SET_NAME, "fox-loopgen", 0, 0, 0);
    setpriority(PRIO_PROCESS, (id_t) syscall(SYS_gettid), 8);   // background: never competes with the UI / GL / audio threads

    for (;;) {
        std::vector<Job> batch;
        {
            Job first;
            gJobs.wait_dequeue(first);
            batch.push_back(std::move(first));
            Job more;
            while (gJobs.try_dequeue(more)) batch.push_back(std::move(more));
        }
        // only the newest request of each group is worth computing
        std::unordered_map<int, size_t> newest;
        for (size_t i = 0; i < batch.size(); i++) {
            auto it = newest.find(batch[i].group);
            if (it == newest.end() || batch[it->second].ticket < batch[i].ticket) newest[batch[i].group] = i;
        }
        for (size_t i = 0; i < batch.size(); i++) {
            Job& jb = batch[i];
            if (newest[jb.group] != i) continue;
            if (jb.latest->load(std::memory_order_relaxed) != jb.ticket) continue;
            Done d;
            d.group = jb.group;
            d.ticket = jb.ticket;
            // the project builds with -fno-exceptions: memory is bounded by kMaxEntries instead of a catch
            if (!compute(jb, d.out)) {
                if (jb.latest->load(std::memory_order_relaxed) != jb.ticket) continue;   // overtaken: the newer job answers
                LG_LOGE("malformed request for group %d", jb.group);
                d.out.assign({-1, 0});                                                   // malformed: tell Kotlin to keep what it has
            }
            gDone.enqueue(std::move(d));
            jb.req = std::vector<int>();   // free the request now, not at the end of the batch
        }
    }
}

}  // namespace

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_fox_foxiru_foxcat_fox2d_jnicallers_NativeLoopGen_##name

// Any thread. Cheap: copies the request and enqueues it.
FN(void, nativeSubmit)(JNIEnv* env, jclass, jint group, jint ticket, jintArray req) {
    std::call_once(gOnce, [] { std::thread(workerMain).detach(); });
    Job j;
    j.group = group;
    j.ticket = ticket;
    const jsize n = env->GetArrayLength(req);
    j.req.resize((size_t) n);
    if (n > 0) env->GetIntArrayRegion(req, 0, n, j.req.data());
    {
        std::lock_guard<std::mutex> l(gMapMu);
        auto& slot = gLatest[group];
        if (!slot) slot = std::make_shared<std::atomic<int>>(-1);
        slot->store(ticket);
        j.latest = slot;
    }
    gJobs.enqueue(std::move(j));
}

// Any thread. [group, ticket, status, ...] of one finished request, or null when nothing is ready.
FN(jintArray, nativePoll)(JNIEnv* env, jclass) {
    Done d;
    if (!gDone.try_dequeue(d)) return nullptr;
    jintArray a = env->NewIntArray((jsize) (2 + d.out.size()));
    if (!a) return nullptr;
    const jint head[2] = {d.group, d.ticket};
    env->SetIntArrayRegion(a, 0, 2, head);
    if (!d.out.empty()) env->SetIntArrayRegion(a, 2, (jsize) d.out.size(), d.out.data());
    return a;
}

// Any thread. The group no longer wants copies: queued / running work for it is dropped.
FN(void, nativeCancel)(JNIEnv*, jclass, jint group) {
    std::lock_guard<std::mutex> l(gMapMu);
    auto it = gLatest.find(group);
    if (it != gLatest.end()) it->second->store(-1);
}
