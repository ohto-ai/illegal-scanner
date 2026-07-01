# illegal-scanner 使用文档

> **版本**: 1.0.0  
> **适用**: Paper 1.21+  
> **功能**: 检测并记录服务器中所有原版生存无法获得的超模/非法物品

---

## 目录

1. [安装](#1-安装)
2. [快速开始](#2-快速开始)
3. [命令参考](#3-命令参考)
4. [权限节点](#4-权限节点)
5. [配置说明](#5-配置说明)
6. [检测规则详解](#6-检测规则详解)
7. [数据库结构](#7-数据库结构)
8. [常见场景](#8-常见场景)
9. [FAQ](#9-faq)

---

## 1. 安装

### 环境要求

- **服务端**: Paper 1.21+（1.21.1 推荐）
- **Java**: JDK 21+
- **存储**: SQLite（无需额外安装）

### 安装步骤

1. 将 `illegal-scanner-1.0.0.jar` 放入服务器的 `plugins/` 目录
2. 重启服务器（或执行 `/reload confirm`）
3. 插件会自动生成配置文件 `plugins/illegal-scanner/config.yml`
4. 插件会自动创建数据库 `plugins/illegal-scanner/items.db`

### 验证安装

在控制台看到以下日志即表示安装成功：

```
illegal-scanner v1.0.0
Configuration loaded.
Database initialized.
Validation engine initialized with 7 validators.
Event listeners registered.
Commands registered.
```

---

## 2. 快速开始

### 2.1 检查手中物品

手持任意物品，执行：

```
/is item check
```

如果物品合法：
> ✅ `Item is clean — no violations found.`

如果物品非法：
> ❌ 列出所有违规项（附魔超标、互斥附魔、属性修饰异常等）

### 2.2 查看物品数据组件

```
/is item info
```

显示物品的完整数据组件列表（unbreakable、lore、custom_name、attribute_modifiers 等）。

### 2.3 扫描玩家

```
/is scan player Steve
```

扫描 Steve 的背包、盔甲、副手、末影箱，检测到的非法物品自动入库。

### 2.4 扫描当前区块

站在目标区块内执行：

```
/is scan chunk
```

### 2.5 启动全服渐进扫描

```
/is scan full
```

扫描流程：

| 阶段 | 内容 |
|---|---|
| Phase 1 | 立即扫描所有在线玩家 |
| Phase 2 | 立即扫描所有已加载区块 |
| Phase 3 | 后台扫描离线玩家数据文件 |
| Phase 4 | 后台逐区块扫描未加载区块（每 tick 1 区块） |

### 2.6 查看进度

```
/is status
```

输出示例：
> `Full scan running: 1247/8932 chunks scanned, 15 items flagged, 342 queued`

### 2.7 查看报告

```
/is report
```

---

## 3. 命令参考

### `/es` 主命令（别名: `/illegalscanner`）

#### 扫描类

| 命令 | 说明 |
|---|---|
| `/is scan player <玩家名>` | 扫描指定玩家（在线或离线） |
| `/is scan chunk` | 扫描当前所在区块的所有容器 |
| `/is scan world [世界名]` | 扫描指定世界的已加载区块 |
| `/is scan full [世界名]` | 启动全量渐进扫描（不指定则扫描所有世界） |
| `/is scan cancel` | 取消正在进行的扫描 |

#### 报告类

| 命令 | 说明 |
|---|---|
| `/is report` | 扫描统计摘要 |
| `/is report stats` | 同上 |
| `/is report detail <ID>` | 查看指定记录的完整详情 |
| `/is report player <玩家名>` | 查看指定玩家的所有违规物品 |

#### 物品检查

| 命令 | 说明 |
|---|---|
| `/is item check` | 校验手中物品的合法性 |
| `/is item info` | 显示手中物品的完整数据组件列表 |

#### 数据库管理

| 命令 | 说明 |
|---|---|
| `/is db export [json\|csv]` | 导出数据库（默认 JSON） |
| `/is db vacuum` | 压缩优化数据库 |

#### 其他

| 命令 | 说明 |
|---|---|
| `/is reload` | 重载配置文件 |
| `/is status` | 查看当前扫描进度 |

---

## 4. 权限节点

| 权限 | 包含内容 | 默认 |
|---|---|---|
| `illegalscanner.admin` | 所有命令 | OP |
| `illegalscanner.scan` | 扫描命令 (`/is scan ...`) | OP |
| `illegalscanner.report` | 报告命令 (`/is report`) | OP |
| `illegalscanner.inspect` | 物品检查 (`/is item ...`) | OP |
| `illegalscanner.notify` | 收到管理员通知 | OP |

---

## 5. 配置说明

配置文件：`plugins/illegal-scanner/config.yml`

### 5.1 扫描设置

```yaml
scan:
  # 渐进式全量扫描
  full_scan_enabled: true           # 启动时自动开始全量扫描
  full_scan_delay_ticks: 1200       # 启动后延迟（1200 ticks = 60秒）
  full_scan_chunks_per_tick: 1      # 每tick扫描区块数（抬高会增加TPS压力）
  full_scan_player_data: true       # 同时扫描离线玩家数据

  # 事件驱动扫描
  event_scan_enabled: true          # 启用实时事件监听
  online_player_scan_on_join: true  # 玩家加入时自动扫描
  chunk_load_scan_enabled: true     # 新区块加载时自动扫描
  chunk_scan_cooldown_seconds: 30   # 同一区块的扫描冷却

  # 各容器类型开关
  scan_chests: true
  scan_furnaces: true
  scan_hoppers: true
  scan_dispensers: true
  scan_droppers: true
  scan_brewing_stands: true
  scan_shulker_boxes: true
  scan_item_frames: true
  scan_armor_stands: true
  scan_minecart_containers: true
  scan_dropped_items: false         # 性能开销大，默认关闭
  scan_player_enderchests: true
  scan_player_inventories: true
```

### 5.2 校验设置

```yaml
validation:
  # 附魔检查
  enforce_max_levels: true
  enforce_conflicts: true
  enforce_item_compatibility: true

  # 数据组件检查
  flag_unbreakable: true            # 无法破坏标签
  flag_custom_model_data: true      # 自定义模型数据
  flag_adventure_tags: true         # can_place_on / can_destroy
  flag_lore_presence: true          # lore存在即非法（原版生存无来源）
  flag_item_name_presence: true     # item_name存在即非法

  # 药水
  potion_max_amplifier: 1           # 最大效果等级（0=I级, 1=II级）
  potion_max_duration_ticks: 9600   # 最大持续时间（9600=8分钟）

  # 书册
  book_max_pages: 100

  # 烟花
  firework_max_flight: 3
  firework_max_explosions: 10

  # 自定义名称
  custom_name_max_length: 50        # 铁砧限制
  flag_non_italic_custom_name: true # 非斜体名称=命令专属
```

### 5.3 告警设置

```yaml
alerts:
  console_log: true                 # 检测到非法物品时控制台输出
  console_log_severity: "ILLEGAL"   # ILLEGAL / SUSPICIOUS / ALL
  admin_notify: false               # 通知在线管理员
```

### 5.4 忽略物品

```yaml
ignore_materials:
  - COMMAND_BLOCK
  - COMMAND_BLOCK_MINECART
  - STRUCTURE_BLOCK
  - JIGSAW
  - LIGHT
  - BARRIER
  - KNOWLEDGE_BOOK
  - DEBUG_STICK
  - STRUCTURE_VOID
```


物品名称或 lore 中包含这些关键字将被标记为 SUSPICIOUS。

---

## 6. 检测规则详解

插件通过 **7 个校验器** 逐项检查每个物品。校验器采用白名单机制：**只要原版生存无法获得，就判定为非法，无论数值大小**。

### 6.1 附魔校验器 (`EnchantValidator`)

| 检查项 | 说明 | 示例 |
|---|---|---|
| 等级超标 | 附魔等级超过原版最大 | 锋利 VI (原版最大 V) |
| 互斥共存 | 不可共存的附魔同时出现 | 精准采集 + 时运、无限 + 经验修补 |
| 物品类型错误 | 附魔无法应用于该物品 | 弓箭上的三重射击、石头上的效率 |
| 重复附魔 | 同一附魔ID出现多次 | 两个锋利 V |

### 6.2 属性修饰校验器 (`AttributeValidator`)

**核心原则: 白名单匹配，精确值对比。**

原版物品的属性修饰完全由物品类型决定，玩家无法通过任何生存手段添加、修改或删除。

| 检查项 | 说明 |
|---|---|
| 修饰不在白名单 | 该物品类型的原版原型中没有此属性 → ILLEGAL |
| 修饰值不匹配 | 属性名在白名单中但值与原版不同 → ILLEGAL |
| 修饰数量超标 | 修饰条目多于原版应有数量 → ILLEGAL |
| Slot 不匹配 | 修饰槽位与物品穿戴位置不符 → ILLEGAL |
| UUID/操作类型异常 | 格式或操作类型无效 → ILLEGAL |

> **举例**: 靴子的原版属性只有 `armor`、`armor_toughness`、`knockback_resistance`（下界合金）。如果靴子上出现 `movement_speed`（移动速度），无论值是 0.1 还是 999999，都判定 ILLEGAL——因为灵魂疾行的加速效果是通过附魔系统动态计算的，**不会**出现在 `attribute_modifiers` 组件中。

### 6.3 数据组件校验器 (`ComponentValidator`)

| 检查项 | 判定 | 原因 |
|---|---|---|
| `unbreakable` | **ILLEGAL** | 仅创造/命令可获得 |
| `lore` | **ILLEGAL** | 原版生存没有任何方式添加 lore |
| `item_name` | **ILLEGAL** | 原版生存没有任何方式覆写物品名 |
| `custom_name` > 50字符 | **ILLEGAL** | 铁砧限制 50 字符 |
| `custom_name` 非斜体 | **ILLEGAL** | 铁砧命名永远是斜体，关闭斜体需要命令 |
| `custom_name` JSON注入 | **ILLEGAL** | 名称中包含 `{"text":"..."}` 模式 |
| `custom_name` 不可见字符 | **SUSPICIOUS** | 零宽空格等Unicode控制字符 |
| `can_place_on` / `can_destroy` | **ILLEGAL** | 冒险模式专属 |
| `max_stack_size` 超标 | **ILLEGAL** | 超过物品类型原版堆叠上限 |
| `max_damage` 不符 | **ILLEGAL** | 与物品类型原版耐久值不一致 |
| `custom_model_data` | **SUSPICIOUS** | 存在自定义模型数据 |
| `hide_tooltip` | **SUSPICIOUS** | 隐藏提示信息（可能掩盖非法物品） |

### 6.4 药水校验器 (`PotionValidator`)

| 检查项 | 说明 |
|---|---|
| 效果等级超标 | 大多数效果原版最大 II 级（amplifier=1） |
| 持续时间超标 | 超过 8 分钟 |
| 不可能的组合 | 瞬间伤害 + 瞬间治疗、速度 + 缓慢 等 |
| 未知效果 | 非原版药水效果 |
| 自定义颜色无效果 | 有自定义颜色但没有对应药水效果 |

### 6.5 书册校验器 (`BookValidator`)

| 检查项 | 说明 |
|---|---|
| 页数超标 | 超过 100 页 |
| 单页过长 | 超过 1000 字符 |
| 作者名过长 | 超过 16 字符 |
| 标题过长 | 超过 32 字符 |

### 6.6 烟花校验器 (`FireworkValidator`)

| 检查项 | 说明 |
|---|---|
| 飞行时间超标 | 超过 3（原版最大） |
| 爆炸效果数量超标 | 超过 10 |

### 6.7 容器校验器 (`ContainerValidator`)

**递归检查** 容器内物品：

- **潜影盒**: 提取内部所有物品，逐一送入校验流水线
- **收纳袋**: 提取内部所有物品，逐一送入校验流水线
- **容器战利品数据**: 物品携带 `container_loot` 组件 → SUSPICIOUS（原版方块破坏后不保留战利品表）

### 严重级别

| 级别 | 含义 |
|---|---|
| **ILLEGAL** | 确定原版生存无法获得，需要处理 |
| **SUSPICIOUS** | 可疑特征，建议人工审查 |
| **PASS** | 物品合法 |

---

## 7. 数据库结构

数据库文件：`plugins/illegal-scanner/items.db`

### illegal_items 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER | 主键 |
| `item_type` | TEXT | 物品 Material 名（如 `DIAMOND_SWORD`） |
| `item_count` | INTEGER | 堆叠数量 |
| `item_slot` | INTEGER | 容器内槽位 |
| `item_snapshot` | TEXT | JSON 格式的完整物品快照（含 Base64 NBT） |
| `location_type` | TEXT | `PLAYER` / `BLOCK` / `ENTITY` |
| `world` | TEXT | 所在世界名 |
| `player_uuid` | TEXT | 玩家 UUID（location_type=PLAYER 时） |
| `player_name` | TEXT | 玩家名 |
| `block_x/y/z` | INTEGER | 方块坐标（location_type=BLOCK 时） |
| `container_type` | TEXT | 容器类型（`CHEST`, `BARREL` 等） |
| `entity_type` | TEXT | 实体类型（`ITEM_FRAME`, `ARMOR_STAND` 等） |
| `entity_uuid` | TEXT | 实体 UUID |
| `violations` | TEXT | JSON 数组，每条违规的类型、描述、严重级别 |
| `severity` | TEXT | `ILLEGAL` / `SUSPICIOUS` |
| `first_detected` | INTEGER | 首次检测时间（epoch 毫秒） |
| `last_detected` | INTEGER | 最后检测时间 |
| `detection_count` | INTEGER | 检测次数 |
| `resolved` | INTEGER | 0=活跃, 1=已处理 |
| `resolved_by` | TEXT | 处理人 |
| `resolved_at` | INTEGER | 处理时间 |
| `notes` | TEXT | 管理员备注 |

### 查询示例

```sql
-- 查看所有活跃的非法物品
SELECT id, item_type, player_name, severity, world, block_x, block_y, block_z
FROM illegal_items WHERE resolved = 0 ORDER BY last_detected DESC;

-- 按玩家统计
SELECT player_name, COUNT(*) as cnt
FROM illegal_items WHERE resolved = 0 AND location_type = 'PLAYER'
GROUP BY player_name ORDER BY cnt DESC;

-- 查看某个物品的完整快照（用于复原）
SELECT item_snapshot FROM illegal_items WHERE id = 1;
```

---

## 8. 常见场景

### 场景 1: 管理员自查

管理员持有可疑物品，想知道是否合法：

```
/is item check
```

### 场景 2: 新玩家加入时自动扫描

配置中开启 `online_player_scan_on_join: true`（默认开启），玩家加入后 5 秒自动扫描。

### 场景 3: 定期全服扫描

设置定时任务（如通过 Cron 或其他插件），每小时执行一次：

```
/is scan full
```

### 场景 4: 发现非法物品后处理

1. 查看报告：`/is report`
2. 查看详情：`/is report detail <ID>`
3. 根据 `location_type` 定位物品：
   - `PLAYER` → 找到对应玩家，手动清除其背包
   - `BLOCK` → 传送到坐标，打开对应容器清除
   - `ENTITY` → 找到展示框/盔甲架，破坏或清除物品
4. 清除后，管理员可以在数据库中标记 `resolved = 1`

### 场景 5: 只关心 ILLEGAL 不关心 SUSPICIOUS

在 `config.yml` 中设置：

```yaml
alerts:
  console_log_severity: "ILLEGAL"
```

### 场景 6: 服务器用插件给物品添加了 lore（允许 lore）

在 `config.yml` 中关闭 lore 检查：

```yaml
validation:
  flag_lore_presence: false
```

---

## 9. FAQ

### Q: 插件会影响服务器 TPS 吗？

全量扫描默认每 tick 处理 1 个区块，实测 TPS 影响 < 5%。可通过调整 `full_scan_chunks_per_tick` 控制负载。事件监听中的单物品校验 < 1ms。

### Q: 离线玩家的物品怎么扫描？

插件读取 `world/playerdata/<uuid>.dat` 文件中的玩家 NBT 数据，提取背包、末影箱物品。Phase 3 后台执行，不阻塞主线程。

### Q: 物品快照能用于恢复物品吗？

可以。`item_snapshot` 字段包含 Base64 编码的完整 NBT 数据，通过 `ItemStack.deserializeBytes()` 可以完整还原物品，包括所有附魔、属性、名称、lore 等。

### Q: 如果服务器允许某些"超标"物品（比如某些插件赋予的），如何避免误报？

在 `config.yml` 中调整对应设置。例如：
- 允许更高等级的附魔 → 调高或关闭 `enforce_max_levels`
- 允许 lore → 设置 `flag_lore_presence: false`
### Q: 数据库会越来越大吗？

定期执行 `/is db vacuum` 可以压缩数据库。建议配合定期任务（如每天一次）自动清理。

### Q: 支持哪些 Minecraft 版本？

编译目标为 Paper 1.21.1，使用 paperweight userdev。1.21.0 和 1.21.3 可能也能运行，但未测试。

### Q: 可以在 BungeeCord/Velocity 网络中使用吗？

这是 Bukkit/Paper 插件，安装在每个子服上即可。不支持 BungeeCord/Velocity 端安装。
