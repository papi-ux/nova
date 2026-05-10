package com.papi.nova.nvstream.http;

import com.papi.nova.LimeLog;
import com.papi.nova.api.PolarisGame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NvApp {
    public static final String REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4";
    private String appName = "";
    private String appUUID = "";
    private int appId;
    private int appIndex;
    private boolean initialized;
    private boolean hdrSupported;
    private String source = "";
    private String launcherSource = "";
    private String launcherDetail = "";
    private String platform = "";
    private String runtime = "";
    private String steamAppid = "";
    private String category = "";
    
    public NvApp() {}
    
    public NvApp(String appName) {
        this.appName = appName;
    }
    
    public NvApp(String appName, String appUUID, int appId, boolean hdrSupported) {
        this.appName = appName;
        this.appUUID = appUUID;
        this.appId = appId;
        this.hdrSupported = hdrSupported;
        this.initialized = true;
    }
    
    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setAppUUID(String appUUID) {
        this.appUUID = appUUID;
    }
    
    public void setAppId(String appId) {
        try {
            this.appId = Integer.parseInt(appId);
            this.initialized = true;
        } catch (NumberFormatException e) {
            LimeLog.warning("Malformed app ID: "+appId);
        }
    }

    public void setAppIndex(String appIndex) {
        try {
            this.appIndex = Integer.parseInt(appIndex);
            this.initialized = true;
        } catch (NumberFormatException e) {
            LimeLog.warning("Malformed app index: "+appIndex);
        }
    }

    public void setAppId(int appId) {
        this.appId = appId;
        this.initialized = true;
    }

    public void setAppIndex(int appIndex) {
        this.appIndex = appIndex;
    }

    public void setHdrSupported(boolean hdrSupported) {
        this.hdrSupported = hdrSupported;
    }

    public boolean applyPolarisMetadata(PolarisGame game) {
        if (game == null) {
            return false;
        }

        String nextSource = normalizeToken(game.getSource());
        String nextLauncherSource = normalizeToken(game.getLauncherSource());
        String nextLauncherDetail = normalizeToken(game.getLauncherDetail());
        String nextPlatform = normalizeToken(game.getPlatform());
        String nextRuntime = normalizeToken(game.getRuntime());
        String nextSteamAppid = safeString(game.getSteamAppid());
        String nextCategory = normalizeToken(game.getCategory());

        boolean changed = !source.equals(nextSource)
                || !launcherSource.equals(nextLauncherSource)
                || !launcherDetail.equals(nextLauncherDetail)
                || !platform.equals(nextPlatform)
                || !runtime.equals(nextRuntime)
                || !steamAppid.equals(nextSteamAppid)
                || !category.equals(nextCategory);

        source = nextSource;
        launcherSource = nextLauncherSource;
        launcherDetail = nextLauncherDetail;
        platform = nextPlatform;
        runtime = nextRuntime;
        steamAppid = nextSteamAppid;
        category = nextCategory;
        return changed;
    }
    
    public String getAppName() {
        return this.appName;
    }

    public String getAppUUID() {
        return this.appUUID;
    }
    
    public int getAppId() {
        return this.appId;
    }

    public int getAppIndex() {
        return this.appIndex;
    }

    public boolean isHdrSupported() {
        return this.hdrSupported;
    }

    public String getSource() {
        return source;
    }

    public String getLauncherSource() {
        return launcherSource;
    }

    public String getPlatform() {
        return platform;
    }

    public String getRuntime() {
        return runtime;
    }

    public String getSteamAppid() {
        return steamAppid;
    }

    public String getSourceLabel() {
        switch (!launcherSource.isEmpty() ? launcherSource : source) {
            case "steam":
                return "Steam";
            case "lutris":
                return "Lutris";
            case "heroic":
                return "Heroic";
            case "manual":
                return "Manual";
            default:
                return "";
        }
    }

    public String getPlatformLabel() {
        switch (platform) {
            case "linux":
                return "Linux";
            case "windows":
                return "Windows";
            case "macos":
                return "macOS";
            default:
                return "";
        }
    }

    public String getRuntimeLabel() {
        switch (runtime) {
            case "native":
                return "Native";
            case "proton":
                return "Proton";
            case "wine":
                return "Wine";
            case "steam":
                return "Steam";
            case "umu":
                return "UMU";
            default:
                return "";
        }
    }

    public String getMetadataLabel() {
        List<String> parts = new ArrayList<>();
        addDistinct(parts, getSourceLabel());
        addDistinct(parts, getPlatformLabel());
        addDistinct(parts, getRuntimeLabel());
        StringBuilder label = new StringBuilder();
        for (String part : parts) {
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append(part);
        }
        return label.toString();
    }

    public String getMetadataKey() {
        return source + "|" + launcherSource + "|" + launcherDetail + "|" + platform + "|" + runtime + "|" + steamAppid + "|" + category;
    }
    
    public boolean isInitialized() {
        return this.initialized;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Name: ").append(appName).append("\n");
        str.append("UUID: ").append(appUUID).append("\n");
        str.append("ID: ").append(appId).append("\n");
        str.append("HDR Supported: ").append(hdrSupported ? "Yes" : "Unknown").append("\n");
        String metadata = getMetadataLabel();
        if (!metadata.isEmpty()) {
            str.append("Source: ").append(metadata).append("\n");
        }
        return str.toString();
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeToken(String value) {
        return safeString(value).toLowerCase(Locale.US);
    }

    private static void addDistinct(List<String> parts, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (String part : parts) {
            if (part.equalsIgnoreCase(value)) {
                return;
            }
        }
        parts.add(value);
    }
}
