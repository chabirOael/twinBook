package io.github.chabiroael.twinbook

import android.app.Application
import android.util.Log
import io.github.chabiroael.twinbook.capture.CaptureStore
import java.io.File

class TwinBookApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // GeckoView runs extra processes of this app (content, GPU); they must not touch the
        // capture store, or they would delete the session the main process is recording.
        if (processName() != packageName) return
        // A session without FINALIZED was interrupted (process death): it may hold secrets that
        // layer 2 never scrubbed, so it is deleted now and can never be pulled.
        val deleted = CaptureStore(capturesDir(this)).deleteUnfinalized()
        if (deleted.isNotEmpty()) Log.w(AppEngine.TAG, "deleted unfinalized capture sessions at start: $deleted")
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else File("/proc/self/cmdline").readText().trimEnd('\u0000')

    companion object {
        fun capturesDir(app: android.content.Context): File = File(app.filesDir, "captures")
    }
}
