package com.papi.nova.grid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.papi.nova.R
import com.papi.nova.utils.UiHelper

abstract class GenericGridAdapter<T>(
    @JvmField protected val context: Context,
    private var layoutId: Int
) : RecyclerView.Adapter<GenericGridAdapter.ViewHolder>() {
    @JvmField val itemList = ArrayList<T>()
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var clickListener: OnItemClickListener<T>? = null

    fun interface OnItemClickListener<T> {
        fun onItemClick(item: T)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        @JvmField val imgView: ImageView = itemView.findViewById(R.id.grid_image)
        @JvmField val gridMask: RelativeLayout? = itemView.findViewById(R.id.grid_mask)
        @JvmField val overlayView: ImageView = itemView.findViewById(R.id.grid_overlay)
        @JvmField val txtView: TextView = itemView.findViewById(R.id.grid_text)
        @JvmField val prgView: ProgressBar? = itemView.findViewById(R.id.grid_spinner)
    }

    fun setOnItemClickListener(listener: OnItemClickListener<T>?) {
        clickListener = listener
    }

    fun setItems(items: List<T>?) {
        itemList.clear()
        if (items != null) {
            itemList.addAll(items)
        }
        notifyDataSetChanged()
    }

    fun setLayoutId(layoutId: Int) {
        if (layoutId != this.layoutId) {
            this.layoutId = layoutId

            // Force the view to be redrawn with the new layout.
            notifyDataSetChanged()
        }
    }

    open fun clear() {
        itemList.clear()
    }

    override fun getItemCount(): Int = itemList.size

    fun getItem(i: Int): T = itemList[i]

    override fun getItemId(i: Int): Long = i.toLong()

    abstract fun populateView(
        parentView: View,
        imgView: ImageView,
        gridMask: RelativeLayout?,
        prgView: ProgressBar?,
        txtView: TextView,
        overlayView: ImageView,
        obj: T
    )

    open fun onItemFocusChanged(parentView: View, hasFocus: Boolean) = Unit

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = inflater.inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = itemList[position]
        populateView(
            holder.itemView,
            holder.imgView,
            holder.gridMask,
            holder.prgView,
            holder.txtView,
            holder.overlayView,
            item
        )
        UiHelper.applyTvFocusStyle(context, holder.itemView)

        val focusRing = holder.itemView.findViewById<View?>(R.id.nova_focus_ring)
        focusRing?.visibility = if (holder.itemView.hasFocus()) View.VISIBLE else View.GONE
        applyFocusMotionState(holder.itemView, holder.itemView.hasFocus(), animate = false)
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            focusRing?.visibility = if (hasFocus) View.VISIBLE else View.GONE
            applyFocusMotionState(holder.itemView, hasFocus, animate = true)
            onItemFocusChanged(holder.itemView, hasFocus)
        }

        holder.itemView.setOnClickListener {
            clickListener?.onItemClick(item)
        }
    }

    private fun applyFocusMotionState(view: View, hasFocus: Boolean, animate: Boolean) {
        val scale = if (hasFocus) FOCUSED_SCALE else 1f
        val elevation = if (hasFocus) focusedTranslationZPx(view) else 0f
        view.animate().cancel()
        if (!animate) {
            view.scaleX = scale
            view.scaleY = scale
            view.translationZ = elevation
            return
        }

        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation)
            .setDuration(FOCUS_MOTION_DURATION_MS)
            .setInterpolator(FOCUS_INTERPOLATOR)
            .withLayer()
            .start()
    }

    private fun focusedTranslationZPx(view: View): Float =
        FOCUSED_TRANSLATION_Z_DP * view.resources.displayMetrics.density

    private companion object {
        private const val FOCUS_MOTION_DURATION_MS = 140L
        private const val FOCUSED_SCALE = 1.025f
        private const val FOCUSED_TRANSLATION_Z_DP = 8f
        private val FOCUS_INTERPOLATOR = DecelerateInterpolator()
    }
}
