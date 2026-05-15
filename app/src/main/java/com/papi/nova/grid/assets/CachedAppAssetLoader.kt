@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.papi.nova.grid.assets

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.AsyncTask
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CachedAppAssetLoader(
    private val computer: ComputerDetails,
    private val scalingDivider: Double,
    private val networkLoader: NetworkAssetLoader,
    private val memoryLoader: MemoryAssetLoader,
    private val diskLoader: DiskAssetLoader,
    private val noAppImageBitmap: Bitmap
) {
    private val cacheExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_CACHE_LOADS,
        MAX_CONCURRENT_CACHE_LOADS,
        Long.MAX_VALUE,
        TimeUnit.DAYS,
        LinkedBlockingQueue(MAX_PENDING_CACHE_LOADS),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val foregroundExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_DISK_LOADS,
        MAX_CONCURRENT_DISK_LOADS,
        Long.MAX_VALUE,
        TimeUnit.DAYS,
        LinkedBlockingQueue(MAX_PENDING_DISK_LOADS),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val networkExecutor = ThreadPoolExecutor(
        MAX_CONCURRENT_NETWORK_LOADS,
        MAX_CONCURRENT_NETWORK_LOADS,
        Long.MAX_VALUE,
        TimeUnit.DAYS,
        LinkedBlockingQueue(MAX_PENDING_NETWORK_LOADS),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val placeholderBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    fun cancelBackgroundLoads() {
        cancelQueuedLoads(cacheExecutor)
    }

    fun cancelForegroundLoads() {
        cancelQueuedLoads(foregroundExecutor)
        cancelQueuedLoads(networkExecutor)
    }

    fun freeCacheMemory() {
        memoryLoader.clearCache()
    }

    private fun doNetworkAssetLoad(tuple: LoaderTuple, task: LoaderTask?): ScaledBitmap? {
        // Deduplicate: skip if another thread is already loading this exact image.
        if (!networkLoader.tryAcquire(tuple)) {
            return null
        }

        try {
            for (i in 0 until NETWORK_LOAD_ATTEMPTS) {
                // Check again whether we've been cancelled or the image view is gone.
                if (task != null && (task.isCancelled || task.imageViewRef.get() == null)) {
                    return null
                }

                val input = networkLoader.getBitmapStream(tuple)
                if (input != null) {
                    // Write the stream straight to disk.
                    diskLoader.populateCacheWithStream(tuple, input)

                    try {
                        input.close()
                    } catch (ignored: IOException) {
                    }

                    // If there's a task associated with this load, we should return the bitmap.
                    if (task != null) {
                        // If the cached bitmap is valid, return it. Otherwise, we'll try the load again.
                        val bitmap = diskLoader.loadBitmapFromCache(tuple, scalingDivider.toInt())
                        if (bitmap != null) {
                            return bitmap
                        }
                    } else {
                        // Otherwise it's a background load and we return nothing.
                        return null
                    }
                }

                try {
                    Thread.sleep((500 + Math.random() * 250).toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }

            return null
        } finally {
            networkLoader.release(tuple)
        }
    }

    private inner class LoaderTask(
        imageView: ImageView,
        textView: TextView?,
        private val diskOnly: Boolean,
        private val manageTextVisibility: Boolean = textView != null
    ) : AsyncTask<LoaderTuple, Void, ScaledBitmap?>() {
        val imageViewRef = WeakReference(imageView)
        private val textViewRef = WeakReference(textView)
        var tuple: LoaderTuple? = null

        override fun doInBackground(vararg params: LoaderTuple): ScaledBitmap? {
            val requestedTuple = params[0]
            tuple = requestedTuple

            // Check whether it has been cancelled or the views are gone.
            if (isCancelled || imageViewRef.get() == null || (manageTextVisibility && textViewRef.get() == null)) {
                return null
            }

            var bitmap = diskLoader.loadBitmapFromCache(requestedTuple, scalingDivider.toInt())
            if (bitmap == null) {
                bitmap = if (!diskOnly) {
                    doNetworkAssetLoad(requestedTuple, this)
                } else {
                    // Report progress to display the placeholder and spin off the network-capable task.
                    publishProgress()
                    null
                }
            }

            if (bitmap != null) {
                memoryLoader.populateCache(requestedTuple, bitmap)
            }

            return bitmap
        }

        override fun onProgressUpdate(vararg values: Void?) {
            if (isCancelled) {
                return
            }

            // If the current loader task for this view isn't us, do nothing.
            val imageView = imageViewRef.get() ?: return
            val textView = textViewRef.get()
            val currentTuple = tuple ?: return
            if (getLoaderTask(imageView) == this) {
                // Set off another loader task on the network executor. This time our AsyncDrawable
                // will use the app image placeholder bitmap, rather than an empty bitmap.
                val task = LoaderTask(imageView, textView, false, manageTextVisibility)
                val asyncDrawable = AsyncDrawable(imageView.resources, noAppImageBitmap, task)
                imageView.setImageDrawable(asyncDrawable)
                imageView.startAnimation(AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadein))
                imageView.visibility = View.VISIBLE
                if (manageTextVisibility && textView != null) {
                    textView.visibility = View.VISIBLE
                }
                task.executeOnExecutor(networkExecutor, currentTuple)
            }
        }

        override fun onPostExecute(bitmap: ScaledBitmap?) {
            if (isCancelled) {
                return
            }

            val imageView = imageViewRef.get() ?: return
            val textView = textViewRef.get()
            if (getLoaderTask(imageView) == this) {
                // Fade in the box art.
                if (bitmap != null) {
                    // Show the text if it's a placeholder.
                    if (manageTextVisibility && textView != null) {
                        textView.visibility = if (isBitmapPlaceholder(bitmap)) View.VISIBLE else View.GONE
                    }

                    if (imageView.visibility == View.VISIBLE) {
                        // Fade out the placeholder first.
                        val fadeOutAnimation = AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadeout)
                        fadeOutAnimation.setAnimationListener(object : Animation.AnimationListener {
                            override fun onAnimationStart(animation: Animation) = Unit

                            override fun onAnimationEnd(animation: Animation) {
                                // Fade in the new box art.
                                imageView.setImageBitmap(bitmap.bitmap)
                                imageView.startAnimation(AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadein))
                            }

                            override fun onAnimationRepeat(animation: Animation) = Unit
                        })
                        imageView.startAnimation(fadeOutAnimation)
                    } else {
                        // View is invisible already, so just fade in the new art.
                        imageView.setImageBitmap(bitmap.bitmap)
                        imageView.startAnimation(AnimationUtils.loadAnimation(imageView.context, R.anim.boxart_fadein))
                        imageView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private class AsyncDrawable(
        resources: Resources,
        bitmap: Bitmap,
        loaderTask: LoaderTask
    ) : BitmapDrawable(resources, bitmap) {
        private val loaderTaskReference = WeakReference(loaderTask)

        fun getLoaderTask(): LoaderTask? = loaderTaskReference.get()
    }

    private fun getLoaderTask(imageView: ImageView?): LoaderTask? {
        if (imageView == null) {
            return null
        }

        val drawable: Drawable = imageView.drawable
        return if (drawable is AsyncDrawable) {
            drawable.getLoaderTask()
        } else {
            null
        }
    }

    private fun cancelPendingLoad(tuple: LoaderTuple, imageView: ImageView): Boolean {
        val loaderTask = getLoaderTask(imageView)

        // Check if any task was pending for this image view.
        if (loaderTask != null && !loaderTask.isCancelled) {
            val taskTuple = loaderTask.tuple

            // Cancel the task if it's not already loading the same data.
            if (taskTuple == null || taskTuple != tuple) {
                loaderTask.cancel(true)
            } else {
                // It's already loading what we want.
                return false
            }
        }

        // Allow the load to proceed.
        return true
    }

    fun queueCacheLoad(app: NvApp) {
        val tuple = LoaderTuple(computer, app)

        if (memoryLoader.loadBitmapFromCache(tuple) != null) {
            // It's in memory which means it must also be on disk.
            return
        }

        cacheExecutor.execute {
            if (diskLoader.checkCacheExists(tuple)) {
                return@execute
            }

            // Try to load the asset from the network and cache result on disk.
            doNetworkAssetLoad(tuple, null)
        }
    }

    private fun isBitmapPlaceholder(bitmap: ScaledBitmap?): Boolean =
        bitmap == null ||
            bitmap.originalWidth == 130 && bitmap.originalHeight == 180 || // GFE 2.0
            bitmap.originalWidth == 628 && bitmap.originalHeight == 888 // GFE 3.0

    fun populateImageView(app: NvApp, imgView: ImageView): Boolean =
        populateImageView(app, imgView, null, false)

    fun populateImageView(app: NvApp, imgView: ImageView, textView: TextView): Boolean =
        populateImageView(app, imgView, textView, true)

    private fun populateImageView(
        app: NvApp,
        imgView: ImageView,
        textView: TextView?,
        manageTextVisibility: Boolean
    ): Boolean {
        val tuple = LoaderTuple(computer, app)

        // If there's already a task in progress for this view, cancel it. If the task is already
        // loading the same image, we return and let that load finish.
        if (!cancelPendingLoad(tuple, imgView)) {
            return true
        }

        // Always set the name text so we have it if needed later.
        if (manageTextVisibility && textView != null) {
            textView.text = app.appName
        }

        // First, try the memory cache in the current context.
        val bitmap = memoryLoader.loadBitmapFromCache(tuple)
        if (bitmap != null) {
            // Show the bitmap immediately.
            imgView.visibility = View.VISIBLE
            imgView.setImageBitmap(bitmap.bitmap)

            // Show the text if it's a placeholder bitmap.
            if (manageTextVisibility && textView != null) {
                textView.visibility = if (isBitmapPlaceholder(bitmap)) View.VISIBLE else View.GONE
            }
            return true
        }

        // If it's not in memory, create an async task to load it. This task will be attached
        // via AsyncDrawable to this view.
        val task = LoaderTask(imgView, textView, true, manageTextVisibility)
        val asyncDrawable = AsyncDrawable(imgView.resources, placeholderBitmap, task)
        if (manageTextVisibility && textView != null) {
            textView.visibility = View.INVISIBLE
        }
        imgView.visibility = View.INVISIBLE
        imgView.setImageDrawable(asyncDrawable)

        // Run the task on our foreground executor.
        task.executeOnExecutor(foregroundExecutor, tuple)
        return false
    }

    class LoaderTuple(
        @JvmField val computer: ComputerDetails,
        @JvmField val app: NvApp
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is LoaderTuple) {
                return false
            }

            return computer.uuid == other.computer.uuid && app.appId == other.app.appId
        }

        override fun toString(): String = "(${computer.uuid}, ${app.appId})"
    }

    companion object {
        private const val MAX_CONCURRENT_DISK_LOADS = 3
        private const val MAX_CONCURRENT_NETWORK_LOADS = 3
        private const val MAX_CONCURRENT_CACHE_LOADS = 1

        private const val MAX_PENDING_CACHE_LOADS = 100
        private const val MAX_PENDING_NETWORK_LOADS = 40
        private const val MAX_PENDING_DISK_LOADS = 40

        private const val NETWORK_LOAD_ATTEMPTS = 3

        private fun cancelQueuedLoads(executor: ThreadPoolExecutor) {
            while (true) {
                val runnable = executor.queue.poll() ?: break
                executor.remove(runnable)
            }
        }
    }
}
