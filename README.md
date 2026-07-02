# Illegal Scanner

[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/ohto-ai/illegal-scanner)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.6+-brightgreen)](https://papermc.io)
[![Platform](https://img.shields.io/badge/platform-Paper-purple)](https://papermc.io)
[![Language](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)

*A powerful anti-cheat utility for Paper servers — detect illegal, overpowered, and suspicious items that cannot be obtained in vanilla survival.*

**一款强大的 Paper 服务器反作弊工具 — 检测原版生存无法获得的超模/非法/可疑物品。**

---

## Features / 功能

| Feature | 功能 |
|---|---|
| **7 validation engines** covering enchantments, attributes, components, potions, books, fireworks & containers | **7 个校验引擎**：附魔、属性修饰、数据组件、药水、书册、烟花、容器 |
| **Progressive full-server scan** — low TPS impact, resumable sessions | **渐进式全服扫描** — 低 TPS 影响，支持暂停/恢复 |
| **Real-time monitor engine** — event-driven detection of newly placed items | **实时监测引擎** — 事件驱动，自动检测新放置的物品 |
| **Interactive GUI viewer** — browse violations, teleport to locations, grab item snapshots | **交互式 GUI 查看器** — 浏览违规、传送定位、提取物品快照 |
| **Text reports** — chat-based paginated reports for every scan scope | **文本报告** — 聊天框分页报告，覆盖所有扫描范围 |
| **6 whitelist types** — player, item, chunk, area, residence, world | **6 种白名单** — 玩家、物品、区块、区域、领地、世界 |
| **Watch list** — flag items by Material ID regardless of NBT | **监控列表** — 按 Material ID 标记物品，无视 NBT |
| **Hot-reloadable config & messages** — no restart needed | **配置/消息热重载** — 无需重启服务器 |
| **WorldGuard & Residence support** — scan protected regions directly | **WorldGuard / Residence 支持** — 直接扫描领地 |

---

## Quick Start / 快速开始

```bash
# 1. Drop the jar into plugins/ and restart
#    将 jar 放入 plugins/ 并重启

# 2. Check the item in your hand
#    检查手中物品
/is check item

# 3. Scan the chunk you're standing in
#    扫描当前所在区块
/is scan chunk

# 4. Start a full-server scan
#    启动全服扫描
/is scan full

# 5. View results in GUI
#    在 GUI 中查看结果
/is view full
```

---

## Requirements / 环境要求

| Requirement | 要求 |
|---|---|
| Paper 1.20.6+ | **Server / 服务端** |
| Java 21+ | **Java** |
| SQLite (bundled, no setup needed / 内置，无需配置) | **Storage / 存储** |
| WorldGuard, Residence (optional soft-depend / 可选软依赖) | **Optional / 可选** |

---

## Installation / 安装

1. Download `illegal-scanner-1.0.0.jar` from [Releases](https://github.com/ohto-ai/illegal-scanner/releases).
   从 [Releases](https://github.com/ohto-ai/illegal-scanner/releases) 下载 `illegal-scanner-1.0.0.jar`。
2. Place it in your server's `plugins/` folder.
   放入服务器的 `plugins/` 文件夹。
3. Restart the server (or run `/reload confirm`).
   重启服务器（或执行 `/reload confirm`）。
4. Configuration and database auto-generate at `plugins/illegal-scanner/`.
   配置文件和数据库自动生成于 `plugins/illegal-scanner/`。

---

## Commands / 命令

All commands use `/is` (alias: `/illegalscanner`).
所有命令使用 `/is`（别名：`/illegalscanner`）。

### Scan / 扫描 — `illegalscanner.scan`

| Command | Description |
|---|---|
| `/is scan chunk` | Scan current chunk / 扫描当前区块 |
| `/is scan player [-online\|-offline\|-all] [name]` | Scan player inventory / 扫描玩家背包 |
| `/is scan area <x1> <z1> <x2> <z2> [world]` | Scan rectangular area / 扫描矩形区域 |
| `/is scan res <region>` | Scan WorldGuard/Residence region / 扫描领地 |
| `/is scan world [world\|all_world] [loaded\|unloaded\|all]` | Scan world chunks / 扫描世界区块 |
| `/is scan full` | Progressive full-server scan / 渐进式全服扫描 |
| `/is scan pause\|resume\|stop\|restart <sessionId>` | Manage scan sessions / 管理扫描会话 |

### Check / 快速检测 — `illegalscanner.inspect`

| Command | Description |
|---|---|
| `/is check item` | Validate held item *(not recorded to DB)* / 校验手中物品（不记录） |
| `/is check player [name]` | Check player inventory + ender chest / 检查玩家背包+末影箱 |
| `/is check chunk` | Quick chunk check *(not recorded to DB)* / 快速区块检查（不记录） |

### View (GUI) / 查看（GUI） — `illegalscanner.report`

| Command | Description |
|---|---|
| `/is view chunk` | Current chunk violations / 当前区块违规 |
| `/is view player [name]` | Player violation list / 玩家违规列表 |
| `/is view world [world]` | World violation overview / 世界违规概览 |
| `/is view area <x1> <z1> <x2> <z2> [world]` | Area violation overview / 区域违规概览 |
| `/is view res <plugin> <region> [world]` | Region violation overview / 领地违规概览 |
| `/is view full` | Server-wide violation overview / 全服违规概览 |
| `/is view scan [sessionId]` | Scan session list/details / 扫描会话列表/详情 |
| `/is view record <SCAN\|MONITOR> <id>` | Single violation record / 单条违规详情 |
| `/is view item [itemHash]` | Violation by item type / 按物品类型查看 |

### Report (Text) / 报告（文本） — `illegalscanner.report`

| Command | Description |
|---|---|
| `/is report chunk` | Chunk text report / 区块文本报告 |
| `/is report player <name> [page]` | Player text report / 玩家文本报告 |
| `/is report item [hash] [page]` | Item type report / 物品类型报告 |
| `/is report scan [sessionId] [page]` | Scan session report / 扫描会话报告 |
| `/is report record <SCAN\|MONITOR> <id>` | Full violation details / 完整违规详情 |
| `/is report area <x1> <z1> <x2> <z2> [world] [page]` | Area text report / 区域文本报告 |
| `/is report res <plugin> <region> [world] [page]` | Region text report / 领地文本报告 |
| `/is report world [world] [page]` | World text report / 世界文本报告 |

### Monitor / 实时监测 — `illegalscanner.admin`

| Command | Description |
|---|---|
| `/is monitor enable\|disable\|status` | Toggle monitor engine / 开关监测引擎 |

### Config / 配置管理 — `illegalscanner.admin`

| Command | Description |
|---|---|
| `/is config reload` | Reload config, messages & monitor / 重载配置、消息和监测 |
| `/is config list` | List all config key-values / 列出所有配置项 |
| `/is config rules ...` | Tune validation rules / 动态调整检测规则 |
| `/is config monitor ...` | Configure monitor engine / 配置监测引擎参数 |
| `/is config scan ...` | Configure scan behavior / 配置扫描行为 |

### Whitelist / 白名单 — `illegalscanner.admin`

| Type | Description |
|---|---|
| `player` | Whitelist players / 白名单玩家 |
| `item` | Whitelist specific items (by hash) / 白名单特定物品（按 hash） |
| `chunk` | Whitelist chunks / 白名单区块 |
| `area` | Whitelist coordinate areas / 白名单坐标区域 |
| `res` | Whitelist WorldGuard/Residence regions / 白名单领地 |
| `world` | Whitelist entire worlds / 白名单整个世界 |

### Watch List / 监控列表 — `illegalscanner.admin`

| Command | Description |
|---|---|
| `/is watchlist add <materialID>` | Add material to watch list / 添加 Material 到监控 |
| `/is watchlist remove <materialID>` | Remove material / 移除 Material |
| `/is watchlist list\|clear` | List or clear / 列表或清空 |

### Misc / 其他

| Command | Description |
|---|---|
| `/is give <item_hash>` | Generate item from hash (debugging) / 根据 hash 生成物品（调试） |
| `/is reload` | Reload config & messages / 重载配置和消息 |
| `/is status` | Service status overview / 服务状态概览 |
| `/is history chunk\|player ...` | Historical violation records / 历史违规记录 |

---

## Permissions / 权限

| Permission | Scope | Default |
|---|---|---|
| `illegalscanner.admin` | All commands (parent node) / 所有命令 | OP |
| `illegalscanner.scan` | `/is scan ...` | OP |
| `illegalscanner.report` | `/is report`, `/is view`, `/is history` | OP |
| `illegalscanner.inspect` | `/is check ...` | OP |
| `illegalscanner.notify` | Receive admin alerts / 接收管理员通知 | OP |

---

## Validation Rules / 检测规则

The plugin uses a **whitelist philosophy**: if it can't be obtained in vanilla survival, it's flagged.
插件采用**白名单原则**：只要原版生存无法获得，就判定为非法。

### 1. Enchantment Validator / 附魔校验器

- Level exceeds vanilla maximum (e.g. Sharpness VI)
  等级超过原版上限（如锋利 VI）
- Conflicting enchantments coexist (e.g. Silk Touch + Fortune)
  互斥附魔共存（如精准采集 + 时运）
- Incompatible item type (e.g. Efficiency on a sword)
  附魔不兼容物品类型（如剑上的效率）
- Duplicate enchantment IDs
  重复附魔 ID

### 2. Attribute Validator / 属性修饰校验器

- Attribute not in vanilla whitelist for that item type
  属性不在该物品类型的原版白名单中
- Attribute value differs from vanilla
  属性值与原版不一致
- Wrong slot, UUID, or operation type
  槽位、UUID 或操作类型异常

### 3. Component Validator / 数据组件校验器

| Component | Severity | Reason |
|---|---|---|
| `unbreakable` | **ILLEGAL** | Creative/commands only / 仅创造/命令 |
| `lore` | **ILLEGAL** | No vanilla survival source / 原版生存无来源 |
| `item_name` | **ILLEGAL** | Commands only / 仅命令可覆写 |
| `custom_name` > 50 chars | **ILLEGAL** | Anvil limit / 铁砧限制 |
| `custom_name` non-italic | **ILLEGAL** | Anvil always italic / 铁砧命名永久斜体 |
| `custom_name` JSON injection | **ILLEGAL** | Contains `{"text":"..."}` patterns |
| `custom_name` zero-width chars | **SUSPICIOUS** | Unicode control characters / 零宽空格等 |
| `can_place_on` / `can_destroy` | **ILLEGAL** | Adventure mode only / 冒险模式专属 |
| `max_stack_size` overflow | **ILLEGAL** | Exceeds vanilla stack limit / 超过原版堆叠上限 |
| `max_damage` mismatch | **ILLEGAL** | Doesn't match item type / 与物品类型耐久不一致 |
| `custom_model_data` | **SUSPICIOUS** | Custom model present / 存在自定义模型数据 |
| `hide_tooltip` | **SUSPICIOUS** | May hide illegal attributes / 可能掩盖非法属性 |

### 4. Potion Validator / 药水校验器

- Amplifier exceeds max (default: level II / amplifier=1)
  效果等级超标（默认最大 II 级）
- Duration exceeds max (default: 8 minutes)
  持续时间超标（默认最大 8 分钟）
- Impossible effect combinations (Instant Damage + Instant Health, etc.)
  不可能的药水效果组合（瞬间伤害 + 瞬间治疗等）
- Unknown effect IDs / 未知效果 ID
- Custom color without effects / 自定义颜色但无对应效果

### 5. Book Validator / 书册校验器

- Pages > 100 / 页数超标
- Single page > 1000 chars / 单页过长
- Author > 16 chars / 作者名过长
- Title > 32 chars / 标题过长

### 6. Firework Validator / 烟花校验器

- Flight duration > 3 / 飞行时间超标
- Explosions > 10 / 爆炸效果超标

### 7. Container Validator / 容器校验器

- Recursively validates contents of shulker boxes & bundles
  递归检查潜影盒和收纳袋内容物
- Flags `container_loot` component (loot tables don't survive block break)
  标记 `container_loot` 组件（方块破坏后战利品表不保留）

### Severity / 严重级别

| Level | Meaning |
|---|---|
| **ILLEGAL** | Definitely unobtainable in survival — action required / 确定生存无法获得 — 需处理 |
| **SUSPICIOUS** | Unusual characteristics — manual review recommended / 可疑特征 — 建议人工审查 |
| **PASS** | Item is clean / 物品合法 |

---

## Configuration / 配置

Config file: `plugins/illegal-scanner/config.yml`. All settings can be changed via `/is config` without restart.
配置文件位于 `plugins/illegal-scanner/config.yml`，所有参数可通过 `/is config` 命令动态修改。

### Key Settings / 关键配置项

```yaml
scan:
  full_scan_enabled: true           # Auto-start full scan on boot / 启动时自动全扫
  full_scan_chunks_per_tick: 1      # Chunks per tick (increase = faster, higher TPS cost)
                                    # 每 tick 区块数（增加 = 更快，更高 TPS 开销）
  thread_pool_size: 4               # Thread pool for parallel processing / 线程池大小
  scan_player_inventories: true     # Scan player inventories / 扫描玩家背包
  scan_player_enderchests: true     # Scan player ender chests / 扫描末影箱
  scan_shulker_boxes: true          # Scan shulker box contents / 扫描潜影盒
  scan_item_frames: true            # Scan item frames / 扫描物品展示框
  scan_armor_stands: true           # Scan armor stands / 扫描盔甲架

validation:
  enforce_max_levels: true          # Enforce enchant max levels / 附魔等级限制
  enforce_conflicts: true           # Enforce enchant conflicts / 附魔冲突检测
  enforce_item_compatibility: true  # Enforce enchant compatibility / 附魔兼容性检测
  flag_unbreakable: true            # Flag unbreakable items / 标记不可破坏物品
  flag_lore_presence: true          # Flag lore-bearing items / 标记含 lore 物品
  flag_item_name_presence: true     # Flag renamed items / 标记被覆写名称的物品
  custom_name_max_length: 50        # Max custom name length / 铁砧命名最大长度
  enforce_stack_limits: true        # Enforce stack limits / 堆叠限制检测
  enforce_attribute_limits: true    # Enforce attribute limits / 属性修饰限制
  potion_max_amplifier: 1           # Max potion amplifier / 药水最大效果等级
  potion_max_duration_ticks: 9600   # Max potion duration / 药水最大持续时间
  potion_max_effects: 5             # Max effects per potion / 单药水最大效果数
  book_max_pages: 100               # Max book pages / 书册最大页数
  firework_max_flight: 3            # Max firework flight / 烟花最大飞行时间
  firework_max_explosions: 10       # Max firework explosions / 烟花最大爆炸数

monitor:
  enabled: false                    # Enable real-time monitor / 实时监测开关
  interval_seconds: 5               # Scan interval / 扫描间隔
  flush_seconds: 300                # Dedup window / 去重窗口
  retention_days: 7                 # Data retention / 数据保留天数

alerts:
  console_log: true                 # Log violations to console / 控制台输出违规
  console_log_severity: "ILLEGAL"   # ILLEGAL | SUSPICIOUS | ALL
  admin_notify: false               # Notify online admins / 通知在线管理员
```

---

## Database / 数据库

SQLite database at `plugins/illegal-scanner/items.db`. Useful queries:
数据库位于 `plugins/illegal-scanner/items.db`，常用查询：

```sql
-- Active illegal items / 活跃非法物品
SELECT id, item_type, player_name, severity
FROM illegal_items WHERE resolved = 0 ORDER BY last_detected DESC;

-- Count by player / 按玩家统计
SELECT player_name, COUNT(*) as cnt
FROM illegal_items WHERE resolved = 0 AND location_type = 'PLAYER'
GROUP BY player_name ORDER BY cnt DESC;

-- Item snapshot for restoration / 物品快照（用于还原）
SELECT item_snapshot FROM illegal_items WHERE id = 1;
```

---

## FAQ / 常见问题

**Q: Does this affect server TPS? / 会影响 TPS 吗？**
A: Default scan rate (1 chunk/tick) has <5% TPS impact. Single-item validation in event listeners takes <1ms. Adjust `full_scan_chunks_per_tick` and `thread_pool_size` for your hardware.
默认扫描速率（1 区块/tick）TPS 影响 <5%。事件监听的单物品校验 <1ms。可根据硬件调整配置。

**Q: How are offline players scanned? / 离线玩家如何扫描？**
A: The plugin reads `world/playerdata/<uuid>.dat` NBT files. Use `/is scan player -offline <name>` for on-demand scanning.
插件读取 `world/playerdata/<uuid>.dat` 文件。使用 `/is scan player -offline <name>` 按需扫描。

**Q: Can I restore items from the snapshot? / 能从快照恢复物品吗？**
A: Yes! Use `/is give <item_hash>` to recreate the exact item from its stored snapshot.
可以！使用 `/is give <item_hash>` 从存储的快照完整还原物品。

**Q: How do I prevent false positives from legitimate plugin items? / 如何避免合法插件物品误报？**
A: Four options / 四种方式：
1. Tune rules dynamically: `/is config rules ...` / 动态调整规则
2. Whitelist specific items: `/is whitelist item add` / 白名单特定物品
3. Whitelist players: `/is whitelist player add <name>` / 白名单玩家
4. Use watch list to only monitor specific materials / 用监控列表仅追踪特定物品

**Q: Watch list vs item whitelist? / 监控列表和物品白名单的区别？**
A: Whitelist **skips** a specific hash entirely. Watch list **flags** all items of a Material regardless of NBT — use it to track distribution of specific item types.
白名单让特定 hash **完全跳过**检查。监控列表**标记**所有匹配 Material 的物品（无视 NBT），用于追踪特定物品类型的分布。

**Q: Does it support BungeeCord/Velocity? / 支持 BungeeCord/Velocity 吗？**
A: This is a Bukkit/Paper plugin — install on each backend server. Not supported on proxy.
这是 Bukkit/Paper 插件，安装于每个子服即可，不支持代理端。

**Q: Supported Minecraft versions? / 支持的 Minecraft 版本？**
A: Built targeting Paper 1.20.6. May work on newer versions but is untested.
编译目标为 Paper 1.20.6，更新版本可能可用但未测试。

---

## Links / 链接

- **Source Code / 源码**: [GitHub](https://github.com/ohto-ai/illegal-scanner)
- **Issues / 问题反馈**: [GitHub Issues](https://github.com/ohto-ai/illegal-scanner/issues)
- **Releases / 下载**: [GitHub Releases](https://github.com/ohto-ai/illegal-scanner/releases)

---

## License / 许可证

All rights reserved. See the repository for license details.
保留所有权利。详见仓库许可证。
