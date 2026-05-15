package com.papi.nova.profiles

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.EditProfileActivity
import com.papi.nova.R

class ProfilesAdapter(private val context: Context) : RecyclerView.Adapter<ProfilesAdapter.ProfileViewHolder>() {
    private val profilesManager = ProfilesManager.getInstance()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.row_profile, parent, false)
        return ProfileViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profiles = profilesManager.getProfiles()
        val profile = profiles[position]
        val activeProfile = profilesManager.getActive()

        holder.profileName.text = profile.getName()
        holder.profileTimestamp.text = DateUtils.getRelativeTimeSpanString(
            profile.getModifiedUtc(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )

        val isActive = activeProfile != null && activeProfile.getUuid() == profile.getUuid()
        holder.profileActive.isChecked = isActive

        holder.profileActive.setOnClickListener {
            if (isActive) {
                profilesManager.setActive(null)
                Toast.makeText(context, R.string.profile_manager_deactivated_profile, Toast.LENGTH_SHORT).show()
            } else {
                profilesManager.setActive(profile.getUuid())
                Toast.makeText(
                    context,
                    context.getString(R.string.profile_manager_activated_profile, profile.getName()),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            profilesManager.save(context)
        }

        holder.editProfile.setOnClickListener {
            val intent = Intent(context, EditProfileActivity::class.java)
            intent.putExtra("profileUuid", profile.getUuid().toString())
            context.startActivity(intent)
        }

        holder.deleteProfile.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle(R.string.profile_manager_delete_profile)
                .setMessage(context.getString(R.string.profile_manager_confirm_profile_deleteion, profile.getName()))
                .setPositiveButton(R.string.profile_manager_delete) { _, _ ->
                    profilesManager.delete(profile.getUuid())
                    profilesManager.save(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.profile_manager_profile_deleted, profile.getName()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
        }

        holder.itemView.setOnClickListener {
            holder.editProfile.performClick()
        }
    }

    override fun getItemCount(): Int = profilesManager.getProfiles().size

    class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileName: TextView = itemView.findViewById(R.id.profileName)
        val profileTimestamp: TextView = itemView.findViewById(R.id.profileTimestamp)
        val profileActive: RadioButton = itemView.findViewById(R.id.profileActive)
        val editProfile: ImageButton = itemView.findViewById(R.id.editProfile)
        val deleteProfile: ImageButton = itemView.findViewById(R.id.deleteProfile)
    }
}
