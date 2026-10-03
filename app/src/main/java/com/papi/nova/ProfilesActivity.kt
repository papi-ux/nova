package com.papi.nova

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.papi.nova.profiles.ProfilesAdapter
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.UiHelper

class ProfilesActivity : NovaActivity(), ProfilesManager.ProfileChangeListener {
    private lateinit var adapter: ProfilesAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private var addProfileFab: FloatingActionButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        NovaThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        recyclerView = findViewById(R.id.profilesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = ProfilesAdapter(this)
        recyclerView.adapter = adapter

        emptyState = findViewById(R.id.emptyState)

        val fab: FloatingActionButton = findViewById(R.id.addProfileFab)
        fab.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java)
            startActivity(intent)
        }
        // The + showed no focus, so a controller could reach it without seeing it. It takes the one
        // focus ring, and no shadow, like every other control.
        configureFabFocus(findViewById(R.id.addProfileFocusFrame))
        fab.compatElevation = 0f
        addProfileFab = fab

        ProfilesManager.getInstance().addListener(this)
        updateUI()

        UiHelper.notifyNewRootView(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ProfilesManager.getInstance().removeListener(this)
    }

    // FrameLayout.setForeground is available on Android 5; View.setForeground is Android 6.
    // The wrapper inherits the child's focused state and keeps the ring on every supported SDK.
    @android.annotation.SuppressLint("NewApi")
    private fun configureFabFocus(frame: FrameLayout) {
        frame.setAddStatesFromChildren(true)
        frame.foreground = com.papi.nova.ui.panel.NovaViewBridge.focusRing(this, com.papi.nova.ui.compose.NovaRadius.pill)
    }

    override fun onProfilesChanged() {
        runOnUiThread { updateUI() }
    }

    private fun updateUI() {
        val profileCount = ProfilesManager.getInstance().getProfiles().size
        if (profileCount == 0) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
            // With nothing listed, the first A makes a preset.
            addProfileFab?.requestFocus()
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
        adapter.notifyDataSetChanged()
    }
}
