package com.papi.nova.ui.panel

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import java.lang.ref.WeakReference

/** Where focus goes when a panel closes: a Compose element, a View (held weakly), or nowhere. */
sealed interface NovaFocusReturn {
    data object None : NovaFocusReturn

    class Compose(val requester: FocusRequester) : NovaFocusReturn

    class View(view: android.view.View) : NovaFocusReturn {
        private val ref = WeakReference(view)

        /** The view, while it is still alive. */
        val view: android.view.View? get() = ref.get()
    }
}

/**
 * One page on a stack, with what it had when another page covered it: its list position and
 * the key and list index of the element that last held focus.
 */
internal class NovaStackEntry(val id: Long, val page: NovaPage) {
    val listState = LazyListState()
    val initialRequester = FocusRequester()
    val groupRequester = FocusRequester()
    private val requesters = HashMap<Any, FocusRequester>()
    var focusKey: Any? = null
    var focusIndex: Int = -1

    /** Whether the page has been on top before, so returning to it restores rather than starts. */
    var shown: Boolean = false

    /** Whether the page's own buttons or B answered it, so leaving it runs no Stay or Close of its own. */
    var answered: Boolean = false

    fun requesterFor(key: Any): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun rememberFocus(key: Any, index: Int) {
        focusKey = key
        focusIndex = index
    }
}

/**
 * What a panel shows: a stack of pages, the edge it is attached to, and where focus returns
 * when it closes. Only the top page is composed. The stack is not saved across recreation,
 * because pages hold lambdas.
 *
 * A [NovaCommonPage.Confirm] or [NovaCommonPage.Notice] that leaves the stack without being
 * answered, by the header, the scrim, Start, a close, a pop or a new root, runs its Stay or its
 * Close, after it has gone, exactly as if the player had chosen it.
 */
@Stable
class NovaPanelState {
    private var nextId = 0L
    private val entries = mutableStateListOf<NovaStackEntry>()

    /** The edge the panel is attached to. */
    var edge: NovaEdge by mutableStateOf(NovaEdge.End)
        private set

    /** Where focus returns when the open panel closes. */
    var returnFocus: NovaFocusReturn = NovaFocusReturn.None
        private set

    // The closed panel's return target, held until the window that showed it closes and takes it.
    private var closedReturnFocus: NovaFocusReturn = NovaFocusReturn.None

    val isOpen: Boolean get() = entries.isNotEmpty()
    val top: NovaPage? get() = entries.lastOrNull()?.page
    val depth: Int get() = entries.size

    internal val topEntry: NovaStackEntry? get() = entries.lastOrNull()

    internal fun entryBelow(entry: NovaStackEntry): NovaStackEntry? =
        entries.indexOfFirst { it.id == entry.id }.takeIf { it > 0 }?.let { entries[it - 1] }

    internal fun contains(key: String): Boolean = entries.any { it.page.key == key }

    /** Opens the panel at [edge] with [root] as its only page, replacing anything it showed. */
    fun open(root: NovaPage, edge: NovaEdge = NovaEdge.End, returnFocus: NovaFocusReturn = NovaFocusReturn.None) {
        this.returnFocus = returnFocus
        switchRoot(root, edge)
    }

    /** Swaps to a peer root in the same window (L1/R1), keeping where focus returns. */
    fun switchRoot(root: NovaPage, edge: NovaEdge) {
        this.edge = edge
        val removed = entries.toList()
        entries.clear()
        entries += newEntry(root)
        dismissUnanswered(removed)
    }

    /** Pushes [page] on top. A closed panel opens with it as the root, at the right edge. */
    fun push(page: NovaPage) {
        if (!isOpen) {
            open(page)
        } else {
            entries += newEntry(page)
        }
    }

    /** Pops the top page. Returns false at the root, where the host closes the panel instead. */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        val removed = entries.removeAt(entries.lastIndex)
        dismissUnanswered(listOf(removed))
        return true
    }

    /** Replaces the top page with [page], which starts fresh. */
    fun replaceTop(page: NovaPage) {
        if (!isOpen) {
            open(page)
        } else {
            val removed = entries[entries.lastIndex]
            entries[entries.lastIndex] = newEntry(page)
            dismissUnanswered(listOf(removed))
        }
    }

    /** Closes the panel. Where focus returns is kept for [takeReturnFocus], and forgotten here. */
    fun close() {
        if (isOpen) closedReturnFocus = returnFocus
        returnFocus = NovaFocusReturn.None
        val removed = entries.toList()
        entries.clear()
        dismissUnanswered(removed)
    }

    /**
     * Where focus goes now that the panel has closed, handed out once, so a later window that
     * only held state pages never sends focus back to this panel's opener.
     */
    internal fun takeReturnFocus(): NovaFocusReturn =
        closedReturnFocus.also { closedReturnFocus = NovaFocusReturn.None }

    /**
     * Removes every page [predicate] matches, wherever it is in the stack, and quietly: an owner
     * taking its own pages down (closeDialogs, a settled confirm) runs none of their callbacks.
     */
    internal fun removeWhere(predicate: (NovaPage) -> Boolean) {
        entries.removeAll { predicate(it.page) }
    }

    /** Runs Stay or Close for the pages in [removed] nobody answered, top first. */
    private fun dismissUnanswered(removed: List<NovaStackEntry>) {
        removed.asReversed().filterNot { it.answered }.forEach { entry ->
            entry.answered = true
            when (val page = entry.page) {
                is NovaCommonPage.Confirm -> page.onStay()
                is NovaCommonPage.Notice -> page.onClose()
                else -> Unit
            }
        }
    }

    private fun newEntry(page: NovaPage) = NovaStackEntry(nextId++, page)
}

/** A [NovaPanelState] that lives as long as the caller's composition. */
@Composable
fun rememberNovaPanelState(): NovaPanelState = remember { NovaPanelState() }
