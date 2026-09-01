package com.papi.nova.api

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/**
 * Execute a blocking OkHttp call while retaining transport-level cancellation.
 * Job cancellation closes the socket even when [Call.execute] or response-body
 * consumption is blocked on network I/O.
 */
internal suspend fun <T> executeCancellableHttpCall(
    call: Call,
    consume: (Response) -> T,
): T = coroutineScope {
    val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        try {
            withContext(Dispatchers.IO) {
                call.execute().use(consume)
            }
        } catch (failure: Throwable) {
            // OkHttp normally reports Call.cancel() as IOException. Preserve
            // coroutine cancellation as the authoritative outcome instead of
            // leaking that transport detail as an ordinary request failure.
            currentCoroutineContext().ensureActive()
            throw failure
        }
    } finally {
        cancellationWatcher.cancel()
    }
}
