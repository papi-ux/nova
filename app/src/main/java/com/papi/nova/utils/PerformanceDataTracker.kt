package com.papi.nova.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tracks streaming performance data in a file-backed bounded log.
 * Uses a dedicated JSON file instead of SharedPreferences to avoid
 * full-blob parse/serialize overhead on every write.
 */
class PerformanceDataTracker {
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    fun savePerformanceStatistics(
        context: Context,
        device: String?,
        osVersion: String?,
        appVersion: String?,
        codec: String?,
        decodingTimeMs: String?,
        stats: String?,
        bitrateMbps: String?,
        resolution: String?,
        frameRateFps: String?,
        average: String?,
        framePacing: String?,
        dateTime: String?,
    ) {
        executorService.execute {
            saveToFile(
                context,
                device,
                osVersion,
                appVersion,
                codec,
                decodingTimeMs,
                stats,
                bitrateMbps,
                resolution,
                frameRateFps,
                average,
                framePacing,
                dateTime,
            )
        }
    }

    private fun saveToFile(
        context: Context,
        device: String?,
        osVersion: String?,
        appVersion: String?,
        codec: String?,
        decodingTimeMs: String?,
        stats: String?,
        bitrateMbps: String?,
        resolution: String?,
        frameRateFps: String?,
        average: String?,
        framePacing: String?,
        dateTime: String?,
    ) {
        try {
            val newEntry = JSONObject()
            newEntry.put(FIELD_DEVICE, device)
            newEntry.put(FIELD_OS_VERSION, osVersion)
            newEntry.put(FIELD_APP_VERSION, appVersion)
            newEntry.put(FIELD_CODEC, codec)
            newEntry.put(FIELD_DECODING_TIME, decodingTimeMs)
            newEntry.put(FIELD_STATS_LOG, stats)
            newEntry.put(FIELD_BITRATE, bitrateMbps)
            newEntry.put(FIELD_RESOLUTION, resolution)
            newEntry.put(FIELD_FRAME_RATE, frameRateFps)
            newEntry.put(FIELD_AVERAGE, average)
            newEntry.put(FIELD_FRAME_PACING, framePacing)
            newEntry.put(FIELD_DATETIME, dateTime)

            val logsArray = readLogArray(context)
            val newDecodingTime = parseDecodingTime(decodingTimeMs)
            var duplicateIndex = -1

            for (i in 0 until logsArray.length()) {
                val entry = logsArray.getJSONObject(i)
                val isSameConfig =
                    device == entry.optString(FIELD_DEVICE) &&
                        osVersion == entry.optString(FIELD_OS_VERSION) &&
                        appVersion == entry.optString(FIELD_APP_VERSION) &&
                        codec == entry.optString(FIELD_CODEC) &&
                        bitrateMbps == entry.optString(FIELD_BITRATE) &&
                        resolution == entry.optString(FIELD_RESOLUTION) &&
                        frameRateFps == entry.optString(FIELD_FRAME_RATE) &&
                        framePacing == entry.optString(FIELD_FRAME_PACING)

                if (isSameConfig) {
                    val existingDecodingTime = parseDecodingTime(entry.optString(FIELD_DECODING_TIME))
                    if (existingDecodingTime <= newDecodingTime) {
                        return
                    }
                    duplicateIndex = i
                    break
                }
            }

            if (duplicateIndex != -1) {
                logsArray.remove(duplicateIndex)
            }

            logsArray.put(newEntry)

            while (logsArray.length() > MAX_ENTRIES) {
                logsArray.remove(0)
            }

            writeLogArray(context, logsArray)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save: ${e.message}")
        }
    }

    private fun readLogArray(context: Context): JSONArray {
        val file = File(context.filesDir, LOG_FILE)
        if (!file.exists()) return JSONArray()

        return try {
            FileInputStream(file).use { fis ->
                val data = ByteArray(file.length().toInt())
                fis.read(data)
                JSONArray(String(data, StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid log file, starting fresh")
            JSONArray()
        }
    }

    private fun writeLogArray(context: Context, array: JSONArray) {
        val file = File(context.filesDir, LOG_FILE)
        try {
            FileOutputStream(file).use { fos ->
                fos.write(array.toString().toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log file: ${e.message}")
        }
    }

    private fun parseDecodingTime(decodingTimeString: String?): Float {
        if (decodingTimeString == null) return Float.MAX_VALUE
        return try {
            val numericPart = decodingTimeString.replace("[^0-9.]".toRegex(), "")
            numericPart.toFloat()
        } catch (e: Exception) {
            Float.MAX_VALUE
        }
    }

    fun getLog(context: Context): String = readLogArray(context).toString()

    fun clearLogs(context: Context) {
        val file = File(context.filesDir, LOG_FILE)
        if (file.exists()) {
            file.delete()
        }
        Log.d(TAG, "All logs cleared.")
    }

    private companion object {
        private const val TAG = "PerformanceDataTracker"
        private const val LOG_FILE = "performance_log.json"
        private const val MAX_ENTRIES = 50
        private const val FIELD_DEVICE = "Device"
        private const val FIELD_OS_VERSION = "OS Version"
        private const val FIELD_APP_VERSION = "App Version"
        private const val FIELD_CODEC = "Codec"
        private const val FIELD_STATS_LOG = "Performance Stats Log"
        private const val FIELD_DECODING_TIME = "Decoding Time (ms)"
        private const val FIELD_BITRATE = "Bitrate (Mbps)"
        private const val FIELD_RESOLUTION = "Resolution"
        private const val FIELD_FRAME_RATE = "Frame Rate (FPS)"
        private const val FIELD_AVERAGE = "Average Latency"
        private const val FIELD_FRAME_PACING = "Frame Pacing"
        private const val FIELD_DATETIME = "Date/Time"
    }
}
