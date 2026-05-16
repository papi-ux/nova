package com.papi.nova.utils

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.papi.nova.LimeLog
import com.papi.nova.R
import com.papi.nova.nvstream.http.ComputerDetails
import com.papi.nova.nvstream.http.NvApp
import java.io.IOException
import java.nio.charset.Charset
import java.util.Collections
import java.util.LinkedList

class ShortcutHelper(private val context: Activity) {
    private val sm: ShortcutManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            context.getSystemService(ShortcutManager::class.java)
        } else {
            null
        }
    private val tvChannelHelper = TvChannelHelper(context)

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun reapShortcutsForDynamicAdd() {
        val manager = sm ?: return
        val dynamicShortcuts = manager.dynamicShortcuts
        while (dynamicShortcuts.isNotEmpty() && dynamicShortcuts.size >= manager.maxShortcutCountPerActivity) {
            var maxRankShortcut = dynamicShortcuts[0]
            for (shortcut in dynamicShortcuts) {
                if (maxRankShortcut.rank < shortcut.rank) {
                    maxRankShortcut = shortcut
                }
            }
            manager.removeDynamicShortcuts(Collections.singletonList(maxRankShortcut.id))
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getAllShortcuts(): List<ShortcutInfo> {
        val manager = sm ?: return emptyList()
        return LinkedList<ShortcutInfo>().apply {
            addAll(manager.dynamicShortcuts)
            addAll(manager.pinnedShortcuts)
        }
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun getInfoForId(id: String): ShortcutInfo? {
        for (info in getAllShortcuts()) {
            if (info.id == id) {
                return info
            }
        }

        return null
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    private fun isExistingDynamicShortcut(id: String): Boolean {
        val manager = sm ?: return false
        for (shortcut in manager.dynamicShortcuts) {
            if (shortcut.id == id) {
                return true
            }
        }

        return false
    }

    fun reportComputerShortcutUsed(computer: ComputerDetails) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = sm ?: return
            if (getInfoForId(computer.uuid) != null) {
                manager.reportShortcutUsed(computer.uuid)
            }
        }
    }

    fun reportGameLaunched(computer: ComputerDetails, app: NvApp) {
        tvChannelHelper.createTvChannel(computer)
        tvChannelHelper.addGameToChannel(computer, app)
    }

    fun createAppViewShortcut(computer: ComputerDetails, forceAdd: Boolean, newlyPaired: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = sm ?: return
            val shortcutInfo = ShortcutInfo.Builder(context, computer.uuid)
                .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                .setShortLabel(computer.name)
                .setLongLabel(computer.name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                .build()

            val existingShortcutInfo = getInfoForId(computer.uuid)
            if (existingShortcutInfo != null) {
                manager.updateShortcuts(Collections.singletonList(shortcutInfo))
                manager.enableShortcuts(Collections.singletonList(computer.uuid))
            }

            if (!isExistingDynamicShortcut(computer.uuid)) {
                if (forceAdd) {
                    reapShortcutsForDynamicAdd()
                }

                if (manager.dynamicShortcuts.size < manager.maxShortcutCountPerActivity) {
                    manager.addDynamicShortcuts(Collections.singletonList(shortcutInfo))
                }
            }
        }

        if (newlyPaired) {
            tvChannelHelper.createTvChannel(computer)
            tvChannelHelper.requestChannelOnHomeScreen(computer)
        }
    }

    fun createAppViewShortcutForOnlineHost(details: ComputerDetails) {
        createAppViewShortcut(details, false, false)
    }

    private fun getShortcutIdForGame(computer: ComputerDetails, app: NvApp): String {
        return computer.uuid + app.appId
    }

    @TargetApi(Build.VERSION_CODES.O)
    fun createPinnedGameShortcut(computer: ComputerDetails, app: NvApp, iconBits: Bitmap?): Boolean {
        val manager = sm ?: return false
        return if (manager.isRequestPinShortcutSupported) {
            val appIcon = if (iconBits != null) {
                Icon.createWithAdaptiveBitmap(iconBits)
            } else {
                Icon.createWithResource(context, R.mipmap.ic_pc_scut)
            }

            val shortcutInfo = ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel(app.appName + " (" + computer.name + ")")
                .setIcon(appIcon)
                .build()

            manager.requestPinShortcut(shortcutInfo, null)
        } else {
            false
        }
    }

    fun disableComputerShortcut(computer: ComputerDetails, reason: CharSequence) {
        tvChannelHelper.deleteChannel(computer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = sm ?: return
            if (getInfoForId(computer.uuid) != null) {
                manager.disableShortcuts(Collections.singletonList(computer.uuid), reason)
            }

            val appShortcutIds = LinkedList<String>()
            for (info in getAllShortcuts()) {
                if (info.id.startsWith(computer.uuid)) {
                    appShortcutIds.add(info.id)
                }
            }
            manager.disableShortcuts(appShortcutIds, reason)
        }
    }

    fun disableAppShortcut(computer: ComputerDetails, app: NvApp, reason: CharSequence) {
        tvChannelHelper.deleteProgram(computer, app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = sm ?: return
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                manager.disableShortcuts(Collections.singletonList(id), reason)
            }
        }
    }

    fun enableAppShortcut(computer: ComputerDetails, app: NvApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val manager = sm ?: return
            val id = getShortcutIdForGame(computer, app)
            if (getInfoForId(id) != null) {
                manager.enableShortcuts(Collections.singletonList(id))
            }
        }
    }

    fun exportLauncherFile(computer: ComputerDetails?, app: NvApp?) {
        if (computer == null || computer.uuid.isEmpty() || computer.name.isEmpty()) {
            Toast.makeText(
                context,
                R.string.export_launcher_computer_details_incomplete,
                Toast.LENGTH_LONG,
            ).show()
            LimeLog.warning("exportLauncherFile: Computer details incomplete.")
            return
        }

        if (
            app == null ||
            app.appName.isEmpty() ||
            app.appUUID == null ||
            app.appUUID!!.isEmpty()
        ) {
            Toast.makeText(
                context,
                R.string.export_launcher_app_details_incomplete,
                Toast.LENGTH_LONG,
            ).show()
            LimeLog.warning("exportLauncherFile: App details incomplete.")
            return
        }

        val builder = StringBuilder()
        builder.append("# Artemis app entry\n")
        builder.append("# Generated by Artemis for Android\n\n")
        builder.append("[").append(KEY_HOST_UUID).append("] ").append(computer.uuid).append("\n")
        builder.append("[").append(KEY_HOST_NAME).append("] ").append(computer.name).append("\n")

        val appUuid = app.appUUID
        if (appUuid != null && appUuid.isNotEmpty()) {
            builder.append("[").append(KEY_APP_UUID).append("] ").append(appUuid).append("\n")
        }
        if (app.appName.isNotEmpty()) {
            builder.append("[").append(KEY_APP_NAME).append("] ").append(app.appName).append("\n")
        } else if (app.appId > 0) {
            builder.append("[").append(KEY_APP_ID).append("] ").append(app.appId).append("\n")
        }

        artFileContentToExport = builder.toString()

        val fileName = app.appName.trim() + ".art"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

        try {
            context.startActivityForResult(intent, REQUEST_CODE_EXPORT_ART_FILE)
        } catch (e: Exception) {
            LimeLog.severe("Failed to start activity for file export: " + e.message)
            Toast.makeText(
                context,
                context.getString(R.string.failed_to_initiate_file_export, e.message),
                Toast.LENGTH_LONG,
            ).show()
            artFileContentToExport = null
        }
    }

    companion object {
        const val REQUEST_CODE_EXPORT_ART_FILE: Int = 778

        @JvmField
        var artFileContentToExport: String? = null

        const val KEY_HOST_UUID: String = "host_uuid"
        const val KEY_HOST_NAME: String = "host_name"
        const val KEY_APP_UUID: String = "app_uuid"
        const val KEY_APP_NAME: String = "app_name"
        const val KEY_APP_ID: String = "app_id"

        @JvmStatic
        fun writeArtFileToUri(activityContext: Activity, uri: Uri?) {
            if (uri == null) {
                LimeLog.warning("writeArtFileToUri: URI is null.")
                Toast.makeText(
                    activityContext,
                    R.string.file_export_failed_no_location_selected,
                    Toast.LENGTH_LONG,
                ).show()
                artFileContentToExport = null
                return
            }

            val content = artFileContentToExport
            if (content.isNullOrEmpty()) {
                LimeLog.warning("writeArtFileToUri: No content to export.")
                Toast.makeText(
                    activityContext,
                    R.string.file_export_failed_no_content_to_write,
                    Toast.LENGTH_LONG,
                ).show()
                return
            }

            try {
                activityContext.contentResolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream != null) {
                        outputStream.write(content.toByteArray(Charset.defaultCharset()))
                        outputStream.flush()
                        LimeLog.info("Successfully wrote .art file to: $uri")
                        Toast.makeText(
                            activityContext,
                            R.string.file_exported_successfully,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        LimeLog.severe("Failed to open output stream for URI: $uri")
                        Toast.makeText(
                            activityContext,
                            R.string.failed_to_open_file_for_writing,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: IOException) {
                LimeLog.severe("Error writing .art file to URI: $uri - " + e.message)
                Toast.makeText(
                    activityContext,
                    activityContext.getString(R.string.error_writing_file, e.message),
                    Toast.LENGTH_LONG,
                ).show()
            } catch (e: Exception) {
                LimeLog.severe("Unexpected error writing .art file to URI: $uri - " + e.message)
                Toast.makeText(
                    activityContext,
                    R.string.unexpected_error_during_file_export,
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                artFileContentToExport = null
            }
        }
    }
}
