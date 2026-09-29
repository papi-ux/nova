package com.papi.nova.profiles

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.EditProfileActivity
import com.papi.nova.R
import com.papi.nova.ui.NovaThemeManager
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
        holder.showCurrent(isActive)

        // The row is the one place that says which preset is in use, so A or a tap on it is what
        // changes it: the preset in use stops being used, any other starts. Edit has its button.
        holder.itemView.isFocusable = true
        ViewCompat.replaceAccessibilityAction(
            holder.itemView,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            context.getString(if (isActive) R.string.nova_profiles_stop_using else R.string.nova_profiles_use),
            null,
        )
        // The current mark moving is the answer, in the row that was pressed: a Toast said it again
        // over the list (audit X2).
        holder.itemView.setOnClickListener {
            profilesManager.setActive(if (isActive) null else profile.getUuid())
            profilesManager.save(context)
        }

        holder.editProfile.setOnClickListener {
            val intent = Intent(context, EditProfileActivity::class.java)
            intent.putExtra("profileUuid", profile.getUuid().toString())
            context.startActivity(intent)
        }

        holder.bindDelete(profile)
    }

    /**
     * Delete splits in place into Keep and Delete, with Keep focused and what is lost said under
     * them, and the pair takes the whole row while it is armed: the name, the current mark and Edit
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

    /** The row leaving the list is the answer; the split already said what Delete loses (X2). */
    private fun delete(profile: SettingsProfile) {
        profilesManager.delete(profile.getUuid())
        profilesManager.save(context)
    }

    override fun getItemCount(): Int = profilesManager.getProfiles().size

    class ProfileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileDetails: View = itemView.findViewById(R.id.profileDetails)
        val profileName: TextView = itemView.findViewById(R.id.profileName)
        val profileTimestamp: TextView = itemView.findViewById(R.id.profileTimestamp)
        val profileCurrent: ImageView = itemView.findViewById(R.id.profileCurrent)
        val editProfile: ImageButton = itemView.findViewById(R.id.editProfile)
        val deleteProfile: ComposeView = itemView.findViewById(R.id.deleteProfile)
        private var current = false

        /**
         * The one mark for the preset in use (R9): the trailing accent check, a SemiBold name, and
         * the row selected with the state description Current. Never a radio button.
         */
        internal fun showCurrent(isCurrent: Boolean) {
            current = isCurrent
            profileCurrent.imageTintList = ColorStateList.valueOf(NovaThemeManager.getAccentColor(itemView.context))
            profileName.typeface = if (isCurrent) CurrentTypeface else RestTypeface
            itemView.isSelected = isCurrent
            ViewCompat.setStateDescription(
                itemView,
                if (isCurrent) itemView.context.getString(R.string.nova_panel_current) else null,
            )
            if (profileDetails.visibility == View.VISIBLE) {
                profileCurrent.visibility = if (isCurrent) View.VISIBLE else View.GONE
            }
        }

        /** While Delete is armed, the pair takes the row; disarmed, the row is back as it was. */
        internal fun showArmed(armed: Boolean) {
            val others = if (armed) View.GONE else View.VISIBLE
            profileDetails.visibility = others
            profileCurrent.visibility = if (armed || !current) View.GONE else View.VISIBLE
            editProfile.visibility = others
            deleteProfile.layoutParams = (deleteProfile.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (armed) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (armed) 1f else 0f
            }
        }

        private companion object {
            val RestTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val CurrentTypeface: Typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(Typeface.DEFAULT, 600, false)
            } else {
                Typeface.DEFAULT_BOLD
            }
        }
    }
}
