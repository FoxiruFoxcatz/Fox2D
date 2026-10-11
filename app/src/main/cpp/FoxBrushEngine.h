// FoxBrushEngine.h - brush engine for the fox2d native canvas (libfoxcanvas.so).
//
//   Samples (x, y, pressure, time)           CPU, deterministic            GPU, GL thread
//   ---------------------------------  ->  ----------------------  ->  ------------------------------
//   Builder::add()                          stabiliser (rope + EMA)       Renderer::draw()
//                                           Catmull-Rom path walk         instanced dab quads, tip shader
//                                           spacing / dynamics / jitter   opacity cap via scratch + backup
//                                           taper start + tail            (tile-wise, only touched pixels)
//
// Determinism is the one hard requirement: the canvas replays a stroke from its stored samples after undo,
// a cel switch or a project load, and the pixels must be identical to what was drawn live. So the Builder
//   * depends only on the sample sequence (never on how it was chunked into emit() calls),
//   * draws its randomness from a counter hash (seed, dab index), never from a stateful RNG,
//   * never changes a dab it already emitted (the end taper is an ADDED tail, not a retroactive edit).
// tests/brush_test.cpp checks the chunking invariant.
//
// Units: sample x / y are paper-normalised 0..1 (what CanvasViewport.toUnit gives), sizes / lengths in the
// 512-unit brush space of InkStroke.size, dab output in paper pixels (the layer FBO texel space).
//
// Parameters travel as one flat float array (kParamCount floats, index = enum P below). BrushUI.kt mirrors
// the enum as `object BP`; keep both in sync. Unknown trailing entries are ignored, missing ones default,
// every value is clamped (Params::fromArray), so an old saved array never breaks a newer engine.
#pragma once

#include <GLES3/gl3.h>

#include <cstddef>
#include <cstdint>
#include <vector>

namespace fox {
namespace brush {

constexpr float kBrushSpace = 512.f;   // same as kBrushSpace in fox_canvas.cpp

enum P : int {
    P_SIZE = 0,        // 512-units, 1..256
    P_TIP,             // Tip
    P_HARDNESS,        // 0 soft .. 1 crisp edge
    P_ROUNDNESS,       // 0.05..1, squashes the tip along its local y axis
    P_ANGLE,           // deg, tip rotation
    P_ANGLE_FOLLOW,    // 0..1, how much of the stroke direction is added to the angle
    P_SPACING,         // fraction of the dab diameter between dabs
    P_FLOW,            // 0..1 strength of one dab
    P_OPACITY,         // 0..1 of the stroke
    P_BUILDUP,         // 1 = dabs accumulate (airbrush), 0 = stroke opacity is a hard cap (marker)
    P_SIZE_PRESS,      // 0..1 pressure -> size
    P_OPAC_PRESS,      // 0..1 pressure -> opacity
    P_PRESS_GAMMA,     // 0.3..3 pressure curve (< 1 = softer press needed)
    P_SIZE_SPEED,      // -1..1 speed -> size (negative = faster is thinner)
    P_OPAC_SPEED,      // -1..1 speed -> opacity
    P_MIN_SIZE,        // 0..1 smallest size (fraction) pressure may reach
    P_TAPER_START,     // 512-units, ramp-in length
    P_TAPER_END,       // 512-units, tail length added after the last point
    P_SCATTER,         // diameters, random offset of every dab
    P_SIZE_JIT,        // 0..1
    P_OPAC_JIT,        // 0..1
    P_ANGLE_JIT,       // 0..180 deg
    P_HUE_JIT,         // 0..1 (1 = +-180 deg)
    P_SAT_JIT,         // 0..1
    P_BRI_JIT,         // 0..1
    P_COUNT,           // 1..8 dabs per stamp
    P_GRAIN,           // 0..1 paper-tooth / noise strength
    P_GRAIN_SCALE,     // 512-units, size of one grain cell
    P_GRAIN_PAPER,     // 1 = grain fixed to the paper, 0 = moves with every dab
    P_WET_EDGE,        // 0..1 pigment pools at the edge (watercolour)
    P_STABILIZER,      // 512-units, lazy-rope length
    P_SMOOTHING,       // 0..1 extra low-pass of the stabilised path
    P_BRISTLES,        // 2..32 hairs of the bristle tip
    P_SEED,            // stroke random seed (the UI picks a fresh one per stroke and the stroke stores it)
    P_SIM_PRESSURE,    // 1 = derive pressure from speed (finger input has no real pressure)
    kParamCount
};

enum Tip : int { TipRound = 0, TipSquare, TipDiamond, TipStar, TipChalk, TipBristle, TipCount };

struct Params {
    float v[kParamCount];
    static Params defaults();
    // Clamps everything; short arrays keep the defaults for the missing tail. Returns false for null / empty.
    static bool fromArray(const float* a, int n, Params& out);
    float operator[](int i) const { return v[i]; }
};

struct Sample { float x, y, p, t; };   // paper 0..1, pressure 0..1, milliseconds since the stroke began

// One stamp. 9 floats, uploaded as is: GL attribs are (x y r ang) (alpha seed) (r g b).
struct Dab { float x, y, r, ang, a, seed, cr, cg, cb; };
static_assert(sizeof(Dab) == 9 * sizeof(float), "Dab is uploaded as a tightly packed array");

struct Rect {
    int x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    bool empty() const { return x1 <= x0 || y1 <= y0; }
    void merge(const Rect& o);
};

// -------------------------------------------------------------------------------------------- CPU

class Builder {
public:
    // pxPerUnit = paper px per brush unit (T / 512 on the canvas). argb = straight 0xAARRGGBB; alpha is ignored
    // (stroke opacity is a brush parameter).
    void begin(const Params& p, uint32_t argb, float pxPerUnit);
    void add(const Sample& s);                                   // paper-normalised
    void addPx(float x, float y, float pressure, float tMs);     // paper pixels (preview)
    // Appends the dabs that are final. finish = true also drains the look-ahead and adds the end taper; call it once.
    void emit(std::vector<Dab>& out, bool finish);
    size_t samples() const { return nRaw_; }

private:
    struct Node { float x, y, p, t; };
    Params p_{};
    float k_ = 1.f, T_ = kBrushSpace, baseR_ = 5.f;
    float rgb_[3] = {0, 0, 0};
    uint32_t seed_ = 1;
    // stabiliser
    bool haveRaw_ = false;
    size_t nRaw_ = 0;
    float bx_ = 0, by_ = 0, sx_ = 0, sy_ = 0, sp_ = 1;
    Node lastRaw_{};
    std::vector<Node> pts_;
    // walker
    size_t seg_ = 0;
    bool started_ = false, finished_ = false, single_ = false;
    float arc_ = 0, toNext_ = 0, speed_ = 0.3f;
    uint32_t dabN_ = 0;

    void stabilise(float x, float y, float p, float t);
    void pushNode(float x, float y, float p, float t);
    void catchUp();
    void tail(std::vector<Dab>& out);   // end taper: straight run-out past the last node
    void walkSegment(size_t j, std::vector<Dab>& out);
    void walkLine(float x0, float y0, float x1, float y1, float p0, float p1, float tailLen, float tailStart, std::vector<Dab>& out);
    void place(float x, float y, float pressure, float dx, float dy, float tailU, std::vector<Dab>& out);
    float R(uint32_t sub, uint32_t ch) const;
};

// Software twin of the dab shader: renders the whole stroke of [w] x [h] px into straight-alpha ARGB for the
// brush preview, along an S-shaped test stroke with a pressure ramp. [unit] = preview px per brush unit.
void renderPreview(const Params& p, uint32_t argb, int w, int h, float unit, std::vector<uint32_t>& outArgb);

// -------------------------------------------------------------------------------------------- GPU (GL thread only)

class Renderer {
public:
    bool init(int paperPx);    // compiles programs; call again after forget() when the GL context was recreated
    void forget();             // context lost: the names are gone, drop them without deleting
    void shutdown();
    bool ready() const { return prog_ != 0; }

    // Stamps [n] dabs into the layer behind [layerFbo] (any state; the framebuffer binding is replaced).
    // Returns the paper-pixel box that changed so the caller can grow its snapshot box.
    // Opacity-capped brushes (P_BUILDUP = 0) keep a tile-wise backup of the layer plus a scratch picture until
    // endStroke(), so call endStroke() after the last draw() of every stroke (and on cancel).
    Rect draw(GLuint layerFbo, const Params& p, uint32_t argb, bool erase, const Dab* d, size_t n);
    // Clears the scratch picture + backup tiles; [layerFbo] (0 = leave the binding) is re-bound afterwards.
    void endStroke(GLuint layerFbo);

private:
    int T_ = 0;
    GLuint prog_ = 0, comp_ = 0, vao_ = 0, vbo_ = 0;
    GLint uT_ = -1, uTip_ = -1, uHard_ = -1, uAsp_ = -1, uGrain_ = -1, uGScale_ = -1, uGPaper_ = -1, uWet_ = -1, uBris_ = -1, uSeed_ = -1;
    GLint cTex_ = -1, cOp_ = -1;
    GLuint scratchTex_ = 0, scratchFbo_ = 0, backupTex_ = 0, backupFbo_ = 0;
    std::vector<uint8_t> tiles_;   // 1 = this tile of the layer is already in the backup
    int tilesX_ = 0, tilesY_ = 0;
    Rect touched_;
    bool capBuffers();
    void backupTiles(GLuint layerFbo, const Rect& r);
};

}  // namespace brush
}  // namespace fox
