# --- Full R8 optimization/shrinking enabled (no -dontoptimize/-dontshrink) ---
# Keep your app package name-stable: JNI RegisterNatives, reflection, and
# your patchmod.py smali injection all resolve by exact class/method name.
-keep class fox.foxiru.foxcat.fox2d.** { *; }
-keepclassmembers class fox.foxiru.foxcat.fox2d.** { *; }

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-dontwarn org.apache.commons.io.**
-dontwarn java.nio.file.spi.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**

# Preserve JNI-callable natives anywhere in the app (defensive, in case any
# non-app-package class also declares native methods)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep anything JNI touches by name via FindClass/GetMethodID even if not
# in your package (e.g. loader iface structs, callback classes passed cross-so)
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Line numbers for tombstone/logcat symbolication, but hide real source file
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Signature/annotation attrs needed if you use generics/reflection anywhere
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Everything else (androidx, kotlin stdlib, third-party libs not covered by
# their own consumer-rules.txt) shrinks/obfuscates/optimizes normally.