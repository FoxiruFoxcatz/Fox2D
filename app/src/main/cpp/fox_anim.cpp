// Host-side check of fox_anim.h:  g++ -std=c++17 -I<path to glm> -I. fox_anim_test.cpp -o t && ./t
#include "fox_anim.h"
#include <cstdio>
#include <cassert>
using namespace fox::anim;
static bool near(float a, float b, float e = 1e-4f) { return std::fabs(a - b) < e; }
int main() {
    // endpoints: every easing maps 0->0 and 1->1 (except Hold: 1 handled by the next key)
    for (int id = Linear; id < EaseCount; id++) {
        if (!near(ease(id, 0.f), 0.f) || !near(ease(id, 1.f), 1.f)) { std::printf("endpoint FAIL id=%d  e0=%f e1=%f\n", id, ease(id, 0.f), ease(id, 1.f)); return 1; }
    }
    // monotone for the non-overshooting ones
    int mono[] = {Linear, Sine, Sine+1, Sine+2, Quad, Quad+1, Quad+2, Cubic, Cubic+1, Cubic+2, Quart, Quart+2, Expo, Expo+1, Bezier};
    for (int id : mono) { float p = -1; for (int i = 0; i <= 200; i++) { float v = ease(id, i / 200.f); if (v + 1e-4f < p) { std::printf("monotone FAIL id=%d\n", id); return 1; } p = v; } }
    // symmetry of InOut at 0.5
    for (int base : {Sine, Quad, Cubic, Quart, Expo, Back, Bounce}) assert(near(ease(base + 2, 0.5f), 0.5f, 2e-3f));
    // bezier(0,0,1,1) ~ linear
    assert(near(ease(Bezier, 0.3f, glm::vec4(0, 0, 1, 1)), 0.3f, 1e-3f));
    // evalRow
    float k[2 * kKeyStride] = {
        0, 0.f, 0.f, 1.f, 1.f, 0.f, (float)Linear, 0, 0, 0, 0,
        10, 0.4f, -0.2f, 2.f, 2.f, 720.f, (float)Hold, 0, 0, 0, 0 };
    Xf base; base.px = 0.3f; base.py = 0.7f;
    Xf r = evalRow(k, 2, 5.f, base);
    assert(near(r.tx, 0.2f) && near(r.ty, -0.1f) && near(r.sx, 1.5f) && near(r.rot, 360.f) && near(r.px, 0.3f) && near(r.py, 0.7f));
    r = evalRow(k, 2, -3.f, base);  assert(near(r.tx, 0.f));          // before first: held
    r = evalRow(k, 2, 99.f, base);  assert(near(r.tx, 0.4f) && near(r.rot, 720.f)); // after last: held
    k[6] = (float)Hold; r = evalRow(k, 2, 9.99f, base); assert(near(r.tx, 0.f)); // hold until next key
    r = evalRow(nullptr, 0, 5.f, base); assert(near(r.sx, 1.f) && near(r.px, 0.3f));
    std::puts("fox_anim OK");
}
