package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.diagnostics.NovaMomentShare
import com.papi.nova.diagnostics.NovaStreamRecap
import com.papi.nova.diagnostics.NovaStreamRecapReporter
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPanelState
import java.io.File

/**
 * Save Report's pages in a panel, owned by identity.
 *
 * The press freezes the report (the reporter renders it there) and shows a closeable Saving Report
 * page that this controller owns. The save's answer replaces exactly that page, and only while it
 * is still the top page and the screen is in the foreground. Leaving it any way (B, Close, Start,
 * the scrim, the panel closing, a root switch, a quiet removal), a newer save or [retire] drops
 * that answer, so a result never reopens later or lands on someone else's page. Share stays the
 * Saved page's own explicit choice.
 */
internal class NovaStreamRecapReportPanel(
    private val context: Context,
    private val panel: NovaPanelState,
    /** Shows [page] in the panel: pushed when it's open, opened with it otherwise. */
    private val show: (NovaPage) -> Unit,
    /** The screen is still in the foreground. */
    private val foreground: () -> Boolean,
    runtime: NovaCommandCenterRuntime,
    render: (NovaStreamRecap.Recap) -> ByteArray?,
    write: (endedAtUtc: String, bytes: ByteArray) -> File?,
    offer: (File) -> NovaMomentShare.Result,
) {
    /** The Saving Report page this controller is waiting to answer, if any. Main thread only. */
    private var pending: NovaCommonPage.Notice? = null
    private var stopWatching: (() -> Unit)? = null
    private var tokens = 0L

    private val reporter = NovaStreamRecapReporter(
        runtime = runtime,
        render = render,
        write = write,
        saved = { report -> answer(NovaStreamRecapPages.saved(context, report) { share(report) }) },
        offer = offer,
        feedback = ::feedback,
        canShare = foreground,
    )

    /** Main thread, the recap page's explicit Save Report press. */
    fun save(recap: NovaStreamRecap.Recap) {
        // A newer save retires the older one and takes its pending page down quietly.
        drop()
        val page = savingPage(++tokens)
        pending = page
        show(page)
        stopWatching = panel.watchQuietRemoval(page) { abandon(page) }
        reporter.report(recap)
    }

    /** The screen is going: no answer may appear, and an unanswered Saving page goes quietly. */
    fun retire() = drop()

    private fun share(report: NovaStreamRecapReporter.Saved) = reporter.share(report)

    private fun savingPage(token: Long): NovaCommonPage.Notice {
        lateinit var page: NovaCommonPage.Notice
        page = NovaCommonPage.Notice(
            key = "nova-stream-recap-saving-$token",
            title = context.getString(R.string.nova_recap_report_saving_title),
            message = context.getString(R.string.nova_recap_report_saving_message),
            closeLabel = context.getString(R.string.nova_recap_close),
            // Any way off the stack other than its own answer: the save's result is no longer wanted.
            onClose = { abandon(page) },
        )
        return page
    }

    /** [page] left the stack unanswered; retire its save if it is still the one pending. */
    private fun abandon(page: NovaCommonPage.Notice) {
        if (pending !== page) return
        pending = null
        stopWatching?.invoke()
        stopWatching = null
        reporter.retire()
    }

    private fun drop() {
        val page = pending ?: run {
            reporter.retire()
            return
        }
        abandon(page)
        panel.removeWhere { it === page }
    }

    private fun feedback(outcome: NovaStreamRecapReporter.Outcome) {
        val problem = NovaStreamRecapPages.problem(context, outcome)
        if (pending != null) {
            // The save's own answer: a problem replaces its Saving page.
            answer(problem)
        } else if (problem != null && foreground()) {
            // Share's answer, from the Saved page's explicit press; that page has already left.
            show(problem)
        }
    }

    /**
     * Replaces the pending page with [result], only if that exact page is still on top and the
     * screen is in the foreground. Otherwise the answer is dropped and a covered Saving page goes
     * quietly, so nothing steals another page's place.
     */
    private fun answer(result: NovaPage?) {
        val page = pending ?: return
        pending = null
        stopWatching?.invoke()
        stopWatching = null
        if (result != null && foreground() && panel.top === page) {
            // replaceTop runs the Saving page's onClose; with pending already cleared it retires nothing.
            panel.replaceTop(result)
        } else {
            panel.removeWhere { it === page }
        }
    }
}
