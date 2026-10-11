// fox_onion.h - Onion skin: ghosts of the drawings BEFORE / AFTER the playhead.
//
// All ghosts of one side (past / future) are baked into ONE paper-sized texture (2 textures in total, 32 MB at 2048), each ghost tinted
// and faded by its distance from the playhead and placed with the row's pose of ITS OWN frame. The canvas then draws the two pictures
// right above the paper and below every layer, so the real drawing is never touched. Nothing is re-baked unless a ghost's drawing,
// pose or the settings changed (signature compare), and everything is freed again while the onion is off.
//
// GL-thread only. Pure GL + glm: the stroke replay and the row pose live in fox_canvas.cpp and come in as callbacks.
#pragma once

#include <GLES3/gl3.h>
#include <glm/glm.hpp>
#include <glm/gtc/matrix_transform.hpp>
#include <glm/gtc/type_ptr.hpp>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <vector>

namespace fox::onion {

struct Ghost {
    int cel = -1;     // drawing to show
    int frame = 0;    // timeline frame it belongs to (the row's pose is evaluated there)
    int dist = 1;     // drawings away from the playhead, >= 1
};

struct Params {
    float opacity = 0.5f;   // nearest ghost
    float falloff = 0.6f;   // every further ghost: x falloff
    float tint = 1.f;       // 0 = original colours, 1 = flat colour
    glm::vec3 pastCol{1.f, 0.25f, 0.25f};
    glm::vec3 futureCol{0.2f, 0.85f, 0.35f};
};

struct Side {
    GLuint tex = 0, fbo = 0;
    std::vector<uint64_t> sig;   // what the picture was baked from
    bool has = false;            // something was drawn into it
};

struct Onion {
    int row = -1;                // timeline row the ghosts belong to (-1 = off)
    std::vector<Ghost> past, future;
    Params prm;
    Side sidePast, sideFuture;
    bool on() const { return row >= 0 && (!past.empty() || !future.empty()); }
};

// ---- baking shader: vertex shader = the canvas layer one (kLayerVS) ----------------------------------------------------------
inline const char* kBakeFS = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in vec2 vShown;
uniform sampler2D uTex;
uniform float uSide;
uniform vec4 uTint;      // rgb = colour, a = how much of it (0..1)
uniform float uAlpha;
out vec4 o;
void main() {
    vec4 c = texture(uTex, vLocal / uSide);          // premultiplied
    vec3 rgb = mix(c.rgb, uTint.rgb * c.a, uTint.a);
    o = vec4(rgb, c.a) * uAlpha;
}
)";

struct BakeProg {
    GLuint p = 0;
    GLint uMVP = -1, uModel = -1, uSide = -1, uMargin = -1, uTex = -1, uTint = -1, uAlpha = -1;
};

inline void locate(BakeProg& B) {
    auto u = [&](const char* n) { return glGetUniformLocation(B.p, n); };
    B.uMVP = u("uMVP"); B.uModel = u("uModel"); B.uSide = u("uSide"); B.uMargin = u("uMargin");
    B.uTex = u("uTex"); B.uTint = u("uTint"); B.uAlpha = u("uAlpha");
}

// ---- targets -----------------------------------------------------------------------------------------------------------------
inline void makeTarget(Side& S, int T) {
    if (S.tex) return;
    glGenTextures(1, &S.tex);
    glBindTexture(GL_TEXTURE_2D, S.tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, T, T);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &S.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, S.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, S.tex, 0);
}

inline void freeSide(Side& S) {
    if (S.fbo) glDeleteFramebuffers(1, &S.fbo);
    if (S.tex) glDeleteTextures(1, &S.tex);
    S = Side{};
}

/// Onion switched off: give the GPU memory back (the ghost list stays).
inline void release(Onion& O) {
    if (O.sidePast.tex) freeSide(O.sidePast);
    if (O.sideFuture.tex) freeSide(O.sideFuture);
}

/// New GL context: the old names are gone, forget them without deleting (the ghost list stays).
inline void forget(Onion& O) {
    O.sidePast = Side{};
    O.sideFuture = Side{};
}

// ---- bake --------------------------------------------------------------------------------------------------------------------
/// [pictures](cel, each): call each(texture) once per layer picture of that drawing, bottom -> top (the texture is only valid inside the call).
using Pictures = std::function<void(int cel, const std::function<void(GLuint)>& each)>;
using StampOf = std::function<uint64_t(int cel)>;          // changes whenever the drawing's strokes (or its layers' visibility) change
using ModelAt = std::function<glm::mat4(int frame)>;       // row pose at a timeline frame, in paper-texture px

inline uint64_t bits(float f) { uint32_t u; std::memcpy(&u, &f, 4); return u; }

inline void bakeSide(Side& S, const std::vector<Ghost>& list, const glm::vec3& col, const Params& P, const BakeProg& B, int T,
                     const StampOf& stampOf, const ModelAt& modelAt, const Pictures& pictures) {
    std::vector<uint64_t> sig;
    sig.push_back(list.size());
    sig.push_back(bits(P.opacity)); sig.push_back(bits(P.falloff)); sig.push_back(bits(P.tint));
    sig.push_back(bits(col.r)); sig.push_back(bits(col.g)); sig.push_back(bits(col.b));
    sig.push_back((uint64_t) T);
    std::vector<glm::mat4> models(list.size());
    for (size_t i = 0; i < list.size(); i++) {
        models[i] = modelAt(list[i].frame);
        const glm::mat4& m = models[i];
        sig.push_back((uint64_t) (uint32_t) list[i].cel);
        sig.push_back((uint64_t) (uint32_t) list[i].dist);
        sig.push_back(stampOf(list[i].cel));
        for (float f : {m[0].x, m[0].y, m[1].x, m[1].y, m[3].x, m[3].y}) sig.push_back(bits(f));
    }
    if (list.empty()) {
        if (S.tex) freeSide(S);
        return;
    }
    if (S.tex && sig == S.sig) return;   // nothing changed

    makeTarget(S, T);
    S.sig = std::move(sig);
    S.has = false;
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, S.fbo);
    glViewport(0, 0, T, T);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);

    // far ghosts first, so the nearest one ends up on top
    std::vector<size_t> order(list.size());
    for (size_t i = 0; i < order.size(); i++) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return list[a].dist > list[b].dist; });

    const glm::mat4 mvp = glm::ortho(0.f, (float) T, 0.f, (float) T);   // texture px -> clip (same orientation the stroke shader writes)
    for (size_t i : order) {
        const float alpha = std::clamp(P.opacity * std::pow(P.falloff, (float) (list[i].dist - 1)), 0.f, 1.f);
        if (alpha <= 0.003f) continue;
        const glm::mat4 model = models[i];
        pictures(list[i].cel, [&](GLuint tex) {
            glBindFramebuffer(GL_FRAMEBUFFER, S.fbo);   // the picture callback left its scratch layer bound
            glViewport(0, 0, T, T);
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glUseProgram(B.p);
            glUniformMatrix4fv(B.uMVP, 1, GL_FALSE, glm::value_ptr(mvp));
            glUniformMatrix4fv(B.uModel, 1, GL_FALSE, glm::value_ptr(model));
            glUniform1f(B.uSide, (float) T);
            glUniform1f(B.uMargin, 0.f);
            glUniform1i(B.uTex, 0);
            glUniform4f(B.uTint, col.r, col.g, col.b, P.tint);
            glUniform1f(B.uAlpha, alpha);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, tex);
            glBindVertexArray(0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            S.has = true;
        });
    }
}

/// Bake whatever changed. Call once per frame on the GL thread, before the screen is drawn.
inline void update(Onion& O, const BakeProg& B, int T, const StampOf& stampOf, const ModelAt& modelAt, const Pictures& pictures) {
    if (!B.p) return;
    bakeSide(O.sidePast, O.past, O.prm.pastCol, O.prm, B, T, stampOf, modelAt, pictures);
    bakeSide(O.sideFuture, O.future, O.prm.futureCol, O.prm, B, T, stampOf, modelAt, pictures);
}

}  // namespace fox::onion
