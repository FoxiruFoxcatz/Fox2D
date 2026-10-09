#pragma once
#include <GLES3/gl3.h>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace fox::img {

struct Image { int w = 0, h = 0; std::vector<uint8_t> px; };   // premultiplied RGBA8, row 0 = top

inline std::mutex gMu;
inline std::unordered_map<int, std::shared_ptr<const Image>> gStore;
inline int gNext = 1;

inline int put(std::shared_ptr<const Image> im) {
    std::lock_guard<std::mutex> l(gMu);
    const int h = gNext++;
    gStore[h] = std::move(im);
    return h;
}
inline std::shared_ptr<const Image> get(int h) {
    std::lock_guard<std::mutex> l(gMu);
    auto it = gStore.find(h);
    return it == gStore.end() ? nullptr : it->second;
}
inline void drop(int h) { std::lock_guard<std::mutex> l(gMu); gStore.erase(h); }

// Quad in layer paper units (0..1, y down): centre (cx,cy), size (w,h), rotation clockwise. Same mapping as the stroke VS.
inline constexpr const char* kImageVS = R"(#version 300 es
uniform vec4 uBox;
uniform float uRot;
out vec2 vUV;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUV = c;
    vec2 d = (c - 0.5) * uBox.zw;
    float cs = cos(uRot), sn = sin(uRot);
    vec2 p = uBox.xy + vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";
inline constexpr const char* kImageFS = R"(#version 300 es
precision highp float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 o;
void main() { o = texture(uTex, vUV); }
)";

inline GLuint makeTexture(const Image& im) {
    GLuint t = 0;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, im.w, im.h, 0, GL_RGBA, GL_UNSIGNED_BYTE, im.px.data());
    glGenerateMipmap(GL_TEXTURE_2D);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

// Draws into the currently bound FBO. Caller binds the FBO / viewport.
inline void draw(GLuint prog, GLuint tex, float cx, float cy, float w, float h, float rotDeg, GLuint emptyVao) {
    glUseProgram(prog);
    glUniform4f(glGetUniformLocation(prog, "uBox"), cx, cy, w, h);
    glUniform1f(glGetUniformLocation(prog, "uRot"), rotDeg * 0.017453292f);
    glUniform1i(glGetUniformLocation(prog, "uTex"), 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glBindVertexArray(emptyVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

}  // namespace fox::img