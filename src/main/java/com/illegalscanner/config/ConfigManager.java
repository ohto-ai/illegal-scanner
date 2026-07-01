package com.illegalscanner.config;

import com.illegalscanner.IllegalScanner;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class ConfigManager {

    private final IllegalScanner plugin;
    private File configFile;
    private FileConfiguration config;
    private final Set<Material> watchMaterials = new HashSet<>();

    public ConfigManager(IllegalScanner plugin) {
        this.plugin = plugin;
    }

    public void load() {
        // Ensure plugin data folder exists
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        configFile = new File(plugin.getDataFolder(), "config.yml");

        // Create default config if not exists
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(configFile);

        // Merge with defaults from jar
        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream != null) {
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            config.setDefaults(defaultConfig);
            // Save any missing keys
            boolean needsSave = false;
            for (String key : defaultConfig.getKeys(true)) {
                if (!config.contains(key)) {
                    config.set(key, defaultConfig.get(key));
                    needsSave = true;
                }
            }
            if (needsSave) {
                save();
            }
        }

        // Load watch materials
        loadWatchMaterials();
    }

    public void reload() {
        if (configFile == null) {
            load();
            return;
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultStream = plugin.getResource("config.yml");
        if (defaultStream != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
        }
        // Reload whitelist caches
        plugin.getItemWhitelistManager().loadCache();
        plugin.getRegionWhitelistManager().loadCache();
        plugin.getPlayerWhitelistManager().loadCache();

        // Reload watch materials
        loadWatchMaterials();
    }

    public void save() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save config.yml!", e);
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public File getConfigFile() {
        return configFile;
    }

    // ==================== Watch Materials ====================

    /**
     * Load watch materials from config into the in-memory set.
     */
    private void loadWatchMaterials() {
        watchMaterials.clear();
        List<String> list = config.getStringList("watch_materials");
        if (list != null) {
            for (String name : list) {
                Material mat = Material.matchMaterial(name);
                if (mat != null && mat.isItem()) {
                    watchMaterials.add(mat);
                } else if (mat == null) {
                    plugin.getLogger().warning("Unknown material in watch_materials: " + name);
                }
            }
        }
    }

    /**
     * Check if a material is on the watch list.
     */
    public boolean isMaterialWatched(Material material) {
        return watchMaterials.contains(material);
    }

    /**
     * Get an unmodifiable view of the watched materials set.
     */
    public Set<Material> getWatchMaterials() {
        return Collections.unmodifiableSet(watchMaterials);
    }

    /**
     * Add a material to the watch list. Saves config immediately.
     * @return true if added, false if already present
     */
    public boolean addWatchMaterial(String materialName) {
        Material mat = Material.matchMaterial(materialName);
        if (mat == null || !mat.isItem()) {
            return false;
        }
        if (!watchMaterials.add(mat)) {
            return false; // already present
        }
        // Persist to config
        List<String> list = config.getStringList("watch_materials");
        if (list == null) {
            list = new java.util.ArrayList<>();
        }
        list.add(mat.name());
        config.set("watch_materials", list);
        save();
        return true;
    }

    /**
     * Remove a material from the watch list. Saves config immediately.
     * @return true if removed, false if not found
     */
    public boolean removeWatchMaterial(String materialName) {
        Material mat = Material.matchMaterial(materialName);
        if (mat == null) {
            // Try to remove by name even if material isn't a valid enum (e.g. from old config)
            if (watchMaterials.removeIf(m -> m.name().equalsIgnoreCase(materialName))) {
                persistWatchMaterials();
                return true;
            }
            return false;
        }
        if (!watchMaterials.remove(mat)) {
            return false;
        }
        persistWatchMaterials();
        return true;
    }

    /**
     * Clear all watch materials. Saves config immediately.
     * @return the number of entries removed
     */
    public int clearWatchMaterials() {
        int count = watchMaterials.size();
        watchMaterials.clear();
        config.set("watch_materials", new java.util.ArrayList<>());
        save();
        return count;
    }

    /**
     * Write the current in-memory watch set back to config.
     */
    private void persistWatchMaterials() {
        List<String> list = new java.util.ArrayList<>();
        for (Material mat : watchMaterials) {
            list.add(mat.name());
        }
        config.set("watch_materials", list);
        save();
    }
}
