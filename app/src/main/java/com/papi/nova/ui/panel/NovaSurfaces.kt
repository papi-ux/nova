package com.papi.nova.ui.panel

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.papi.nova.Game
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.utils.ExternalDisplayControlHost
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets a [NovaSurfaces.busy] block report what it is doing. */
interface NovaBusyScope : CoroutineScope {
    fun message(text: String)

    /** A fraction from 0 to 1, or null for indeterminate progress. */
    fun progress(fraction: Float?)
}

/**
 * The panel and the state pages of one activity (or one companion display), in one window.
 *
 * The window is created on demand and exists while the panel is open or a state page is showing.
 * State pages draw above the panel. [show], [update], [dismiss] and [clear] may be called from any
 * thread: state pages live in a [MutableStateFlow], and panel changes are posted to the main thread.
 * Pages [present]ed or shown for an owner are removed by [clear] for that owner, here or, through
 * [clearEverywhere], in every live activity.
 */
class NovaSurfaces internal constructor(internal val placement: NovaWindowPlacement) {
    val panel = NovaPanelState()
    private val stateList = MutableStateFlow<List<NovaStatePage>>(emptyList())
    val states: StateFlow<List<NovaStatePage>> = stateList.asStateFlow()

    /** Draws the owner pages of the panel opened last through [open]. */
    internal var pageContent: NovaPageContent by mutableStateOf({})
        private set

    /** The owner's hints (L1/R1 for peer panels, X/Y page actions) for the panel opened last. */
    internal var pageHints: List<NovaControllerHint> by mutableStateOf(emptyList())
        private set

    /** Where L1 and R1 go in the panel opened last; null leaves them alone. */
    internal var pageShoulder: ((NovaShoulder) -> Unit)? by mutableStateOf(null)
        private set

    // Main thread only: which owner presented each page key still on the stack.
    private val presented = HashMap<String, NovaStateOwner>()
    private var window: NovaPanelWindow? = null
    private var disposed = false

    /**
     * Opens the panel with [root] at [edge]. [hints] join A and B in the hint bar, such as L1/R1
     * for peer panels, and [onShoulder] takes L1 and R1, such as a [NovaPanelState.switchRoot] to
     * a peer. Main thread (posts if called elsewhere).
     */
    fun open(
        root: NovaPage,
        edge: NovaEdge = NovaEdge.End,
        returnFocus: NovaFocusReturn = NovaFocusReturn.None,
        hints: List<NovaControllerHint> = emptyList(),
        onShoulder: ((NovaShoulder) -> Unit)? = null,
        content: NovaPageContent = {},
    ) = onMain {
        pageContent = content
        pageHints = hints
        pageShoulder = onShoulder
        panel.open(root, edge, returnFocus)
        ensureWindow()
    }

    /** Pushes onto the open panel, or opens a right-edge panel with [page] as its root. */
    fun present(page: NovaPage, owner: NovaStateOwner = NovaStateOwner.App) = onMain {
        presented.keys.retainAll { panel.contains(it) }
        presented[page.key] = owner
        if (panel.isOpen) {
            panel.push(page)
        } else {
            pageHints = emptyList()
            pageShoulder = null
            panel.open(page)
        }
        ensureWindow()
    }

    /** Shows [page] above everything, replacing a page with the same key. Any thread. */
    fun show(page: NovaStatePage) {
        stateList.update { pages -> pages.filterNot { it.key == page.key } + page }
        onMain { ensureWindow() }
    }

    /** Replaces the state page with [key] by [transform] of it. Any thread. */
    fun update(key: String, transform: (NovaStatePage) -> NovaStatePage) {
        stateList.update { pages -> pages.map { if (it.key == key) transform(it) else it } }
    }

    /** Removes the state page with [key]. A visible Busy page still honours its minimum time. Any thread. */
    fun dismiss(key: String) {
        stateList.update { pages -> pages.filterNot { it.key == key } }
    }

    /** Removes the state pages and the presented pages [owner] put up. Any thread. */
    fun clear(owner: NovaStateOwner) {
        stateList.update { pages -> pages.filterNot { it.owner == owner } }
        onMain {
            presented.keys.retainAll { panel.contains(it) }
            val keys = presented.filterValues { it == owner }.keys.toSet()
            if (keys.isNotEmpty()) {
                panel.removeWhere { it.key in keys }
                presented.keys.removeAll(keys)
            }
        }
    }

    /**
     * Presents [page] and suspends until it is answered: true for its action, false for Stay, B,
     * or the panel closing any other way.
     */
    suspend fun confirm(page: NovaCommonPage.Confirm): Boolean = withContext(Dispatchers.Main.immediate) {
        val answer = CompletableDeferred<Boolean>()
        val asked = NovaCommonPage.Confirm(
            key = page.key,
            title = page.title,
            message = page.message,
            stayLabel = page.stayLabel,
            actionLabel = page.actionLabel,
            destructive = page.destructive,
            onConfirm = {
                page.onConfirm()
                answer.complete(true)
            },
            onStay = {
                page.onStay()
                answer.complete(false)
            },
        )
        present(asked)
        val closedElsewhere = launch {
            snapshotFlow { panel.contains(asked.key) }.first { !it }
            answer.complete(false)
        }
        try {
            answer.await()
        } finally {
            closedElsewhere.cancel()
            panel.removeWhere { it === asked }
        }
    }

    /**
     * Runs [block] under a full-screen Busy page titled [title]. With [cancelLabel], Cancel and B
     * cancel the block, and this throws its CancellationException; without it the page cannot be
     * left until the block ends.
     */
    suspend fun <T> busy(
        title: String,
        message: String = "",
        cancelLabel: String? = null,
        block: suspend NovaBusyScope.() -> T,
    ): T = coroutineScope {
        val key = "nova-busy-" + busySerial.incrementAndGet()
        val text = MutableStateFlow(message)
        val fraction = MutableStateFlow<Float?>(null)
        val job = coroutineContext.job
        show(
            NovaStatePage.Busy(
                key = key,
                title = title,
                message = text,
                progress = fraction,
                cancel = cancelLabel?.let { NovaAction(it) { job.cancel() } },
            ),
        )
        try {
            BusyScope(this, text, fraction).block()
        } finally {
            dismiss(key)
        }
    }

    /** Closes the window and forgets this instance. Main thread (posts if called elsewhere). */
    fun dispose() = onMain {
        disposed = true
        stateList.value = emptyList()
        panel.close()
        closeWindow()
        unregister(this)
    }

    /** Called by the window when the panel has closed and no state page is showing. */
    internal fun onWindowIdle() {
        if (!panel.isOpen && stateList.value.isEmpty()) closeWindow()
    }

    private fun ensureWindow() {
        if (disposed || window != null || !placement.canShow) return
        window = NovaPanelWindow(placement, this).also { created ->
            created.setOnDismissListener {
                // A dismissal closeWindow did not start (the system took the window away): the
                // panel goes with it, and input and focus still go back where they belong.
                if (window === created) {
                    window = null
                    panel.close()
                    created.restorePlacement()
                    returnFocus()
                }
            }
            created.showNow()
        }
    }

    private fun closeWindow() {
        val closing = window ?: return
        window = null
        closing.closeNow()
        returnFocus()
    }

    /** Sends focus back to what opened the panel that closed last, once. */
    private fun returnFocus() {
        val target = panel.takeReturnFocus()
        if (placement is NovaWindowPlacement.Screen) {
            placement.hostView.post { returnFocusTo(target) }
        }
    }

    private fun returnFocusTo(target: NovaFocusReturn) {
        when (target) {
            NovaFocusReturn.None -> Unit
            is NovaFocusReturn.Compose -> target.requester.requestFocus()
            is NovaFocusReturn.View -> target.view?.requestFocus()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun watch(activity: ComponentActivity) = onMain {
        val lifecycle = activity.lifecycle
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
            dispose()
        } else {
            lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) dispose() })
        }
    }

    private class BusyScope(
        scope: CoroutineScope,
        private val text: MutableStateFlow<String>,
        private val fraction: MutableStateFlow<Float?>,
    ) : NovaBusyScope, CoroutineScope by scope {
        override fun message(text: String) {
            this.text.value = text
        }

        override fun progress(fraction: Float?) {
            this.fraction.value = fraction
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val busySerial = AtomicLong()
        private val byActivity = WeakHashMap<Activity, NovaSurfaces>()
        private val companions: MutableSet<NovaSurfaces> = Collections.newSetFromMap(WeakHashMap())

        /**
         * The surfaces of [activity]: Game gets Stream placement, any other ComponentActivity gets
         * Screen. Created on first use from any thread, and disposed at the activity's ON_DESTROY.
         */
        fun of(activity: Activity): NovaSurfaces {
            require(activity is ComponentActivity) {
                "NovaSurfaces needs a ComponentActivity; ${activity.javaClass.name} is not one"
            }
            synchronized(byActivity) {
                byActivity[activity]?.let { return it }
                val placement = if (activity is Game) {
                    NovaWindowPlacement.Stream(activity)
                } else {
                    NovaWindowPlacement.Screen(activity)
                }
                return NovaSurfaces(placement).also {
                    byActivity[activity] = it
                    it.watch(activity)
                }
            }
        }

        /** The surfaces [activity] already has, or null; unlike [of], this never creates any. */
        fun existing(activity: Activity): NovaSurfaces? = synchronized(byActivity) { byActivity[activity] }

        /** Surfaces on a companion display. The caller disposes them with the display. */
        fun forCompanion(host: ExternalDisplayControlHost, onClosed: () -> Unit): NovaSurfaces =
            NovaSurfaces(NovaWindowPlacement.Companion(host, onClosed)).also {
                synchronized(companions) { companions += it }
            }

        /** Clears [owner]'s pages in every live instance. Any thread. */
        fun clearEverywhere(owner: NovaStateOwner) {
            val all = synchronized(byActivity) { byActivity.values.toList() } +
                synchronized(companions) { companions.toList() }
            all.forEach { it.clear(owner) }
        }

        private fun unregister(surfaces: NovaSurfaces) {
            synchronized(byActivity) { byActivity.values.removeAll { it === surfaces } }
            synchronized(companions) { companions.remove(surfaces) }
        }
    }
}

/** This activity's [NovaSurfaces]. */
val Activity.novaSurfaces: NovaSurfaces get() = NovaSurfaces.of(this)

/**
 * The window's content: the panel frame with its page stack, and the state pages above it.
 * [onIdle] runs once the panel's exit motion has landed and no state page is showing or pending.
 */
@Composable
internal fun NovaSurfacesLayer(
    panel: NovaPanelState,
    states: List<NovaStatePage>,
    scrim: NovaScrim,
    pageContent: NovaPageContent,
    onIdle: () -> Unit,
    modifier: Modifier = Modifier,
    hints: List<NovaControllerHint> = emptyList(),
    onShoulder: ((NovaShoulder) -> Unit)? = null,
) {
    val panelOpen = panel.isOpen
    var framePresent by remember { mutableStateOf(panelOpen) }
    LaunchedEffect(panelOpen) { if (panelOpen) framePresent = true }
    var statesShowing by remember { mutableStateOf(false) }
    var focusRefresh by remember { mutableIntStateOf(0) }
    val lastWidth = remember { RetainedWidth() }
    val width = panel.top?.width?.also { lastWidth.width = it } ?: lastWidth.width
    Box(modifier = modifier.fillMaxSize()) {
        if (framePresent || panelOpen) {
            CompositionLocalProvider(LocalNovaFocusRefresh provides focusRefresh) {
                NovaPanelFrame(
                    edge = panel.edge,
                    width = width,
                    open = panelOpen,
                    onDismissRequest = panel::close,
                    onClosed = { framePresent = false },
                    scrim = scrim,
                ) {
                    NovaPageStackHost(state = panel, onShoulder = onShoulder, hints = hints, content = pageContent)
                }
            }
        }
        NovaStatePages(
            pages = states,
            onShowingChange = { showing ->
                if (statesShowing && !showing) focusRefresh++
                statesShowing = showing
            },
        )
    }
    val idle = !panelOpen && !framePresent && !statesShowing && states.isEmpty()
    LaunchedEffect(idle) { if (idle) onIdle() }
}

private class RetainedWidth {
    var width: NovaPanelWidth = NovaPanelWidth.Standard
}

