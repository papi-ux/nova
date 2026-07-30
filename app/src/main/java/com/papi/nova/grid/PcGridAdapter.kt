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
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.ui.NovaThemeManager
import java.util.IdentityHashMap
import java.util.Locale

class PcGridAdapter(
    context: Context,
    prefs: PreferenceConfiguration
) : GenericGridAdapter<PcViewModel.ComputerObject>(context, getLayoutIdForPreferences(prefs)) {
    // PcViewModel and ComputerDetails treat a nonblank UUID as the logical host identity.
    // Object identity is only a provisional fallback until discovery supplies that UUID.
    private val provisionalIdsByObject = IdentityHashMap<PcViewModel.ComputerObject, Long>()
    private val stableIdsByUuid = HashMap<String, Long>()
    private val reservedStableIds = HashSet<Long>()
    private val itemIds = ArrayList<Long>()
    private var nextFallbackStableId = Long.MIN_VALUE
    private var serverActionListener: ((PcViewModel.ComputerObject) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(i: Int): Long = itemIds[i]

    override fun setItems(items: List<PcViewModel.ComputerObject>?) {
        val nextItems = normalizeItems(items.orEmpty())
        val oldIds = itemIds.toList()
        val newIds = assignStableIds(nextItems)

        if (oldIds == newIds) {
            itemList.clear()
            itemList.addAll(nextItems)
            itemIds.clear()
            itemIds.addAll(newIds)
            if (itemList.isNotEmpty()) {
                notifyItemRangeChanged(0, itemList.size, SERVER_ROW_REFRESH_PAYLOAD)
            }
            return
        }

        val diff =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = oldIds.size

                    override fun getNewListSize(): Int = newIds.size

                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        oldIds[oldItemPosition] == newIds[newItemPosition]

                    // ComputerObject instances are updated in place by PcViewModel, so retained
                    // rows must rebind even when their identity and position stay the same.
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = false

                    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any =
                        SERVER_ROW_REFRESH_PAYLOAD
                }
            )

        itemList.clear()
        itemList.addAll(nextItems)
        itemIds.clear()
        itemIds.addAll(newIds)
        diff.dispatchUpdatesTo(this)
    }

    private fun normalizeItems(items: List<PcViewModel.ComputerObject>): List<PcViewModel.ComputerObject> {
        val seenObjects = IdentityHashMap<PcViewModel.ComputerObject, Boolean>(items.size)
        val seenUuids = HashSet<String>(items.size)
        return items.filter { computer ->
            if (seenObjects.put(computer, true) != null) {
                false
            } else {
                val uuid = computer.details.uuid.trim()
                uuid.isEmpty() || seenUuids.add(uuid)
            }
        }
    }

    private fun assignStableIds(items: List<PcViewModel.ComputerObject>): List<Long> =
        items.map(::stableIdFor)

    private fun stableIdFor(computer: PcViewModel.ComputerObject): Long {
        val uuid = computer.details.uuid.trim()
        if (uuid.isEmpty()) {
            return provisionalIdsByObject.getOrPut(computer, ::reserveFallbackId)
        }

        val existingId = stableIdsByUuid[uuid]
        if (existingId != null) {
            provisionalIdsByObject.remove(computer)
            return existingId
        }

        val id = provisionalIdsByObject.remove(computer) ?: reserveUniqueId(stableServerId(uuid))
        stableIdsByUuid[uuid] = id
        return id
    }

    private fun reserveFallbackId(): Long {
        val id = reserveUniqueId(nextFallbackStableId)
        nextFallbackStableId = id + 1
        return id
    }

    private fun reserveUniqueId(candidate: Long): Long {
        var id = candidate
        while (id == RecyclerView.NO_ID || !reservedStableIds.add(id)) {
            id++
        }
        return id
    }

    fun updateLayoutWithPreferences(context: Context, prefs: PreferenceConfiguration) {
        // This will trigger the view to reload with the new layout.
        setLayoutId(getLayoutIdForPreferences(prefs))
    }

    fun addComputer(computer: PcViewModel.ComputerObject) {
        val updatedItems = itemList.toMutableList()
        updatedItems.add(computer)
        updatedItems.sortWith { lhs, rhs ->
            lhs.details.name.lowercase(Locale.getDefault())
                .compareTo(rhs.details.name.lowercase(Locale.getDefault()))
        }
        setItems(updatedItems)
    }

    fun removeComputer(computer: PcViewModel.ComputerObject): Boolean {
        val updatedItems = itemList.toMutableList()
        if (!updatedItems.remove(computer)) {
            return false
        }
        setItems(updatedItems)
        return true
    }

    override fun clear() {
        setItems(emptyList())
    }

    fun setOnServerActionListener(listener: (PcViewModel.ComputerObject) -> Unit) {
        serverActionListener = listener
    }

    /** Cached references for PcView-specific views (status dot + text). */
    private class PcViewHolder {
        var statusDot: View? = null
        var statusText: TextView? = null
        var statusHint: TextView? = null
        var primaryAction: TextView? = null
        var serverActions: View? = null
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
        holder.serverActions = parentView.findViewById(R.id.server_actions_button)
        parentView.setTag(TAG_PC_HOLDER, holder)
        return holder
    }

    override fun populateView(
        parentView: View,
        imgView: ImageView,
        gridMask: RelativeLayout?,
        prgView: ProgressBar?,
        txtView: TextView,
        overlayView: ImageView,
        obj: PcViewModel.ComputerObject
    ) {
        val pcHolder = getPcHolder(parentView)
        applyCardTheme(parentView, imgView, prgView!!, txtView, pcHolder)
        pcHolder.serverActions?.apply {
            isActivated = true
            setOnClickListener { serverActionListener?.invoke(obj) }
        }
        parentView.setOnLongClickListener {
            val listener = serverActionListener
            if (listener == null) {
                false
            } else {
                listener(obj)
                true
            }
        }

        imgView.setImageResource(R.drawable.ic_computer)
        val iconColor = if (obj.details.state == ComputerDetails.State.ONLINE) {
            NovaThemeManager.getAccentColor(context)
        } else {
            NovaThemeManager.getTextSecondaryColor(context)
        }
        ImageViewCompat.setImageTintList(imgView, ColorStateList.valueOf(iconColor))

        val statusDot = pcHolder.statusDot
        val statusText = pcHolder.statusText
        val statusHint = pcHolder.statusHint
        val primaryAction = pcHolder.primaryAction
        setPrimaryActionReady(primaryAction, false)

        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.alpha = 1.0f
            statusDot?.setBackgroundResource(R.drawable.nova_status_online)
            if (statusText != null) {
                if (obj.details.pairState == PairingManager.PairState.PAIRED && obj.details.serverCert == null) {
                    statusText.setText(R.string.pcview_card_status_repair_pair)
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_warning))
                    primaryAction?.setText(R.string.pcview_card_action_pair)
                    setPrimaryActionReady(primaryAction, true)
                    setStatusHint(statusHint, R.string.pcview_card_hint_pair_repair)
                } else if (obj.details.pairState == PairingManager.PairState.NOT_PAIRED) {
                    statusText.setText(
                        if (obj.details.serverCert == null) {
                            R.string.pcview_card_status_pair_required
                        } else {
                            R.string.pcview_card_status_repair_pair
                        }
                    )
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_warning))
                    primaryAction?.setText(R.string.pcview_card_action_pair)
                    setPrimaryActionReady(primaryAction, true)
                    setStatusHint(statusHint, R.string.pcview_card_hint_pair)
                } else if (obj.details.runningGameId != 0) {
                    statusText.setText(R.string.pcview_card_status_streaming)
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_success))
                    primaryAction?.setText(
                        if (obj.details.currentGameOwnedByClient == false) {
                            R.string.applist_menu_watch
                        } else {
                            R.string.pcview_card_action_resume
                        }
                    )
                    setPrimaryActionReady(primaryAction, true)
                    setStatusHint(statusHint, R.string.pcview_card_hint_streaming)
                } else if (obj.details.libraryState == ComputerDetails.LibraryState.AVAILABLE) {
                    statusText.text = context.getString(
                        R.string.pcview_card_status_library_ready_format,
                        formatAddressSuffix(obj.details.activeAddress?.address)
                    )
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_open_library)
                    setPrimaryActionReady(primaryAction, true)
                    setStatusHint(statusHint, R.string.pcview_card_hint_open_library)
                } else if (obj.details.libraryState == ComputerDetails.LibraryState.UNKNOWN) {
                    statusText.setText(R.string.pcview_card_status_checking_library)
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_checking_library)
                    setStatusHint(statusHint, R.string.pcview_card_hint_checking_library)
                } else {
                    statusText.text = context.getString(
                        R.string.pcview_card_status_compatibility_format,
                        formatAddressSuffix(obj.details.activeAddress?.address)
                    )
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_open_apps)
                    setPrimaryActionReady(primaryAction, true)
                    setStatusHint(statusHint, R.string.pcview_card_hint_open_apps)
                }
            }
        } else if (obj.details.state == ComputerDetails.State.OFFLINE) {
            imgView.alpha = 0.4f
            statusDot?.setBackgroundResource(R.drawable.nova_status_offline)
            if (statusText != null) {
                statusText.setText(R.string.pcview_card_status_offline)
                statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
            }
            if (obj.details.macAddress != null) {
                primaryAction?.setText(R.string.pcview_card_action_wake)
                setPrimaryActionReady(primaryAction, true)
                setStatusHint(statusHint, R.string.pcview_card_hint_wake)
            } else {
                primaryAction?.setText(R.string.pcview_card_action_refreshing)
                setStatusHint(statusHint, R.string.pcview_card_hint_offline_no_wake)
            }
        } else {
            imgView.alpha = 0.6f
            statusDot?.setBackgroundResource(R.drawable.nova_status_connecting)
            if (statusText != null) {
                statusText.setText(R.string.pcview_card_status_connecting)
                statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
            }
            primaryAction?.setText(R.string.pcview_card_action_refreshing)
            setStatusHint(statusHint, R.string.pcview_card_hint_refreshing)
        }
        primaryAction?.let {
            it.contentDescription = it.text
            it.isSelected = false
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
        primaryAction?.isSelected = false
    }

    private fun setPrimaryActionReady(primaryAction: TextView?, ready: Boolean) {
        primaryAction ?: return
        primaryAction.isActivated = ready
        primaryAction.isSelected = false
        primaryAction.setTextColor(
            if (ready) NovaThemeManager.getTextPrimaryColor(context) else NovaThemeManager.getTextMutedColor(context),
        )
    }

    private fun formatAddressSuffix(address: String?): String =
        address?.takeIf { it.isNotBlank() } ?: context.getString(R.string.pcview_card_status_local_network)

    private fun setStatusHint(statusHint: TextView?, textRes: Int) {
        statusHint ?: return
        statusHint.setText(textRes)
        statusHint.visibility = View.VISIBLE
    }

    private fun applyCardTheme(parentView: View, imgView: ImageView, prgView: ProgressBar, txtView: TextView, pcHolder: PcViewHolder) {
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
        pcHolder.statusText?.setTextColor(NovaThemeManager.getTextMutedColor(context))
        pcHolder.statusHint?.setTextColor(NovaThemeManager.getTextMutedColor(context))
    }

    companion object {
        private const val TAG_PC_HOLDER = R.id.status_dot
        private val SERVER_ROW_REFRESH_PAYLOAD = Any()

        private fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int = R.layout.pc_grid_item
    }
}

private fun stableServerId(uuid: String): Long {
    var hash = -3750763034362895579L
    for (character in uuid) {
        hash = hash xor character.code.toLong()
        hash *= 1099511628211L
    }
    return hash
}
