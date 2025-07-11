package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.content.res.XmlResourceParser;
import com.limelight.R;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class OptionDiffUtil {
    private static Map<String, Object> xmlDefaults;

    private static void init(Context ctx) {
        if (xmlDefaults != null) return;
        xmlDefaults = new HashMap<>();
        XmlResourceParser parser = ctx.getResources().getXml(R.xml.preferences);
        try {
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("CheckBoxPreference".equals(tag)
                            || "ListPreference".equals(tag)
                            || "EditTextPreference".equals(tag)
                            || "com.limelight.preferences.SeekBarPreference".equals(tag)) {
                        String key = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "key");
                        String def = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "defaultValue");
                        if (key != null && def != null) {
                            Object val = def;
                            if ("true".equals(def) || "false".equals(def)) {
                                val = Boolean.parseBoolean(def);
                            } else {
                                try {
                                    val = Integer.parseInt(def);
                                } catch (NumberFormatException e1) {
                                    try {
                                        val = Float.parseFloat(def);
                                    } catch (NumberFormatException e2) {
                                        // leave as String
                                    }
                                }
                            }
                            xmlDefaults.put(key, val);
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            // ignore
        } finally {
            parser.close();
        }
    }

    /**
     * Returns a map of all preference keys whose stored values differ from their XML defaults.
     */
    public static Map<String, Object> diff(Context ctx) {
        init(ctx);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        Map<String, Object> patch = new HashMap<>();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (xmlDefaults.containsKey(k)) {
                Object def = xmlDefaults.get(k);
                if (v == null || !v.equals(def)) {
                    patch.put(k, v);
                }
            } else {
                // unknown key, include
                patch.put(k, v);
            }
        }
        return patch;
    }
}