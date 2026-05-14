package com.papi.nova.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisClientSettings
import com.papi.nova.api.PolarisGame
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.ServerHelper
import com.papi.nova.utils.UiHelper

/**
 * Nova Game Library — browse and launch games from the Polaris server.
 * Shows a cover art grid with search and category filters.
 * D-pad navigable for RP6 and other controllers.
 */
class NovaLibraryActivity : AppCompatActivity() {

    private lateinit var apiClient: PolarisApiClient
    private lateinit var adapter: NovaGameAdapter
    private lateinit var recentAdapter: NovaGameAdapter
    private lateinit var searchBar: EditText
    private lateinit var gameGrid: RecyclerView
    private lateinit var recentSection: View
    private lateinit var recentList: RecyclerView
    private lateinit var recentSummary: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyText: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var serverContext: TextView
    private lateinit var librarySummary: TextView
    private lateinit var resultsSummary: TextView
    private lateinit var autoQualityState: TextView
    private lateinit var modeState: TextView
    private lateinit var shimmer: ShimmerFrameLayout

    private var allGames = listOf<PolarisGame>()
    private var filterState = LibraryFilterState()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private lateinit var streamHost: String
    private var streamHttpPort: Int = 47989
    private var streamHttpsPort: Int = 47984
    private var streamUniqueId: String? = null
    private var streamPcUuid: String? = null
    private var streamPcName: String = ""
    private var streamServerCommands: ArrayList<String>? = null
    private var streamServerCert: ByteArray? = null
    private var clientSettings: PolarisClientSettings? = null

    private enum class PrimaryFilter {
        ALL,
        RECENT,
        SOURCES,
        HDR,
        MORE
    }

    private data class LibraryFilterState(
        val primary: PrimaryFilter = PrimaryFilter.ALL,
        val source: String = "",
        val category: String = "",
        val genre: String = ""
    ) {
        val hasActiveConstraint: Boolean
            get() = primary != PrimaryFilter.ALL
    }

    private data class FilterChoice(
        val title: String,
        val subtitle: String = "",
        val selected: Boolean = false,
        val onSelect: () -> Unit
    )

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_HTTPS_PORT = "https_port"
        const val EXTRA_HTTP_PORT = "http_port"
        const val EXTRA_UNIQUE_ID = "unique_id"
        const val EXTRA_PC_UUID = "pc_uuid"
        const val EXTRA_SERVER_COMMANDS = "server_commands"
        const val EXTRA_SERVER_CERT = "server_cert"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nova_library)
        applyHeaderInsets()
        if (savedInstanceState != null) {
            dismissOpenGameDetail()
        }

        val host = intent.getStringExtra(EXTRA_HOST) ?: run {
            finish()
            return
        }
        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME)
        val httpsPort = intent.getIntExtra(EXTRA_HTTPS_PORT, 47984)
        streamHost = host
        streamHttpPort = intent.getIntExtra(EXTRA_HTTP_PORT, 47989)
        streamHttpsPort = httpsPort
        streamUniqueId = intent.getStringExtra(EXTRA_UNIQUE_ID)
        streamPcUuid = intent.getStringExtra(EXTRA_PC_UUID)
        streamPcName = serverName ?: ""
        streamServerCommands = intent.getStringArrayListExtra(EXTRA_SERVER_COMMANDS)
        streamServerCert = intent.getByteArrayExtra(EXTRA_SERVER_CERT)

        apiClient = PolarisApiClient(this, host, httpsPort, streamServerCert)

        // Enable dense particles (nebulae + shooting stars) for library
        findViewById<SpaceParticleView>(R.id.space_particles_dense)?.dense = true

        searchBar = findViewById(R.id.nova_search)
        gameGrid = findViewById(R.id.nova_game_grid)
        recentSection = findViewById(R.id.nova_recent_section)
        recentList = findViewById(R.id.nova_recent_list)
        recentSummary = findViewById(R.id.nova_recent_summary)
        swipeRefresh = findViewById(R.id.nova_swipe_refresh)
        emptyText = findViewById(R.id.nova_empty_text)
        emptyTitle = findViewById(R.id.nova_empty_title)
        emptyHint = findViewById(R.id.nova_empty_hint)
        shimmer = findViewById(R.id.nova_shimmer_container)
        serverContext = findViewById(R.id.nova_library_context)
        librarySummary = findViewById(R.id.nova_library_summary)
        resultsSummary = findViewById(R.id.nova_library_results)
        autoQualityState = findViewById(R.id.nova_library_auto_quality_state)
        modeState = findViewById(R.id.nova_library_mode_state)
        serverContext.text = if (serverName.isNullOrBlank()) {
            getString(R.string.nova_library_server_context_fallback)
        } else {
            getString(R.string.nova_library_server_context, serverName)
        }
        applyLibraryTheme()

        val columns = computeGridColumns()
        gameGrid.layoutManager = GridLayoutManager(this, columns)
        recentList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        adapter = NovaGameAdapter(
            apiClient,
            onGameClick = { game -> showGameDetail(game) },
            onGameLongClick = { game -> showGameDetail(game) }
        )
        gameGrid.adapter = adapter
        recentAdapter = NovaGameAdapter(
            apiClient,
            onGameClick = { game -> showGameDetail(game) },
            onGameLongClick = { game -> showGameDetail(game) },
            cardLayoutRes = R.layout.nova_recent_game_card
        )
        recentList.adapter = recentAdapter

        // Swipe to refresh
        swipeRefresh.setColorSchemeColors(NovaThemeManager.getAccentColor(this))
        swipeRefresh.setProgressBackgroundColorSchemeColor(NovaThemeManager.getCardBackgroundColor(this))
        swipeRefresh.setOnRefreshListener {
            swipeRefresh.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            loadGames()
        }

        findViewById<MaterialButton>(R.id.nova_library_back).setOnClickListener {
            finish()
            NovaThemeManager.applyBackTransition(this)
        }

        findViewById<MaterialButton>(R.id.nova_library_refresh).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            loadGames()
        }

        findViewById<MaterialButton>(R.id.nova_library_manage).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            openServerManagement()
        }

        // Search
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val query = s?.toString() ?: ""
                searchRunnable = Runnable { filterGames(query) }
                searchHandler.postDelayed(searchRunnable!!, 150)
            }
        })

        // Smart browse filters
        setupFilterTab(R.id.filter_all, PrimaryFilter.ALL)
        setupFilterTab(R.id.filter_recent, PrimaryFilter.RECENT)
        setupFilterTab(R.id.filter_sources, PrimaryFilter.SOURCES)
        setupFilterTab(R.id.filter_hdr, PrimaryFilter.HDR)
        setupFilterTab(R.id.filter_more, PrimaryFilter.MORE)
        updateFilterTabs()

        // Retry button
        findViewById<MaterialButton>(R.id.nova_empty_retry).setOnClickListener { loadGames() }

        // Load games
        loadGames()
    }

    private fun computeGridColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            when {
                widthDp >= 1200 -> 5
                widthDp >= 960 -> 4
                widthDp >= 720 -> 3
                else -> 2
            }
        } else {
            when {
                widthDp >= 960 -> 5
                widthDp >= 720 -> 4
                widthDp >= 600 -> 3
                else -> 2
            }
        }
    }

    private fun setupFilterTab(id: Int, filter: PrimaryFilter) {
        findViewById<TextView>(id).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            when (filter) {
                PrimaryFilter.SOURCES -> showSourceFilterSheet()
                PrimaryFilter.MORE -> showMoreFilterSheet()
                else -> applyFilterState(LibraryFilterState(primary = filter))
            }
        }
    }

    private fun applyFilterState(state: LibraryFilterState) {
        filterState = state
        activeTabIndex = when (state.primary) {
            PrimaryFilter.ALL -> filterTabIds.indexOf(R.id.filter_all)
            PrimaryFilter.RECENT -> filterTabIds.indexOf(R.id.filter_recent)
            PrimaryFilter.SOURCES -> filterTabIds.indexOf(R.id.filter_sources)
            PrimaryFilter.HDR -> filterTabIds.indexOf(R.id.filter_hdr)
            PrimaryFilter.MORE -> filterTabIds.indexOf(R.id.filter_more)
        }.coerceAtLeast(0)
        updateFilterTabs()
        filterGames(searchBar.text.toString())
    }

    private var isInitialLoad = true

    private fun loadGames() {
        if (isInitialLoad) {
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
            swipeRefresh.visibility = View.GONE
        } else {
            swipeRefresh.isRefreshing = true
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val games = apiClient.getGames(limit = 100)
                val settings = try {
                    apiClient.getClientSettings()
                } catch (e: Exception) {
                    LimeLog.warning("Nova: Failed to load Polaris client settings for library launch modes: ${e.message}")
                    null
                }
                games to settings
            }
            val games = result.first
            clientSettings = result.second
            apiClient.clearCoverCache()
            allGames = games
            updateLibraryStats()
            updateStreamStatus()
            updateRecentRail()
            // Hide shimmer, show content
            if (shimmer.visibility == View.VISIBLE) {
                shimmer.stopShimmer()
                shimmer.visibility = View.GONE
                swipeRefresh.visibility = View.VISIBLE
            }
            isInitialLoad = false

            if (allGames.isEmpty()) {
                updateEmptyState("")
                emptyText.visibility = View.VISIBLE
            } else {
                emptyText.visibility = View.GONE
                filterGames(searchBar.text.toString(), forceCoverRefresh = true)
            }
            swipeRefresh.isRefreshing = false
            LimeLog.info("Nova: Loaded ${allGames.size} games")
        }
    }

    private fun filterGames(search: String, forceCoverRefresh: Boolean = false) {
        var filtered = allGames

        // Text search
        if (search.isNotEmpty()) {
            filtered = filtered.filter { it.name.contains(search, ignoreCase = true) }
        }

        filtered = when (filterState.primary) {
            PrimaryFilter.RECENT -> filtered
                .filter { it.lastLaunched > 0 }
                .sortedByDescending { it.lastLaunched }
            PrimaryFilter.SOURCES -> filtered.filter { it.source == filterState.source }
            PrimaryFilter.HDR -> filtered.filter { it.hdrSupported }
            PrimaryFilter.MORE -> {
                when {
                    filterState.category.isNotBlank() -> filtered.filter { it.category == filterState.category }
                    filterState.genre.isNotBlank() -> filtered.filter { game ->
                        game.genres.any { it.equals(filterState.genre, ignoreCase = true) }
                    }
                    else -> filtered
                }
            }
            PrimaryFilter.ALL -> filtered
        }

        adapter.updateGames(filtered)
        if (forceCoverRefresh) {
            adapter.reloadAllCovers()
        }
        resultsSummary.text = getString(R.string.nova_library_results_format, filtered.size)
        updateEmptyState(search)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateRecentRail() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            recentSection.visibility = View.GONE
            recentAdapter.updateGames(emptyList())
            return
        }

        val recentGames = allGames
            .filter { it.lastLaunched > 0 }
            .sortedByDescending { it.lastLaunched }
            .take(6)

        recentSection.visibility = if (recentGames.isEmpty()) View.GONE else View.VISIBLE
        recentSummary.text = if (recentGames.isEmpty()) {
            getString(R.string.nova_library_continue_empty)
        } else {
            getString(R.string.nova_library_continue_count, recentGames.size)
        }
        recentAdapter.updateGames(recentGames)
        if (recentGames.isNotEmpty()) {
            recentAdapter.reloadAllCovers()
        }
    }

    private fun updateLibraryStats() {
        val recentCount = allGames.count { it.lastLaunched > 0 }
        val hdrCount = allGames.count { it.hdrSupported }

        librarySummary.text = getString(
            R.string.nova_library_summary_format,
            allGames.size,
            recentCount,
            hdrCount
        )
        resultsSummary.text = getString(R.string.nova_library_results_format, allGames.size)
    }

    private fun updateStreamStatus() {
        val settings = clientSettings
        val aiEnabled = settings?.effective?.aiAutoQualityEnabled == true ||
            settings?.desired?.aiAutoQualityEnabled == true ||
            settings?.effective?.adaptiveBitrateEnabled == true ||
            settings?.desired?.adaptiveBitrateEnabled == true ||
            settings?.effective?.aiOptimizerEnabled == true ||
            settings?.desired?.aiOptimizerEnabled == true

        autoQualityState.setText(
            when {
                settings == null -> R.string.nova_library_status_checking
                aiEnabled -> R.string.nova_library_auto_quality_on
                else -> R.string.nova_library_auto_quality_off
            }
        )

        val modeLabel = settings?.effectiveModeLabel
            ?.takeIf { it.isNotBlank() }
            ?: settings?.desiredModeLabel?.takeIf { it.isNotBlank() }
            ?: getString(R.string.nova_library_mode_checking)
        modeState.text = modeLabel
    }

    private fun updateEmptyState(search: String) {
        when {
            filterState.primary == PrimaryFilter.RECENT -> {
                emptyTitle.setText(R.string.nova_library_empty_title_recent)
                emptyHint.setText(R.string.nova_library_empty_hint_recent)
            }
            search.isNotBlank() || filterState.hasActiveConstraint -> {
                emptyTitle.setText(R.string.nova_library_empty_title_filtered)
                emptyHint.setText(R.string.nova_library_empty_hint_filtered)
            }
            else -> {
                emptyTitle.setText(R.string.nova_library_empty_title_default)
                emptyHint.setText(R.string.nova_library_empty_hint_default)
            }
        }
    }

    // Filter tab IDs in order for bumper switching
    private val filterTabIds = listOf(
        R.id.filter_all, R.id.filter_recent, R.id.filter_sources,
        R.id.filter_hdr, R.id.filter_more
    )
    private var activeTabIndex = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // L1/R1 bumper buttons switch filter tabs
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                activeTabIndex = (activeTabIndex - 1 + filterTabIds.size) % filterTabIds.size
                findViewById<TextView>(filterTabIds[activeTabIndex]).performClick()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                activeTabIndex = (activeTabIndex + 1) % filterTabIds.size
                findViewById<TextView>(filterTabIds[activeTabIndex]).performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateFilterTabs() {
        val tabContainer = findViewById<LinearLayout>(R.id.nova_filter_tabs) ?: return
        findViewById<TextView>(R.id.filter_sources)?.text = filterState.source
            .takeIf { filterState.primary == PrimaryFilter.SOURCES && it.isNotBlank() }
            ?.let { sourceLabelFor(it) }
            ?: getString(R.string.nova_library_filter_sources)
        findViewById<TextView>(R.id.filter_more)?.text = when {
            filterState.primary != PrimaryFilter.MORE -> getString(R.string.nova_library_filter_more)
            filterState.category.isNotBlank() -> categoryLabelFor(filterState.category)
            filterState.genre.isNotBlank() -> filterState.genre
            else -> getString(R.string.nova_library_filter_more)
        }

        val accent = NovaThemeManager.getAccentColor(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val divider = NovaThemeManager.getDividerColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textSecondary = NovaThemeManager.getTextSecondaryColor(this)
        val selectedFill = ColorUtils.blendARGB(surface, accent, 0.28f)
        val defaultFill = ColorUtils.blendARGB(surface, NovaThemeManager.getWindowBackgroundColor(this), 0.18f)

        for (i in 0 until tabContainer.childCount) {
            val child = tabContainer.getChildAt(i) as? TextView ?: continue
            val selected = child.id == filterTabIds.getOrNull(activeTabIndex)
            child.background = roundedDrawable(
                if (selected) selectedFill else defaultFill,
                if (selected) accent else divider,
                8f,
                if (selected) 1.5f else 1f
            )
            child.setTextColor(if (selected) textPrimary else textSecondary)
            child.typeface = Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun showSourceFilterSheet() {
        val sources = allGames
            .map { it.source }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy({ sourceSortOrder(it) }, { sourceLabelFor(it) }))

        val choices = mutableListOf(
            FilterChoice(
                title = getString(R.string.nova_library_filter_all_sources),
                subtitle = getString(R.string.nova_library_filter_all_sources_hint),
                selected = filterState.primary == PrimaryFilter.ALL
            ) {
                applyFilterState(LibraryFilterState())
            }
        )
        sources.forEach { source ->
            choices += FilterChoice(
                title = sourceLabelFor(source),
                subtitle = getString(
                    R.string.nova_library_filter_source_count,
                    allGames.count { it.source == source }
                ),
                selected = filterState.primary == PrimaryFilter.SOURCES && filterState.source == source
            ) {
                applyFilterState(LibraryFilterState(primary = PrimaryFilter.SOURCES, source = source))
            }
        }
        showFilterChoiceSheet(
            getString(R.string.nova_library_filter_sheet_sources),
            getString(R.string.nova_library_filter_sheet_sources_hint),
            choices
        )
    }

    private fun showMoreFilterSheet() {
        val choices = mutableListOf(
            FilterChoice(
                title = getString(R.string.nova_library_filter_clear_more),
                subtitle = getString(R.string.nova_library_filter_clear_more_hint),
                selected = filterState.primary == PrimaryFilter.ALL
            ) {
                applyFilterState(LibraryFilterState())
            }
        )

        val categories = listOf("fast_action", "cinematic", "desktop", "vr")
            .filter { category -> allGames.any { it.category == category } }
        categories.forEach { category ->
            choices += FilterChoice(
                title = categoryLabelFor(category),
                subtitle = getString(R.string.nova_library_filter_category),
                selected = filterState.primary == PrimaryFilter.MORE && filterState.category == category
            ) {
                applyFilterState(LibraryFilterState(primary = PrimaryFilter.MORE, category = category))
            }
        }

        val genres = allGames
            .flatMap { it.genres }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sorted()
            .take(10)
        genres.forEach { genre ->
            choices += FilterChoice(
                title = genre,
                subtitle = getString(R.string.nova_library_filter_genre),
                selected = filterState.primary == PrimaryFilter.MORE && filterState.genre.equals(genre, ignoreCase = true)
            ) {
                applyFilterState(LibraryFilterState(primary = PrimaryFilter.MORE, genre = genre))
            }
        }

        showFilterChoiceSheet(
            getString(R.string.nova_library_filter_sheet_more),
            getString(R.string.nova_library_filter_sheet_more_hint),
            choices
        )
    }

    private fun showFilterChoiceSheet(title: String, hint: String, choices: List<FilterChoice>) {
        val dialog = BottomSheetDialog(this, R.style.NovaBottomSheet)
        val content = layoutInflater.inflate(R.layout.nova_library_filter_sheet, null, false)
        val accent = NovaThemeManager.getAccentColor(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val dialogSurface = NovaThemeManager.getDialogBackgroundColor(this)
        val divider = NovaThemeManager.getDividerColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textSecondary = NovaThemeManager.getTextSecondaryColor(this)
        val textMuted = NovaThemeManager.getTextMutedColor(this)

        content.findViewById<View>(R.id.nova_filter_sheet_root)?.background = topRoundedDrawable(
            dialogSurface,
            ColorUtils.blendARGB(divider, accent, 0.18f),
            18f
        )
        content.findViewById<View>(R.id.nova_filter_sheet_panel)?.background = roundedDrawable(
            ColorUtils.blendARGB(surface, dialogSurface, 0.12f),
            ColorUtils.blendARGB(divider, accent, 0.16f),
            8f
        )
        content.findViewById<TextView>(R.id.nova_filter_sheet_title)?.apply {
            text = title
            setTextColor(textPrimary)
        }
        content.findViewById<TextView>(R.id.nova_filter_sheet_hint)?.apply {
            text = hint
            setTextColor(textMuted)
        }

        val choicesContainer = content.findViewById<LinearLayout>(R.id.nova_filter_sheet_choices)
        choicesContainer.removeAllViews()
        choices.forEach { choice ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                isSelected = choice.selected
                minimumHeight = dp(52)
                setPadding(dp(11), dp(8), dp(11), dp(8))
                background = roundedDrawable(
                    if (choice.selected) ColorUtils.blendARGB(surface, accent, 0.16f) else Color.TRANSPARENT,
                    if (choice.selected) ColorUtils.setAlphaComponent(accent, 120) else Color.TRANSPARENT,
                    8f,
                    if (choice.selected) 1f else 0f
                )
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    dialog.dismiss()
                    choice.onSelect()
                }

                addView(LinearLayout(this@NovaLibraryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@NovaLibraryActivity).apply {
                        text = choice.title
                        setTextColor(textPrimary)
                        textSize = 13f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    if (choice.subtitle.isNotBlank()) {
                        addView(TextView(this@NovaLibraryActivity).apply {
                            text = choice.subtitle
                            setTextColor(textSecondary)
                            textSize = 10f
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                if (choice.selected) {
                    addView(TextView(this@NovaLibraryActivity).apply {
                        text = getString(R.string.nova_library_filter_selected)
                        setTextColor(accent)
                        textSize = 10f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        background = roundedDrawable(
                            ColorUtils.blendARGB(surface, accent, 0.18f),
                            ColorUtils.setAlphaComponent(accent, 95),
                            8f
                        )
                        setPadding(dp(9), dp(4), dp(9), dp(4))
                    })
                }
            }
            choicesContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(2)
                }
            )
        }

        dialog.setContentView(content)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            if (sheet != null) {
                BottomSheetBehavior.from(sheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        dialog.show()
    }

    private fun sourceSortOrder(source: String): Int {
        return when (source) {
            "steam" -> 0
            "lutris" -> 1
            "heroic" -> 2
            else -> 3
        }
    }

    private fun sourceLabelFor(source: String): String {
        return when (source) {
            "steam" -> "Steam"
            "lutris" -> "Lutris"
            "heroic" -> "Heroic"
            else -> source
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase() else char.toString()
                    }
                }
                .ifBlank { getString(R.string.nova_library_filter_other_source) }
        }
    }

    private fun categoryLabelFor(category: String): String {
        return when (category) {
            "fast_action" -> getString(R.string.nova_library_filter_action)
            "cinematic" -> getString(R.string.nova_library_filter_cinematic)
            "desktop" -> getString(R.string.nova_library_filter_desktop)
            "vr" -> getString(R.string.nova_library_filter_vr)
            else -> getString(R.string.nova_library_filter_more)
        }
    }

    private fun applyLibraryTheme() {
        val background = NovaThemeManager.getWindowBackgroundColor(this)
        val surface = NovaThemeManager.getCardBackgroundColor(this)
        val dialogSurface = NovaThemeManager.getDialogBackgroundColor(this)
        val accent = NovaThemeManager.getAccentColor(this)
        val divider = NovaThemeManager.getDividerColor(this)
        val textPrimary = NovaThemeManager.getTextPrimaryColor(this)
        val textSecondary = NovaThemeManager.getTextSecondaryColor(this)
        val textMuted = NovaThemeManager.getTextMutedColor(this)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        findViewById<View>(R.id.nova_library_root)?.setBackgroundColor(background)
        findViewById<View>(R.id.nova_library_panel)?.background = roundedDrawable(
            ColorUtils.blendARGB(dialogSurface, background, 0.12f),
            ColorUtils.blendARGB(divider, accent, 0.18f),
            8f
        )
        findViewById<View>(R.id.nova_library_status_panel)?.background = if (isLandscape) {
            null
        } else {
            roundedDrawable(
                ColorUtils.blendARGB(surface, accent, 0.08f),
                ColorUtils.blendARGB(divider, accent, 0.16f),
                8f
            )
        }
        findViewById<View>(R.id.nova_recent_section)?.background = roundedDrawable(surface, divider, 8f)
        findViewById<View>(R.id.nova_empty_panel)?.background = roundedDrawable(surface, divider, 8f)

        searchBar.background = roundedDrawable(
            ColorUtils.blendARGB(surface, background, 0.18f),
            ColorUtils.blendARGB(divider, accent, 0.18f),
            8f
        )
        searchBar.setTextColor(textPrimary)
        searchBar.setHintTextColor(textMuted)
        TextViewCompat.setCompoundDrawableTintList(searchBar, ColorStateList.valueOf(textMuted))

        findViewById<TextView>(R.id.nova_library_title)?.setTextColor(textPrimary)
        serverContext.setTextColor(textMuted)
        librarySummary.setTextColor(textMuted)
        resultsSummary.setTextColor(textSecondary)
        findViewById<TextView>(R.id.nova_recent_label)?.setTextColor(textPrimary)
        recentSummary.setTextColor(textSecondary)
        emptyTitle.setTextColor(textPrimary)
        emptyHint.setTextColor(textMuted)

        styleStatePill(autoQualityState, accent, surface, textPrimary)
        styleStatePill(modeState, accent, surface, textSecondary)
        styleStatePill(recentSummary, accent, surface, textSecondary)

        styleActionButton(
            findViewById(R.id.nova_library_back),
            ColorUtils.blendARGB(surface, accent, 0.14f),
            textPrimary,
            ColorUtils.blendARGB(divider, accent, 0.35f)
        )
        styleActionButton(
            findViewById(R.id.nova_library_refresh),
            ColorUtils.blendARGB(surface, accent, if (isLandscape) 0.11f else 0.06f),
            textPrimary,
            ColorUtils.blendARGB(divider, accent, if (isLandscape) 0.32f else 0.18f)
        )
        styleActionButton(
            findViewById(R.id.nova_library_manage),
            ColorUtils.blendARGB(surface, accent, if (isLandscape) 0.11f else 0.06f),
            textPrimary,
            ColorUtils.blendARGB(divider, accent, if (isLandscape) 0.32f else 0.18f)
        )
        styleActionButton(
            findViewById(R.id.nova_empty_retry),
            ColorUtils.blendARGB(surface, accent, 0.16f),
            textPrimary,
            ColorUtils.blendARGB(divider, accent, 0.35f)
        )

        swipeRefresh.setColorSchemeColors(accent)
        swipeRefresh.setProgressBackgroundColorSchemeColor(surface)
    }

    private fun styleActionButton(button: MaterialButton?, backgroundColor: Int, textColor: Int, strokeColor: Int) {
        if (button == null) return
        button.backgroundTintList = ColorStateList.valueOf(backgroundColor)
        button.strokeColor = ColorStateList.valueOf(strokeColor)
        button.strokeWidth = dp(1)
        button.setTextColor(textColor)
        button.iconTint = ColorStateList.valueOf(textColor)
        button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(textColor, 34))
    }

    private fun styleStatePill(view: TextView, accent: Int, surface: Int, textColor: Int) {
        view.background = roundedDrawable(
            ColorUtils.blendARGB(surface, accent, 0.10f),
            ColorUtils.setAlphaComponent(accent, 70),
            8f
        )
        view.setTextColor(textColor)
    }

    private fun roundedDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float, strokeDp: Float = 1f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiHelper.dpToPx(this@NovaLibraryActivity, radiusDp)
            setColor(fillColor)
            val strokePx = UiHelper.dpToPx(this@NovaLibraryActivity, strokeDp).toInt()
            setStroke(strokePx.coerceAtLeast(0), strokeColor)
        }
    }

    private fun topRoundedDrawable(fillColor: Int, strokeColor: Int, radiusDp: Float): GradientDrawable {
        val radius = UiHelper.dpToPx(this, radiusDp)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            setColor(fillColor)
            setStroke(UiHelper.dpToPx(this@NovaLibraryActivity, 1f).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int = UiHelper.dpToPx(this, value.toFloat()).toInt()

    private fun showGameDetail(game: PolarisGame) {
        dismissOpenGameDetail()
        val defaultToVirtualDisplay = PreferenceConfiguration.readPreferences(this).useVirtualDisplay
        val sheet = NovaGameDetailSheet.newInstance(game, apiClient, defaultToVirtualDisplay, clientSettings) { g, withVirtualDisplay ->
            launchGame(g, withVirtualDisplay)
        }
        sheet.show(supportFragmentManager, "game_detail")
    }

    override fun onStop() {
        dismissOpenGameDetail()
        super.onStop()
    }

    private fun dismissOpenGameDetail() {
        (supportFragmentManager.findFragmentByTag("game_detail") as? BottomSheetDialogFragment)
            ?.dismissAllowingStateLoss()
    }

    private fun applyHeaderInsets() {
        val header = findViewById<View>(R.id.nova_library_header) ?: return
        header.setOnApplyWindowInsetsListener { v, insets ->
            val topInset = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    insets.systemWindowInsetTop
                else -> 0
            }

            v.setPadding(
                v.paddingLeft,
                topInset + UiHelper.dpToPx(this, 16f).toInt(),
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }
        header.requestApplyInsets()
    }

    private fun openServerManagement() {
        val managementPort = if (streamHttpPort > 0) streamHttpPort + 1 else 47990
        val url = "https://$streamHost:$managementPort"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            LimeLog.warning("Nova: Failed to open Polaris management page $url: ${e.message}")
            Toast.makeText(this, R.string.nova_library_manage_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGame(game: PolarisGame, withVirtualDisplay: Boolean) {
        if (game.appId <= 0) {
            Toast.makeText(this, "This game entry is missing a launch ID", Toast.LENGTH_SHORT).show()
            return
        }
        if (streamUniqueId.isNullOrBlank() || streamPcUuid.isNullOrBlank() || streamServerCert == null) {
            Toast.makeText(this, "Missing Polaris session details for launch", Toast.LENGTH_SHORT).show()
            LimeLog.warning("Nova: Cannot launch from library; missing uniqueId, pcUuid, or server cert")
            return
        }

        val modeLabel = if (withVirtualDisplay) {
            getString(R.string.nova_library_launch_virtual_display)
        } else {
            getString(R.string.nova_library_launch_headless)
        }
        Toast.makeText(this, getString(R.string.nova_library_launching_mode, game.name, modeLabel), Toast.LENGTH_SHORT).show()
        LimeLog.info("Nova: Launching game ${game.name} (${game.id}/${game.appId})")

        val app = NvApp(game.name, game.id, game.appId, game.hdrSupported)

        ServerHelper.doStart(
            this,
            app,
            streamHost,
            streamHttpPort,
            streamHttpsPort,
            streamUniqueId!!,
            streamPcUuid!!,
            streamPcName,
            streamServerCommands,
            withVirtualDisplay,
            true,
            false,
            streamServerCert
        )
    }
}
