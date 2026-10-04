package fox.foxiru.foxcat.fox2d

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FoxiruApp : Application() {
    @Inject lateinit var startup: StartupState
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                System.loadLibrary(BuildConfig.NATIVE_LIB_NAME)
            } finally {
                startup.markReady()
            }
        }
    }
}