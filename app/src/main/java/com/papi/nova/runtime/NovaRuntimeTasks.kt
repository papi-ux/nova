package com.papi.nova.runtime

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.papi.nova.LimeLog
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NovaRuntimeTasks(
    private val owner: LifecycleOwner,
    private val logPrefix: String = "Nova"
) {
    private val jobsByName = Collections.synchronizedMap(LinkedHashMap<String, MutableSet<Job>>())

    fun launchIo(name: String, block: suspend CoroutineScope.() -> Unit): Job =
        launch(name, Dispatchers.IO, block)

    fun launchIoReplacing(name: String, block: suspend CoroutineScope.() -> Unit): Job {
        cancel(name)
        return launchIo(name, block)
    }

    fun launchMain(name: String, block: suspend CoroutineScope.() -> Unit): Job =
        launch(name, Dispatchers.Main.immediate, block)

    suspend fun runOnMainIfActive(block: () -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            if (isOwnerActive()) {
                block()
            }
        }
    }

    fun cancel(name: String) {
        val jobs = synchronized(jobsByName) {
            jobsByName.remove(name)?.toList().orEmpty()
        }
        jobs.forEach { it.cancel() }
    }

    fun cancelAll() {
        val jobs = synchronized(jobsByName) {
            jobsByName.values.flatten().also { jobsByName.clear() }
        }
        jobs.forEach { it.cancel() }
    }

    fun activeJobCount(name: String? = null): Int {
        return synchronized(jobsByName) {
            if (name == null) jobsByName.values.sumOf { it.size } else jobsByName[name]?.size ?: 0
        }
    }

    private fun launch(
        name: String,
        dispatcher: CoroutineDispatcher,
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val handler = CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                LimeLog.warning("$logPrefix: $name failed: ${throwable.message}")
            }
        }
        val job = owner.lifecycleScope.launch(dispatcher + CoroutineName(name) + handler) {
            if (!isOwnerActive()) return@launch
            block()
        }
        track(name, job)
        return job
    }

    private fun track(name: String, job: Job) {
        synchronized(jobsByName) {
            jobsByName.getOrPut(name) { LinkedHashSet() }.add(job)
        }
        job.invokeOnCompletion {
            synchronized(jobsByName) {
                val jobs = jobsByName[name] ?: return@synchronized
                jobs.remove(job)
                if (jobs.isEmpty()) {
                    jobsByName.remove(name)
                }
            }
        }
    }

    private fun isOwnerActive(): Boolean =
        owner.lifecycle.currentState != Lifecycle.State.DESTROYED
}
