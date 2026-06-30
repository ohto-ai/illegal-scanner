package com.illegalscanner.config;

import com.illegalscanner.IllegalScanner;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Centralized i18n message provider.
 * Loads messages_zh.yml from the plugin jar.
 * Supports {key} placeholder replacement.
 */
public final class Messages {

    private final IllegalScanner plugin;
    private YamlConfiguration messages;

    public Messages(IllegalScanner plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        InputStream stream = plugin.getResource("messages_zh.yml");
        if (stream == null) {
            plugin.getLogger().warning("messages_zh.yml not found, falling back to hardcoded strings.");
            messages = new YamlConfiguration();
            return;
        }
        messages = YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    /**
     * Get a message by dot-path key (e.g. "scan.starting_full").
     * Replace {key} placeholders with provided key-value pairs.
     *
     * Usage: msg("scan.player_done", "{count}", "5")
     */
    public String get(String path, String... replacements) {
        String value = messages.getString(path);
        if (value == null) {
            return "<<" + path + ">>"; // Debug marker for missing keys
        }
        for (int i = 0; i < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return value;
    }

    /**
     * Get raw message without formatting.
     */
    public String getRaw(String path) {
        String value = messages.getString(path);
        return value != null ? value : "<<" + path + ">>";
    }

    /**
     * Reload messages from jar (used when /is reload is called).
     */
    public void reload() {
        load();
    }
}
