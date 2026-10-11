package fox.foxiru.foxcat.fox2d

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xcrash.ICrashCallback
import xcrash.XCrash

private const val TAG = "FoxiruApp"

private const val CRASH_UI_RENDER_TIMEOUT_MS = 3000L

@HiltAndroidApp
class FoxiruApp : Application() {
    @Inject lateinit var startup: StartupState
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Lazy: needs the base context, which only exists once attachBaseContext() has run.
    // If the process name can't be read we assume "main" so crash reporting is never lost.
    private val isMainProcess: Boolean by lazy {
        val name = currentProcessName()
        name == null || name == packageName
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Earliest hook point: before ContentProviders, Hilt and the native engine, so crashes
        // there are captured too. The ":crashui" process must NOT install handlers.
        if (isMainProcess) initXCrash()
    }

    override fun onCreate() {
        super.onCreate()
        // ":crashui" only hosts CrashActivity: no native engine, no startup work. This also means
        // a crash inside the native library can never take the crash viewer down with it.
        if (!isMainProcess) return
        appScope.launch {
            try {
                System.loadLibrary(BuildConfig.NATIVE_LIB_NAME)
            } finally {
                startup.markReady()
            }
        }
    }

    // ───────────────────────────── xCrash ─────────────────────────────

    private fun initXCrash() {
        val onCrash = ICrashCallback { logPath, _ -> showCrashUi(logPath) }
        val params = XCrash.InitParameters()
            .setAppVersion(BuildConfig.VERSION_NAME)
            // Same directory xCrash uses by default; CrashActivity.deleteTombstones() cleans it.
            .setLogDir(File(filesDir, "tombstones").absolutePath)
            .setJavaCallback(onCrash)
            .setNativeCallback(onCrash)
        // ANR handler stays at its defaults (log only, no UI): ANRs are often recoverable.
        try {
            XCrash.init(this, params)
        } catch (t: Throwable) {
            // e.g. libxcrash.so failed to load; never let crash reporting break startup.
            Log.e(TAG, "xCrash init failed", t)
        }
    }

    /**
     * Runs on the crashing thread (Java crash) or on xCrash's callback thread (native crash), and
     * the process is killed as soon as it returns. So: launch the viewer, then wait (bounded)
     * until it has drawn its first frame. The viewer is another process, hence the broadcast.
     *
     * [logPath] is null when xCrash could not create the log file; there is nothing to show then.
     */
    private fun showCrashUi(logPath: String?) {
        if (logPath.isNullOrEmpty()) return

        val rendered = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = rendered.countDown()
        }
        // Own looper: after a main-thread crash the main looper is blocked by this very callback,
        // so a receiver on it could never fire.
        val thread = HandlerThread("xcrash-ui-wait").apply { start() }
        var registered = false
        try {
            val filter = IntentFilter(CrashActivity.ACTION_RENDERED)
            val handler = Handler(thread.looper)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter, null, handler)
            }
            registered = true

            startActivity(
                Intent(this, CrashActivity::class.java)
                    .putExtra(CrashActivity.LOG_PATH, logPath)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            rendered.await(CRASH_UI_RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            // Swallow everything: an exception escaping here would mask the original crash.
            Log.e(TAG, "failed to show crash UI", t)
        } finally {
            if (registered) runCatching { unregisterReceiver(receiver) }
            thread.quitSafely()
        }
    }

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // cmdline is NUL-terminated; trim strips the padding.
            runCatching { File("/proc/self/cmdline").readText().trim { it <= ' ' } }.getOrNull()
        }
}
