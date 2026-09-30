package com.papi.nova.preferences

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import com.papi.nova.ui.compose.NovaInPlaceKeyboard
import com.papi.nova.ui.panel.NovaBackHandler
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.NovaSplitShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.papi.nova.R
import com.papi.nova.binding.video.PyroWaveAvailability
import com.papi.nova.ui.NovaHudMode
import com.papi.nova.ui.NovaHudPreferences
import com.papi.nova.ui.NovaHudUiState
import com.papi.nova.ui.NovaMenuOpacityPreview
import com.papi.nova.ui.NovaMenuPreferences
import com.papi.nova.ui.NovaStreamHudContent
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.compose.LocalNovaComposeColors
import com.papi.nova.ui.compose.LocalNovaFormFactor
import com.papi.nova.ui.compose.NovaFormFactor
import com.papi.nova.ui.compose.LocalNovaLibrarySurfaces
import com.papi.nova.ui.compose.LocalNovaMenuOpacityScale
import com.papi.nova.ui.compose.NovaActionSurface
import com.papi.nova.ui.compose.NovaControllerHint
import com.papi.nova.ui.compose.NovaRadius
import com.papi.nova.ui.compose.NOVA_FIRST_FOCUS_SETTLE_MS
import com.papi.nova.ui.compose.NovaSearchTextField
import com.papi.nova.ui.compose.novaHoldsFirstFocus
import com.papi.nova.ui.panel.NovaCommonPage
import com.papi.nova.ui.panel.NovaEdge
import com.papi.nova.ui.panel.NovaOption
import com.papi.nova.ui.panel.NovaPageScope
import com.papi.nova.ui.panel.NovaPageStackHost
import com.papi.nova.ui.panel.NovaPanelDensity
import com.papi.nova.ui.panel.NovaPanelDensityHost
import com.papi.nova.ui.panel.LocalNovaPanelDensity
import com.papi.nova.ui.panel.NovaPanelMetrics
import com.papi.nova.ui.panel.NovaPanelState
import com.papi.nova.ui.panel.NovaKeys
import com.papi.nova.ui.panel.NovaPressLatch
import com.papi.nova.ui.panel.NovaRemoteInput
import com.papi.nova.ui.panel.NovaRow
import com.papi.nova.ui.panel.NovaRowTrailing
import com.papi.nova.ui.panel.NovaStepperRow
import com.papi.nova.ui.panel.NovaUseDefault
import com.papi.nova.ui.panel.NovaValueRow
import com.papi.nova.ui.panel.NovaValueStyle
import com.papi.nova.ui.panel.novaClickable
import com.papi.nova.ui.panel.novaFocusRing
import com.papi.nova.ui.panel.novaPanelType
import com.papi.nova.ui.panel.novaRowRest
import com.papi.nova.ui.panel.novaScrollEdgeFade
import com.papi.nova.ui.panel.novaTouchReach
import com.papi.nova.ui.panel.rememberNovaSplitConfirmState
import com.papi.nova.ui.panel.NovaRowContextScrolling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NovaSettingsScreen(
    viewModel: NovaSettingsViewModel,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenLegacy: () -> Unit,
    onAction: (NovaSettingDefinition) -> Unit,
    headerActions: List<NovaSettingsHeaderAction> = emptyList(),
    /** The row to put focus back on, such as the one that opened the Legacy screen B just left. */
    returnToRow: String? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    NovaSettingsContent(
        state = state,
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        onOpenLegacy = onOpenLegacy,
        onSearch = viewModel::updateSearch,
        onClearSearch = viewModel::clearSearch,
        onCategory = viewModel::selectCategory,
        headerActions = headerActions,
        onResetSetting = viewModel::resetValue,
        onValue = { definition, value, onCompleted ->
            viewModel.setValue(definition = definition, value = value, onCompleted = onCompleted)
            applyThemeSelectionIfNeeded(context, definition, value)
        },
        onSetting = { definition ->
            if (definition.key == RESET_STREAM_UI_DEFAULTS_KEY) {
                viewModel.resetStreamUiDefaults()
            } else {
                onAction(definition)
            }
        },
        returnToRow = returnToRow,
    )
}

data class NovaSettingsHeaderAction(
    val label: String,
    val onClick: () -> Unit
)

/** Writes a setting and reports when the write has landed. */
internal typealias NovaSettingWrite = (NovaSettingDefinition, NovaSettingValue, onCompleted: () -> Unit) -> Unit

private const val RESET_STREAM_UI_DEFAULTS_KEY = "nova_reset_stream_ui"
private const val RESET_ON_SCREEN_CONTROLS_KEY = "option_reset_osc_preference"

/** Resets that cannot be undone, which confirm in their own row. */
private val SPLIT_CONFIRM_KEYS = setOf(RESET_STREAM_UI_DEFAULTS_KEY, RESET_ON_SCREEN_CONTROLS_KEY)
private const val OVERLAYS_CATEGORY_KEY = "category_overlays"
private const val SEARCH_PANE_KEY = "search"

private fun applyThemeSelectionIfNeeded(
    context: Context,
    definition: NovaSettingDefinition,
    value: NovaSettingValue
) {
    if (definition.key != "nova_theme" || value !is NovaSettingValue.StringValue) return

    NovaThemeManager.setTheme(context, value.value)
    val activity = context.findActivity() ?: return
    activity.window.decorView.post { activity.recreate() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private val NovaSettingsCardShape = RoundedCornerShape(NovaRadius.row)

private object NovaSettingsMetrics {
    fun categoryRailWidthDp(): Int = 196
    fun wideColumnSpacingDp(): Int = 14
    fun headerMinHeightDp(): Int = 52
    fun headerToQuickStripSpacingDp(): Int = 6
    fun quickStripToContentSpacingDp(): Int = 6
    fun quickPillMaxWidthDp(): Int = 280
    fun searchClearMinHeightDp(): Int = 32

    /** The least a finger is given to hit, whatever a control looks like. */
    fun touchTargetMinDp(): Int = 48
}

/**
 * Modern Settings: a header, the quick strip, the category rail and the pane.
 *
 * The pane is a [NovaPageStackHost] whose root page is the selected category's rows. A Select
 * changes in its own row or opens its list as a page, by [selectPresentation]; sliders step in
 * place and open an exact page on A; text settings open a Form page. Pages replace the rows in
 * the pane, and B pops one. At the rows, B goes back to the rail, and on the rail it leaves.
 * L1 and R1 step through the categories.
 */
@Composable
internal fun NovaSettingsContent(
    state: NovaSettingsUiState,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onOpenLegacy: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onCategory: (String) -> Unit,
    headerActions: List<NovaSettingsHeaderAction>,
    onResetSetting: (NovaSettingDefinition) -> Unit,
    onValue: NovaSettingWrite,
    onSetting: (NovaSettingDefinition) -> Unit,
    returnToRow: String? = null,
) {
    val colors = LocalNovaComposeColors.current
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val latestState by rememberUpdatedState(state)
    val back by rememberUpdatedState(onBack)
    val select by rememberUpdatedState(onCategory)
    val clearSearch by rememberUpdatedState(onClearSearch)
    val paneKey = state.paneKey()
    val rootTitle = if (state.isSearchActive()) {
        stringResource(R.string.nova_settings_search_title)
    } else {
        state.categories.firstOrNull { it.key == state.selectedCategoryKey }?.title.orEmpty()
    }
    val pane = remember { NovaPanelState().apply { open(SettingsPage.Rows(paneKey, rootTitle)) } }
    val focus = rememberNovaSettingsFocus(pane)
    // Back from the Legacy screen a row opened lands on that row again, not on the rail's first
    // category with the row out of sight in the pane. It waits as the rail's first focus would.
    LaunchedEffect(Unit) {
        val row = returnToRow ?: return@LaunchedEffect
        delay(NOVA_FIRST_FOCUS_SETTLE_MS)
        focus.enterPane(latestState.paneKey(), row)
    }
    // A new category or a search swaps the pane's root; that also drops any page pushed over it.
    LaunchedEffect(paneKey, rootTitle) {
        val root = SettingsPage.Rows(paneKey, rootTitle)
        if (pane.depth != 1 || pane.top != root) pane.switchRoot(root, NovaEdge.End)
    }
    val pyroWave = rememberPyroWaveStatus(state)
    val menuOpacityPreview = rememberMenuOpacityPreview(pane)
    val opener = remember(context, pane) {
        NovaSettingsPageOpener(context, pane, onValue, menuOpacityPreview)
    }
    opener.onValue = onValue
    opener.onReset = onResetSetting
    opener.pyroWave = pyroWave

    // Whether the last key came from a remote, which has neither X nor shoulders: the hint bar then
    // names its OK and Back, as the Library's does (C04). A television starts there, before any key.
    var remoteKeys by remember { mutableStateOf(NovaRemoteInput.startsOnRemote(context)) }
    // The row with focus, so the hint bar can say a hold resets it (C02).
    var focusedRow by remember { mutableStateOf<String?>(null) }
    val holdReset = focusedRow
        ?.let { key -> state.visibleSettings.firstOrNull { it.key == key } }
        ?.let { it.resetsByHold && state.canReset(it) && state.isEnabled(it) } == true
    val hints = novaSettingsHints(
        wide = wide,
        canReset = state.resettableKeys.isNotEmpty(),
        remote = remoteKeys,
        holdReset = holdReset,
    )
    val shoulderLatch = remember { NovaPressLatch() }
    fun stepCategory(delta: Int) {
        val current = latestState
        val categories = current.categories
        if (categories.isEmpty()) return
        val from = categories.indexOfFirst { it.key == current.selectedCategoryKey }.coerceAtLeast(0)
        // The shoulders stop at the ends, as the rail does: L1 on the first category had wrapped
        // to the last one.
        val to = (from + delta).coerceIn(0, categories.lastIndex)
        if (to == from) return
        val next = categories[to].key
        if (current.isSearchActive()) clearSearch()
        select(next)
        when {
            focus.paneHasFocus -> focus.enterPane(next)
            wide -> focus.focusRail(next)
        }
    }

    // The hint bar's block under the pane, so the rail beside it ends on the pane's last line.
    var hintBlock by remember { mutableStateOf(0.dp) }

    // Settings is a panel host: its rail and pane are drawn at the panel density for the window.
    NovaPanelDensityHost {
        // A short window gives its height to the rows: the quick strip, which repeats values the
        // pane shows, and the subtitle go, so the pane shows whole rows rather than two and a half.
        val compact = LocalNovaPanelDensity.current == NovaPanelDensity.Compact
        val showQuickStrip = !compact && state.quickSettings.isNotEmpty()
        focus.hasQuickStrip = showQuickStrip
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.window)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (NovaRemoteInput.tellsTheInput(native)) remoteKeys = NovaRemoteInput.isRemote(native)
                    false
                }
                // L1 and R1 step categories from anywhere on the screen, on release.
                .onKeyEvent { event ->
                    val delta = when (event.key) {
                        Key.ButtonL1 -> -1
                        Key.ButtonR1 -> 1
                        else -> return@onKeyEvent false
                    }
                    val native = event.nativeKeyEvent
                    when (event.type) {
                        KeyEventType.KeyDown -> if (native.repeatCount == 0) shoulderLatch.press(native.keyCode)
                        KeyEventType.KeyUp -> if (shoulderLatch.release(native.keyCode) && !native.isCanceled) stepCategory(delta)
                    }
                    true
                }
        ) {
            NovaSettingsCompactHeader(
                title = title,
                subtitle = subtitle.takeIf { !compact },
                query = state.searchQuery,
                onQuery = onSearch,
                onClear = onClearSearch,
                onBack = onBack,
                onOpenLegacy = onOpenLegacy,
                headerActions = headerActions,
                wide = wide
            )
            if (showQuickStrip) {
            Spacer(Modifier.height(NovaSettingsMetrics.headerToQuickStripSpacingDp().dp))
            NovaSettingsQuickStrip(
                state = state,
                // Down from the strip lands on the rail; without one it moves on as Compose finds.
                modifier = if (wide) focus.quickStripModifier { latestState.selectedCategoryKey } else Modifier,
                firstPillModifier = Modifier.focusRequester(focus.firstQuick),
                onPill = { definition ->
                    pane.popToRoot()
                    if (latestState.isSearchActive()) clearSearch()
                    select(definition.categoryKey)
                    focus.enterPane(
                        paneKey = definition.categoryKey,
                        rowKey = definition.key,
                        then = if (definition.opensPageFromRow()) ({ opener.open(definition, latestState) }) else null,
                    )
                }
            )
            Spacer(Modifier.height(NovaSettingsMetrics.quickStripToContentSpacingDp().dp))
            } else {
                Spacer(Modifier.height(NovaPanelMetrics.SpaceSm))
            }

            val paneHost: @Composable (Modifier) -> Unit = { modifier ->
                NovaPageStackHost(
                    state = pane,
                    modifier = modifier
                        .then(focus.paneModifier)
                        .then(if (wide) focus.paneLeftModifier { latestState.selectedCategoryKey } else Modifier),
                    // Pages pushed over the rows keep focus; the rows themselves may give it to the rail.
                    containFocus = pane.depth > 1,
                    onCloseRequest = {
                        if (wide && focus.paneHasFocus) focus.focusRail(latestState.selectedCategoryKey) else back()
                    },
                    hints = hints,
                    remoteKeys = remoteKeys,
                    onHintBarBlock = { hintBlock = it },
                ) { page ->
                    when (page) {
                        is SettingsPage.Rows -> NovaSettingsRowsPage(
                            page = page,
                            state = latestState,
                            focus = focus,
                            onValue = { definition, value, done -> opener.onValue(definition, value, done) },
                            onOpen = { definition -> opener.open(definition, latestState) },
                            onSetting = onSetting,
                            onResetSetting = onResetSetting,
                            onRowFocus = { key, focused ->
                                if (focused) focusedRow = key else if (focusedRow == key) focusedRow = null
                            },
                        )
                        is SettingsPage.DisplayRole -> NovaDisplayRolePage(page)
                        else -> Unit
                    }
                }
            }

            if (wide) {
                focus.RailFocusEffect(state)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NovaSettingsMetrics.wideColumnSpacingDp().dp)
                ) {
                    // Settings opened on whatever Android's traversal picked first, which is
                    // Back, so the first press on a controller left the screen you had just
                    // asked for. It lands on the rail instead, where the next press moves
                    // between categories.
                    NovaSettingsCategoryRail(
                        state = state,
                        focus = focus,
                        onCategory = onCategory,
                        modifier = Modifier
                            // A return to a row takes first focus itself.
                            .then(if (returnToRow == null) Modifier.novaHoldsFirstFocus() else Modifier.focusGroup())
                            .width(NovaSettingsMetrics.categoryRailWidthDp().dp)
                            .fillMaxHeight()
                            // Ends on the pane's last line, above its hint bar, not beside the bar.
                            .padding(bottom = hintBlock)
                    )
                    paneHost(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                NovaSettingsCategoryChips(state, onCategory)
                Spacer(Modifier.height(NovaPanelMetrics.SpaceSm))
                paneHost(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Drops every page pushed over the pane's rows. */
private fun NovaPanelState.popToRoot() {
    while (depth > 1) pop()
}

/** The pane's root for this state: the selected category, or the search results. */
private fun NovaSettingsUiState.paneKey(): String = if (isSearchActive()) SEARCH_PANE_KEY else selectedCategoryKey

/**
 * The hints beside A Select and B Back: L1/R1 through the categories, and X for a profile's reset.
 * A remote has neither: after its key the rail does the categories with the D-pad, and a setting's
 * page ends in Use Preset Default. On a switch or a choice that changes in place, a hold of OK or A
 * resets it ([holdReset]), and the bar names that hold for the keys last pressed (C02).
 */
@Composable
private fun novaSettingsHints(wide: Boolean, canReset: Boolean, remote: Boolean, holdReset: Boolean): List<NovaControllerHint> {
    val lbRb = stringResource(R.string.nova_controller_hint_lb_rb)
    val category = stringResource(R.string.nova_settings_hint_category)
    val x = stringResource(R.string.nova_controller_hint_x)
    val reset = stringResource(R.string.nova_settings_reset)
    val holdOk = stringResource(R.string.nova_settings_hint_hold_ok)
    val holdA = stringResource(R.string.nova_settings_hint_hold_a)
    return remember(wide, canReset, remote, holdReset, lbRb, category, x, reset, holdOk, holdA) {
        buildList {
            if (remote) {
                if (holdReset) add(NovaControllerHint(holdOk, reset))
                return@buildList
            }
            if (wide) add(NovaControllerHint(lbRb, category))
            when {
                holdReset -> add(NovaControllerHint(holdA, reset))
                canReset -> add(NovaControllerHint(x, reset))
            }
        }
    }
}

/** Only the codec list needs the PyroWave check, and only when it offers PyroWave. */
@Composable
private fun rememberPyroWaveStatus(state: NovaSettingsUiState): PyroWaveAvailability.Status? {
    val context = LocalContext.current
    val needed = remember(state.quickSettings, state.visibleSettings) {
        (state.quickSettings + state.visibleSettings).any { definition ->
            needsPyroWaveCheck(definition.key, definition.options.map { it.value })
        }
    }
    val status by produceState<PyroWaveAvailability.Status?>(initialValue = null, needed) {
        if (needed) {
            value = withContext(Dispatchers.Default) { PyroWaveAvailability.inspect(context.applicationContext) }
        }
    }
    return status
}

/**
 * Menu & Drawer Opacity previews on the whole app while its exact page is on top, through an
 * owner-scoped preview, so the durable SharedPreferences key is written only by Save. Leaving the
 * page any other way clears the preview; Save clears it only once the write has landed, so the
 * menus never flash back to the old value.
 */
internal class NovaMenuOpacityPreviewOwner {
    var owner: Long? = null

    fun start(): Long {
        owner?.let(NovaMenuOpacityPreview::clear)
        return NovaMenuOpacityPreview.newOwner().also { owner = it }
    }

    fun update(percent: Int) {
        owner?.let { owner -> NovaMenuOpacityPreview.update(owner, percent) }
    }

    /** Hands the preview to a save, which clears it once the write has landed. */
    fun takeForSave(): Long? = owner.also { owner = null }

    fun clear() {
        owner?.let(NovaMenuOpacityPreview::clear)
        owner = null
    }
}

@Composable
private fun rememberMenuOpacityPreview(pane: NovaPanelState): NovaMenuOpacityPreviewOwner {
    val preview = remember { NovaMenuOpacityPreviewOwner() }
    DisposableEffect(Unit) { onDispose { preview.clear() } }
    val topKey = pane.top?.key
    LaunchedEffect(topKey) {
        if (topKey != MENU_OPACITY_PAGE_KEY) preview.clear()
    }
    return preview
}

private const val MENU_OPACITY_PAGE_KEY = "slider:" + NovaMenuPreferences.KEY_OPACITY

/** Whether A on this setting's row opens a page, so a quick pill that lands on the row opens it too. */
private fun NovaSettingDefinition.opensPageFromRow(): Boolean = when (type) {
    NovaSettingType.Select -> selectPresentation == NovaSelectPresentation.Page
    NovaSettingType.Text -> true
    else -> false
}

/** Pushes the page a setting opens: its list, the display role composer, its exact value or its text. */
private class NovaSettingsPageOpener(
    private val context: Context,
    private val pane: NovaPanelState,
    var onValue: NovaSettingWrite,
    private val menuOpacity: NovaMenuOpacityPreviewOwner,
) {
    var pyroWave: PyroWaveAvailability.Status? = null

    /** Drops a profile's override of a setting, as X does on its row. */
    var onReset: (NovaSettingDefinition) -> Unit = {}

    /**
     * The setting's page gets a last row, Use Preset Default, while the profile overrides it: a
     * remote has no X to reset the row with, and its Reset is for touch only (C02).
     */
    private fun useDefault(definition: NovaSettingDefinition, state: NovaSettingsUiState): NovaUseDefault? =
        if (state.canReset(definition)) {
            NovaUseDefault(
                label = if (state.generatedQuality && definition.key == NovaTierControls.QUALITY_KEY) "Use recommended"
                    else context.getString(R.string.nova_settings_use_preset_default),
                caption = if (state.generatedQuality && definition.key == NovaTierControls.QUALITY_KEY)
                    definition.options.firstOrNull { it.value == "recommended" }?.caption
                    else context.getString(R.string.nova_settings_use_preset_default_caption),
            ) { onReset(definition) }
        } else {
            null
        }

    fun open(definition: NovaSettingDefinition, state: NovaSettingsUiState) {
        when (definition.type) {
            NovaSettingType.Select -> openSelect(definition, state)
            NovaSettingType.Slider -> openSlider(definition, state)
            NovaSettingType.Text -> pane.push(
                novaTextFormPage(
                    context = context,
                    key = definition.key,
                    title = definition.title,
                    current = state.stringValue(definition),
                    risky = definition.risk != NovaSettingRisk.Normal,
                    onSave = { value -> onValue(definition, NovaSettingValue.StringValue(value)) {} },
                    useDefault = useDefault(definition, state),
                ),
            )
            else -> Unit
        }
    }

    private fun openSelect(definition: NovaSettingDefinition, state: NovaSettingsUiState) {
        if (state.generatedQuality && definition.key == NovaTierControls.QUALITY_KEY) {
            pane.push(NovaCommonPage.Choice(
                key = "choice:" + definition.key, title = definition.title,
                options = novaSelectOptions(context, definition.key, definition.options, null),
                current = state.stringValue(definition),
                initialFocus = if (definition.options.any { it.value == "recommended" && it.disabledReason == null }) "recommended"
                    else state.stringValue(definition),
                onChoose = { value -> onValue(definition, NovaSettingValue.StringValue(value)) {} },
                useDefault = useDefault(definition, state)))
            return
        }
        val current = state.stringValue(definition)
        if (definition.key == PreferenceConfiguration.ANDROID_STREAM_DISPLAY_TARGET_PREF_STRING) {
            pane.push(
                SettingsPage.DisplayRole(
                    title = context.getString(R.string.title_display_role_composer),
                    currentTarget = current.ifEmpty { com.papi.nova.utils.AndroidStreamDisplayTarget.AUTO },
                    onApply = { target -> onValue(definition, NovaSettingValue.StringValue(target)) {} },
                    useDefault = useDefault(definition, state),
                ),
            )
            return
        }
        if (definition.key == "nova_theme") {
            // The same picker as the Hosts screen's Theme, with each theme's caption: Settings had
            // a generic list of bare names beside it.
            pane.push(
                com.papi.nova.novaThemePickerPage(
                    context = context,
                    themes = definition.options.map { it.value }.filter { theme ->
                        theme != NovaThemeManager.THEME_MATERIAL_YOU || NovaThemeManager.isMaterialYouAvailable()
                    },
                    current = current.ifEmpty { NovaThemeManager.getTheme(context) },
                    onChoose = { value -> onValue(definition, NovaSettingValue.StringValue(value)) {} },
                    useDefault = useDefault(definition, state),
                ),
            )
            return
        }
        val status = if (needsPyroWaveCheck(definition.key, definition.options.map { it.value })) {
            pyroWave ?: PyroWaveAvailability.Status.CHECKING
        } else {
            null
        }
        pane.push(
            novaSelectChoicePage(
                key = definition.key,
                title = definition.title,
                options = novaSelectOptions(context, definition.key, definition.options, status),
                current = current,
                onChoose = { value -> onValue(definition, NovaSettingValue.StringValue(value)) {} },
                useDefault = useDefault(definition, state),
            ),
        )
    }

    private fun openSlider(definition: NovaSettingDefinition, state: NovaSettingsUiState) {
        val opacity = definition.key == NovaMenuPreferences.KEY_OPACITY
        if (opacity) menuOpacity.start()
        val min = definition.min ?: 0
        val max = (definition.max ?: 100).coerceAtLeast(min)
        pane.push(
            NovaCommonPage.Slider(
                key = "slider:" + definition.key,
                title = definition.title,
                value = state.intValue(definition),
                range = min..max,
                step = definition.step ?: 1,
                format = { value -> formatSettingInt(context, definition, value) },
                onPreview = if (opacity) menuOpacity::update else null,
                exactDivisor = 1,
                exactLabel = if (definition.isBitrateKbps()) "kbps" else null,
                onSave = { value ->
                    val previewOwnerAtSave = if (opacity) menuOpacity.takeForSave() else null
                    onValue(definition, NovaSettingValue.IntValue(value)) {
                        previewOwnerAtSave?.let(NovaMenuOpacityPreview::clear)
                    }
                },
                useDefault = useDefault(definition, state)?.let { reset ->
                    // The menu opacity preview goes with the value it previewed.
                    if (opacity) reset.copy(run = { menuOpacity.clear(); reset.run() }) else reset
                },
            ),
        )
    }
}

@Composable
private fun NovaSettingsCompactHeader(
    title: String,
    subtitle: String?,
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onOpenLegacy: () -> Unit,
    headerActions: List<NovaSettingsHeaderAction>,
    wide: Boolean
) {
    val colors = LocalNovaComposeColors.current
    val type = novaPanelType
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NovaSettingsMetrics.headerMinHeightDp().dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm)
        ) {
            NovaSettingsHeaderButton(stringResource(R.string.nova_settings_back), onBack)
            // Titles wrap rather than cut: a long preset name takes a second line.
            Column(Modifier.weight(1f)) {
                Text(text = title, style = type.panelTitle, color = colors.textPrimary)
                subtitle?.let { Text(text = it, style = type.caption, color = colors.textMuted) }
            }
            if (wide) {
                NovaSettingsSearchField(
                    query = query,
                    onQuery = onQuery,
                    onClear = onClear,
                    modifier = Modifier.widthIn(min = 280.dp, max = 440.dp)
                )
            }
            for (action in headerActions) {
                NovaSettingsHeaderButton(action.label, action.onClick)
            }
            NovaSettingsHeaderButton(stringResource(R.string.nova_settings_legacy), onOpenLegacy)
        }
        if (!wide) {
            Spacer(Modifier.height(NovaPanelMetrics.SpaceSm))
            NovaSettingsSearchField(
                query = query,
                onQuery = onQuery,
                onClear = onClear,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * A header button in the one focus look, acting on release. The [compact] one sits inside the
 * 44dp search field, so it keeps clear of the field's own edge.
 */
@Composable
private fun NovaSettingsHeaderButton(label: String, onClick: () -> Unit, compact: Boolean = false) {
    NovaActionSurface(
        onClick = onClick,
        contentDescription = label,
        minHeight = if (compact) NovaSettingsMetrics.searchClearMinHeightDp().dp else NovaPanelMetrics.ButtonMinHeight,
        cornerRadius = NovaRadius.hero,
        contentPadding = if (compact) {
            PaddingValues(horizontal = NovaPanelMetrics.SpaceSm, vertical = NovaPanelMetrics.SpaceXs)
        } else {
            PaddingValues(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm)
        },
    ) { contentColor, _ ->
        Text(text = label, style = if (compact) novaPanelType.caption else novaPanelType.value, color = contentColor)
    }
}

@Composable
private fun NovaSettingsSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    // This was a plain BasicTextField: focus it with a d-pad and the direction keys went into
    // the text rather than moving on, so there was no way off the field without a touchscreen.
    val colors = LocalNovaComposeColors.current
    // B clears a query before it leaves Settings, the way a page's B unwinds one level.
    NovaBackHandler(active = query.isNotBlank()) { onClear() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            // Typed where it stands: without this the RP6 swapped the screen for the keyboard's
            // full-screen white extract view.
            NovaInPlaceKeyboard {
                NovaSearchTextField(
                    value = query,
                    onValueChange = onQuery,
                    contentDescription = stringResource(R.string.nova_settings_search_hint),
                    modifier = Modifier.fillMaxWidth(),
                    shape = NovaSettingsCardShape
                ) { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = NovaPanelMetrics.SpaceMd),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(R.string.nova_settings_search_hint),
                                style = novaPanelType.caption,
                                color = colors.textMuted,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        }
        // Beside the field, not inside its decoration, where the D-pad could never reach it.
        if (query.isNotBlank()) {
            NovaSettingsHeaderButton(stringResource(R.string.nova_settings_search_clear), onClear, compact = true)
        }
    }
}

/**
 * The quick strip: the stream settings changed most often, each a pill with its name and value.
 * It wraps onto a second line rather than scrolling sideways, so no pill is ever cut at the
 * screen's edge (R13). A pill takes focus to its setting's row, and opens the setting's page when
 * the row would.
 */
@Composable
private fun NovaSettingsQuickStrip(
    state: NovaSettingsUiState,
    onPill: (NovaSettingDefinition) -> Unit,
    modifier: Modifier = Modifier,
    firstPillModifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        // On a television the strip took a third of the screen and left room for two and a half
        // rows; there it keeps the pills that fit on one line, whole, and the rows get the room.
        maxLines = if (LocalNovaFormFactor.current == NovaFormFactor.Television) 1 else Int.MAX_VALUE,
    ) {
        for (definition in state.quickSettings) {
            NovaSettingPill(
                definition = definition,
                value = state.valueLabel(LocalContext.current, definition),
                modifier = if (definition == state.quickSettings.firstOrNull()) firstPillModifier else Modifier,
                onClick = { onPill(definition) }
            )
        }
    }
}

@Composable
private fun NovaSettingPill(
    definition: NovaSettingDefinition,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val shape = NovaSettingsCardShape
    val label = remember(definition.title, value, type, colors) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.textSecondary, fontSize = type.caption.fontSize)) { append(definition.title) }
            append("  ")
            withStyle(SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)) { append(value) }
        }
    }
    Box(
        modifier = modifier
            .testTag("nova-settings-quick-${definition.key}")
            .widthIn(max = NovaSettingsMetrics.quickPillMaxWidthDp().dp)
            .heightIn(min = NovaPanelMetrics.ButtonMinHeight)
            .clip(shape)
            .novaFocusRing(
                shape = shape,
                restFill = surfaces.control,
                restBorder = surfaces.tileBorder,
                restBorderWidth = NovaPanelMetrics.Hairline,
            )
            .semantics(mergeDescendants = true) { contentDescription = "${definition.title}, $value" }
            .novaClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text = label, style = type.value)
    }
}

@Composable
private fun NovaSettingsCategoryRail(
    state: NovaSettingsUiState,
    focus: NovaSettingsFocus,
    onCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    // It scrolls as the pane's rows do, a row of context past the focused category, so the edge
    // fade only ever covers the category beyond it and the last one never runs into the screen.
    NovaRowContextScrolling {
        LazyColumn(
            state = focus.railState,
            modifier = modifier.novaScrollEdgeFade(focus.railState),
            // The pane's own row gap, so the rail and the rows beside it read as one stack of tiles.
            verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
            contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm)
        ) {
            itemsIndexed(state.categories, key = { _, category -> category.key }) { _, category ->
                NovaCategoryRow(
                    category = category,
                    selected = category.key == state.selectedCategoryKey && state.searchQuery.isBlank(),
                    modifier = focus.categoryModifier(category, state, onCategory),
                    onClick = {
                        focus.pane.popToRoot()
                        onCategory(category.key)
                        // A on a category enters its rows, as Right does; a tap only shows them.
                        if (keyboard) focus.enterPane(category.key)
                    }
                )
            }
        }
    }
}

@Composable
private fun NovaSettingsCategoryChips(
    state: NovaSettingsUiState,
    onCategory: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm)
    ) {
        for (category in state.categories) {
            NovaCategoryRow(
                category = category,
                selected = category.key == state.selectedCategoryKey && state.searchQuery.isBlank(),
                onClick = { onCategory(category.key) },
                modifier = Modifier.fillMaxWidth(0.48f)
            )
        }
    }
}

/**
 * One category of the rail. The category the pane shows is marked the way a current value is
 * (R9): a SemiBold, accent label and selected semantics. Fills and rings only ever mean focus.
 * It is a row like the pane's rows beside it: the row tile, a row's least height and the row
 * title type, so the rail no longer reads a size smaller than the pane.
 */
@Composable
private fun NovaCategoryRow(
    category: NovaSettingCategory,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalNovaComposeColors.current
    val shape = NovaSettingsCardShape
    Box(
        modifier = modifier
            .testTag("nova-settings-category-${category.key}")
            .fillMaxWidth()
            .heightIn(min = NovaPanelMetrics.rowMinHeight(LocalNovaFormFactor.current))
            .clip(shape)
            .novaFocusRing(shape, rest = novaRowRest)
            .semantics { this.selected = selected }
            .novaClickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceSm),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = category.title,
            style = novaPanelType.rowTitle,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.accent else colors.textPrimary,
        )
    }
}

/**
 * The pane's root page: the selected category's rows (or the search results), under the live
 * HUD preview on the overlays category. Its rows take focus only as [NovaSettingsFocus] allows.
 */
@Composable
private fun NovaPageScope.NovaSettingsRowsPage(
    page: SettingsPage.Rows,
    state: NovaSettingsUiState,
    focus: NovaSettingsFocus,
    onValue: NovaSettingWrite,
    onOpen: (NovaSettingDefinition) -> Unit,
    onSetting: (NovaSettingDefinition) -> Unit,
    onResetSetting: (NovaSettingDefinition) -> Unit,
    onRowFocus: (key: String, focused: Boolean) -> Unit,
) {
    val colors = LocalNovaComposeColors.current
    val context = LocalContext.current
    val current = page.paneKey == state.paneKey()
    val settings = if (current) state.visibleSettings else emptyList()
    val showHudPreview = current && page.paneKey == OVERLAYS_CATEGORY_KEY
    val summary = when {
        !current -> null
        state.isSearchActive() -> stringResource(R.string.nova_settings_search_results, state.searchResultCount)
        else -> state.categories.firstOrNull { it.key == page.paneKey }?.summary?.takeIf { it.isNotBlank() }
    }
    val leading = (if (summary != null) 1 else 0) + (if (showHudPreview) 1 else 0)
    val enabledRows = settings.filter { state.isEnabled(it) && it.takesFocus() }
    val entryRow = (enabledRows.firstOrNull { it.key == focus.rememberedRow(page.paneKey) } ?: enabledRows.firstOrNull())?.key

    // Carries out a move into the pane: scroll the row into view, then focus it past the gate.
    val entry = focus.paneEntry
    LaunchedEffect(entry, current, settings) {
        if (entry == null || entry.paneKey != page.paneKey || !current) return@LaunchedEffect
        val target = entry.rowKey?.takeIf { key -> settings.any { it.key == key } } ?: entryRow
        if (target == null) {
            // Nothing here takes focus: it stays where it was.
            focus.paneEntry = null
            return@LaunchedEffect
        }
        val index = settings.indexOfFirst { it.key == target } + leading
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        withFrameNanos { }
        val moved = focus.focusRow(target)
        // Cleared last: clearing it recomposes this page and cancels the effect.
        focus.paneEntry = null
        if (moved) entry.then?.invoke()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .novaScrollEdgeFade(listState)
            .then(focus.rootGate)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.RowGap),
        contentPadding = PaddingValues(vertical = NovaPanelMetrics.SpaceSm),
    ) {
        summary?.let { text ->
            item(key = "summary", contentType = "summary") {
                Text(text = text, style = novaPanelType.caption, color = colors.textSecondary)
            }
        }
        if (showHudPreview) {
            item(key = "nova_hud_preview", contentType = "hud_preview") {
                NovaHudSettingsPreview(state)
            }
        }
        itemsIndexed(settings, key = { _, definition -> definition.key }, contentType = { _, definition -> definition.type }) { index, definition ->
            val rowModifier = Modifier
                .testTag("nova-settings-row-${definition.key}")
                .then(if (definition.key == entryRow) Modifier.novaInitialFocus() else Modifier)
                .novaRestorableFocus(definition.key, index + leading)
                .then(focus.rowModifier(page.paneKey, definition.key))
            NovaSettingRow(
                definition = definition,
                state = state,
                context = context,
                onValue = onValue,
                onOpen = onOpen,
                onSetting = onSetting,
                onReset = onResetSetting,
                onFocus = { onRowFocus(definition.key, it) },
                onRefocus = { focus.focusRow(definition.key) },
                modifier = rowModifier,
            )
        }
    }
}

@Composable
private fun NovaHudSettingsPreview(state: NovaSettingsUiState) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val type = novaPanelType
    val enabled = state.booleanSetting("nova_polaris_hud", false)
    val mode = NovaHudMode.fromPreference(
        state.stringSetting("nova_polaris_hud_mode", NovaHudMode.MINIMAL.preferenceValue)
    )
    val opacityPercent = NovaHudPreferences.coerceOpacityPercent(
        state.intSetting(NovaHudPreferences.KEY_OPACITY, NovaHudPreferences.DEFAULT_OPACITY_PERCENT)
    )
    val previewState = NovaHudUiState.preview(mode)
    val modeLabel = mode.name.lowercase().replaceFirstChar { it.uppercase() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NovaSettingsCardShape)
            .background(surfaces.panel.copy(alpha = 0.86f * LocalNovaMenuOpacityScale.current))
            .border(NovaPanelMetrics.Hairline, surfaces.panelBorder, NovaSettingsCardShape)
            .padding(horizontal = NovaPanelMetrics.SpaceMd, vertical = NovaPanelMetrics.SpaceMd),
        verticalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm)
    ) {
        Text(
            text = stringResource(R.string.nova_settings_live_hud_preview),
            style = type.rowTitle,
            color = colors.textPrimary,
        )
        Text(
            text = if (enabled) {
                stringResource(R.string.nova_settings_hud_preview_enabled, modeLabel, opacityPercent)
            } else {
                stringResource(R.string.nova_settings_hud_preview_saved)
            },
            style = type.caption,
            color = colors.textMuted,
        )
        NovaStreamHudContent(
            state = previewState,
            opacityScale = NovaHudPreferences.opacityScale(opacityPercent),
            modifier = Modifier.widthIn(max = 320.dp)
        )
    }
}

private fun NovaSettingsUiState.booleanSetting(key: String, defaultValue: Boolean): Boolean {
    return (values[key] as? NovaSettingValue.BooleanValue)?.value ?: defaultValue
}

private fun NovaSettingsUiState.intSetting(key: String, defaultValue: Int): Int {
    return (values[key] as? NovaSettingValue.IntValue)?.value ?: defaultValue
}

private fun NovaSettingsUiState.stringSetting(key: String, defaultValue: String): String {
    return (values[key] as? NovaSettingValue.StringValue)?.value ?: defaultValue
}

/**
 * One setting in the one component its situation calls for: a switch or an in-place choice in a
 * [NovaValueRow], a slider in a [NovaStepperRow], and a list, text or action in a [NovaRow] that
 * opens its page. In a profile, a setting the profile overrides can be reset with X or its Reset
 * button, and from the page it opens. A switch or a choice that changes in place opens no page, so
 * a remote resets it with a hold of OK (A on a controller): the row splits in place into Keep,
 * focused, and Use Preset Default (C02). [onFocus] hears focus reach the row itself and leave it,
 * which it does for Keep and Use Preset Default, and [onRefocus] puts focus back on it once the
 * split is answered.
 */
@Composable
private fun NovaSettingRow(
    definition: NovaSettingDefinition,
    state: NovaSettingsUiState,
    context: Context,
    onValue: NovaSettingWrite,
    onOpen: (NovaSettingDefinition) -> Unit,
    onSetting: (NovaSettingDefinition) -> Unit,
    onReset: (NovaSettingDefinition) -> Unit,
    onFocus: (Boolean) -> Unit,
    onRefocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.isEnabled(definition)
    val quality = state.generatedQuality && definition.key == NovaTierControls.QUALITY_KEY
    val disabledReason = if (enabled) null else state.disabledReason(context, definition)
    val caption = disabledReason ?: if (quality) listOfNotNull(
        definition.options.firstOrNull { it.value == state.stringValue(definition) }?.caption,
        state.caption(context, definition)).joinToString("\n") else state.caption(context, definition)
    val canReset = state.canReset(definition)
    val reset by rememberUpdatedState({ onReset(definition) })
    val resetLatch = remember { NovaPressLatch() }
    val (shown, write) = rememberSettingValue(definition, state.values[definition.key] ?: definition.defaultValue, onValue)
    // The hold that splits an overridden switch or in-place choice (C02). Armed, the split is
    // answered as any split is, on release: a hold on Keep or Use Preset Default is a press of it,
    // and never arms the split again over itself.
    val holdable = canReset && enabled && definition.resetsByHold
    val split = rememberNovaSplitConfirmState()
    val holdableNow by rememberUpdatedState(holdable && !split.armed)
    val hold = remember { NovaHoldToReset() }
    val holdScope = rememberCoroutineScope()
    var pairFocused by remember { mutableStateOf(false) }
    val refocus by rememberUpdatedState(onRefocus)
    // Focus on the row itself, not on Keep or Use Preset Default: only there is a hold to name.
    var rowFocused by remember { mutableStateOf(false) }
    val ownFocus = rowFocused && !split.armed
    val reportFocus by rememberUpdatedState(onFocus)
    DisposableEffect(ownFocus) {
        reportFocus(ownFocus)
        onDispose { if (ownFocus) reportFocus(false) }
    }
    // Keep, Use Preset Default and B hand focus back to the row, which the split stood over.
    LaunchedEffect(split.armed) {
        if (split.armed) return@LaunchedEffect
        pairFocused = false
        if (split.refocus) {
            withFrameNanos { }
            refocus()
            split.refocus = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                rowFocused = it.hasFocus
                if (!it.hasFocus) hold.cancel()
            }
            // A held OK or A on an overridden switch or in-place choice splits the row instead of
            // changing it: its repeats and its release are the hold's, never a step of the value.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!NovaKeys.isActivation(native.keyCode)) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (native.repeatCount == 0) {
                            hold.cancel()
                            if (holdableNow) {
                                hold.job = holdScope.launch {
                                    delay(NOVA_SETTINGS_HOLD_RESET_MS)
                                    hold.fired = true
                                    pairFocused = false
                                    split.arm()
                                }
                            }
                        }
                        hold.fired
                    }
                    KeyEventType.KeyUp -> {
                        val fired = hold.fired
                        hold.cancel()
                        fired
                    }
                    else -> false
                }
            }
            // X resets a profile override, on release.
            .onKeyEvent { event ->
                if (!canReset || event.key != Key.ButtonX) return@onKeyEvent false
                val native = event.nativeKeyEvent
                when (event.type) {
                    KeyEventType.KeyDown -> if (native.repeatCount == 0) resetLatch.press(native.keyCode)
                    KeyEventType.KeyUp -> if (resetLatch.release(native.keyCode) && !native.isCanceled) reset()
                }
                true
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NovaPanelMetrics.SpaceSm),
    ) {
        val rowModifier = modifier.weight(1f)
        // Armed, the split stands over the row, which keeps its place and gives focus to Keep.
        val splitRow: @Composable (@Composable (Modifier) -> Unit) -> Unit = { valueRow ->
            Box(modifier = Modifier.weight(1f)) {
                valueRow(
                    Modifier
                        .then(if (split.armed) Modifier.alpha(0f) else Modifier)
                        .focusProperties { canFocus = !(split.armed && pairFocused) }
                        .then(modifier),
                )
                if (split.armed) {
                    NovaSplitConfirm(
                        label = definition.title,
                        confirmLabel = stringResource(R.string.nova_settings_use_preset_default),
                        onConfirm = reset,
                        stayLabel = stringResource(R.string.nova_settings_keep),
                        consequence = stringResource(R.string.nova_settings_use_preset_default_caption),
                        icon = R.drawable.ic_update,
                        shape = NovaSplitShape.Row,
                        state = split,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { pairFocused = it.hasFocus },
                    )
                }
            }
        }
        when (definition.type) {
            NovaSettingType.Toggle -> splitRow { valueModifier ->
                NovaValueRow(
                    title = definition.title,
                    options = switchOptions(context),
                    current = (shown as? NovaSettingValue.BooleanValue)?.value ?: false,
                    onChange = { write(NovaSettingValue.BooleanValue(it)) },
                    caption = caption,
                    style = NovaValueStyle.Switch,
                    enabled = enabled,
                    modifier = valueModifier,
                )
            }
            NovaSettingType.Select -> if (quality || definition.selectPresentation == NovaSelectPresentation.InPlace) {
                splitRow { valueModifier ->
                    NovaValueRow(
                        title = definition.title,
                        options = remember(definition.options) { definition.options.map {
                            NovaOption(it.value, it.label, it.caption, it.disabledReason) } },
                        current = (shown as? NovaSettingValue.StringValue)?.value.orEmpty(),
                        onChange = { write(NovaSettingValue.StringValue(it)) },
                        caption = caption,
                        ordered = definition.isOrderedScale,
                        enabled = enabled,
                        onOpenList = { onOpen(definition) },
                        onActivateChoice = if (quality) ({ onOpen(definition) }) else null,
                        modifier = valueModifier,
                    )
                }
            } else {
                NovaRow(
                    title = definition.title,
                    onClick = { onOpen(definition) },
                    caption = caption,
                    trailing = NovaRowTrailing.Value(state.valueLabel(context, definition)),
                    disabledReason = disabledReason,
                    modifier = rowModifier,
                )
            }
            NovaSettingType.Slider -> {
                val min = definition.min ?: 0
                val max = (definition.max ?: 100).coerceAtLeast(min)
                NovaStepperRow(
                    title = definition.title,
                    value = (shown as? NovaSettingValue.IntValue)?.value ?: min,
                    range = min..max,
                    step = definition.step ?: 1,
                    format = { value -> if (definition.key == PreferenceConfiguration.BITRATE_PREF_STRING &&
                        state.bitrateAuto == true) NovaBitrateAdvice.text(value, true) else formatSettingInt(context, definition, value) },
                    onChange = { write(NovaSettingValue.IntValue(it)) },
                    caption = caption,
                    enabled = enabled,
                    onExact = { onOpen(definition) },
                    modifier = rowModifier,
                )
            }
            NovaSettingType.Text -> NovaRow(
                title = definition.title,
                onClick = { onOpen(definition) },
                caption = caption,
                trailing = NovaRowTrailing.Value(state.valueLabel(context, definition)),
                disabledReason = disabledReason,
                modifier = rowModifier,
            )
            NovaSettingType.Action -> if (definition.key == APP_VERSION_KEY) {
                // Nothing to do here: the version reads as the caption, and the row takes no focus.
                NovaRow(
                    title = definition.title,
                    onClick = null,
                    caption = state.valueLabel(context, definition),
                    modifier = rowModifier,
                )
            } else if (definition.key in SPLIT_CONFIRM_KEYS) {
                // A reset that cannot be undone splits in its own row with Keep focused (R3); one
                // A had reset every stream UI setting.
                NovaSplitConfirm(
                    label = definition.title,
                    confirmLabel = stringResource(
                        if (definition.key == RESET_STREAM_UI_DEFAULTS_KEY) R.string.nova_settings_reset_confirm else R.string.nova_settings_clear_confirm,
                    ),
                    stayLabel = stringResource(R.string.nova_settings_keep),
                    onConfirm = { onSetting(definition) },
                    consequence = stringResource(
                        if (definition.key == RESET_STREAM_UI_DEFAULTS_KEY) {
                            R.string.nova_settings_reset_stream_ui_consequence
                        } else {
                            R.string.nova_settings_reset_osc_consequence
                        },
                    ),
                    icon = R.drawable.ic_update,
                    shape = NovaSplitShape.Row,
                    enabled = enabled,
                    caption = caption,
                    modifier = rowModifier,
                )
            } else {
                NovaRow(
                    title = definition.title,
                    onClick = { onSetting(definition) },
                    caption = caption,
                    trailing = NovaRowTrailing.Opens,
                    disabledReason = disabledReason,
                    modifier = rowModifier,
                )
            }
        }
        if (canReset) NovaSettingResetButton(enabled = enabled, onReset = reset)
    }
}

/** A switch or a choice that changes in its own row: it has no page to carry Use Preset Default. */
internal val NovaSettingDefinition.resetsByHold: Boolean
    get() = type == NovaSettingType.Toggle ||
        (type == NovaSettingType.Select && selectPresentation == NovaSelectPresentation.InPlace)

/** How long OK or A is held on an overridden switch or in-place choice before its row splits (C02). */
internal const val NOVA_SETTINGS_HOLD_RESET_MS = 500L

/** One hold of OK or A on a row: its timer, and whether it has split the row. */
private class NovaHoldToReset {
    var job: Job? = null
    var fired = false

    fun cancel() {
        job?.cancel()
        job = null
        fired = false
    }
}

/**
 * Reset, for touch. A pad resets with X, so the button is not a focus stop and never sits between
 * a value row and its Left and Right. It looks the size of a header button, and a finger has the
 * full 48dp to hit: the target reaches past the button above and below, and the row takes only the
 * button's height, so a compact 44dp row does not grow when its Reset appears (C24).
 */
@Composable
private fun NovaSettingResetButton(enabled: Boolean, onReset: () -> Unit) {
    val colors = LocalNovaComposeColors.current
    val surfaces = LocalNovaLibrarySurfaces.current
    val label = stringResource(R.string.nova_settings_reset)
    val shape = RoundedCornerShape(NovaRadius.hero)
    val reset by rememberUpdatedState(onReset)
    val reach = (NovaSettingsMetrics.touchTargetMinDp().dp - NovaPanelMetrics.ButtonMinHeight) / 2
    // The tap and the button's meaning span the full target; the outline inside it keeps the look.
    val target = Modifier
        .focusProperties { canFocus = false }
        .pointerInput(enabled) { if (enabled) detectTapGestures(onTap = { reset() }) }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (enabled) {
                onClick(label = label) {
                    reset()
                    true
                }
            }
        }
    Box(
        modifier = Modifier.novaTouchReach(reach, target),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = NovaPanelMetrics.ButtonMinHeight)
                .clip(shape)
                .border(NovaPanelMetrics.Hairline, surfaces.tileBorder, shape)
                .padding(horizontal = NovaPanelMetrics.SpaceMd),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, style = novaPanelType.value, color = if (enabled) colors.textPrimary else colors.textMuted)
        }
    }
}

/**
 * The value a row shows: the one stored, or, while writes it started are still landing, the one
 * it last wrote, so a held Right never shows the steps it has already taken snap back.
 */
@Composable
private fun rememberSettingValue(
    definition: NovaSettingDefinition,
    stored: NovaSettingValue?,
    onValue: NovaSettingWrite,
): Pair<NovaSettingValue?, (NovaSettingValue) -> Unit> {
    var shown by remember(definition.key) { mutableStateOf(stored) }
    var pending by remember(definition.key) { mutableIntStateOf(0) }
    val write by rememberUpdatedState(onValue)
    LaunchedEffect(stored) { if (pending == 0) shown = stored }
    val set: (NovaSettingValue) -> Unit = remember(definition) {
        { value ->
            shown = value
            pending++
            write(definition, value) { pending-- }
        }
    }
    return shown to set
}

@Composable
private fun switchOptions(context: Context): List<NovaOption<Boolean>> = remember(context) {
    listOf(
        NovaOption(false, context.getString(R.string.nova_settings_off)),
        NovaOption(true, context.getString(R.string.nova_settings_on)),
    )
}

private const val APP_VERSION_KEY = "nova_app_version"

/** Every row is a focus stop except the app version, which has nothing to do. */
private fun NovaSettingDefinition.takesFocus(): Boolean = key != APP_VERSION_KEY

/** The caption under a row: its summary, when it applies if not at once, and a profile's override. */
private fun NovaSettingsUiState.caption(context: Context, definition: NovaSettingDefinition): String? {
    val parts = buildList {
        definition.summary.takeIf { it.isNotBlank() && it != "%s" }?.let(::add)
        when (definition.applyTiming) {
            NovaSettingApplyTiming.Instant -> Unit
            NovaSettingApplyTiming.NextStream -> add(context.getString(R.string.nova_settings_applies_next_stream))
            NovaSettingApplyTiming.RestartApp -> add(context.getString(R.string.nova_settings_applies_after_restart))
        }
        if (isOverride(definition)) add(context.getString(R.string.nova_settings_profile_override))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(context.getString(R.string.nova_settings_caption_separator))
}

/** Why a setting is off: the switch it depends on, by name when the pane shows it. */
private fun NovaSettingsUiState.disabledReason(context: Context, definition: NovaSettingDefinition): String {
    val dependency = definition.dependencyKey
    val title = (visibleSettings + quickSettings).firstOrNull { it.key == dependency }?.title
    return if (title != null) {
        context.getString(R.string.nova_settings_needs_dependency, title)
    } else {
        context.getString(R.string.nova_settings_needs_dependency_generic)
    }
}

internal fun NovaSettingsUiState.valueLabel(context: Context, definition: NovaSettingDefinition): String {
    val value = values[definition.key] ?: definition.defaultValue
    return when (value) {
        is NovaSettingValue.BooleanValue -> context.getString(if (value.value) R.string.nova_settings_on else R.string.nova_settings_off)
        is NovaSettingValue.IntValue -> formatSettingInt(context, definition, value.value)
        is NovaSettingValue.StringValue -> {
            definition.options.firstOrNull { it.value == value.value }?.label
                ?: value.value.ifEmpty { context.getString(R.string.nova_settings_not_set) }
        }
        is NovaSettingValue.StringSetValue -> value.value.joinToString(", ")
        null -> context.getString(R.string.nova_settings_not_set)
    }
}

internal fun NovaSettingsUiState.intValue(definition: NovaSettingDefinition): Int {
    val value = values[definition.key] ?: definition.defaultValue
    return (value as? NovaSettingValue.IntValue)?.value ?: definition.min ?: 0
}

internal fun NovaSettingsUiState.stringValue(definition: NovaSettingDefinition): String {
    val value = values[definition.key] ?: definition.defaultValue
    return (value as? NovaSettingValue.StringValue)?.value.orEmpty()
}

internal fun NovaSettingsUiState.isEnabled(definition: NovaSettingDefinition): Boolean {
    val dependency = definition.dependencyKey ?: return true
    val value = values[dependency]
    return (value as? NovaSettingValue.BooleanValue)?.value ?: true
}

/** A bitrate stored in kbps and shown in Mbps. */
private fun NovaSettingDefinition.isBitrateKbps(): Boolean =
    key == PreferenceConfiguration.BITRATE_PREF_STRING || key == "seekbar_metered_bitrate_kbps"

/** A slider's value as the player reads it: bitrates in Mbps (0 is Auto), anything else with its suffix. */
internal fun formatSettingInt(context: Context, definition: NovaSettingDefinition, value: Int): String {
    return when (definition.key) {
        PreferenceConfiguration.BITRATE_PREF_STRING,
        "seekbar_metered_bitrate_kbps" -> if (value == 0) {
            context.getString(R.string.nova_settings_bitrate_auto)
        } else {
            val mbps = if (value % 1000 == 0) (value / 1000).toString() else "%.1f".format(value / 1000f)
            context.getString(R.string.nova_settings_bitrate_mbps, mbps)
        }
        else -> value.toString() + definition.suffix.orEmpty()
    }
}
