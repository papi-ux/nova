package com.papi.nova.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Adaptive blur for content underneath Nova menus and dialogs.
 *
 * This intentionally uses View RenderEffect instead of optional cross-window blur,
 * which OEMs can disable even on Android 12+. Menu/dialog content stays in its own
 * sharp window or overlay while only the owning Activity background is softened.
 *
 * Blur is lease-based: overlapping Nova overlays can share a target without one
 * overlay clearing another overlay's effect when it closes.
 */
object NovaMenuBlur {
    class BlurLease internal constructor(
        private var target: View?,
        private var owner: Any?
    ) {
        fun release() {
            val releaseTarget: View
            val releaseOwner: Any
            synchronized(this) {
                releaseTarget = target ?: return
                releaseOwner = owner ?: return
                NovaMenuBlur.requireMainThread()
                target = null
                owner = null
            }
            NovaMenuBlur.release(releaseTarget, releaseOwner)
        }
    }

    private data class ViewBlurState(
        val ownerRadiiDp: IdentityHashMap<Any, Float> = IdentityHashMap(),
        var novaEffectApplied: Boolean = false
    )

    private data class DialogBinding(
        val listener: View.OnAttachStateChangeListener,
        var lease: BlurLease? = null
    )

    private val viewBlurStates = WeakHashMap<View, ViewBlurState>()
    private val dialogBindings = WeakHashMap<View, DialogBinding>()

    internal fun acquire(view: View, opacityPercent: Int): BlurLease {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return BlurLease(null, null)
        }
        requireMainThread()
        val owner = Any()
        val radiusDp = NovaMenuPreferences.blurRadiusDp(opacityPercent)
        synchronized(viewBlurStates) {
            val state = viewBlurStates.getOrPut(view) { ViewBlurState() }
            state.ownerRadiiDp[owner] = radiusDp
            applyStrongestOwnedEffect(view, state)
        }
        return BlurLease(view, owner)
    }

    fun acquireActivityBackground(context: Context, opacityPercent: Int): BlurLease? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        requireMainThread()
        val activity = context.findActivity() ?: return null
        return acquire(activity.window.decorView, opacityPercent)
    }

    fun attachBehindDialog(dialog: Dialog, opacityPercent: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        requireMainThread()
        val activity = dialog.context.findActivity() ?: return
        val dialogDecor = dialog.window?.decorView ?: return
        val target = activity.window.decorView

        dialogBindings.remove(dialogDecor)?.let { existing ->
            dialogDecor.removeOnAttachStateChangeListener(existing.listener)
            existing.lease?.release()
        }

        lateinit var binding: DialogBinding
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                requireMainThread()
                if (dialogBindings[view] !== binding) {
                    view.removeOnAttachStateChangeListener(this)
                    return
                }
                binding.lease?.release()
                binding.lease = acquire(target, opacityPercent)
            }

            override fun onViewDetachedFromWindow(view: View) {
                requireMainThread()
                if (dialogBindings[view] === binding) {
                    binding.lease?.release()
                    binding.lease = null
                    dialogBindings.remove(view)
                }
                view.removeOnAttachStateChangeListener(this)
            }
        }
        binding = DialogBinding(listener = listener)
        dialogDecor.addOnAttachStateChangeListener(listener)
        dialogBindings[dialogDecor] = binding
        if (dialogDecor.isAttachedToWindow) {
            binding.lease = acquire(target, opacityPercent)
        }
    }

    fun acquireChildren(parent: ViewGroup, opacityPercent: Int): List<BlurLease> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        requireMainThread()
        return buildList {
            for (index in 0 until parent.childCount) {
                add(acquire(parent.getChildAt(index), opacityPercent))
            }
        }
    }

    fun releaseAll(leases: List<BlurLease>) {
        leases.forEach(BlurLease::release)
    }

    internal fun releaseOnUnexpectedDetach(view: View, cleanup: () -> Unit) {
        requireMainThread()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                cleanup()
            }
        })
    }

    internal fun currentRadiusDp(view: View): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return synchronized(viewBlurStates) {
            viewBlurStates[view]?.ownerRadiiDp?.values?.maxOrNull()
        }
    }

    private fun release(view: View, owner: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        synchronized(viewBlurStates) {
            val state = viewBlurStates[view] ?: return
            state.ownerRadiiDp.remove(owner)
            if (state.ownerRadiiDp.isEmpty()) {
                if (state.novaEffectApplied) {
                    view.setRenderEffect(null)
                }
                viewBlurStates.remove(view)
            } else {
                applyStrongestOwnedEffect(view, state)
            }
        }
    }

    private fun applyStrongestOwnedEffect(view: View, state: ViewBlurState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radiusDp = state.ownerRadiiDp.values.maxOrNull() ?: 0f
        if (radiusDp <= 0f) {
            if (state.novaEffectApplied) {
                view.setRenderEffect(null)
                state.novaEffectApplied = false
            }
            return
        }
        val radiusPx = radiusDp * view.resources.displayMetrics.density
        runCatching {
            view.setRenderEffect(
                RenderEffect.createBlurEffect(
                    radiusPx,
                    radiusPx,
                    Shader.TileMode.CLAMP
                )
            )
            state.novaEffectApplied = true
        }.onFailure {
            if (state.novaEffectApplied) {
                view.setRenderEffect(null)
            }
            state.novaEffectApplied = false
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "NovaMenuBlur View and dialog mutations must run on the main thread"
        }
    }

    private tailrec fun Context.findActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
