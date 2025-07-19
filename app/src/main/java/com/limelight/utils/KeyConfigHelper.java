package com.limelight.utils;

import com.google.gson.Gson;

import java.util.List;
import java.util.ArrayList;

public class KeyConfigHelper {
    public static class ShortcutFile {
        public List<Shortcut> data;
        
        // Default constructor for Gson
        public ShortcutFile() {
            this.data = new ArrayList<>();
        }
        public ShortcutFile(List<Shortcut> data) {
            this.data = data;
        }
    }

    public static class Shortcut {
        public String id;
        public String name;
        public boolean sticky = false;  // Default to false
        public List<String> keys;

        // Default constructor for Gson
        public Shortcut() {
            this.keys = new ArrayList<>();
        }
        
        public Shortcut(String id, String name, boolean sticky, List<String> keys) {
            this.id = id;
            this.name = name;
            this.sticky = sticky;
            this.keys = keys;
        }
    }

    public static ShortcutFile parseShortcutFile(String json) {
        Gson gson = new Gson();
        return gson.fromJson(json, ShortcutFile.class);
    }
}
