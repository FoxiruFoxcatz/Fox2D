// fox_project.cpp - JNI for NativeProject.kt: load strokes.bin in C++ (zlib), hand the result to Kotlin as a few flat arrays.
// Add this file to the same shared library as fox_canvas.cpp and link zlib (NDK: find_library(z-lib z)).
#include <jni.h>

#include <algorithm>
#include <string>

#include "fox_strokeblob.h"

namespace {
thread_local std::string tlsErr;
}

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_fox_foxiru_foxcat_fox2d_jnicallers_NativeProject_##name

// Blocking (inflate + parse): call from Dispatchers.IO. 0 = failed, see nativeError().
FN(jlong, nativeLoadStrokes)(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    tlsErr.clear();
    auto b = fox::proj::parseStrokesFile(path, tlsErr);
    env->ReleaseStringUTFChars(jpath, path);
    return b ? (jlong) fox::proj::put(std::move(b)) : 0;
}

FN(jstring, nativeError)(JNIEnv* env, jclass) { return env->NewStringUTF(tlsErr.c_str()); }

FN(jint, nativeCount)(JNIEnv*, jclass, jlong h) {
    auto b = fox::proj::get(h);
    return b ? b->count : 0;
}

FN(jintArray, nativeMeta)(JNIEnv* env, jclass, jlong h) {
    auto b = fox::proj::get(h);
    const jsize n = b ? (jsize) b->meta.size() : 0;
    jintArray a = env->NewIntArray(n);
    if (a && n) env->SetIntArrayRegion(a, 0, n, b->meta.data());
    return a;
}

FN(jfloatArray, nativeSizes)(JNIEnv* env, jclass, jlong h) {
    auto b = fox::proj::get(h);
    const jsize n = b ? (jsize) b->sizes.size() : 0;
    jfloatArray a = env->NewFloatArray(n);
    if (a && n) env->SetFloatArrayRegion(a, 0, n, b->sizes.data());
    return a;
}

// Direct buffer over the point floats (host byte order): no copy onto the Java heap. Valid until nativeRelease; null = no points.
FN(jobject, nativePoints)(JNIEnv* env, jclass, jlong h) {
    auto b = fox::proj::get(h);
    if (!b || b->pts.empty()) return nullptr;
    return env->NewDirectByteBuffer(b->pts.data(), (jlong) (b->pts.size() * sizeof(float)));
}

FN(jobjectArray, nativeImageFiles)(JNIEnv* env, jclass, jlong h) {
    auto b = fox::proj::get(h);
    const jsize n = b ? (jsize) b->files.size() : 0;
    jclass sc = env->FindClass("java/lang/String");
    jobjectArray a = env->NewObjectArray(n, sc, nullptr);
    for (jsize i = 0; i < n; i++) {
        jstring s = env->NewStringUTF(b->files[(size_t) i].c_str());
        env->SetObjectArrayElement(a, i, s);
        env->DeleteLocalRef(s);
    }
    return a;
}

FN(jfloatArray, nativeImageData)(JNIEnv* env, jclass, jlong h) {
    auto b = fox::proj::get(h);
    const jsize n = b ? (jsize) b->img.size() : 0;
    jfloatArray a = env->NewFloatArray(n);
    if (a && n) env->SetFloatArrayRegion(a, 0, n, b->img.data());
    return a;
}

FN(void, nativeRelease)(JNIEnv*, jclass, jlong h) { fox::proj::drop(h); }
