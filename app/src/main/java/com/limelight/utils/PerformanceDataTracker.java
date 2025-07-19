package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PerformanceDataTracker {

    private static final String PREF_KEY_LOG = "performance_log";

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

        executorService.execute(() -> saveToPreferences(context, device, osVersion, appVersion, codec,
                decodingTimeMs, stats, bitrateMbps, resolution, frameRateFps, average, framePacing, dateTime));
    }

    private void saveToPreferences(Context context, String device, String osVersion, String appVersion, String codec,
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

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String existingLogsRaw = prefs.getString(PREF_KEY_LOG, "");

            JSONArray logsArray;

            try {
                logsArray = new JSONArray(existingLogsRaw);
            } catch (Exception e) {
                logsArray = new JSONArray();
                Log.w("PerformanceDataTracker", "Invalid old logs cleared.");
            }

            float newDecodingTime = parseDecodingTime(decodingTimeMs);
            int duplicateIndex = -1;
            float worstDecodingTime = Float.MAX_VALUE;

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
                        Log.d("PerformanceDataTracker", "Duplicate with equal or better decoding time. Skipping.");
                        return;
                    } else {
                        duplicateIndex = i;
                        worstDecodingTime = existingDecodingTime;
                    }
                }
            }

            if (duplicateIndex != -1) {
                logsArray.remove(duplicateIndex);
                Log.d("PerformanceDataTracker", "Replaced older entry with decoding time: " + worstDecodingTime);
            }

            logsArray.put(newEntry);
            prefs.edit().putString(PREF_KEY_LOG, logsArray.toString()).apply();
            Log.d("PerformanceDataTracker", "New performance data saved.");

        } catch (Exception e) {
            Log.e("PerformanceDataTracker", "Failed to save to preferences: " + e.getMessage());
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
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(PREF_KEY_LOG, "");
    }

    public void clearLogs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(PREF_KEY_LOG).apply();
        Log.d("PerformanceDataTracker", "All logs cleared.");
    }
}
