package com.papi.nova.grid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import com.papi.nova.AppView
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.grid.assets.CachedAppAssetLoader
import com.papi.nova.grid.assets.DiskAssetLoader
import com.papi.nova.grid.assets.MemoryAssetLoader
import com.papi.nova.grid.assets.NetworkAssetLoader
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.preferences.PreferenceConfiguration
import java.util.Locale

@Suppress("UNCHECKED_CAST")
class AppGridAdapter(
    context: Context,
    prefs: PreferenceConfiguration,
    private val computer: ComputerDetails,
    private val uniqueId: String,
    private val showHiddenApps: Boolean
) : GenericGridAdapter<AppView.AppObject>(context, getLayoutIdForPreferences(prefs)) {
    private lateinit var loader: CachedAppAssetLoader
    private var showHdrBadges = false
    private val hiddenAppIds: MutableSet<Int> = HashSet()
    private val pinnedAppIds: MutableSet<Int> = HashSet()
    private val allApps = ArrayList<AppView.AppObject>()
    private var searchFilter = ""

    init {
        updateLayoutWithPreferences(context, prefs)
    }

    fun filterByName(query: String) {
        searchFilter = query.lowercase(Locale.getDefault()).trim()
        rebuildFilteredList()
    }

    private fun rebuildFilteredList() {
        val newList = ArrayList<AppView.AppObject>()
        for (app in allApps) {
            app.isHidden = hiddenAppIds.contains(app.app.appId)
            if (app.isHidden && !showHiddenApps) {
                continue
            }
            val searchableText = (app.app.appName + " " + app.app.metadataLabel).lowercase(Locale.getDefault())
            if (searchFilter.isNotEmpty() && !searchableText.contains(searchFilter)) {
                continue
            }
            newList.add(app)
        }
        sortList(newList)
        val result = DiffUtil.calculateDiff(AppDiffCallback(itemList, newList))
        itemList.clear()
        itemList.addAll(newList)
        result.dispatchUpdatesTo(this)
    }

    fun getTotalAppCount(): Int = allApps.size

    fun updateHiddenApps(newHiddenAppIds: Set<Int>, hideImmediately: Boolean) {
        hiddenAppIds.clear()
        hiddenAppIds.addAll(newHiddenAppIds)

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set.
            val newList = ArrayList<AppView.AppObject>()
            for (app in allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.appId)
                if (!app.isHidden || showHiddenApps) {
                    newList.add(app)
                }
            }
            val result = DiffUtil.calculateDiff(AppDiffCallback(itemList, newList))
            itemList.clear()
            itemList.addAll(newList)
            result.dispatchUpdatesTo(this)
        } else {
            // Just update the isHidden state to show the correct UI indication.
            for (app in allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.appId)
            }
            notifyDataSetChanged()
        }
    }

    fun updateLayoutWithPreferences(context: Context, prefs: PreferenceConfiguration) {
        val dpi = context.resources.displayMetrics.densityDpi
        showHdrBadges = prefs.enableHdr

        val dp = if (prefs.smallIconMode) {
            SMALL_WIDTH_DP
        } else {
            LARGE_WIDTH_DP
        }

        var scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0))
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time.
            scalingDivisor = 1.0
        }
        LimeLog.info("Art scaling divisor: $scalingDivisor")

        if (::loader.isInitialized) {
            // Cancel operations on the old loader.
            cancelQueuedOperations()
        }

        loader = CachedAppAssetLoader(
            computer,
            scalingDivisor,
            NetworkAssetLoader(context, uniqueId),
            MemoryAssetLoader(),
            DiskAssetLoader(context),
            createPlaceholderBitmap(context)
        )

        // This will trigger the view to reload with the new layout.
        setLayoutId(getLayoutIdForPreferences(prefs))
    }

    fun cancelQueuedOperations() {
        loader.cancelForegroundLoads()
        loader.cancelBackgroundLoads()
        loader.freeCacheMemory()
    }

    fun updatePinnedApps(newPinnedIds: Set<Int>) {
        pinnedAppIds.clear()
        pinnedAppIds.addAll(newPinnedIds)
        for (app in allApps) {
            app.isPinned = pinnedAppIds.contains(app.app.appId)
        }
        rebuildFilteredList()
    }

    fun isAppPinned(appId: Int): Boolean = pinnedAppIds.contains(appId)

    fun addApp(app: AppView.AppObject) {
        // Update hidden and pinned state.
        app.isHidden = hiddenAppIds.contains(app.app.appId)
        app.isPinned = pinnedAppIds.contains(app.app.appId)

        // Always add the app to the all apps list.
        allApps.add(app)
        sortList(allApps)

        // Add the app to the adapter data if it's not hidden.
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache.
            loader.queueCacheLoad(app.app)

            // Add the app to our sorted list.
            itemList.add(app)
            sortList(itemList)
        }
    }

    fun removeApp(app: AppView.AppObject) {
        itemList.remove(app)
        allApps.remove(app)
    }

    override fun clear() {
        super.clear()
        allApps.clear()
    }

    override fun populateView(
        parentView: View,
        imgView: ImageView,
        gridMask: RelativeLayout,
        prgView: ProgressBar,
        txtView: TextView,
        overlayView: ImageView,
        obj: AppView.AppObject
    ) {
        // Let the cached asset loader handle it.
        loader.populateImageView(obj.app, imgView, txtView)

        val hdrBadge = parentView.findViewById<TextView>(R.id.hdr_badge)
        val sourceBadge = parentView.findViewById<TextView>(R.id.grid_source_badge)
        val metaView = parentView.findViewById<TextView>(R.id.grid_meta)
        val runningBadge = parentView.findViewById<View>(R.id.running_badge)
        val runningBorder = parentView.findViewById<View>(R.id.running_border)

        if (hdrBadge != null) {
            hdrBadge.visibility = if (showHdrBadges && obj.app.isHdrSupported) View.VISIBLE else View.GONE
        }

        bindSourceBadge(sourceBadge, obj.app.sourceLabel, obj.app.source)

        if (metaView != null) {
            val metadata = obj.app.metadataLabel
            metaView.text = metadata
            metaView.visibility = if (metadata.isEmpty()) View.GONE else View.VISIBLE
        }

        if (obj.isRunning) {
            overlayView.setImageResource(R.drawable.ic_play)
            overlayView.visibility = View.VISIBLE
            gridMask.setBackgroundColor(0x44000000)
            runningBadge?.visibility = View.VISIBLE
            runningBorder?.visibility = View.VISIBLE
        } else {
            overlayView.visibility = View.GONE
            gridMask.setBackgroundColor(0x00000000)
            runningBadge?.visibility = View.GONE
            runningBorder?.visibility = View.GONE
        }

        parentView.alpha = if (obj.isHidden) 0.40f else 1.0f
    }

    fun populateFeaturedArt(obj: AppView.AppObject, imageView: ImageView) {
        loader.populateImageView(obj.app, imageView)
    }

    private fun bindSourceBadge(badge: TextView?, label: String?, source: String?) {
        if (badge == null) {
            return
        }
        if (label.isNullOrEmpty()) {
            badge.visibility = View.GONE
            return
        }

        badge.text = label
        badge.visibility = View.VISIBLE

        val bgColor: Int
        val textColor: Int
        when (source) {
            "steam" -> {
                bgColor = 0x1A3B82F6
                textColor = 0xFF60A5FA.toInt()
            }
            "lutris" -> {
                bgColor = 0x1AF97316
                textColor = 0xFFFB923C.toInt()
            }
            "heroic" -> {
                bgColor = 0x1AA855F7
                textColor = 0xFFC084FC.toInt()
            }
            else -> {
                bgColor = 0x1A6B7280
                textColor = 0xFF9CA3AF.toInt()
            }
        }

        val bg = GradientDrawable()
        bg.cornerRadius = context.resources.displayMetrics.density * 8f
        bg.setColor(bgColor)
        badge.setTextColor(textColor)
        badge.background = bg
    }

    private class AppDiffCallback(
        private val oldList: List<AppView.AppObject>,
        private val newList: List<AppView.AppObject>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size

        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            oldList[oldPos].app.appId == newList[newPos].app.appId

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            val a = oldList[oldPos]
            val b = newList[newPos]
            return a.app.appId == b.app.appId &&
                a.isRunning == b.isRunning &&
                a.isHidden == b.isHidden &&
                a.isPinned == b.isPinned &&
                a.app.appName == b.app.appName &&
                a.app.metadataKey == b.app.metadataKey
        }
    }

    companion object {
        private const val ART_WIDTH_PX = 300
        private const val SMALL_WIDTH_DP = 110
        private const val LARGE_WIDTH_DP = 170

        private fun getLayoutIdForPreferences(prefs: PreferenceConfiguration): Int =
            if (prefs.smallIconMode) R.layout.app_grid_item_small else R.layout.app_grid_item

        private fun createPlaceholderBitmap(context: Context): Bitmap {
            // Vector drawables can't be decoded by BitmapFactory, so render to bitmap manually.
            val drawable = ContextCompat.getDrawable(context, R.drawable.nova_app_placeholder)
                ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 200
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 266
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            return bitmap
        }

        private fun sortList(list: MutableList<AppView.AppObject>) {
            list.sortWith { lhs, rhs ->
                if (lhs.isPinned != rhs.isPinned) {
                    return@sortWith if (lhs.isPinned) -1 else 1
                }
                val leftIndex = lhs.app.appIndex
                val rightIndex = rhs.app.appIndex
                if (leftIndex == rightIndex) {
                    lhs.app.appName.lowercase(Locale.getDefault())
                        .compareTo(rhs.app.appName.lowercase(Locale.getDefault()))
                } else {
                    leftIndex - rightIndex
                }
            }
        }
    }
}
