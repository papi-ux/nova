package com.papi.nova.profiles

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.EditProfileActivity
import com.papi.nova.R
import com.papi.nova.ui.panel.NovaSplitConfirm
import com.papi.nova.ui.panel.rememberNovaSplitConfirmState
import com.papi.nova.ui.panel.setNovaContent

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

        holder.bindDelete(profile)

        holder.itemView.setOnClickListener {
            holder.editProfile.performClick()
        }
    }

    /**
     * Delete splits in place into Keep and Delete, with Keep focused and what is lost said under
     * them, and the pair takes the whole row while it is armed: the name, the active mark and Edit
     * step aside. One A, a held A or mashed presses never delete; A, Right, A does.
     */
    private fun ProfileViewHolder.bindDelete(profile: SettingsProfile) {
        val name = profile.getName()
        deleteProfile.setNovaContent {
            key(profile.getUuid()) {
                val split = rememberNovaSplitConfirmState()
                LaunchedEffect(split.armed) { showArmed(split.armed) }
                NovaSplitConfirm(
                    label = context.getString(R.string.profile_manager_delete),
                    confirmLabel = context.getString(R.string.profile_manager_delete),
                    stayLabel = context.getString(R.string.nova_panel_keep),
                    consequence = context.getString(R.string.nova_profiles_delete_consequence, name),
                    icon = R.drawable.ic_delete,
                    state = split,
                    onConfirm = { delete(profile) },
                )
            }
        }
    }

    private fun delete(profile: SettingsProfile) {
        profilesManager.delete(profile.getUuid())
        profilesManager.save(context)
        Toast.makeText(
            context,
            context.getString(R.string.profile_manager_profile_deleted, profile.getName()),
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun getItemCount(): Int = profilesManager.getProfiles().size

    class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileDetails: View = itemView.findViewById(R.id.profileDetails)
        val profileName: TextView = itemView.findViewById(R.id.profileName)
        val profileTimestamp: TextView = itemView.findViewById(R.id.profileTimestamp)
        val profileActive: RadioButton = itemView.findViewById(R.id.profileActive)
        val editProfile: ImageButton = itemView.findViewById(R.id.editProfile)
        val deleteProfile: ComposeView = itemView.findViewById(R.id.deleteProfile)

        /** While Delete is armed, the pair takes the row; disarmed, the row is back as it was. */
        internal fun showArmed(armed: Boolean) {
            val others = if (armed) View.GONE else View.VISIBLE
            profileDetails.visibility = others
            profileActive.visibility = others
            editProfile.visibility = others
            deleteProfile.layoutParams = (deleteProfile.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (armed) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (armed) 1f else 0f
            }
        }
    }
}
