package com.papi.nova.grid

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaThemeManager
import java.util.Locale

class PcGridAdapter(
    context: Context,
    prefs: PreferenceConfiguration
) : GenericGridAdapter<PcViewModel.ComputerObject>(context, getLayoutIdForPreferences(prefs)) {
    fun updateLayoutWithPreferences(context: Context, prefs: PreferenceConfiguration) {
        // This will trigger the view to reload with the new layout.
        setLayoutId(getLayoutIdForPreferences(prefs))
    }

    fun addComputer(computer: PcViewModel.ComputerObject) {
        itemList.add(computer)
        sortList()
    }

    private fun sortList() {
        itemList.sortWith { lhs, rhs ->
            lhs.details.name.lowercase(Locale.getDefault())
                .compareTo(rhs.details.name.lowercase(Locale.getDefault()))
        }
    }

    fun removeComputer(computer: PcViewModel.ComputerObject): Boolean = itemList.remove(computer)

    /** Cached references for PcView-specific views (status dot + text). */
    private class PcViewHolder {
        var statusDot: View? = null
        var statusText: TextView? = null
        var statusHint: TextView? = null
        var primaryAction: TextView? = null
    }

    private fun getPcHolder(parentView: View): PcViewHolder {
        val tag = parentView.getTag(TAG_PC_HOLDER)
        if (tag is PcViewHolder) {
            return tag
        }

        val holder = PcViewHolder()
        holder.statusDot = parentView.findViewById(R.id.status_dot)
        holder.statusText = parentView.findViewById(R.id.status_text)
        holder.statusHint = parentView.findViewById(R.id.status_hint_text)
        holder.primaryAction = parentView.findViewById(R.id.primary_action_text)
        parentView.setTag(TAG_PC_HOLDER, holder)
        return holder
    }

    override fun populateView(
        parentView: View,
        imgView: ImageView,
        gridMask: RelativeLayout?,
        prgView: ProgressBar,
        txtView: TextView,
        overlayView: ImageView,
        obj: PcViewModel.ComputerObject
    ) {
        applyCardTheme(parentView, imgView, prgView, txtView)

        imgView.setImageResource(R.drawable.ic_computer)
        imgView.setColorFilter(NovaThemeManager.getTextSecondaryColor(context))

        val pcHolder = getPcHolder(parentView)
        val statusDot = pcHolder.statusDot
        val statusText = pcHolder.statusText
        val statusHint = pcHolder.statusHint
        val primaryAction = pcHolder.primaryAction

        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.alpha = 1.0f
            statusDot?.setBackgroundResource(R.drawable.nova_status_online)
            if (statusText != null) {
                if (obj.details.pairState == PairingManager.PairState.PAIRED && obj.details.serverCert == null) {
                    statusText.text = "Repair pair"
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_warning))
                    primaryAction?.setText(R.string.pcview_card_action_pair)
                    setStatusHint(statusHint, R.string.pcview_card_hint_pair)
                } else if (obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
                    statusText.text = if (obj.details.serverCert == null) "Pair required" else "Repair pair"
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_warning))
                    primaryAction?.setText(R.string.pcview_card_action_pair)
                    setStatusHint(statusHint, R.string.pcview_card_hint_pair)
                } else if (obj.details.runningGameId != 0) {
                    statusText.text = "Streaming"
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_success))
                    primaryAction?.setText(
                        if (obj.details.currentGameOwnedByClient == false) {
                            R.string.applist_menu_watch
                        } else {
                            R.string.pcview_card_action_resume
                        }
                    )
                    setStatusHint(statusHint, R.string.pcview_card_hint_streaming)
                } else if (obj.details.libraryState == ComputerDetails.LibraryState.AVAILABLE) {
                    val addr = obj.details.activeAddress?.address ?: ""
                    statusText.text = "Library ready \u00b7 $addr"
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_open_library)
                    setStatusHint(statusHint, R.string.pcview_card_hint_open_library)
                } else if (obj.details.libraryState == ComputerDetails.LibraryState.UNKNOWN) {
                    statusText.text = "Checking library"
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_checking_library)
                    setStatusHint(statusHint, R.string.pcview_card_hint_checking_library)
                } else {
                    val addr = obj.details.activeAddress?.address ?: ""
                    statusText.text = "Compatibility mode \u00b7 $addr"
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_open_apps)
                    setStatusHint(statusHint, R.string.pcview_card_hint_open_apps)
                }
            }
        } else if (obj.details.state == ComputerDetails.State.OFFLINE) {
            imgView.alpha = 0.4f
            statusDot?.setBackgroundResource(R.drawable.nova_status_offline)
            if (statusText != null) {
                statusText.text = "Offline"
                statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
            }
            primaryAction?.setText(R.string.pcview_card_action_wake)
            setStatusHint(statusHint, R.string.pcview_card_hint_wake)
        } else {
            imgView.alpha = 0.6f
            statusDot?.setBackgroundResource(R.drawable.nova_status_connecting)
            if (statusText != null) {
                statusText.text = "Connecting\u2026"
                statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
            }
            primaryAction?.setText(R.string.pcview_card_action_refreshing)
            setStatusHint(statusHint, R.string.pcview_card_hint_refreshing)
        }
        primaryAction?.let {
            it.contentDescription = it.text
            it.isSelected = parentView.hasFocus()
        }

        prgView.visibility = if (obj.details.state == ComputerDetails.State.UNKNOWN) View.VISIBLE else View.INVISIBLE

        txtView.text = obj.details.name
        txtView.alpha = if (obj.details.state == ComputerDetails.State.ONLINE) 1.0f else 0.6f

        if (obj.details.state == ComputerDetails.State.OFFLINE) {
            overlayView.setImageResource(R.drawable.ic_pc_offline)
            overlayView.alpha = 0.4f
            overlayView.visibility = View.VISIBLE
        } else if (
            obj.details.state == ComputerDetails.State.ONLINE &&
            (
                obj.details.pairState == PairingManager.PairState.NOT_PAIRED ||
                    obj.details.pairState == PairingManager.PairState.PAIRED && obj.details.serverCert == null
                )
        ) {
            overlayView.setImageResource(R.drawable.ic_lock)
            overlayView.alpha = 1.0f
            overlayView.visibility = View.VISIBLE
        } else {
            overlayView.visibility = View.GONE
        }
    }

    override fun onItemFocusChanged(parentView: View, hasFocus: Boolean) {
        val primaryAction = getPcHolder(parentView).primaryAction
        primaryAction?.isSelected = hasFocus
    }

    private fun setStatusHint(statusHint: TextView?, textRes: Int) {
        statusHint ?: return
        statusHint.setText(textRes)
        statusHint.visibility = View.VISIBLE
    }

    private fun applyCardTheme(parentView: View, imgView: ImageView, prgView: ProgressBar, txtView: TextView) {
        val card = if (parentView is ViewGroup && parentView.childCount > 0) {
            parentView.getChildAt(0)
        } else {
            parentView
        }

        val background = GradientDrawable()
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = context.resources.displayMetrics.density * 16f
        background.setColor(NovaThemeManager.getCardBackgroundColor(context))
        background.setStroke(context.resources.displayMetrics.density.toInt(), NovaThemeManager.getDividerColor(context))
        card.background = background

        txtView.setTextColor(NovaThemeManager.getTextPrimaryColor(context))
        prgView.indeterminateTintList = ColorStateList.valueOf(NovaThemeManager.getAccentColor(context))
        imgView.imageTintList = ColorStateList.valueOf(NovaThemeManager.getTextSecondaryColor(context))

        val primaryAction = parentView.findViewById<TextView>(R.id.primary_action_text)
        if (primaryAction != null) {
            primaryAction.setTextColor(NovaThemeManager.getAccentColor(context))
        }
    }

    companion object {
        private const val TAG_PC_HOLDER = R.id.status_dot

        private fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int = R.layout.pc_grid_item
    }
}
