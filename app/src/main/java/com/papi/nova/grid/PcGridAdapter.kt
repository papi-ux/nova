package com.papi.nova.grid

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import com.papi.nova.PcViewModel
import com.papi.nova.R
import com.papi.nova.api.PolarisApiClient
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.PairingManager
import com.papi.nova.preferences.PreferenceConfiguration
import com.papi.nova.shared.polaris.model.PolarisGame
import com.papi.nova.ui.NovaControlSize
import com.papi.nova.ui.NovaHostSessionAction
import com.papi.nova.ui.NovaControlSizePreferences
import com.papi.nova.ui.NovaHostNowPlaying
import com.papi.nova.ui.NovaHostsViewMetrics
import com.papi.nova.ui.NovaThemeManager
import com.papi.nova.ui.novaBreakAtDots
import com.papi.nova.ui.novaNowPlayingSpoken
import com.papi.nova.ui.novaNowPlayingWords
import com.papi.nova.ui.panel.NovaViewBridge
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.roundToInt

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
    private var hostConnections: Map<String, String> = emptyMap()
    private var nowPlaying: Map<String, NovaHostNowPlaying> = emptyMap()
    private var endSessionListener: ((NovaHostSessionAction) -> Unit)? = null
    private var resumeSessionListener: ((NovaHostSessionAction) -> Unit)? = null
    private var nowPlayingArtwork: ((ImageView, String, PolarisGame) -> Unit)? = null

    init {
        setHasStableIds(true)
    }

    /**
     * A host's row is as wide as its list, so the poster grid's focus zoom pushed both its ends,
     * and the sides of its focus ring, past the list's edge, where they were cut off. The ring
     * and the lift say it has focus; it does not grow.
     */
    override val focusedScale: Float get() = 1f

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

    /** A device appearance change rebinds existing stable rows without replacing their host IDs. */
    fun refreshControlSize() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, SERVER_ROW_REFRESH_PAYLOAD)
    }

    /** The chip words each host, by UUID, has confirmed about this device's connection to it. */
    fun setHostConnections(connections: Map<String, String>) {
        if (connections == hostConnections) return
        hostConnections = connections
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, SERVER_ROW_REFRESH_PAYLOAD)
    }

    fun setOnServerActionListener(listener: (PcViewModel.ComputerObject) -> Unit) {
        serverActionListener = listener
    }

    /** This device's resumable sessions, by host UUID, for each card's now-playing pill. */
    internal fun setNowPlaying(sessions: Map<String, NovaHostNowPlaying>) {
        if (sessions == nowPlaying) return
        nowPlaying = sessions
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, SERVER_ROW_REFRESH_PAYLOAD)
    }

    internal fun setOnEndSessionListener(listener: (NovaHostSessionAction) -> Unit) {
        endSessionListener = listener
    }

    internal fun setOnResumeSessionListener(listener: (NovaHostSessionAction) -> Unit) {
        resumeSessionListener = listener
    }

    internal fun isCurrent(action: NovaHostSessionAction): Boolean =
        itemList.firstOrNull { it.details.uuid == action.hostUuid }?.let {
            action.matches(it.details, nowPlaying[action.hostUuid])
        } == true

    override fun onBindViewHolder(holder: GenericGridAdapter.ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val computer = getItem(position)
        val action = nowPlaying[computer.details.uuid]?.let { NovaHostSessionAction(computer.details, it) }
            ?.takeIf { isCurrent(it) } ?: return
        holder.itemView.setOnClickListener {
            if (isCurrent(action)) resumeSessionListener?.invoke(action)
        }
    }

    /** Draws a game's art into the pill: the view, the host's UUID, and the game. */
    fun setNowPlayingArtwork(loader: (ImageView, String, PolarisGame) -> Unit) {
        nowPlayingArtwork = loader
    }

    /** Cached references for PcView-specific views (status dot + text). */
    private class PcViewHolder {
        var statusDot: View? = null
        var statusText: TextView? = null
        var statusHint: TextView? = null
        var primaryAction: TextView? = null
        var serverActions: View? = null
        var well: View? = null
        var dotRing: View? = null
        var badges: View? = null
        var badgeKind: TextView? = null
        var badgeSpaces: TextView? = null
        var badgeConnection: TextView? = null
        var nowPlayingRow: View? = null
        var nowPlaying: View? = null
        var nowPlayingArt: ImageView? = null
        var nowPlayingText: TextView? = null
        var endSession: View? = null
        var nowPlayingArtGame: Pair<String, PolarisGame>? = null
        var body: LinearLayout? = null
        var identity: View? = null
        var actions: View? = null
        var watchesWidth = false
        lateinit var metrics: NovaHostsViewMetrics
        var controlSize: NovaControlSize? = null
        /** Null until the card has been arranged once, so the first arrangement always applies. */
        var stacked: Boolean? = null
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
        holder.well = parentView.findViewById(R.id.grid_image_layout)
        holder.dotRing = parentView.findViewById(R.id.status_dot_ring)
        holder.badges = parentView.findViewById(R.id.host_badges)
        holder.badgeKind = parentView.findViewById(R.id.host_badge_kind)
        holder.badgeSpaces = parentView.findViewById(R.id.host_badge_spaces)
        holder.badgeConnection = parentView.findViewById(R.id.host_badge_connection)
        holder.nowPlayingRow = parentView.findViewById(R.id.host_now_playing_row)
        holder.nowPlaying = parentView.findViewById(R.id.host_now_playing)
        holder.nowPlayingArt = parentView.findViewById(R.id.host_now_playing_art)
        holder.nowPlayingText = parentView.findViewById(R.id.host_now_playing_text)
        holder.endSession = parentView.findViewById(R.id.host_end_session_button)
        holder.body = parentView.findViewById(R.id.server_card_body)
        holder.identity = parentView.findViewById(R.id.server_card_identity)
        holder.actions = parentView.findViewById(R.id.server_card_actions)
        holder.metrics = NovaHostsViewMetrics(context).apply { capture(parentView) }
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
        val controlSize = NovaControlSizePreferences.read(PreferenceManager.getDefaultSharedPreferences(context))
        if (pcHolder.controlSize != controlSize) {
            pcHolder.metrics.apply(controlSize)
            pcHolder.controlSize = controlSize
            pcHolder.stacked = null
        }
        val online = obj.details.state == ComputerDetails.State.ONLINE
        applyCardTheme(parentView, imgView, prgView!!, txtView, pcHolder, online)
        fitCardToWidth(parentView, pcHolder)
        showHostBadges(
            pcHolder,
            novaHostBadges(
                online = online,
                paired = obj.details.pairState == PairingManager.PairState.PAIRED && obj.details.serverCert != null,
                library = obj.details.libraryState,
                spacesAvailable = obj.details.spacesAvailable,
                connection = hostConnections[obj.details.uuid],
            ),
        )
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
        statusHint?.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        val primaryAction = pcHolder.primaryAction
        setPrimaryActionReady(primaryAction, false)

        if (obj.details.state == ComputerDetails.State.ONLINE) {
            imgView.alpha = 1.0f
            statusDot?.setBackgroundResource(R.drawable.nova_status_online)
            if (statusText != null) {
                if (obj.details.pairState == PairingManager.PairState.PAIRED && obj.details.serverCert == null) {
                    statusDot?.setBackgroundResource(R.drawable.nova_status_connecting)
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
                    statusDot?.setBackgroundResource(R.drawable.nova_status_connecting)
                    statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_warning))
                    primaryAction?.setText(R.string.pcview_card_action_pair)
                    setPrimaryActionReady(primaryAction, true)
                    setStatusHint(statusHint, R.string.pcview_card_hint_pair)
                } else if (obj.details.runningGameId != 0) {
                    // The pill says where a press leads, and someone else's game does not lead
                    // away from this device's library.
                    val surface = novaHostPlaySurface(
                        runningGame = true,
                        ownedByThisDevice = obj.details.currentGameOwnedByClient,
                        library = obj.details.libraryState,
                        watchable = obj.details.currentGameWatchable,
                    )
                    if (surface != NovaHostPlaySurface.RESUME && surface != NovaHostPlaySurface.WATCH) {
                        // Someone else's game, and this device's own way in is not through it.
                        val inUse = novaHostInUse(obj.details.currentGameOwnerDeviceName, obj.details.currentGameWatchable)
                        statusText.text = if (inUse.owner != null) {
                            context.getString(inUse.statusRes, inUse.owner)
                        } else {
                            context.getString(inUse.statusRes)
                        }
                        statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                        primaryAction?.setText(novaHostOwnWayInLabel(surface))
                        setPrimaryActionReady(primaryAction, surface != NovaHostPlaySurface.CHECK_LIBRARY)
                        setStatusHint(statusHint, inUse.cardHintRes)
                    } else {
                        statusText.setText(R.string.pcview_card_status_streaming)
                        statusText.setTextColor(ContextCompat.getColor(context, R.color.nova_success))
                        primaryAction?.setText(
                            if (surface == NovaHostPlaySurface.WATCH) {
                                R.string.applist_menu_watch
                            } else {
                                R.string.pcview_card_action_resume
                            }
                        )
                        setPrimaryActionReady(primaryAction, true)
                        setStatusHint(statusHint, R.string.pcview_card_hint_streaming)
                    }
                } else if (obj.details.libraryState == ComputerDetails.LibraryState.AVAILABLE) {
                    statusText.text = novaBreakAtDots(context.getString(
                        R.string.pcview_card_status_library_ready_format,
                        formatAddressSuffix(obj.details.activeAddress?.address)
                    ))
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_open_library)
                    setPrimaryActionReady(primaryAction, true)
                    if (obj.details.spacesAvailable) {
                        // The planet is on the Spaces badge now; the hint says what to do about it.
                        setStatusHint(statusHint, R.string.pcview_card_hint_spaces)
                    } else {
                        setStatusHint(statusHint, R.string.pcview_card_hint_open_library)
                    }
                } else if (obj.details.libraryState == ComputerDetails.LibraryState.UNKNOWN) {
                    statusText.setText(R.string.pcview_card_status_checking_library)
                    statusText.setTextColor(NovaThemeManager.getTextMutedColor(context))
                    primaryAction?.setText(R.string.pcview_card_action_checking_library)
                    setStatusHint(statusHint, R.string.pcview_card_hint_checking_library)
                } else {
                    statusText.text = novaBreakAtDots(context.getString(
                        R.string.pcview_card_status_compatibility_format,
                        formatAddressSuffix(obj.details.activeAddress?.address)
                    ))
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
            if (obj.details.wakeMacAddress != null) {
                primaryAction?.setText(R.string.pcview_card_action_wake)
                setPrimaryActionReady(primaryAction, true)
                setStatusHint(statusHint, R.string.pcview_card_hint_wake)
            } else {
                // Nothing to wake it with, so no action at all: it said Refreshing forever, a
                // disabled button that read as one more thing to press.
                primaryAction?.visibility = View.GONE
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
            // Filled while the card holds focus: that is what A on the card presses.
            it.isSelected = it.isActivated && parentView.isFocused
        }
        showNowPlaying(parentView, pcHolder, obj, primaryAction)

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
        val holder = getPcHolder(parentView)
        val primaryAction = holder.primaryAction
        primaryAction?.isSelected = hasFocus && primaryAction?.isActivated == true
        holder.nowPlaying?.isSelected = hasFocus
    }

    /**
     * The pill takes the primary's place: it says Resume, and a press on the card is what resumes,
     * so a second Resume beside it would be one more thing to choose between.
     */
    private fun showNowPlaying(
        parentView: View,
        holder: PcViewHolder,
        obj: PcViewModel.ComputerObject,
        primaryAction: TextView?,
    ) {
        val pill = holder.nowPlaying ?: return
        val details = obj.details
        val action = nowPlaying[details.uuid]?.let { NovaHostSessionAction(details, it) }?.takeIf { isCurrent(it) }
        holder.nowPlayingRow?.visibility = if (action != null) View.VISIBLE else View.GONE
        if (action == null) {
            holder.nowPlayingArt?.let { PolarisApiClient.releaseArtworkView(it) }
            holder.nowPlayingArtGame = null
            holder.endSession?.setOnClickListener(null)
            return
        }
        val playing = action.playing
        pill.background = NovaViewBridge.primaryButton(context)
        pill.isSelected = parentView.isFocused
        // Every host poll rebinds the card and gives the pill a new background. A view hands a new
        // background its state before it becomes the drawable's callback, so on a card that already
        // holds focus the fade to the accent began with nobody to run it and stopped at its first
        // frame: the accent at nothing over the tile, under a label already in its on-accent colour,
        // until focus left (#469). A new background shows the state it is in at once.
        pill.background.jumpToCurrentState()
        primaryAction?.visibility = View.GONE
        holder.nowPlayingText?.let {
            it.text = novaNowPlayingWords(context, playing)
            it.contentDescription = novaNowPlayingSpoken(context, playing)
            it.setTextColor(NovaViewBridge.primaryButtonText(context))
        }
        holder.endSession?.let {
            it.contentDescription = context.getString(
                R.string.nova_host_now_playing_end_description,
                playing.gameName.ifBlank { context.getString(R.string.applist_menu_watch_active_name) },
            )
            it.setOnClickListener { if (isCurrent(action)) endSessionListener?.invoke(action) }
        }
        val art = holder.nowPlayingArt ?: return
        val game = playing.game
        art.visibility = if (game != null) View.VISIBLE else View.GONE
        val artKey = game?.let { details.uuid to it }
        if (artKey != holder.nowPlayingArtGame) {
            holder.nowPlayingArtGame = artKey
            PolarisApiClient.releaseArtworkView(art)
            if (game != null) nowPlayingArtwork?.invoke(art, details.uuid, game)
        }
    }

    /**
     * Arranges the card for the width it has, now and whenever that changes.
     *
     * The width is usually known when the card is bound, from the list it sits in, so the
     * first frame is already right. The listener is what keeps it right when the window is
     * resized or the rail folds away and the list gets wider.
     */
    private fun fitCardToWidth(parentView: View, holder: PcViewHolder) {
        val body = holder.body ?: return
        if (!holder.watchesWidth) {
            holder.watchesWidth = true
            body.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    // This runs inside a layout pass, and a change of layout has to wait for the next.
                    view.post { applyCardArrangement(holder, right - left) }
                }
            }
        }
        val known = body.width.takeIf { it > 0 }
            ?: (parentView.parent as? View)?.let { it.width - it.paddingLeft - it.paddingRight }?.takeIf { it > 0 }
            ?: return
        applyCardArrangement(holder, known)
    }

    private fun applyCardArrangement(holder: PcViewHolder, widthPx: Int) {
        val body = holder.body ?: return
        val identity = holder.identity ?: return
        val actions = holder.actions ?: return
        val resources = context.resources
        val scale = holder.controlSize?.layoutScale ?: 1f
        val spacing = resources.getDimensionPixelSize(R.dimen.nova_spacing_md)
        val gap = resources.getDimensionPixelSize(R.dimen.nova_spacing_sm)
        val actualExtraPadding = holder.metrics.horizontalPaddingDelta(body, holder.primaryAction,
            holder.serverActions?.findViewById<View>(R.id.server_actions_label)) +
            (gap * scale).roundToInt() - gap + (spacing * scale).roundToInt() - spacing
        val stacked = novaHostCardStacks(
            cardWidthDp = (widthPx - actualExtraPadding) / resources.displayMetrics.density,
            fontScale = resources.configuration.fontScale,
        )
        if (holder.stacked == stacked) return
        holder.stacked = stacked

        body.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        identity.layoutParams = (identity.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            weight = if (stacked) 0f else 1f
        }
        actions.layoutParams = (actions.layoutParams as LinearLayout.LayoutParams).apply {
            // Under the text rather than under the icon: the actions belong to the lines above them.
            marginStart = if (stacked) {
                resources.getDimensionPixelSize(R.dimen.nova_icon_server_container) +
                    (resources.getDimensionPixelSize(R.dimen.nova_spacing_lg) * scale).roundToInt()
            } else {
                (resources.getDimensionPixelSize(R.dimen.nova_spacing_md) * scale).roundToInt()
            }
            topMargin = if (stacked) (resources.getDimensionPixelSize(R.dimen.nova_spacing_sm) * scale).roundToInt() else 0
        }
    }

    private fun setPrimaryActionReady(primaryAction: TextView?, ready: Boolean) {
        primaryAction ?: return
        primaryAction.visibility = View.VISIBLE
        primaryAction.isActivated = ready
        // Its selection is left as it is: the bind sets it once, from the card's focus, after this.
        // It was dropped here and set again on every host poll, and each time the view announced
        // itself as newly selected, so TalkBack said "Open Library" every 1.6 s for as long as the
        // card held focus (#470).
        // What a press on the card does, drawn as every primary is: a tile with its label in the
        // accent at rest, and the accent fill while the card holds focus. It was filled at rest,
        // as loud as focus, so the ring never said which card A would act on. Until it is ready
        // it is the same quiet tile as any other chip.
        if (ready) {
            primaryAction.background = NovaViewBridge.primaryButton(context)
            // A view hands a new background its state before it becomes the drawable's callback, so
            // on a button already selected the fade to the accent would stop at its first frame,
            // the accent at nothing over the tile (as the now-playing pill did, #469). It shows the
            // state it is in at once.
            primaryAction.background.jumpToCurrentState()
            primaryAction.setTextColor(NovaViewBridge.primaryButtonText(context))
        } else {
            primaryAction.setBackgroundResource(R.drawable.nova_chip_default)
            primaryAction.setTextColor(NovaThemeManager.getTextMutedColor(context))
        }
    }

    private fun formatAddressSuffix(address: String?): String =
        address?.takeIf { it.isNotBlank() } ?: context.getString(R.string.pcview_card_status_local_network)

    private fun setStatusHint(statusHint: TextView?, textRes: Int) {
        statusHint ?: return
        statusHint.setText(textRes)
        // Cards are recycled: the planet belongs to the Spaces hint alone, never beside "Offline".
        statusHint.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        statusHint.visibility = View.VISIBLE
    }

    /** What is true of the host and worth a glance: the kind of host it is, and whether it has Spaces. */
    private fun showHostBadges(holder: PcViewHolder, badges: NovaHostBadges) {
        val accent = NovaThemeManager.getAccentColor(context)
        val density = context.resources.displayMetrics.density
        fun dress(badge: TextView, tinted: Boolean) {
            val ink = if (tinted) accent else NovaThemeManager.getTextSecondaryColor(context)
            badge.setTextColor(ink)
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.resources.getDimension(R.dimen.nova_radius_row)
                setColor(ColorUtils.setAlphaComponent(ink, 0x1F))
                setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(ink, 0x52))
            }
        }
        holder.badgeKind?.let { badge ->
            badge.visibility = if (badges.polaris) View.VISIBLE else View.GONE
            if (badges.polaris) {
                badge.setText(R.string.pcview_card_badge_polaris)
                dress(badge, tinted = false)
            }
        }
        holder.badgeSpaces?.let { badge ->
            badge.visibility = if (badges.spaces) View.VISIBLE else View.GONE
            if (badges.spaces) {
                dress(badge, tinted = true)
                // The planet is drawn at the size of the badge's own type. At its intrinsic 24dp it
                // set the badge's height, and Spaces stood half again as tall as Polaris beside it.
                val planetSize = (badge.textSize * NOVA_HOST_BADGE_ICON_EM).toInt()
                val planet = ContextCompat.getDrawable(context, R.drawable.ic_spaces_planet)?.mutate()
                planet?.setBounds(0, 0, planetSize, planetSize)
                badge.setCompoundDrawablesRelative(planet, null, null, null)
                badge.compoundDrawablePadding = (4 * density).toInt()
                TextViewCompat.setCompoundDrawableTintList(badge, ColorStateList.valueOf(accent))
                // No leading margin when it stands alone, so the row still starts on the text's edge.
                (badge.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.marginStart = if (badges.polaris) (6 * density).toInt() else 0
                    badge.layoutParams = params
                }
            }
        }
        holder.badges?.visibility = if (badges.polaris || badges.spaces) View.VISIBLE else View.GONE
        holder.badgeConnection?.let { badge ->
            val connection = badges.connection
            badge.visibility = if (connection != null) View.VISIBLE else View.GONE
            if (connection != null) {
                badge.text = connection
                badge.contentDescription = context.getString(R.string.nova_host_connection_chip_description, connection)
                dress(badge, tinted = false)
                val markSize = (badge.textSize * NOVA_HOST_BADGE_ICON_EM).toInt()
                val mark = ContextCompat.getDrawable(context, R.drawable.ic_nova_host_connection)?.mutate()
                mark?.setBounds(0, 0, markSize, markSize)
                badge.setCompoundDrawablesRelative(mark, null, null, null)
                badge.compoundDrawablePadding = (4 * density).toInt()
                TextViewCompat.setCompoundDrawableTintList(badge, ColorStateList.valueOf(NovaThemeManager.getTextSecondaryColor(context)))
            }
        }
    }

    // FrameLayout.setForeground exists on API 21; the emitted call targets FrameLayout. Lint
    // resolves the newer inherited SDK declaration to View.setForeground (API 23).
    @android.annotation.SuppressLint("NewApi")
    private fun applyCardTheme(
        parentView: View,
        imgView: ImageView,
        prgView: ProgressBar,
        txtView: TextView,
        pcHolder: PcViewHolder,
        online: Boolean,
    ) {
        val card = if (parentView is ViewGroup && parentView.childCount > 0) {
            parentView.getChildAt(0)
        } else {
            parentView
        }

        // A host that answers carries the accent in from its leading edge, and its mark stands
        // in an accent well; one that does not is the plain card it always was. The card was
        // one flat grey whatever the host was doing, which is most of why it read as bland.
        val density = context.resources.displayMetrics.density
        val cardColor = NovaThemeManager.getCardBackgroundColor(context)
        val accent = NovaThemeManager.getAccentColor(context)
        val leading = if (online) ColorUtils.blendARGB(cardColor, accent, NOVA_HOST_CARD_ACCENT_WASH) else cardColor
        val fromLeadingEdge = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            GradientDrawable.Orientation.RIGHT_LEFT
        } else {
            GradientDrawable.Orientation.LEFT_RIGHT
        }
        val corner = context.resources.getDimension(R.dimen.nova_radius_row)
        val background = GradientDrawable(fromLeadingEdge, intArrayOf(leading, cardColor, cardColor))
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = corner
        // The hairline is the divider on every card: an accent outline on the online one read as
        // focus beside the real ring (R9). Online still shows in the wash and the well.
        background.setStroke(density.toInt().coerceAtLeast(1), NovaThemeManager.getDividerColor(context))
        card.background = background
        // The one focus look (spec section 2): the 3dp ring drawn inside the card's corner, in the
        // Compose components' colours. The row holds focus and pads the card; a FrameLayout draws
        // its foreground inside its padding, so the ring lands on the card, not around it.
        (parentView as? FrameLayout)?.foreground = NovaViewBridge.focusRing(context)

        pcHolder.well?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(
                if (online) {
                    ColorUtils.blendARGB(cardColor, accent, 0.26f)
                } else {
                    ColorUtils.blendARGB(cardColor, NovaThemeManager.getTextMutedColor(context), 0.16f)
                },
            )
        }
        // The ring takes the colour of the card under it, so the lamp reads as standing off the well.
        pcHolder.dotRing?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(leading, 0xFF))
        }

        txtView.setTextColor(NovaThemeManager.getTextPrimaryColor(context))
        prgView.indeterminateTintList = ColorStateList.valueOf(NovaThemeManager.getAccentColor(context))
        imgView.imageTintList = ColorStateList.valueOf(NovaThemeManager.getTextSecondaryColor(context))

        pcHolder.statusText?.setTextColor(NovaThemeManager.getTextMutedColor(context))
        pcHolder.statusHint?.setTextColor(NovaThemeManager.getTextMutedColor(context))
    }

    companion object {
        // Not const: a resource id is not a compile-time constant, and Kotlin 2.4 folds a
        // const initialised from one to its placeholder, so View.setTag rejects the key.
        private val TAG_PC_HOLDER = R.id.status_dot
        private val SERVER_ROW_REFRESH_PAYLOAD = Any()

        private fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int = R.layout.pc_grid_item
    }
}

/**
 * Whether a host card puts its actions under its text rather than beside it.
 *
 * Beside it, "Open Library" and "Manage" take about 215dp and the icon 64dp, and what is left is
 * the column the host's name, its status and a sentence of advice have to fit in. On a 16:9
 * handheld that is about 310dp. At 4:3 it was 215dp and the advice read "Spaces availab…"; on a
 * phone held upright it was under 120dp. Below this width the text takes the whole card and the
 * actions sit under it. Larger type needs the room sooner, so the width scales with it.
 */
internal fun novaHostCardStacks(cardWidthDp: Float, fontScale: Float): Boolean =
    cardWidthDp < NOVA_HOST_CARD_SIDE_BY_SIDE_MIN_DP * fontScale.coerceAtLeast(1f)

internal const val NOVA_HOST_CARD_SIDE_BY_SIDE_MIN_DP = 500f

/** A badge's icon, in ems of the badge's type, so it grows with the text and never past it. */
private const val NOVA_HOST_BADGE_ICON_EM = 1.2f

/** How far the accent washes into an online host's card from its leading edge. */
private const val NOVA_HOST_CARD_ACCENT_WASH = 0.20f

internal data class NovaHostBadges(val polaris: Boolean, val spaces: Boolean, val connection: String? = null)

/**
 * The facts a host's card wears as badges. Only what is known and true right now: a host that
 * is not answering, or not paired, or whose library has not been asked about yet has nothing to
 * claim, and a badge that guessed would be one more thing on the card to distrust. A host in
 * compatibility mode wears none, because its status line already says exactly that.
 */
internal fun novaHostBadges(
    online: Boolean,
    paired: Boolean,
    library: ComputerDetails.LibraryState?,
    spacesAvailable: Boolean,
    /** What the host itself confirmed about this connection, in the chip's words. */
    connection: String? = null,
): NovaHostBadges {
    if (!online || !paired) {
        return NovaHostBadges(polaris = false, spaces = false)
    }
    val polaris = library == ComputerDetails.LibraryState.AVAILABLE
    return NovaHostBadges(polaris = polaris, spaces = polaris && spacesAvailable, connection = connection)
}

private fun stableServerId(uuid: String): Long {
    var hash = -3750763034362895579L
    for (character in uuid) {
        hash = hash xor character.code.toLong()
        hash *= 1099511628211L
    }
    return hash
}
