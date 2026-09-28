package com.papi.nova.ui.panel

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.StateFlow

/** Anything a panel can show. Owners declare sealed families; the host draws [NovaCommonPage] itself. */
interface NovaPage {
    /** Stable inside one stack; it keys the page's saved scroll and focus. */
    val key: String
    val title: String
    val width: NovaPanelWidth get() = NovaPanelWidth.Standard
}

/** The screen edge a panel is attached to. */
enum class NovaEdge { Start, End }

/** A panel's width class; the pixel width comes from [NovaPanelMetrics.panelWidth]. */
enum class NovaPanelWidth { Standard, Wide }

/** The tone of a status line, such as a host's connection state in a menu header. */
enum class NovaTone { Neutral, Info, Active, Warning, Danger }

/** One option of a choice. An option with a [disabledReason] shows it and cannot be picked. */
@Immutable
data class NovaOption<out T>(
    val value: T,
    val label: String,
    val caption: String? = null,
    val disabledReason: String? = null,
) {
    val enabled: Boolean get() = disabledReason == null
}

/** A labelled action on a page or a state page. */
@Immutable
data class NovaAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)

enum class NovaFieldKind { Text, Number, Password, Url }

/** One field of a [NovaCommonPage.Form]. */
@Immutable
data class NovaField(
    val key: String,
    val label: String,
    val initial: String = "",
    val kind: NovaFieldKind = NovaFieldKind.Text,
    val maxLength: Int? = null,
    val hint: String? = null,
)

/** One entry of a [NovaCommonPage.Menu]. */
sealed interface NovaMenuItem {
    val key: String

    /** Runs [onClick]; when [closesPanel], the panel closes first. */
    data class Action(
        override val key: String,
        val label: String,
        val caption: String? = null,
        @DrawableRes val icon: Int? = null,
        val emphasis: Boolean = false,
        val closesPanel: Boolean = true,
        val disabledReason: String? = null,
        val onClick: () -> Unit,
    ) : NovaMenuItem

    /** Pushes the page [page] builds, showing [value] beside the label when there is one. */
    data class Opens(
        override val key: String,
        val label: String,
        val caption: String? = null,
        val value: String? = null,
        @DrawableRes val icon: Int? = null,
        val page: () -> NovaPage,
    ) : NovaMenuItem

    /**
     * Splits in its own row into [stayLabel] and [confirmLabel]; see NovaSplitConfirm. [stayLabel]
     * is Stay unless the safe half has a better word, as Keep is for deleting something.
     */
    data class Destructive(
        override val key: String,
        val label: String,
        val confirmLabel: String,
        val consequence: String? = null,
        val stayLabel: String? = null,
        @DrawableRes val icon: Int? = null,
        val onConfirm: () -> Unit,
    ) : NovaMenuItem

    /** A value that changes in its own row; see NovaValueRow. */
    data class Value<T>(
        override val key: String,
        val label: String,
        val options: List<NovaOption<T>>,
        val current: T,
        val onChange: (T) -> Unit,
        val style: NovaValueStyle = NovaValueStyle.Auto,
    ) : NovaMenuItem
}

/** The identity shown above a menu, such as a host or an app. */
@Immutable
data class NovaMenuHeader(
    val title: String,
    val status: String? = null,
    val tone: NovaTone = NovaTone.Neutral,
    val hint: String? = null,
    @DrawableRes val icon: Int? = null,
)

/** Pages the host draws itself; no owner code is needed for them. */
sealed interface NovaCommonPage : NovaPage {
    /** A list of options that opens on [current]; one A picks and pops. */
    class Choice<T>(
        override val key: String,
        override val title: String,
        val options: List<NovaOption<T>>,
        val current: T?,
        val onChoose: (T) -> Unit,
        val leading: (@Composable (NovaOption<T>) -> Unit)? = null,
        override val width: NovaPanelWidth = NovaPanelWidth.Standard,
    ) : NovaCommonPage

    /** Options that each toggle, applied together by [doneLabel]. */
    class MultiChoice<T>(
        override val key: String,
        override val title: String,
        val options: List<NovaOption<T>>,
        val selected: Set<T>,
        val doneLabel: String,
        val onDone: (Set<T>) -> Unit,
    ) : NovaCommonPage

    /** One column of [items], under an optional [header]. */
    class Menu(
        override val key: String,
        override val title: String,
        val items: List<NovaMenuItem>,
        val header: NovaMenuHeader? = null,
    ) : NovaCommonPage

    /**
     * The fallback confirm for a caller with no button to split. Stay is focused; B runs Stay, and
     * so does leaving the page any other way (the header, the scrim, Start, the panel closing).
     */
    class Confirm(
        override val key: String,
        override val title: String,
        val message: AnnotatedString,
        val stayLabel: String,
        val actionLabel: String,
        val destructive: Boolean,
        val onConfirm: () -> Unit,
        val onStay: () -> Unit = {},
    ) : NovaCommonPage

    /**
     * A message with an optional [primary] action, a close action and an optional [help]. Leaving
     * it without an answer (the header, the scrim, Start, the panel closing) runs [onClose].
     */
    class Notice(
        override val key: String,
        override val title: String,
        val message: String,
        val primary: NovaAction? = null,
        val closeLabel: String,
        val onClose: () -> Unit = {},
        val help: NovaAction? = null,
        val monospace: Boolean = false,
    ) : NovaCommonPage

    /** Fields, then [submitLabel]. [onSubmit] returns error text, or null to pop. */
    class Form(
        override val key: String,
        override val title: String,
        val fields: List<NovaField>,
        val submitLabel: String,
        val warning: String? = null,
        val onSubmit: (Map<String, String>) -> String?,
    ) : NovaCommonPage

    /** An exact number: a track moved with Left and Right, a numeric field, and Save. */
    class Slider(
        override val key: String,
        override val title: String,
        val value: Int,
        val range: IntRange,
        val step: Int,
        val format: (Int) -> String,
        val onPreview: ((Int) -> Unit)? = null,
        val onSave: (Int) -> Unit,
    ) : NovaCommonPage

    /** Work in progress inside a panel, with an optional [cancel]. */
    class Busy(
        override val key: String,
        override val title: String,
        val message: StateFlow<String>,
        val cancel: NovaAction? = null,
    ) : NovaCommonPage
}
