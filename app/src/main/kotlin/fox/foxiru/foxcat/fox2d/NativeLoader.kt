package fox.foxiru.foxcat.fox2d

object NativeLoader {
    init { System.loadLibrary(BuildConfig.NATIVE_LIB_NAME) }
    external fun mathTest(): String
}