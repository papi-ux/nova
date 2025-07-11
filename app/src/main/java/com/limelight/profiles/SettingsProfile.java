package com.limelight.profiles;

import java.util.Map;
import java.util.UUID;

public class SettingsProfile {
    private UUID uuid;
    private String name;
    private long createdUtc;
    private long modifiedUtc;
    private Map<String, Object> options;

    private transient boolean isActive;

    public SettingsProfile(UUID uuid, String name, long createdUtc, long modifiedUtc, Map<String, Object> options) {
        this.uuid = uuid;
        this.name = name;
        this.createdUtc = createdUtc;
        this.modifiedUtc = modifiedUtc;
        this.options = options;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCreatedUtc() {
        return createdUtc;
    }

    public long getModifiedUtc() {
        return modifiedUtc;
    }

    public void setModifiedUtc(long modifiedUtc) {
        this.modifiedUtc = modifiedUtc;
    }

    public Map<String, Object> getOptions() {
        return options;
    }

    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }
}