package com.papi.nova.ui

import android.content.Context
import com.papi.nova.R
import com.papi.nova.nvstream.HostRefusal
import com.papi.nova.ui.panel.NovaAction
import com.papi.nova.ui.panel.NovaProblemBack
import com.papi.nova.ui.panel.NovaStatePage

/**
 * A launch the host refused or Nova gave up on, as a full-screen Problem page over the stream.
 *
 * The stream never started, so there is nothing to go back to: [retry] is the focused primary,
 * and B, Back and Escape run [leave], which returns to Nova and never retries. Every action
 * [takeDown]s the page before it runs. In a Space ([space]) the page reads the host's [refusal]
 * and its fix, with Nova's own [message] behind Details; otherwise it reads [message].
 */
internal fun novaLaunchIssuePage(
    context: Context,
    key: String,
    message: String,
    space: Boolean,
    refusal: HostRefusal?,
    retry: () -> Unit,
    leave: () -> Unit,
    takeDown: () -> Unit,
): NovaStatePage.Problem {
    fun leaving(label: Int, run: () -> Unit) = NovaAction(context.getString(label)) {
        takeDown()
        run()
    }
    return if (space) {
        val back = leaving(R.string.nova_space_launch_issue_back, leave)
        NovaStatePage.Problem(
            key = key,
            title = context.getString(R.string.nova_space_launch_issue_title),
            message = listOfNotNull(refusal?.message, refusal?.action ?: context.getString(R.string.nova_space_launch_issue_default))
                .joinToString("\n\n"),
            primary = leaving(R.string.nova_space_launch_issue_retry, retry),
            back = NovaProblemBack.Close(back),
            secondary = listOf(back),
            detail = message,
        )
    } else {
        val dismiss = leaving(R.string.nova_launch_issue_dismiss, leave)
        NovaStatePage.Problem(
            key = key,
            title = context.getString(R.string.nova_launch_issue_title),
            message = message,
            primary = leaving(R.string.nova_stream_launch_retry, retry),
            back = NovaProblemBack.Close(dismiss),
            secondary = listOf(dismiss),
        )
    }
}
