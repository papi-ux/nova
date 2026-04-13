package com.papi.nova.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tracks streaming performance data in a file-backed bounded log.
 * Uses a dedicated JSON file instead of SharedPreferences to avoid
 * full-blob parse/serialize overhead on every write.
 */
public class PerformanceDataTracker {

    private static final String LOG_FILE = "performance_log.json";
    private static final int MAX_ENTRIES = 50;

    // Constants for field names
    private static final String FIELD_DEVICE = "Device";
    private static final String FIELD_OS_VERSION = "OS Version";
    private static final String FIELD_APP_VERSION = "App Version";
    private static final String FIELD_CODEC = "Codec";
    private static final String FIELD_STATS_LOG = "Performance Stats Log";
    private static final String FIELD_DECODING_TIME = "Decoding Time (ms)";
    private static final String FIELD_BITRATE = "Bitrate (Mbps)";
    private static final String FIELD_RESOLUTION = "Resolution";
    private static final String FIELD_FRAME_RATE = "Frame Rate (FPS)";
    private static final String FIELD_AVERAGE = "Average Latency";
    private static final String FIELD_FRAME_PACING = "Frame Pacing";
    private static final String FIELD_DATETIME = "Date/Time";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public void savePerformanceStatistics(
            Context context,
            String device,
            String osVersion,
            String appVersion,
            String codec,
            String decodingTimeMs,
            String stats,
            String bitrateMbps,
            String resolution,
            String frameRateFps,
            String average,
            String framePacing,
            String dateTime) {

        executorService.execute(() -> saveToFile(context, device, osVersion, appVersion, codec,
                decodingTimeMs, stats, bitrateMbps, resolution, frameRateFps, average, framePacing, dateTime));
    }

    private void saveToFile(Context context, String device, String osVersion, String appVersion, String codec,
                            String decodingTimeMs, String stats, String bitrateMbps, String resolution,
                            String frameRateFps, String average, String framePacing, String dateTime) {
        try {
            JSONObject newEntry = new JSONObject();
            newEntry.put(FIELD_DEVICE, device);
            newEntry.put(FIELD_OS_VERSION, osVersion);
            newEntry.put(FIELD_APP_VERSION, appVersion);
            newEntry.put(FIELD_CODEC, codec);
            newEntry.put(FIELD_DECODING_TIME, decodingTimeMs);
            newEntry.put(FIELD_STATS_LOG, stats);
            newEntry.put(FIELD_BITRATE, bitrateMbps);
            newEntry.put(FIELD_RESOLUTION, resolution);
            newEntry.put(FIELD_FRAME_RATE, frameRateFps);
            newEntry.put(FIELD_AVERAGE, average);
            newEntry.put(FIELD_FRAME_PACING, framePacing);
            newEntry.put(FIELD_DATETIME, dateTime);

            JSONArray logsArray = readLogArray(context);

            // Check for duplicate config — replace if new entry has better decoding time
            float newDecodingTime = parseDecodingTime(decodingTimeMs);
            int duplicateIndex = -1;

            for (int i = 0; i < logsArray.length(); i++) {
                JSONObject entry = logsArray.getJSONObject(i);

                boolean isSameConfig =
                        device.equals(entry.optString(FIELD_DEVICE)) &&
                                osVersion.equals(entry.optString(FIELD_OS_VERSION)) &&
                                appVersion.equals(entry.optString(FIELD_APP_VERSION)) &&
                                codec.equals(entry.optString(FIELD_CODEC)) &&
                                bitrateMbps.equals(entry.optString(FIELD_BITRATE)) &&
                                resolution.equals(entry.optString(FIELD_RESOLUTION)) &&
                                frameRateFps.equals(entry.optString(FIELD_FRAME_RATE)) &&
                                framePacing.equals(entry.optString(FIELD_FRAME_PACING));

                if (isSameConfig) {
                    float existingDecodingTime = parseDecodingTime(entry.optString(FIELD_DECODING_TIME));
                    if (existingDecodingTime <= newDecodingTime) {
                        return; // existing entry is equal or better
                    }
                    duplicateIndex = i;
                    break;
                }
            }

            if (duplicateIndex != -1) {
                logsArray.remove(duplicateIndex);
            }

            logsArray.put(newEntry);

            // Enforce retention limit — remove oldest entries
            while (logsArray.length() > MAX_ENTRIES) {
                logsArray.remove(0);
            }

            writeLogArray(context, logsArray);

        } catch (Exception e) {
            Log.e("PerformanceDataTracker", "Failed to save: " + e.getMessage());
        }
    }

    private JSONArray readLogArray(Context context) {
        File file = new File(context.getFilesDir(), LOG_FILE);
        if (!file.exists()) return new JSONArray();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            return new JSONArray(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("PerformanceDataTracker", "Invalid log file, starting fresh");
            return new JSONArray();
        }
    }

    private void writeLogArray(Context context, JSONArray array) {
        File file = new File(context.getFilesDir(), LOG_FILE);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e("PerformanceDataTracker", "Failed to write log file: " + e.getMessage());
        }
    }

    private float parseDecodingTime(String decodingTimeString) {
        if (decodingTimeString == null) return Float.MAX_VALUE;
        try {
            String numericPart = decodingTimeString.replaceAll("[^0-9.]", "");
            return Float.parseFloat(numericPart);
        } catch (Exception e) {
            return Float.MAX_VALUE;
        }
    }

    public String getLog(Context context) {
        return readLogArray(context).toString();
    }

    public void clearLogs(Context context) {
        File file = new File(context.getFilesDir(), LOG_FILE);
        if (file.exists()) {
            file.delete();
        }
        Log.d("PerformanceDataTracker", "All logs cleared.");
    }
}
