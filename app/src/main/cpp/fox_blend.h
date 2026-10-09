#pragma once
// Blend modes, opacity and clipping masks. Shared by the on-screen canvas (fox_canvas.cpp) and the exporter
// (fox_exporter.cpp) so both composite exactly the same way.
//
//   * An item (a drawing layer inside a row, or a whole row inside the picture) carries an Fx: blend mode,
//     opacity and a "clip" flag.
//   * clip = the item is only visible where the nearest non-clipped item BELOW it has pixels (Photoshop's clipping
//     mask). The base item and everything clipped to it are composited together first; the base's blend mode and
//     opacity then apply to that whole group.
//   * Everything is premultiplied RGBA8, screen / frame sized, rendered bottom -> top. Plain items (Normal, 100 %,
//     not clipped, nothing clipped to them) are drawn straight into the destination, so scenes without effects cost
//     exactly what they did before.
//
// GL thread only. Needs an EGL context.

#include <GLES3/gl3.h>
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace fox::blend {

enum Mode : int {
    Normal = 0, Multiply, Screen, Overlay, Darken, Lighten, Add, ColorDodge, ColorBurn, SoftLight, HardLight,
    Difference, Exclusion,
    ModeCount
};

struct Fx {
    int mode = Normal;
    float opacity = 1.f;
    bool clip = false;
    bool plain() const { return mode == Normal && opacity >= 0.9999f; }
    bool none() const { return plain() && !clip; }
};

inline Fx makeFx(int mode, float opacity, bool clip) {
    Fx f;
    f.mode = (mode >= 0 && mode < ModeCount) ? mode : Normal;
    f.opacity = std::min(1.f, std::max(0.f, opacity));
    f.clip = clip;
    return f;
}

inline constexpr const char* kVS = R"(#version 300 es
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    gl_Position = vec4(c * 2.0 - 1.0, 0.0, 1.0);
}
)";

// uDirect = 1: output the source scaled by opacity and let fixed-function blending do the "over" / "atop"
// (Normal mode, no backdrop copy). uDirect = 0: full formula against a copy of the destination (uBack), blending off.
inline constexpr const char* kFS = R"(#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform sampler2D uBack;
uniform int uMode;
uniform float uOpacity;
uniform int uAtop;
uniform int uDirect;
out vec4 o;

float softC(float b, float s) {
    if (s <= 0.5) return b - (1.0 - 2.0 * s) * b * (1.0 - b);
    float d = (b <= 0.25) ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return b + (2.0 * s - 1.0) * (d - b);
}
float dodgeC(float b, float s) {
    if (b <= 0.0) return 0.0;
    if (s >= 1.0) return 1.0;
    return min(1.0, b / (1.0 - s));
}
float burnC(float b, float s) {
    if (b >= 1.0) return 1.0;
    if (s <= 0.0) return 0.0;
    return 1.0 - min(1.0, (1.0 - b) / s);
}
vec3 hardLight(vec3 b, vec3 s) {
    vec3 m = b * (2.0 * s);
    vec3 sc = b + (2.0 * s - 1.0) - b * (2.0 * s - 1.0);
    return mix(m, sc, step(vec3(0.5), s));
}
vec3 blendRGB(int m, vec3 b, vec3 s) {
    if (m == 1) return b * s;
    if (m == 2) return b + s - b * s;
    if (m == 3) return hardLight(s, b);
    if (m == 4) return min(b, s);
    if (m == 5) return max(b, s);
    if (m == 6) return min(vec3(1.0), b + s);
    if (m == 7) return vec3(dodgeC(b.r, s.r), dodgeC(b.g, s.g), dodgeC(b.b, s.b));
    if (m == 8) return vec3(burnC(b.r, s.r), burnC(b.g, s.g), burnC(b.b, s.b));
    if (m == 9) return vec3(softC(b.r, s.r), softC(b.g, s.g), softC(b.b, s.b));
    if (m == 10) return hardLight(b, s);
    if (m == 11) return abs(b - s);
    if (m == 12) return b + s - 2.0 * b * s;
    return s;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 s = texelFetch(uSrc, p, 0) * uOpacity;   // premultiplied
    if (uDirect == 1) { o = s; return; }
    vec4 d = texelFetch(uBack, p, 0);
    float as = s.a, ab = d.a;
    vec3 Cs = as > 0.0 ? s.rgb / as : vec3(0.0);
    vec3 Cb = ab > 0.0 ? d.rgb / ab : vec3(0.0);
    vec3 B = clamp(blendRGB(uMode, Cb, Cs), 0.0, 1.0);
    if (uAtop == 1) o = vec4(as * ab * B + (1.0 - as) * d.rgb, ab);
    else o = vec4(s.rgb * (1.0 - ab) + as * ab * B + (1.0 - as) * d.rgb, as + ab * (1.0 - as));
}
)";

struct Target { GLuint tex = 0, fbo = 0; };

struct Ctx {
    static constexpr int kPool = 8;   // nesting: scene + folder group + folder temp + row group + row temp + layer group + layer temp (+ spare)
    int W = 0, H = 0;
    GLuint prog = 0, vao = 0;
    GLint uSrc = -1, uBack = -1, uMode = -1, uOpacity = -1, uAtop = -1, uDirect = -1;
    Target pool[kPool];
    int used = 0;
    GLuint backTex = 0;
    bool ok = false;

    static GLuint compileShader(GLenum type, const char* src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint st = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &st);
        if (!st) { glDeleteShader(s); return 0; }
        return s;
    }

    // New GL context (or first use): forget every name, rebuild the program.
    bool init(GLuint emptyVao = 0) {
        for (auto& t : pool) t = Target{};
        backTex = 0;
        used = 0;
        W = H = 0;
        vao = emptyVao;
        GLuint v = compileShader(GL_VERTEX_SHADER, kVS), f = compileShader(GL_FRAGMENT_SHADER, kFS);
        if (!v || !f) { ok = false; return false; }
        prog = glCreateProgram();
        glAttachShader(prog, v); glAttachShader(prog, f);
        glLinkProgram(prog);
        glDeleteShader(v); glDeleteShader(f);
        GLint st = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &st);
        if (!st) { glDeleteProgram(prog); prog = 0; ok = false; return false; }
        uSrc = glGetUniformLocation(prog, "uSrc");
        uBack = glGetUniformLocation(prog, "uBack");
        uMode = glGetUniformLocation(prog, "uMode");
        uOpacity = glGetUniformLocation(prog, "uOpacity");
        uAtop = glGetUniformLocation(prog, "uAtop");
        uDirect = glGetUniformLocation(prog, "uDirect");
        ok = true;
        return true;
    }

    static void makeTex(GLuint& tex, int w, int h) {
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    // Size of every intermediate picture. Buffers are created lazily, so a project without effects allocates nothing.
    void resize(int w, int h) {
        if (w == W && h == H) return;
        for (auto& t : pool) {
            if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
            if (t.tex) glDeleteTextures(1, &t.tex);
            t = Target{};
        }
        if (backTex) { glDeleteTextures(1, &backTex); backTex = 0; }
        W = w; H = h;
    }

    void release() {
        for (auto& t : pool) {
            if (t.fbo) glDeleteFramebuffers(1, &t.fbo);
            if (t.tex) glDeleteTextures(1, &t.tex);
            t = Target{};
        }
        if (backTex) glDeleteTextures(1, &backTex);
        backTex = 0;
        if (prog) glDeleteProgram(prog);
        prog = 0; ok = false; used = 0; W = H = 0;
    }

    Target* acquire() {
        if (used >= kPool) return nullptr;
        Target& t = pool[used++];
        if (!t.fbo) {
            makeTex(t.tex, W, H);
            glGenFramebuffers(1, &t.fbo);
            glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.tex, 0);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo);
        glViewport(0, 0, W, H);
        glClearColor(0.f, 0.f, 0.f, 0.f);
        glClear(GL_COLOR_BUFFER_BIT);
        return &t;
    }
    void giveBack() { if (used > 0) used--; }
};

inline void setNormalBlend() {
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
}

// Merges the picture in srcTex into dstFbo. atop = keep the destination's alpha (clipping mask).
inline void composite(Ctx& cx, GLuint srcTex, GLuint dstFbo, const Fx& fx, bool atop) {
    const bool direct = fx.mode == Normal;
    // Create the backdrop texture BEFORE binding anything: makeTex binds on the active unit and would replace srcTex on unit 0.
    if (!direct && !cx.backTex) Ctx::makeTex(cx.backTex, cx.W, cx.H);
    glBindFramebuffer(GL_FRAMEBUFFER, dstFbo);
    glViewport(0, 0, cx.W, cx.H);
    glUseProgram(cx.prog);
    glBindVertexArray(cx.vao);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, srcTex);
    glUniform1i(cx.uSrc, 0);
    glUniform1i(cx.uBack, 1);
    glUniform1f(cx.uOpacity, fx.opacity);
    glUniform1i(cx.uMode, fx.mode);
    glUniform1i(cx.uAtop, atop ? 1 : 0);
    glUniform1i(cx.uDirect, direct ? 1 : 0);
    if (direct) {
        glEnable(GL_BLEND);
        glBlendFunc(atop ? GL_DST_ALPHA : GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    } else {
        // copy of the destination = the backdrop the shader reads (a texture can't be read while it is rendered to)
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, cx.backTex);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, cx.W, cx.H);
        glDisable(GL_BLEND);
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glActiveTexture(GL_TEXTURE0);
    setNormalBlend();
}

// Composites items 0..n-1 (bottom -> top) into dstFbo.
//   skip(i)   : nothing to draw for item i
//   fxOf(i)   : its Fx
//   draw(i, fbo, atop) : renders item i into fbo; must bind the fbo + viewport itself and use the blend function
//               (GL_ONE, 1-srcA) for atop = false or (GL_DST_ALPHA, 1-srcA) for atop = true (clipped to what is there).
//   directAtop : true when draw() can honour atop (single drawing layers). Items made of several draws (a whole row)
//               are rendered to a temporary picture instead and clipped when merged.
template <class Skip, class FxOf, class Draw>
void stack(Ctx& cx, int n, GLuint dstFbo, bool directAtop, Skip skip, FxOf fxOf, Draw draw) {
    int i = 0;
    while (i < n) {
        if (skip(i)) { i++; continue; }
        const int b = i++;
        std::vector<int> fol;   // items clipped to b, bottom -> top
        while (i < n) {
            if (skip(i)) { if (fxOf(i).clip) { i++; continue; } break; }
            if (!fxOf(i).clip) break;
            fol.push_back(i++);
        }
        const Fx fb = fxOf(b);
        if (fol.empty() && fb.plain()) {
            setNormalBlend();
            draw(b, dstFbo, false);
            continue;
        }
        Target* g = cx.acquire();
        if (!g) { setNormalBlend(); draw(b, dstFbo, false); continue; }   // out of buffers: draw it plainly rather than lose it
        setNormalBlend();
        draw(b, g->fbo, false);
        for (int f : fol) {
            const Fx ff = fxOf(f);
            if (ff.plain() && directAtop) {
                draw(f, g->fbo, true);   // atop: only where the base already has pixels
                setNormalBlend();
            } else {
                Target* t = cx.acquire();
                if (!t) continue;
                setNormalBlend();
                draw(f, t->fbo, false);
                composite(cx, t->tex, g->fbo, ff, true);
                cx.giveBack();
            }
        }
        // a lone base with its own effect takes the same road: the group is just that one picture
        composite(cx, g->tex, dstFbo, fb, false);
        cx.giveBack();
    }
}

// Top-level stacking of the timeline rows (bottom -> top) with folders ("groups"). The drawing rows of a group are adjacent.
// A group that carries an effect (blend / opacity / clip) is composited as ONE picture first, then stacked against its
// neighbours like a single row: it can be clipped to what is below it, and a clipped row above it clips to the whole group.
// A group without an effect is transparent: its rows simply stack with the others, exactly as before.
//   skipRow(r) / rowFx(r)   : as for stack()
//   groupOf(r)              : group id of row r (-1 = top level)
//   groupFx(g)              : Fx of group g
//   drawRow(r, fbo, atop)   : renders row r into fbo (atop is never requested for rows)
template <class SkipRow, class RowFx, class GroupOf, class GroupFx, class DrawRow>
void stackRows(Ctx& cx, int nRows, GLuint dstFbo, SkipRow skipRow, RowFx rowFx, GroupOf groupOf, GroupFx groupFx, DrawRow drawRow) {
    struct Unit { int a, b, g; };   // rows [a, b), g = group id or -1 for a single row
    std::vector<Unit> units;
    for (int r = 0; r < nRows;) {
        const int g = groupOf(r);
        if (g >= 0 && !groupFx(g).none()) {
            int e = r + 1;
            while (e < nRows && groupOf(e) == g) e++;
            units.push_back({r, e, g});
            r = e;
        } else {
            units.push_back({r, r + 1, -1});
            r++;
        }
    }
    stack(cx, (int) units.size(), dstFbo, false,
        [&](int u) {
            for (int r = units[(size_t) u].a; r < units[(size_t) u].b; r++) if (!skipRow(r)) return false;
            return true;
        },
        [&](int u) { const Unit& k = units[(size_t) u]; return k.g >= 0 ? groupFx(k.g) : rowFx(k.a); },
        [&](int u, GLuint fbo, bool) {
            const Unit k = units[(size_t) u];
            if (k.g < 0) { drawRow(k.a, fbo, false); return; }
            stack(cx, k.b - k.a, fbo, false,
                [&](int i) { return skipRow(k.a + i); },
                [&](int i) { return rowFx(k.a + i); },
                [&](int i, GLuint f, bool) { drawRow(k.a + i, f, false); });
        });
}

}  // namespace fox::blend
