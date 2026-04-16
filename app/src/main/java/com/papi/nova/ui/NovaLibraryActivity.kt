package com.papi.nova.ui

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
import androidx.lifecycle.lifecycleScope
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisGame
import com.papi.nova.nvstream.http.NvApp
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.utils.ServerHelper

/**
 * Nova Game Library — browse and launch games from the Polaris server.
 * Shows a cover art grid with search and category filters.
 * D-pad navigable for RP6 and other controllers.
 */
class NovaLibraryActivity : AppCompatActivity() {

    private lateinit var apiClient: PolarisApiClient
    private lateinit var adapter: NovaGameAdapter
    private lateinit var searchBar: EditText
    private lateinit var gameGrid: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyText: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var serverContext: TextView
    private lateinit var librarySummary: TextView
    private lateinit var resultsSummary: TextView
    private lateinit var shimmer: ShimmerFrameLayout

    private var allGames = listOf<PolarisGame>()
    private var currentFilter = ""
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

        apiClient = PolarisApiClient(this, host, httpsPort)

        // Enable dense particles (nebulae + shooting stars) for library
        findViewById<SpaceParticleView>(R.id.space_particles_dense)?.dense = true

        searchBar = findViewById(R.id.nova_search)
        gameGrid = findViewById(R.id.nova_game_grid)
        swipeRefresh = findViewById(R.id.nova_swipe_refresh)
        emptyText = findViewById(R.id.nova_empty_text)
        emptyTitle = findViewById(R.id.nova_empty_title)
        emptyHint = findViewById(R.id.nova_empty_hint)
        shimmer = findViewById(R.id.nova_shimmer_container)
        serverContext = findViewById(R.id.nova_library_context)
        librarySummary = findViewById(R.id.nova_library_summary)
        resultsSummary = findViewById(R.id.nova_library_results)
        serverContext.text = if (serverName.isNullOrBlank()) {
            getString(R.string.nova_library_server_context_fallback)
        } else {
            getString(R.string.nova_library_server_context, serverName)
        }

        val columns = when (resources.configuration.screenWidthDp) {
            in 960..Int.MAX_VALUE -> 5
            in 720..959 -> 4
            in 600..719 -> 3
            else -> 2
        }
        gameGrid.layoutManager = GridLayoutManager(this, columns)

        adapter = NovaGameAdapter(
            apiClient,
            onGameClick = { game -> showGameDetail(game) },
            onGameLongClick = { game -> showGameDetail(game) }
        )
        gameGrid.adapter = adapter

        // Swipe to refresh
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.nova_accent))
        swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.nova_bg_elevated))
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

        // Filter tabs
        setupFilterTab(R.id.filter_all, "")
        setupFilterTab(R.id.filter_recent, "recent")
        setupFilterTab(R.id.filter_steam, "steam")
        setupFilterTab(R.id.filter_action, "fast_action")
        setupFilterTab(R.id.filter_cinematic, "cinematic")

        // Retry button
        findViewById<MaterialButton>(R.id.nova_empty_retry).setOnClickListener { loadGames() }

        // Load games
        loadGames()
    }

    private fun setupFilterTab(id: Int, filter: String) {
        findViewById<TextView>(id).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            currentFilter = filter
            activeTabIndex = filterTabIds.indexOf(id).coerceAtLeast(0)
            // Update visual state — selected chip gets accent background
            val tabContainer = findViewById<LinearLayout>(R.id.nova_filter_tabs)
            for (i in 0 until tabContainer.childCount) {
                val child = tabContainer.getChildAt(i)
                child.setBackgroundResource(
                    if (child.id == id) R.drawable.nova_chip_selected
                    else 0 // transparent — style handles default
                )
            }
            filterGames(searchBar.text.toString())
        }
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
            val games = withContext(Dispatchers.IO) { apiClient.getGames(limit = 100) }
            apiClient.clearCoverCache()
            allGames = games
            updateLibraryStats()
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

        // "Recent" sort — show only played games, sorted by most recent
        if (currentFilter == "recent") {
            filtered = filtered
                .filter { it.lastLaunched > 0 }
                .sortedByDescending { it.lastLaunched }
        } else if (currentFilter.isNotEmpty()) {
            // Category/source filter
            filtered = filtered.filter {
                it.source == currentFilter || it.category == currentFilter
            }
        }

        adapter.updateGames(filtered)
        if (forceCoverRefresh) {
            adapter.reloadAllCovers()
        }
        resultsSummary.text = getString(R.string.nova_library_results_format, filtered.size)
        updateEmptyState(search)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
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

    private fun updateEmptyState(search: String) {
        when {
            currentFilter == "recent" -> {
                emptyTitle.setText(R.string.nova_library_empty_title_recent)
                emptyHint.setText(R.string.nova_library_empty_hint_recent)
            }
            search.isNotBlank() || currentFilter.isNotEmpty() -> {
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
        R.id.filter_all, R.id.filter_recent, R.id.filter_steam,
        R.id.filter_action, R.id.filter_cinematic
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

    private fun showGameDetail(game: PolarisGame) {
        val defaultToVirtualDisplay = PreferenceConfiguration.readPreferences(this).useVirtualDisplay
        val sheet = NovaGameDetailSheet.newInstance(game, apiClient, defaultToVirtualDisplay) { g, withVirtualDisplay ->
            launchGame(g, withVirtualDisplay)
        }
        sheet.show(supportFragmentManager, "game_detail")
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
