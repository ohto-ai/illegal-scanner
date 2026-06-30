旧版数据库不用考虑迁移，我可以直接删除重建，也就是从0开始

1. 命令重做, 对应实现也重做
/es
├── scan                                 # 执行扫描并记录
│   ├── chunk
│   ├── player [player_name]
│   ├── area <x1> <z1> <x2> <z2> [world_name]
│   ├── res [res_name]
│   ├── world [world_name]
│   └── full
│
├── check                                # 快速检测，不记录
│   ├── item
│   ├── player [name]
│   └── chunk
│
├── view                                 # GUI形式查看当前综合结果(scan+monitor)
│   ├── chunk
│   ├── player [player_name]
│   ├── area <x1> <z1> <x2> <z2> [world_name]
│   ├── res [res_name]
│   ├── world [world_name]
│   ├── full
│   ├── scan [scan_id]
│   └── record <record_id>
│
├── report                               # 文本形式查看当前综合结果(scan+monitor)
│   ├── chunk
│   ├── player [player_name]
│   ├── area <x1> <z1> <x2> <z2> [world_name]
│   ├── res [res_name]
│   ├── world [world_name]
│   ├── full
│   ├── scan [scan_id]
│   └── record <record_id>
│
├── history                              # 查看历史记录
│   ├── chunk
│   └── player [player_name]
│
├── monitor                              # 实时监测控制
│   ├── enable
│   ├── disable
│   └── status
│
├── config                               # 配置管理
│   ├── reload
│   ├── list
│   ├── rules
│   │   ├── enchant
│   │   │   ├── conflict
│   │   │   │   ├── enable
│   │   │   │   ├── disable
│   │   │   │   └── status
│   │   │   ├── level
│   │   │   │   ├── enable
│   │   │   │   ├── disable
│   │   │   │   ├── status
│   │   │   │   ├── set <enchant> <level>
│   │   │   │   └── reset <enchant>
│   │   │   └── compatibility
│   │   │       ├── enable
│   │   │       ├── disable
│   │   │       ├── status
│   │   │       ├── add <enchant> <item_type>
│   │   │       └── remove <enchant> <item_type>
│   │   ├── potion
│   │   │   ├── enable
│   │   │   ├── disable
│   │   │   ├── status
│   │   │   ├── level
│   │   │   │   ├── set <effect> <max_level>
│   │   │   │   ├── reset <effect>
│   │   │   │   └── list
│   │   │   └── effects
│   │   │       └── max <number>
│   │   ├── stack
│   │   │   ├── enable
│   │   │   ├── disable
│   │   │   ├── status
│   │   │   ├── auto_fix
│   │   │   │   ├── enable
│   │   │   │   └── disable
│   │   │   ├── set <item_type> <max_stack>
│   │   │   ├── reset <item_type>
│   │   │   ├── default <number>
│   │   │   └── list
│   │   ├── attribute
│   │   │   ├── enable
│   │   │   ├── disable
│   │   │   ├── status
│   │   │   ├── mode
│   │   │   │   ├── all
│   │   │   │   └── threshold
│   │   │   ├── set <attribute> <max_value>
│   │   │   ├── reset <attribute>
│   │   │   └── list
│   │   └── unbreakable
│   │       ├── enable
│   │       ├── disable
│   │       ├── status
│   │       ├── action
│   │       │   ├── remove
│   │       │   └── flag
│   │       └── restore
│   │           ├── enable
│   │           └── disable
│   ├── monitor
│   │   ├── enable
│   │   ├── disable
│   │   ├── status
│   │   ├── interval <seconds>
│   │   ├── flush <seconds>
│   │   ├── retention <days>
│   │   └── events
│   │       ├── list
│   │       ├── enable <event>
│   │       └── disable <event>
│   └── scan
│       ├── max_area <chunks>
│       ├── thread_pool <size>
│       └── full_console_only
│           ├── enable
│           └── disable
│
└── whitelist                            # 白名单管理
    ├── player
    │   ├── add <name>
    │   ├── remove <name>
    │   ├── list
    │   └── clear
    ├── item
    │   ├── add                              # 添加手上物品到白名单
    │   ├── gui                              # 通过GUI容器菜单选择添加
    │   ├── remove                           # 移除手上物品（若在白名单中）
    │   ├── list
    │   └── clear
    ├── chunk
    │   ├── add                              # 添加当前区块
    │   ├── remove <id>                      # 通过ID移除
    │   ├── list
    │   └── clear
    ├── area
    │   ├── add <x1> <z1> <x2> <z2> [world_name]
    │   ├── remove <id>
    │   ├── list
    │   └── clear
    ├── res
    │   ├── add [name]
    │   ├── remove [name]
    │   ├── list
    │   └── clear
    └── world
        ├── add [name]
        ├── remove [name]
        ├── list
        └── clear
2. 由于大多数武器都有重复的，因此你需要给这些item分配一个id，各种结果直接索引id而非直接使用原始nbt.

3. 我找别的ai设计了一下scan与monitor的逻辑，请你参考，但是不要直接使用它的代码，仅作思路参考。

# MC违禁物品扫描插件 - Scan与Monitor数据汇总设计

> 本文档说明如何将手动扫描（scan）与自动监测（monitor）的数据统一汇总，以支持查询特定玩家和特定区块的当前违禁状况。


## 一、核心设计目标

| 查询场景 | 数据来源 | 展示要求 |
| :--- | :--- | :--- |
| 查看玩家当前违禁状况 | scan记录 + monitor记录 | 展示该玩家所有违禁物品，以及所在的区块位置 |
| 查看区块当前违禁状况 | scan记录 + monitor记录 | 展示该区块内所有违禁物品，以及持有人信息 |
| 判断某玩家在某区块是否有违禁 | scan记录 + monitor记录 | 快速判定（True/False），用于实时监测过滤 |

**关键原则：** `view/report` 查询时，数据源是 **scan_records + monitor_records 的并集**，两者同等对待。


## 二、数据模型设计

### 2.1 统一违禁条目模型

```java
public class ViolationEntry {
    // 记录元信息
    private int id;                    // 记录ID
    private String source;             // "SCAN" 或 "MONITOR"
    private String scanType;           // "chunk" | "player" | "area" | "full" | "monitor_event"
    private Timestamp scanTime;
    
    // 位置信息
    private String world;
    private int chunkX;
    private int chunkZ;
    
    // 玩家信息
    private UUID playerUuid;
    private String playerName;
    
    // 违禁物品信息
    private Material itemType;
    private int slot;                  // 背包格子/末影箱/容器槽位
    private String container;          // "inventory" | "enderchest" | "chest" | "shulker" | etc.
    private Location containerLocation; // 容器所在位置（箱子等）
    private List<ViolationDetail> details;  // 违禁原因列表
    private String itemNbtHash;        // NBT哈希，用于去重
}
```

### 2.2 统一查询结果模型

```java
public class UnifiedScanResult {
    private String world;
    private int chunkX;
    private int chunkZ;
    private Timestamp lastScanTime;      // 最近一次扫描时间（scan或monitor）
    private String lastSource;           // "SCAN" 或 "MONITOR"
    private int lastRecordId;            // 对应的记录ID
    
    private List<ViolationEntry> violations;  // 所有违禁物品明细
    
    // 按玩家聚合
    private Map<UUID, PlayerViolationSummary> playerSummary;
    
    // 统计信息
    private int totalViolations;
    private int affectedPlayers;
    
    // 区块检测状态
    private BlockStatus status;          // CLEAN | HAS_VIOLATIONS | WHITELISTED | EXEMPT
}

public class PlayerViolationSummary {
    private UUID playerUuid;
    private String playerName;
    private int violationCount;
    private List<ViolationEntry> entries;
    private Timestamp firstDetected;
    private Timestamp lastDetected;
}
```


## 三、查询服务层设计

### 3.1 统一查询服务接口

```java
public interface UnifiedQueryService {
    
    // 查询特定区块的当前违禁状况
    UnifiedScanResult getChunkStatus(String world, int chunkX, int chunkZ);
    
    // 查询特定玩家的违禁状况
    PlayerUnifiedResult getPlayerStatus(UUID playerUuid);
    
    // 查询特定玩家在特定区块的违禁状况
    List<ViolationEntry> getPlayerViolationsInChunk(UUID playerUuid, String world, int chunkX, int chunkZ);
    
    // 批量查询区块违禁状况（用于GUI分页）
    List<UnifiedScanResult> getChunkStatusBatch(String world, List<ChunkKey> chunks);
    
    // 快速判断区块是否有违禁
    boolean hasViolations(String world, int chunkX, int chunkZ);
}
```

### 3.2 核心查询逻辑实现

```java
public class UnifiedQueryServiceImpl implements UnifiedQueryService {
    
    private final ScanRecordDao scanDao;
    private final MonitorRecordDao monitorDao;
    private final LatestCache latestCache;
    private final WhitelistService whitelistService;
    
    @Override
    public UnifiedScanResult getChunkStatus(String world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world, chunkX, chunkZ);
        
        // 1. 检查白名单
        if (whitelistService.isChunkWhitelisted(world, chunkX, chunkZ)) {
            return UnifiedScanResult.whitelisted(key);
        }
        
        // 2. 从内存缓存获取最新汇总结果（O(1)）
        UnifiedScanResult cached = latestCache.getChunkStatus(key);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }
        
        // 3. 缓存未命中，从数据库查询
        UnifiedScanResult result = buildFromDatabase(world, chunkX, chunkZ);
        
        // 4. 写入缓存（TTL 60秒）
        latestCache.putChunkStatus(key, result, 60, TimeUnit.SECONDS);
        
        return result;
    }
    
    private UnifiedScanResult buildFromDatabase(String world, int chunkX, int chunkZ) {
        UnifiedScanResult result = new UnifiedScanResult();
        result.setWorld(world);
        result.setChunkX(chunkX);
        result.setChunkZ(chunkZ);
        
        List<ViolationEntry> allViolations = new ArrayList<>();
        Map<UUID, PlayerViolationSummary> summaryMap = new HashMap<>();
        
        // 3.1 查询手动扫描记录
        List<ScanRecord> scanRecords = scanDao.findByChunk(world, chunkX, chunkZ);
        for (ScanRecord record : scanRecords) {
            List<ViolationEntry> entries = convertScanRecord(record);
            allViolations.addAll(entries);
            aggregateByPlayer(summaryMap, entries);
        }
        
        // 3.2 查询自动监测记录
        List<MonitorRecord> monitorRecords = monitorDao.findByChunk(world, chunkX, chunkZ);
        for (MonitorRecord record : monitorRecords) {
            List<ViolationEntry> entries = convertMonitorRecord(record);
            allViolations.addAll(entries);
            aggregateByPlayer(summaryMap, entries);
        }
        
        // 3.3 去重：同一物品NBT在5分钟内重复出现只保留最新一条
        allViolations = deduplicateViolations(allViolations);
        
        // 3.4 获取最新一条记录的时间戳
        Timestamp latestTime = getLatestTimestamp(scanRecords, monitorRecords);
        String latestSource = getLatestSource(scanRecords, monitorRecords);
        
        result.setLastScanTime(latestTime);
        result.setLastSource(latestSource);
        result.setViolations(allViolations);
        result.setPlayerSummary(summaryMap);
        result.setTotalViolations(allViolations.size());
        result.setAffectedPlayers(summaryMap.size());
        result.setStatus(allViolations.isEmpty() ? BlockStatus.CLEAN : BlockStatus.HAS_VIOLATIONS);
        
        return result;
    }
    
    @Override
    public PlayerUnifiedResult getPlayerStatus(UUID playerUuid) {
        PlayerUnifiedResult result = new PlayerUnifiedResult();
        result.setPlayerUuid(playerUuid);
        
        List<ViolationEntry> allViolations = new ArrayList<>();
        
        // 从scan_records查询该玩家的违禁记录
        List<ScanRecord> scanRecords = scanDao.findByPlayer(playerUuid);
        for (ScanRecord record : scanRecords) {
            allViolations.addAll(convertScanRecord(record));
        }
        
        // 从monitor_records查询该玩家的违禁记录
        List<MonitorRecord> monitorRecords = monitorDao.findByPlayer(playerUuid);
        for (MonitorRecord record : monitorRecords) {
            allViolations.addAll(convertMonitorRecord(record));
        }
        
        // 按区块聚合
        Map<ChunkKey, List<ViolationEntry>> byChunk = allViolations.stream()
            .collect(Collectors.groupingBy(v -> new ChunkKey(v.getWorld(), v.getChunkX(), v.getChunkZ())));
        
        result.setViolations(allViolations);
        result.setByChunk(byChunk);
        result.setTotalViolations(allViolations.size());
        result.setAffectedChunks(byChunk.size());
        
        return result;
    }
    
    @Override
    public List<ViolationEntry> getPlayerViolationsInChunk(UUID playerUuid, String world, int chunkX, int chunkZ) {
        List<ViolationEntry> result = new ArrayList<>();
        
        // 1. 从scan_records查询
        List<ScanRecord> scanRecords = scanDao.findByPlayerAndChunk(playerUuid, world, chunkX, chunkZ);
        for (ScanRecord record : scanRecords) {
            result.addAll(convertScanRecord(record));
        }
        
        // 2. 从monitor_records查询
        List<MonitorRecord> monitorRecords = monitorDao.findByPlayerAndChunk(playerUuid, world, chunkX, chunkZ);
        for (MonitorRecord record : monitorRecords) {
            result.addAll(convertMonitorRecord(record));
        }
        
        // 3. 去重
        return deduplicateViolations(result);
    }
}
```


## 四、数据库查询SQL设计

### 4.1 查询区块汇总（合并两张表）

```sql
-- 获取区块内所有违禁记录（scan + monitor 合并）
SELECT 
    id,
    'SCAN' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
FROM scan_records
WHERE world = ? AND chunk_x = ? AND chunk_z = ?
    AND scan_time > DATE_SUB(NOW(), INTERVAL 7 DAY)  -- 只查最近7天

UNION ALL

SELECT 
    monitor_id AS id,
    'MONITOR' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
FROM monitor_records
WHERE world = ? AND chunk_x = ? AND chunk_z = ?
    AND scan_time > DATE_SUB(NOW(), INTERVAL 7 DAY)

ORDER BY scan_time DESC;
```

### 4.2 查询玩家汇总（合并两张表）

```sql
SELECT 
    id,
    'SCAN' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
FROM scan_records
WHERE player_uuid = ?

UNION ALL

SELECT 
    monitor_id AS id,
    'MONITOR' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
FROM monitor_records
WHERE player_uuid = ?

ORDER BY scan_time DESC
LIMIT 100;  -- 分页限制
```

### 4.3 查询玩家在特定区块的违禁

```sql
SELECT 
    id,
    'SCAN' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
FROM scan_records
WHERE player_uuid = ? AND world = ? AND chunk_x = ? AND chunk_z = ?

UNION ALL

SELECT 
    monitor_id AS id,
    'MONITOR' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
FROM monitor_records
WHERE player_uuid = ? AND world = ? AND chunk_x = ? AND chunk_z = ?

ORDER BY scan_time DESC;
```

### 4.4 查询区块最新状态（快速判定）

```sql
-- 查scan_records最新一条
SELECT scan_time FROM scan_records
WHERE world = ? AND chunk_x = ? AND chunk_z = ?
ORDER BY scan_time DESC LIMIT 1

UNION ALL

-- 查monitor_records最新一条
SELECT scan_time FROM monitor_records
WHERE world = ? AND chunk_x = ? AND chunk_z = ?
ORDER BY scan_time DESC LIMIT 1

ORDER BY scan_time DESC LIMIT 1;
```


## 五、内存缓存设计

### 5.1 缓存结构

```java
public class LatestCache {
    
    // 区块 → 最新汇总结果（scan + monitor 合并）
    private final Cache<ChunkKey, UnifiedScanResult> chunkStatusCache;
    
    // 玩家UUID → 最新汇总结果
    private final Cache<UUID, PlayerUnifiedResult> playerStatusCache;
    
    // 区块 → 最新记录ID（快速判断是否有新数据）
    private final Map<ChunkKey, LatestRecordInfo> latestRecordMap;
    
    public LatestCache() {
        this.chunkStatusCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();
            
        this.playerStatusCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();
            
        this.latestRecordMap = new ConcurrentHashMap<>();
    }
    
    // 更新缓存（scan和monitor写入时调用）
    public void invalidateChunk(String world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world, chunkX, chunkZ);
        chunkStatusCache.invalidate(key);
        // 不删除latestRecordMap，用于判断是否需要重建
    }
    
    // 获取区块状态
    public UnifiedScanResult getChunkStatus(ChunkKey key) {
        return chunkStatusCache.getIfPresent(key);
    }
    
    // 更新最新记录信息（scan/monitor写入时调用）
    public void updateLatestRecord(ChunkKey key, int recordId, String source, Timestamp time) {
        LatestRecordInfo info = new LatestRecordInfo(recordId, source, time);
        latestRecordMap.put(key, info);
        // 使缓存失效，下次查询重建
        chunkStatusCache.invalidate(key);
    }
}
```

### 5.2 缓存更新时机

```
┌─────────────────────────────────────────────────────────────────┐
│                    Scan/Monitor 写入流程                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐     ┌──────────────┐                         │
│  │ /es scan ... │     │  Monitor事件  │                         │
│  └──────┬───────┘     └──────┬───────┘                         │
│         │                    │                                  │
│         └────────┬───────────┘                                  │
│                  ▼                                              │
│         ┌─────────────────────┐                                │
│         │ 写入数据库          │                                │
│         │ (scan_records 或    │                                │
│         │  monitor_records)   │                                │
│         └──────────┬──────────┘                                │
│                    ▼                                            │
│         ┌─────────────────────┐                                │
│         │ 更新内存缓存        │  ◄─── 关键步骤                 │
│         │ latestRecordMap     │                                │
│         │ 使 chunkStatusCache │                                │
│         │ 失效                │                                │
│         └─────────────────────┘                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```


## 六、指令查询实现

### 6.1 `/es view player` - 查看玩家违禁状况

```java
public void viewPlayer(CommandSender sender, String playerName) {
    Player target = Bukkit.getPlayer(playerName);
    if (target == null) {
        sender.sendMessage("§c玩家不在线");
        return;
    }
    
    // 从统一查询服务获取
    PlayerUnifiedResult result = queryService.getPlayerStatus(target.getUniqueId());
    
    // 构建GUI
    GuiBuilder builder = new GuiBuilder("§c违禁物品 - " + target.getName(), 54);
    
    // 左上角显示统计信息
    builder.setItem(0, new ItemBuilder(Material.BOOK)
        .setName("§6统计信息")
        .addLore("§7违禁物品总数: §c" + result.getTotalViolations())
        .addLore("§7涉及区块数: §c" + result.getAffectedChunks())
        .build());
    
    // 按区块分组显示
    int slot = 9;
    for (Map.Entry<ChunkKey, List<ViolationEntry>> entry : result.getByChunk().entrySet()) {
        ChunkKey key = entry.getKey();
        List<ViolationEntry> entries = entry.getValue();
        
        builder.setItem(slot++, new ItemBuilder(Material.MAP)
            .setName("§6区块: " + key)
            .addLore("§7违禁物品: §c" + entries.size() + " 件")
            .addLore("§7点击查看详情")
            .build());
    }
    
    // 添加翻页按钮
    builder.addPagination();
    
    sender.openInventory(builder.build());
}
```

### 6.2 `/es view chunk` - 查看区块违禁状况

```java
public void viewChunk(Player player) {
    World world = player.getWorld();
    int chunkX = player.getLocation().getChunk().getX();
    int chunkZ = player.getLocation().getChunk().getZ();
    
    // 从统一查询服务获取
    UnifiedScanResult result = queryService.getChunkStatus(world.getName(), chunkX, chunkZ);
    
    if (result.getStatus() == BlockStatus.WHITELISTED) {
        player.sendMessage("§e该区块已在白名单中，跳过检测");
        return;
    }
    
    // 构建GUI
    GuiBuilder builder = new GuiBuilder("§c区块违禁物品 - " + chunkX + ", " + chunkZ, 54);
    
    // 顶部显示统计信息
    builder.setItem(0, new ItemBuilder(Material.BOOK)
        .setName("§6扫描信息")
        .addLore("§7最后扫描: §f" + result.getLastScanTime())
        .addLore("§7来源: " + (result.getLastSource().equals("SCAN") ? "§b手动扫描" : "§e自动监测"))
        .addLore("§7违禁总数: §c" + result.getTotalViolations())
        .addLore("§7涉及玩家: §c" + result.getAffectedPlayers())
        .build());
    
    // 显示每个玩家的违禁物品
    int slot = 9;
    for (Map.Entry<UUID, PlayerViolationSummary> entry : result.getPlayerSummary().entrySet()) {
        PlayerViolationSummary summary = entry.getValue();
        
        builder.setItem(slot++, new ItemBuilder(Material.PLAYER_HEAD)
            .setName("§6" + summary.getPlayerName())
            .addLore("§7违禁物品: §c" + summary.getViolationCount() + " 件")
            .addLore("§7首次发现: §f" + summary.getFirstDetected())
            .addLore("§7点击查看详情")
            .build());
    }
    
    player.openInventory(builder.build());
}
```

### 6.3 `/es report player` - 文本报告

```java
public void reportPlayer(CommandSender sender, String playerName) {
    Player target = Bukkit.getPlayer(playerName);
    if (target == null) {
        sender.sendMessage("§c玩家不在线");
        return;
    }
    
    PlayerUnifiedResult result = queryService.getPlayerStatus(target.getUniqueId());
    
    StringBuilder sb = new StringBuilder();
    sb.append("§6===== 玩家违禁报告 =====\n");
    sb.append("§7玩家: §f").append(target.getName()).append("\n");
    sb.append("§7违禁总数: §c").append(result.getTotalViolations()).append("\n");
    sb.append("§7涉及区块: §c").append(result.getAffectedChunks()).append("\n\n");
    
    int index = 1;
    for (ViolationEntry entry : result.getViolations()) {
        sb.append("§7[").append(index++).append("] ")
          .append(entry.getItemType().name()).append("\n");
        sb.append("    §7位置: §f")
          .append(entry.getContainer()).append(" ")
          .append(entry.getContainerLocation() != null ? entry.getContainerLocation() : "")
          .append("\n");
        sb.append("    §7区块: §f")
          .append(entry.getWorld()).append(" (")
          .append(entry.getChunkX()).append(", ")
          .append(entry.getChunkZ()).append(")\n");
        sb.append("    §7违禁原因: §c")
          .append(String.join(", ", entry.getDetails())).append("\n");
        sb.append("    §7来源: ")
          .append(entry.getSource().equals("SCAN") ? "§b手动扫描" : "§e自动监测")
          .append("\n");
    }
    
    sender.sendMessage(sb.toString());
}
```


## 七、数据聚合与去重策略

### 7.1 去重规则

同一违禁物品可能被多次扫描（同一区块被scan和monitor都触发），需要去重：

```java
public List<ViolationEntry> deduplicateViolations(List<ViolationEntry> entries) {
    if (entries.size() <= 1) return entries;
    
    // 按 (玩家UUID + 物品NBT哈希 + 容器位置) 分组
    Map<String, List<ViolationEntry>> groups = entries.stream()
        .collect(Collectors.groupingBy(e -> 
            e.getPlayerUuid().toString() + "|" +
            e.getItemNbtHash() + "|" +
            (e.getContainerLocation() != null ? e.getContainerLocation().toString() : e.getContainer())
        ));
    
    List<ViolationEntry> result = new ArrayList<>();
    for (List<ViolationEntry> group : groups.values()) {
        // 取最新的一条
        group.sort((a, b) -> b.getScanTime().compareTo(a.getScanTime()));
        result.add(group.get(0));
        
        // 如果有多条，在详情中记录检测历史
        if (group.size() > 1) {
            result.get(result.size() - 1).addMeta("detected_count", group.size());
            result.get(result.size() - 1).addMeta("first_detected", group.get(group.size() - 1).getScanTime());
        }
    }
    
    return result;
}
```

### 7.2 聚合查询优化（索引建议）

```sql
-- scan_records 表索引
CREATE INDEX idx_scan_chunk ON scan_records(world, chunk_x, chunk_z, scan_time DESC);
CREATE INDEX idx_scan_player ON scan_records(player_uuid, scan_time DESC);
CREATE INDEX idx_scan_player_chunk ON scan_records(player_uuid, world, chunk_x, chunk_z, scan_time DESC);

-- monitor_records 表索引
CREATE INDEX idx_monitor_chunk ON monitor_records(world, chunk_x, chunk_z, scan_time DESC);
CREATE INDEX idx_monitor_player ON monitor_records(player_uuid, scan_time DESC);
CREATE INDEX idx_monitor_player_chunk ON monitor_records(player_uuid, world, chunk_x, chunk_z, scan_time DESC);
```


## 八、完整查询流程示例

### 示例：玩家 "Notch" 在区块 (4, -3) 的违禁状况

```
管理员执行: /es view player Notch
                    │
                    ▼
         UnifiedQueryService
         .getPlayerStatus(UUID)
                    │
                    ▼
         ┌─────────┴─────────┐
         │                   │
         ▼                   ▼
  scan_records        monitor_records
  查 player_uuid      查 player_uuid
         │                   │
         └─────────┬─────────┘
                   ▼
         合并结果，按区块聚合
                   │
                   ▼
       返回 PlayerUnifiedResult
       包含: 所有违禁物品明细
       包含: 按区块分组
       包含: (4, -3) 区块有 3 件违禁
                   │
                   ▼
        GUI显示: Notch 的违禁物品列表
        区块 (4, -3): 3件
        区块 (5, -2): 1件
        区块 (10, 8): 2件
```

### 数据库查询示例（合并）

```sql
-- 查询 Notch 在区块 (4, -3) 的所有违禁记录
(SELECT 
    scan_id AS id,
    'SCAN' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
 FROM scan_records
 WHERE player_uuid = '069a79f4-44e9-4726-a5be-fca90e38aaf5'
   AND world = 'world'
   AND chunk_x = 4
   AND chunk_z = -3)

UNION ALL

(SELECT 
    monitor_id AS id,
    'MONITOR' AS source,
    world,
    chunk_x,
    chunk_z,
    player_uuid,
    scan_time,
    item_data,
    item_count
 FROM monitor_records
 WHERE player_uuid = '069a79f4-44e9-4726-a5be-fca90e38aaf5'
   AND world = 'world'
   AND chunk_x = 4
   AND chunk_z = -3)

ORDER BY scan_time DESC;

-- 预期返回:
-- id=124, source=SCAN,    时间=14:23:01, item_data={钻石剑: 锋利X}
-- id=8923, source=MONITOR, 时间=14:20:15, item_data={钻石剑: 锋利X}
-- id=98, source=SCAN,    时间=12:10:15, item_data={钻石头盔: 保护V+火焰保护IV}
-- 去重后保留最新2条 (id=124 和 id=8923 去重，保留 id=124)
```
