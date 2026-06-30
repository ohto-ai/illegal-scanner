package com.illegalscanner.whitelist;

import com.illegalscanner.IllegalScanner;
import com.illegalscanner.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages region-based whitelisting.
 * Supports both external land plugins (WorldGuard, Residence) and built-in
 * chunk/area whitelists that require no external plugins.
 * <p>
 * Items found inside whitelisted regions, chunks, or areas are skipped during validation.
 */
public class RegionWhitelistManager {

    private final IllegalScanner plugin;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ---- Plugin-based region whitelist ----
    private List<DatabaseManager.RegionWhitelistEntry> regionCache = new ArrayList<>();
    // worldName -> set of whitelisted region names (lowercase)
    private final Map<String, Set<String>> worldRegionIndex = new HashMap<>();

    // Available land plugins
    private final Set<String> availablePlugins = new LinkedHashSet<>();
    private boolean worldGuardAvailable = false;
    private boolean residenceAvailable = false;

    // ---- Built-in chunk/area whitelist ----
    private List<DatabaseManager.AreaWhitelistEntry> areaCache = new ArrayList<>();
    // worldName (lowercase) -> list of entries in that world
    private final Map<String, List<DatabaseManager.AreaWhitelistEntry>> worldAreaIndex = new HashMap<>();

    public RegionWhitelistManager(IllegalScanner plugin) {
        this.plugin = plugin;
        detectLandPlugins();
        loadCache();
    }

    /**
     * Detect available land protection plugins.
     */
    private void detectLandPlugins() {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg != null && wg.isEnabled()) {
            worldGuardAvailable = true;
            availablePlugins.add("WorldGuard");
            plugin.getLogger().info("WorldGuard detected — region whitelist enabled.");
        }

        Plugin res = Bukkit.getPluginManager().getPlugin("Residence");
        if (res != null && res.isEnabled()) {
            residenceAvailable = true;
            availablePlugins.add("Residence");
            plugin.getLogger().info("Residence detected — region whitelist enabled.");
        }

        if (availablePlugins.isEmpty()) {
            plugin.getLogger().info("No land protection plugin detected — built-in chunk/area whitelist is available.");
        }
    }

    /**
     * Reload all whitelist caches from the database.
     */
    public void loadCache() {
        lock.writeLock().lock();
        try {
            regionCache = plugin.getDatabaseManager().loadRegionWhitelist();
            rebuildRegionIndex();
            areaCache = plugin.getDatabaseManager().loadAreaWhitelist();
            rebuildAreaIndex();
            plugin.getLogger().info("Whitelists loaded: " + regionCache.size() + " regions, "
                    + areaCache.size() + " areas/chunks");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void rebuildRegionIndex() {
        worldRegionIndex.clear();
        for (DatabaseManager.RegionWhitelistEntry entry : regionCache) {
            String worldKey = entry.worldName() != null ? entry.worldName().toLowerCase() : "*";
            worldRegionIndex.computeIfAbsent(worldKey, k -> new HashSet<>())
                    .add(entry.regionName().toLowerCase());
        }
    }

    private void rebuildAreaIndex() {
        worldAreaIndex.clear();
        for (DatabaseManager.AreaWhitelistEntry entry : areaCache) {
            String worldKey = entry.world().toLowerCase();
            worldAreaIndex.computeIfAbsent(worldKey, k -> new ArrayList<>()).add(entry);
        }
    }

    /**
     * Check if a location is inside any whitelisted region, chunk, or area.
     */
    public boolean isLocationWhitelisted(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }

        String worldName = loc.getWorld().getName().toLowerCase();

        lock.readLock().lock();
        try {
            // 1. Check built-in chunk/area whitelist (always works, no plugin needed)
            if (checkAreaWhitelist(loc, worldName)) {
                return true;
            }

            // 2. Check plugin-based region whitelist (requires WorldGuard/Residence)
            if (!availablePlugins.isEmpty()) {
                // Quick check: any regions in this world (or global regions)?
                Set<String> globalRegions = worldRegionIndex.get("*");
                Set<String> worldRegions = worldRegionIndex.get(worldName);
                if ((globalRegions != null && !globalRegions.isEmpty())
                        || (worldRegions != null && !worldRegions.isEmpty())) {
                    Set<String> candidates = new HashSet<>();
                    if (globalRegions != null) candidates.addAll(globalRegions);
                    if (worldRegions != null) candidates.addAll(worldRegions);

                    if (worldGuardAvailable && checkWorldGuard(loc, candidates)) {
                        return true;
                    }
                    if (residenceAvailable && checkResidence(loc, candidates)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking region whitelist: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
        return false;
    }

    /**
     * Check built-in area/chunk whitelist entries for the given location.
     * Y-axis is NOT checked — any Y value within the area bounds is considered inside.
     */
    private boolean checkAreaWhitelist(Location loc, String worldNameLower) {
        List<DatabaseManager.AreaWhitelistEntry> worldEntries = worldAreaIndex.get(worldNameLower);
        if (worldEntries == null || worldEntries.isEmpty()) {
            return false;
        }

        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        for (DatabaseManager.AreaWhitelistEntry entry : worldEntries) {
            if (x >= entry.minX() && x <= entry.maxX() && z >= entry.minZ() && z <= entry.maxZ()) {
                return true;
            }
        }
        return false;
    }

    // ==================== Plugin-based Region Checks ====================

    /**
     * Check WorldGuard regions at the given location.
     */
    private boolean checkWorldGuard(Location loc, Set<String> candidateNames) {
        try {
            Plugin wgPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
            if (wgPlugin == null) return false;

            Object wgInstance = wgPlugin.getClass().getMethod("getInstance").invoke(null);
            Object platform = wgInstance.getClass().getMethod("getPlatform").invoke(wgInstance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);

            Object regionManager = regionContainer.getClass()
                    .getMethod("get", org.bukkit.World.class)
                    .invoke(regionContainer, loc.getWorld());
            if (regionManager == null) return false;

            @SuppressWarnings("unchecked")
            Map<String, ?> regions = (Map<String, ?>)
                    regionManager.getClass().getMethod("getRegions").invoke(regionManager);
            if (regions == null) return false;

            for (Map.Entry<String, ?> mapEntry : regions.entrySet()) {
                if (candidateNames.contains(mapEntry.getKey().toLowerCase())) {
                    Object region = mapEntry.getValue();
                    boolean contains = (boolean) region.getClass()
                            .getMethod("contains", int.class, int.class, int.class)
                            .invoke(region, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    if (contains) return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().fine("WorldGuard region check failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Check Residence regions at the given location.
     */
    private boolean checkResidence(Location loc, Set<String> candidateNames) {
        try {
            Class<?> resApiClass = Class.forName("com.bekvon.bukkit.residence.api.ResidenceAPI");
            Object resManager = resApiClass.getMethod("getResidenceManager").invoke(null);
            Object claimedRes = resManager.getClass().getMethod("getByLoc", Location.class).invoke(resManager, loc);
            if (claimedRes != null) {
                String resName = (String) claimedRes.getClass().getMethod("getName").invoke(claimedRes);
                if (resName != null && candidateNames.contains(resName.toLowerCase())) {
                    return true;
                }
            }
        } catch (ClassNotFoundException e) {
            // Residence not installed — silently ignore
        } catch (Exception e) {
            plugin.getLogger().fine("Residence region check failed: " + e.getMessage());
        }
        return false;
    }

    // ==================== Plugin-based Region CRUD ====================

    public void addRegionEntry(String pluginName, String regionName, String worldName) {
        DatabaseManager.RegionWhitelistEntry entry = new DatabaseManager.RegionWhitelistEntry(
                -1, pluginName, regionName, worldName, System.currentTimeMillis());
        plugin.getDatabaseManager().addRegionWhitelistEntry(entry).thenAccept(id -> {
            if (id >= 0) loadCache();
        });
    }

    public int addRegionEntrySync(String pluginName, String regionName, String worldName) {
        DatabaseManager.RegionWhitelistEntry entry = new DatabaseManager.RegionWhitelistEntry(
                -1, pluginName, regionName, worldName, System.currentTimeMillis());
        try {
            int id = plugin.getDatabaseManager().addRegionWhitelistEntry(entry).get();
            if (id >= 0) loadCache();
            return id;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add region whitelist entry: " + e.getMessage());
            return -1;
        }
    }

    public void removeRegionEntry(int id) {
        plugin.getDatabaseManager().removeRegionWhitelistEntry(id).thenRun(this::loadCache);
    }

    public List<DatabaseManager.RegionWhitelistEntry> listRegionEntries() {
        lock.readLock().lock();
        try {
            return List.copyOf(regionCache);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Region Bounds Query (for view res) ====================

    /**
     * Bounding box result for a named region.
     */
    public record RegionBounds(int minX, int minZ, int maxX, int maxZ) {}

    /**
     * Get the bounding box of a region by its plugin name and region name.
     * Supports WorldGuard and Residence.
     *
     * @param pluginName "WorldGuard" or "Residence"
     * @param regionName the region name (case-insensitive)
     * @param world      the world to look in
     * @return the bounds, or null if not found / plugin not available
     */
    public RegionBounds getRegionBounds(String pluginName, String regionName, org.bukkit.World world) {
        if (pluginName.equalsIgnoreCase("WorldGuard")) {
            return getWorldGuardBounds(regionName, world);
        } else if (pluginName.equalsIgnoreCase("Residence")) {
            return getResidenceBounds(regionName, world);
        }
        return null;
    }

    private RegionBounds getWorldGuardBounds(String regionName, org.bukkit.World world) {
        try {
            Plugin wgPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
            if (wgPlugin == null) return null;

            Object wgInstance = wgPlugin.getClass().getMethod("getInstance").invoke(null);
            Object platform = wgInstance.getClass().getMethod("getPlatform").invoke(wgInstance);
            Object regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object regionManager = regionContainer.getClass()
                    .getMethod("get", org.bukkit.World.class)
                    .invoke(regionContainer, world);
            if (regionManager == null) return null;

            @SuppressWarnings("unchecked")
            Map<String, ?> regions = (Map<String, ?>)
                    regionManager.getClass().getMethod("getRegions").invoke(regionManager);
            if (regions == null) return null;

            for (Map.Entry<String, ?> entry : regions.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(regionName)) {
                    Object region = entry.getValue();
                    Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
                    Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
                    int minX = (int) min.getClass().getMethod("x").invoke(min);
                    int minZ = (int) min.getClass().getMethod("z").invoke(min);
                    int maxX = (int) max.getClass().getMethod("x").invoke(max);
                    int maxZ = (int) max.getClass().getMethod("z").invoke(max);
                    return new RegionBounds(minX, minZ, maxX, maxZ);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("WorldGuard bounds lookup failed: " + e.getMessage());
        }
        return null;
    }

    private RegionBounds getResidenceBounds(String regionName, org.bukkit.World world) {
        try {
            Class<?> resApiClass = Class.forName("com.bekvon.bukkit.residence.api.ResidenceAPI");
            Object resManager = resApiClass.getMethod("getResidenceManager").invoke(null);
            Object claimedRes = resManager.getClass().getMethod("getByName", String.class)
                    .invoke(resManager, regionName);
            if (claimedRes == null) return null;

            // Get the main area
            Object area = claimedRes.getClass().getMethod("getArea").invoke(claimedRes);
            if (area == null) return null;

            // Get low and high locations
            Object lowLoc = area.getClass().getMethod("getLowLoc").invoke(area);
            Object highLoc = area.getClass().getMethod("getHighLoc").invoke(area);

            int minX = (int) lowLoc.getClass().getMethod("getBlockX").invoke(lowLoc);
            int minZ = (int) lowLoc.getClass().getMethod("getBlockZ").invoke(lowLoc);
            int maxX = (int) highLoc.getClass().getMethod("getBlockX").invoke(highLoc);
            int maxZ = (int) highLoc.getClass().getMethod("getBlockZ").invoke(highLoc);

            return new RegionBounds(minX, minZ, maxX, maxZ);
        } catch (ClassNotFoundException e) {
            // Residence not installed
        } catch (Exception e) {
            plugin.getLogger().warning("Residence bounds lookup failed: " + e.getMessage());
        }
        return null;
    }

    // ==================== Built-in Chunk/Area CRUD ====================

    /**
     * Add a chunk whitelist entry (converts chunk coords to block coords).
     */
    public int addChunkEntrySync(String worldName, int chunkX, int chunkZ) {
        // Convert chunk coordinates to block coordinates
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        DatabaseManager.AreaWhitelistEntry entry = new DatabaseManager.AreaWhitelistEntry(
                -1, worldName, "CHUNK", minX, minZ, maxX, maxZ, System.currentTimeMillis());
        try {
            int id = plugin.getDatabaseManager().addAreaWhitelistEntry(entry).get();
            if (id >= 0) loadCache();
            return id;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add chunk whitelist entry: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Add an area whitelist entry (Y-axis unlimited, only XZ bounds matter).
     * Coordinates are normalized so minX <= maxX and minZ <= maxZ.
     */
    public int addAreaEntrySync(String worldName, int x1, int z1, int x2, int z2) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        DatabaseManager.AreaWhitelistEntry entry = new DatabaseManager.AreaWhitelistEntry(
                -1, worldName, "AREA", minX, minZ, maxX, maxZ, System.currentTimeMillis());
        try {
            int id = plugin.getDatabaseManager().addAreaWhitelistEntry(entry).get();
            if (id >= 0) loadCache();
            return id;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to add area whitelist entry: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Remove a chunk/area whitelist entry by database ID.
     */
    public void removeAreaEntry(int id) {
        plugin.getDatabaseManager().removeAreaWhitelistEntry(id).thenRun(this::loadCache);
    }

    /**
     * Get all chunk/area whitelist entries.
     */
    public List<DatabaseManager.AreaWhitelistEntry> listAreaEntries() {
        lock.readLock().lock();
        try {
            return List.copyOf(areaCache);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== Plugin Detection Info ====================

    public Set<String> getAvailablePlugins() {
        return Collections.unmodifiableSet(availablePlugins);
    }

    public boolean hasAnyPlugin() {
        return !availablePlugins.isEmpty();
    }
}
