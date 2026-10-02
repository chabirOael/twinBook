package io.github.chabiroael.twinbook.engine

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult

/**
 * Suspends until the GeckoResult completes. GeckoResult delivers callbacks on the Looper of
 * the thread that registers them, so registration happens on the main thread.
 */
suspend fun <T> GeckoResult<T>.await(): T? = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { cont ->
        accept(
            { value -> if (cont.isActive) cont.resume(value) },
            { error -> if (cont.isActive) cont.resumeWithException(error ?: IllegalStateException("GeckoResult failed")) },
        )
    }
}
