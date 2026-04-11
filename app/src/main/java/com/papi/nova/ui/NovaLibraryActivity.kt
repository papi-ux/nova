package com.papi.nova.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.api.PolarisGame
import com.papi.nova.manager.FeatureFlagManager

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
    private lateinit var emptyText: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var serverContext: TextView

    private var allGames = listOf<PolarisGame>()
    private var currentFilter = ""

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_HTTPS_PORT = "https_port"
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

        apiClient = PolarisApiClient(this, host, httpsPort)

        // Enable dense particles (nebulae + shooting stars) for library
        findViewById<SpaceParticleView>(R.id.space_particles_dense)?.dense = true

        searchBar = findViewById(R.id.nova_search)
        gameGrid = findViewById(R.id.nova_game_grid)
        swipeRefresh = findViewById(R.id.nova_swipe_refresh)
        emptyText = findViewById(R.id.nova_empty_text)
        emptyTitle = findViewById(R.id.nova_empty_title)
        emptyHint = findViewById(R.id.nova_empty_hint)
        serverContext = findViewById(R.id.nova_library_context)
        serverContext.text = if (serverName.isNullOrBlank()) {
            getString(R.string.nova_library_server_context_fallback)
        } else {
            getString(R.string.nova_library_server_context, serverName)
        }

        // Grid layout — 2 columns on phone, 3 on tablet/RP6
        val columns = if (resources.configuration.screenWidthDp >= 600) 3 else 2
        gameGrid.layoutManager = GridLayoutManager(this, columns)

        adapter = NovaGameAdapter(apiClient,
            onGameClick = { game -> launchGame(game) },
            onGameLongClick = { game -> showGameDetail(game) }
        )
        gameGrid.adapter = adapter

        // Pull-to-refresh
        swipeRefresh.setColorSchemeColors(0xFF7c73ff.toInt())
        swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF232340.toInt())
        swipeRefresh.setOnRefreshListener { loadGames() }

        // Search
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterGames(s?.toString() ?: "")
            }
        })

        // Filter tabs
        setupFilterTab(R.id.filter_all, "")
        setupFilterTab(R.id.filter_recent, "recent")
        setupFilterTab(R.id.filter_steam, "steam")
        setupFilterTab(R.id.filter_action, "fast_action")
        setupFilterTab(R.id.filter_cinematic, "cinematic")

        // Load games
        loadGames()
    }

    private fun setupFilterTab(id: Int, filter: String) {
        findViewById<TextView>(id).setOnClickListener {
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

    private fun loadGames() {
        Thread {
            allGames = apiClient.getGames(limit = 100)
            runOnUiThread {
                swipeRefresh.isRefreshing = false
                if (allGames.isEmpty()) {
                    updateEmptyState("")
                    emptyText.visibility = View.VISIBLE
                    gameGrid.visibility = View.GONE
                } else {
                    emptyText.visibility = View.GONE
                    gameGrid.visibility = View.VISIBLE
                    filterGames(searchBar.text.toString())
                }
                LimeLog.info("Nova: Loaded ${allGames.size} games")
            }
        }.start()
    }

    private fun filterGames(search: String) {
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
        updateEmptyState(search)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        gameGrid.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
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
        val sheet = NovaGameDetailSheet.newInstance(game, apiClient) { g ->
            launchGame(g)
        }
        sheet.show(supportFragmentManager, "game_detail")
    }

    private fun launchGame(game: PolarisGame) {
        Toast.makeText(this, "Launching ${game.name}...", Toast.LENGTH_SHORT).show()
        LimeLog.info("Nova: Launching game ${game.name} (${game.id})")

        // Send device display info for smart launch resolution matching
        val dm = resources.displayMetrics
        val displayWidth = dm.widthPixels
        val displayHeight = dm.heightPixels
        val displayFps = windowManager.defaultDisplay?.let { display ->
            display.refreshRate.toInt()
        } ?: 60

        Thread {
            val success = apiClient.launchGame(game.id, displayWidth, displayHeight, displayFps)
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Starting ${game.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to launch ${game.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
