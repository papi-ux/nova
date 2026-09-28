package com.papi.nova.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.papi.nova.R
import com.papi.nova.binding.input.KeyboardTranslator
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaMenuItem
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPage
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelWidth
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaRowTrailing
import com.papi.nova.ui.panel.NovaSectionLabel
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaSplitShape
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.utils.KeyConfigHelper
import com.papi.nova.utils.KeyMapper

/**
 * The Command Center's pages. The root is the Command Center itself; the others are one push away
 * from it and draw as lists of sections: the keys it can send, the host's server commands, and
 * More Controls, which carries every extra the legacy Quick Menu had and the Command Center did not.
 */
sealed interface CommandCenterPage : NovaPage {
    /** The Command Center: session, Doctor, overlays, controls and quick keys. */
    data class Root(override val title: String) : CommandCenterPage {
        override val key: String get() = RootKey
        override val width: NovaPanelWidth get() = NovaPanelWidth.Wide
    }

    /** A page that is a column of sections of rows. */
    sealed interface Listing : CommandCenterPage {
        val sections: List<CommandCenterSection>
    }

    /** Every key the Command Center can send: the default special keys and the imported custom ones. */
    class Keys(override val title: String, override val sections: List<CommandCenterSection>) : Listing {
        override val key: String get() = KeysKey
    }

    /** The host's server commands. */
    class ServerCommands(override val title: String, override val sections: List<CommandCenterSection>) : Listing {
        override val key: String get() = ServerCommandsKey
    }

    /** The legacy Quick Menu extras: clipboard, zoom, touch, server commands and controller mouse. */
    class MoreControls(override val title: String, override val sections: List<CommandCenterSection>) : Listing {
        override val key: String get() = MoreControlsKey
    }

    companion object {
        const val RootKey = "command-center"
        const val KeysKey = "command-center-keys"
        const val ServerCommandsKey = "command-center-server-commands"
        const val MoreControlsKey = "command-center-more-controls"
        const val MouseModeKey = "command-center-mouse-mode"
    }
}

/** A labelled group of rows on a [CommandCenterPage.Listing]; a null title draws no label. */
data class CommandCenterSection(val title: String?, val items: List<NovaMenuItem>)

/**
 * Draws a [CommandCenterPage.Listing]. An action that closes the panel waits for the stream (or
 * the companion deck) to hold focus again before it runs, as the legacy menu's game-focus actions
 * did: the soft keyboard, the clipboard and the keys all need the window they act on.
 */
@Composable
internal fun NovaPageScope.CommandCenterListingPage(page: CommandCenterPage.Listing) {
    val firstEnabled = remember(page) {
        page.sections.flatMap { it.items }.indexOfFirst { it !is NovaMenuItem.Action || it.disabledReason == null }
            .coerceAtLeast(0)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        var itemIndex = 0
        var listIndex = 0
        page.sections.forEachIndexed { sectionIndex, section ->
            section.title?.let { title ->
                item(key = "section-$sectionIndex") { NovaSectionLabel(title) }
                listIndex++
            }
            section.items.forEach { menuItem ->
                val focusIndex = listIndex
                val initial = itemIndex == firstEnabled
                item(key = menuItem.key) {
                    val focus = Modifier
                        .then(if (initial) Modifier.novaInitialFocus() else Modifier)
                        .novaRestorableFocus(menuItem.key, focusIndex)
                    CommandCenterMenuRow(menuItem, focus)
                }
                itemIndex++
                listIndex++
            }
        }
    }
}

@Composable
private fun NovaPageScope.CommandCenterMenuRow(item: NovaMenuItem, modifier: Modifier) {
    when (item) {
        is NovaMenuItem.Action -> NovaRow(
            title = item.label,
            caption = item.caption,
            icon = item.icon,
            emphasis = item.emphasis,
            disabledReason = item.disabledReason,
            onClick = {
                when {
                    !isTop -> Unit
                    item.closesPanel -> closeThen(awaitHostFocus = true, action = item.onClick)
                    else -> item.onClick()
                }
            },
            modifier = modifier,
        )
        is NovaMenuItem.Opens -> NovaRow(
            title = item.label,
            caption = item.caption,
            icon = item.icon,
            trailing = item.value?.let { NovaRowTrailing.Value(it) } ?: NovaRowTrailing.Opens,
            onClick = { if (isTop) panel.push(item.page()) },
            modifier = modifier,
        )
        is NovaMenuItem.Value<*> -> CommandCenterValueRow(item, modifier)
        // Alt + F4 on the Keys page: it closes the host's focused window, usually the game, so it
        // splits in its row (R3). Confirmed, it closes the panel and waits for the stream, as the
        // other keys do.
        is NovaMenuItem.Destructive -> NovaSplitConfirm(
            label = item.label,
            confirmLabel = item.confirmLabel,
            onConfirm = { if (isTop) closeThen(awaitHostFocus = true, action = item.onConfirm) },
            stayLabel = item.stayLabel ?: stringResource(R.string.nova_panel_stay),
            consequence = item.consequence,
            icon = item.icon,
            shape = NovaSplitShape.Row,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** A value row that shows its change at once; the page is a snapshot built when it was pushed. */
@Composable
private fun <T> CommandCenterValueRow(item: NovaMenuItem.Value<T>, modifier: Modifier) {
    var shown by remember(item.current) { mutableStateOf(item.current) }
    NovaValueRow(
        title = item.label,
        options = item.options,
        current = shown,
        onChange = {
            shown = it
            item.onChange(it)
        },
        style = item.style,
        modifier = modifier,
    )
}

/**
 * Mouse Mode as a Choice page: every mode by its original index, then the local cursor toggle.
 * On an external display only the touchpad modes and Disabled make sense, and they keep their
 * indexes, so a list position is never mistaken for a mode.
 */
object NovaMouseModeChoices {
    /** The choice that toggles the local cursor instead of picking a mode. */
    const val LocalCursor: Int = -1

    fun options(
        modeNames: List<String>,
        onExternalDisplay: Boolean,
        externalModes: Set<String>,
        localCursorLabel: String,
    ): List<NovaOption<Int>> =
        modeNames.mapIndexedNotNull { index, label ->
            NovaOption(index, label).takeIf { !onExternalDisplay || label in externalModes }
        } + NovaOption(LocalCursor, localCursorLabel)

    /** The page: it opens on [current], and one A applies a choice and pops back to the row. */
    fun page(title: String, options: List<NovaOption<Int>>, current: Int, onChoose: (Int) -> Unit) =
        NovaCommonPage.Choice(
            key = CommandCenterPage.MouseModeKey,
            title = title,
            options = options,
            current = current,
            onChoose = onChoose,
        )
}

/** Where the special keys and the imported custom shortcuts are stored. */
object NovaSpecialKeyPrefs {
    const val PREF_NAME: String = "specialPrefs"
    const val KEY_NAME: String = "special_key"
}

/** One key combination the Keys page can send. */
class NovaCommandCenterKey(val key: String, val label: String, val codes: ShortArray)

/**
 * The keys the Keys page offers: the default special keys unless Settings turned them off, then
 * the custom shortcuts imported into [NovaSpecialKeyPrefs]. [onBadImport] hears about a stored
 * shortcut file that no longer parses; the defaults are offered anyway.
 */
object NovaCommandCenterKeys {
    /** Alt + F4, which closes the host's focused window, usually the game; the Keys page splits it (R3). */
    const val CLOSE_APP_KEY: String = "alt-f4"

    fun defaults(context: Context): List<NovaCommandCenterKey> = listOf(
        key(context, "esc", R.string.game_menu_send_keys_esc, KeyboardTranslator.VK_ESCAPE),
        key(context, "f11", R.string.game_menu_send_keys_f11, KeyboardTranslator.VK_F11),
        key(context, "insert", R.string.game_menu_send_keys_insert, KeyboardTranslator.VK_INSERT),
        key(context, CLOSE_APP_KEY, R.string.game_menu_send_keys_alt_f4, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4),
        key(context, "alt-enter", R.string.game_menu_send_keys_alt_enter, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN),
        key(context, "ctrl-v", R.string.game_menu_send_keys_ctrl_v, KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V),
        key(context, "win", R.string.game_menu_send_keys_win, KeyboardTranslator.VK_LWIN),
        key(context, "win-d", R.string.game_menu_send_keys_win_d, KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D),
        key(context, "win-g", R.string.game_menu_send_keys_win_g, KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G),
        key(
            context, "ctrl-alt-tab", R.string.game_menu_send_keys_ctrl_alt_tab,
            KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_TAB,
        ),
        key(context, "shift-tab", R.string.game_menu_send_keys_shift_tab, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB),
        key(
            context, "win-shift-left", R.string.game_menu_send_keys_win_shift_left,
            KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT,
        ),
        key(
            context, "ctrl-alt-shift-f1", R.string.game_menu_send_keys_ctrl_alt_shift_f1,
            KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F1,
        ),
        key(
            context, "ctrl-alt-shift-f12", R.string.game_menu_send_keys_ctrl_alt_shift_f12,
            KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F12,
        ),
        key(
            context, "win-alt-b", R.string.game_menu_send_keys_alt_b,
            KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_B,
        ),
        key(context, "ctrl-1", R.string.game_menu_send_keys_ctrl_1, KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_1),
        key(context, "ctrl-2", R.string.game_menu_send_keys_ctrl_2, KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_2),
    )

    /** The imported custom shortcuts, in their stored order. */
    fun custom(context: Context, onBadImport: (Exception) -> Unit): List<NovaCommandCenterKey> {
        val value = context.getSharedPreferences(NovaSpecialKeyPrefs.PREF_NAME, Context.MODE_PRIVATE)
            .getString(NovaSpecialKeyPrefs.KEY_NAME, "")
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            KeyConfigHelper.parseShortcutFile(value).data.mapIndexed { index, shortcut ->
                NovaCommandCenterKey(
                    key = "custom-$index",
                    label = shortcut.name.orEmpty(),
                    codes = ShortArray(shortcut.keys.size) { keyCode(shortcut.keys[it]).toShort() },
                )
            }
        } catch (e: Exception) {
            onBadImport(e)
            emptyList()
        }
    }

    /** A stored key code: hexadecimal after `0x`, or the name of a KeyMapper constant. */
    internal fun keyCode(code: String): Int = when {
        code.startsWith("0x") -> Integer.parseInt(code.substring(2), 16)
        code.startsWith("VK_") -> KeyMapper::class.java.getDeclaredField(code).getInt(null)
        else -> throw IllegalArgumentException("Unknown key code: $code")
    }

    private fun key(context: Context, key: String, label: Int, vararg codes: Int) =
        NovaCommandCenterKey(key, context.getString(label), ShortArray(codes.size) { codes[it].toShort() })
}
