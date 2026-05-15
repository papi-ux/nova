package com.papi.nova

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.papi.nova.profiles.ProfilesAdapter
import com.papi.nova.profiles.ProfilesManager
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.utils.UiHelper

class ProfilesActivity : AppCompatActivity(), ProfilesManager.ProfileChangeListener {
    private lateinit var adapter: ProfilesAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View

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

        ProfilesManager.getInstance().addListener(this)
        updateUI()

        UiHelper.notifyNewRootView(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ProfilesManager.getInstance().removeListener(this)
    }

    override fun onProfilesChanged() {
        runOnUiThread { updateUI() }
    }

    private fun updateUI() {
        val profileCount = ProfilesManager.getInstance().getProfiles().size
        if (profileCount == 0) {
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
        adapter.notifyDataSetChanged()
    }
}
