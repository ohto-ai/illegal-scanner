package com.illegalscanner;

import com.illegalscanner.command.CommandRouter;
import com.illegalscanner.command.ISTabCompleter;
import com.illegalscanner.config.ConfigManager;
import com.illegalscanner.config.Messages;
import com.illegalscanner.database.DatabaseManager;
import com.illegalscanner.listener.ViewGuiListener;
import com.illegalscanner.listener.WhitelistGuiListener;
import com.illegalscanner.monitor.MonitorEngine;
import com.illegalscanner.query.UnifiedQueryService;
import com.illegalscanner.scanner.ItemHashService;
import com.illegalscanner.scanner.ScanService;
import com.illegalscanner.validator.ValidationEngine;
import com.illegalscanner.whitelist.ItemWhitelistManager;
import com.illegalscanner.whitelist.PlayerWhitelistManager;
import com.illegalscanner.whitelist.RegionWhitelistManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

import java.util.Objects;
import java.util.logging.Level;

public final class IllegalScanner extends JavaPlugin {

    private static IllegalScanner instance;

    private ConfigManager configManager;
    private Messages messages;
    private DatabaseManager databaseManager;
    private ItemHashService itemHashService;
    private ValidationEngine validationEngine;
    private ScanService scanService;
    private MonitorEngine monitorEngine;
    private UnifiedQueryService queryService;

    // Whitelist managers
    private ItemWhitelistManager itemWhitelistManager;
    private RegionWhitelistManager regionWhitelistManager;
    private PlayerWhitelistManager playerWhitelistManager;

    // Command
    private CommandRouter commandRouter;

    /** ThreadLocal flag for suppressing log output (hidden admin). */
    private static final ThreadLocal<Boolean> SUPPRESS_LOG = ThreadLocal.withInitial(() -> false);

    public static IllegalScanner getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("============================================");
        getLogger().info("  illegal-scanner v" + getPluginMeta().getVersion());
        getLogger().info("  Detecting illegal/overpowered items");
        getLogger().info("============================================");

        // 1. Load configuration
        try {
            configManager = new ConfigManager(this);
            configManager.load();
            messages = new Messages(this);
            getLogger().info(messages.getRaw("plugin.config_loaded"));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Config failed!", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2. Initialize database
        try {
            databaseManager = new DatabaseManager(this);
            databaseManager.initialize();
            getLogger().info(messages.get("plugin.db_initialized"));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, messages.getRaw("plugin.db_failed"), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Initialize ItemHashService
        itemHashService = new ItemHashService(this);
        getLogger().info("Item hash service initialized.");

        // 4. Initialize validation engine
        try {
            validationEngine = new ValidationEngine(this);
            validationEngine.initialize();
            getLogger().info(messages.get("plugin.validation_initialized", "{count}",
                    String.valueOf(validationEngine.getValidatorCount())));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, messages.getRaw("plugin.validation_failed"), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 5. Initialize whitelist managers
        itemWhitelistManager = new ItemWhitelistManager(this);
        regionWhitelistManager = new RegionWhitelistManager(this);
        playerWhitelistManager = new PlayerWhitelistManager(this);

        // 6. Initialize scan service
        scanService = new ScanService(this);
        getLogger().info("Scan service initialized.");

        // 7. Initialize unified query service
        queryService = new UnifiedQueryService(this);
        getLogger().info("Query service initialized.");

        // 8. Initialize monitor engine
        monitorEngine = new MonitorEngine(this);
        monitorEngine.start();
        getLogger().info("Monitor engine started.");

        // 9. Register View GUI protection listener
        getServer().getPluginManager().registerEvents(new ViewGuiListener(this), this);
        getServer().getPluginManager().registerEvents(new WhitelistGuiListener(this), this);
        getLogger().info("View GUI listener registered.");

        // 10. Register commands
        commandRouter = new CommandRouter(this);
        Objects.requireNonNull(getCommand("illegalscanner")).setExecutor(commandRouter);
        Objects.requireNonNull(getCommand("illegalscanner")).setTabCompleter(new ISTabCompleter(this));
        getLogger().info("Commands registered.");

        getLogger().info(messages.getRaw("plugin.enabled"));
    }

    @Override
    public void onDisable() {
        if (monitorEngine != null) {
            monitorEngine.stop();
        }
        if (scanService != null) {
            scanService.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        getLogger().info(messages.getRaw("plugin.disabled"));
    }

    // --- Getters ---

    public Messages getMessages() { return messages; }

    public ConfigManager getConfigManager() { return configManager; }

    public DatabaseManager getDatabaseManager() { return databaseManager; }

    public ItemHashService getItemHashService() { return itemHashService; }

    public ValidationEngine getValidationEngine() { return validationEngine; }

    public ScanService getScanService() { return scanService; }

    public MonitorEngine getMonitorEngine() { return monitorEngine; }

    public UnifiedQueryService getQueryService() { return queryService; }

    public CommandRouter getCommandRouter() { return commandRouter; }

    public ItemWhitelistManager getItemWhitelistManager() { return itemWhitelistManager; }

    public RegionWhitelistManager getRegionWhitelistManager() { return regionWhitelistManager; }

    public PlayerWhitelistManager getPlayerWhitelistManager() { return playerWhitelistManager; }

    // --- Log Suppression ---

    public static boolean setSuppressLog(boolean suppress) {
        boolean prev = SUPPRESS_LOG.get();
        SUPPRESS_LOG.set(suppress);
        return prev;
    }

    public static boolean isLogSuppressed() { return SUPPRESS_LOG.get(); }
}
