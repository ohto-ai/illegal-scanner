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
/is check item
```

如果物品合法：
> ✅ `Item is clean — no violations found.`

如果物品非法：
> ❌ 列出所有违规项（附魔超标、互斥附魔、属性修饰异常等）

### 2.2 快速扫描当前区块

```
/is scan chunk
```

### 2.3 启动全服渐进扫描

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

### 2.4 查看进度

```
/is status
```

### 2.5 查看报告（GUI）

```
/is view chunk    # 查看当前区块
/is view player   # 查看指定玩家
```

### 2.6 查看报告（文本）

```
/is report chunk    # 文本查看当前区块
/is report player <玩家名>
/is report scan     # 查看扫描会话列表
```

---

## 3. 命令参考

主命令：`/is`（别名：`/illegalscanner`）

### 3.1 扫描 (`/is scan`)

权限: `illegalscanner.scan`

| 命令 | 说明 |
|---|---|
| `/is scan chunk` | 扫描当前所在区块 |
| `/is scan player [-online\|-offline\|-all] [玩家名]` | 扫描玩家（可选标记过滤） |
| `/is scan area <x1> <z1> <x2> <z2> [世界名]` | 扫描矩形区域（按区块） |
| `/is scan res <区域名>` | 扫描领地/区域（WorldGuard / Residence） |
| `/is scan world [世界名\|all_world] [loaded_chunks\|unloaded_chunks\|all_chunks]` | 扫描世界 |
| `/is scan full` | 全服渐进扫描 |
| `/is scan pause <sessionId>` | 暂停扫描会话 |
| `/is scan resume <sessionId>` | 恢复扫描会话 |
| `/is scan stop <sessionId>` | 停止扫描会话 |
| `/is scan restart <sessionId>` | 重启扫描会话 |

### 3.2 快速检测 (`/is check`)

权限: `illegalscanner.inspect` — **不记录到数据库**

| 命令 | 说明 |
|---|---|
| `/is check item` | 校验手中物品的合法性 |
| `/is check player [玩家名]` | 检查玩家背包 + 末影箱 |
| `/is check chunk` | 快速扫描当前区块 |

### 3.3 GUI 查看 (`/is view`)

权限: `illegalscanner.report` — 交互式 GUI 界面（支持翻页、回溯、物品快照、传送）

| 命令 | 说明 |
|---|---|
| `/is view chunk` | 当前区块违规总览 |
| `/is view player [玩家名]` | 玩家违规物品列表 |
| `/is view world [世界名]` | 世界违规区块概览 |
| `/is view area <x1> <z1> <x2> <z2> [世界名]` | 区域违规概览 |
| `/is view res <插件名> <区域名> [世界名]` | 领地违规概览 |
| `/is view full` | 全服违规概览 |
| `/is view scan [sessionId]` | 扫描会话列表 / 详情（支持自动刷新） |
| `/is view record <SCAN\|MONITOR> <id>` | 单条违规记录详情 |
| `/is view item [itemHash]` | 违规物品类型列表 / 详情 |

### 3.4 文本报告 (`/is report`)

权限: `illegalscanner.report` — 聊天框文本输出（支持分页）

| 命令 | 说明 |
|---|---|
| `/is report chunk` | 当前区块文本报告 |
| `/is report player <玩家名> [页码]` | 玩家文本报告 |
| `/is report item [itemHash] [页码]` | 物品类型列表 / 指定 hash 的所有记录 |
| `/is report scan [sessionId] [页码]` | 扫描会话列表 / 会话记录 |
| `/is report record <SCAN\|MONITOR> <id>` | 单条违规记录完整详情 |
| `/is report area <x1> <z1> <x2> <z2> [世界名] [页码]` | 区域文本报告 |
| `/is report res <插件名> <区域名> [世界名] [页码]` | 领地文本报告 |
| `/is report world [世界名] [页码]` | 世界文本报告（已加载区块） |

### 3.5 历史记录 (`/is history`)

权限: `illegalscanner.report`

| 命令 | 说明 |
|---|---|
| `/is history chunk [页码]` | 查看当前区块的历史违规记录 |
| `/is history player <玩家名> [页码]` | 查看玩家的历史违规记录 |

### 3.6 实时监测 (`/is monitor`)

权限: `illegalscanner.admin`

| 命令 | 说明 |
|---|---|
| `/is monitor enable` | 启用实时监测引擎 |
| `/is monitor disable` | 禁用实时监测引擎 |
| `/is monitor status` | 查看监测引擎状态（活跃事件数、轮询间隔、保留天数） |

### 3.7 配置管理 (`/is config`)

权限: `illegalscanner.admin`

#### 基础

| 命令 | 说明 |
|---|---|
| `/is config reload` | 重载配置、消息文件和监测引擎 |
| `/is config list` | 列出当前配置的所有键值 |

#### 规则配置 (`/is config rules`)

| 命令 | 说明 |
|---|---|
| `/is config rules enchant conflict <enable\|disable\|status>` | 附魔冲突检测开关 |
| `/is config rules enchant level <enable\|disable\|status\|set\|reset>` | 附魔等级检测 / 管理 |
| `/is config rules enchant compatibility <enable\|disable\|status\|add\|remove>` | 附魔物品兼容性检测 / 管理 |
| `/is config rules potion <enable\|disable\|status\|level\|effects>` | 药水检测开关 / 等级管理 / 效果上限 |
| `/is config rules stack <enable\|disable\|status\|auto_fix\|set\|reset\|default\|list>` | 堆叠限制检测 / 自定义堆叠值 |
| `/is config rules attribute <enable\|disable\|status\|mode\|set\|reset\|list>` | 属性修饰检测 / 模式切换 |
| `/is config rules unbreakable <enable\|disable\|status\|action\|restore>` | 不可破坏标签检测 / 处理策略 |

#### 监测配置 (`/is config monitor`)

| 命令 | 说明 |
|---|---|
| `/is config monitor enable\|disable\|status` | 监测引擎开关 / 状态 |
| `/is config monitor interval <秒>` | 设置扫描间隔（秒） |
| `/is config monitor flush <秒>` | 设置去重窗口（秒） |
| `/is config monitor retention <天>` | 设置数据保留天数 |
| `/is config monitor events <list\|enable\|disable> [事件名]` | 管理监测事件类型 |

#### 扫描配置 (`/is config scan`)

| 命令 | 说明 |
|---|---|
| `/is config scan max_area <区块数>` | 设置区域扫描最大区块数 |
| `/is config scan thread_pool <大小>` | 设置线程池大小 |
| `/is config scan console_only <enable\|disable>` | 仅控制台扫描模式 |

### 3.8 白名单管理 (`/is whitelist`)

权限: `illegalscanner.admin` — 支持六种白名单类型

| 类型 | 操作 | 说明 |
|---|---|---|
| `player` | `add <玩家名>` | 添加玩家白名单 |
| `player` | `remove <玩家名>` | 移除玩家白名单 |
| `player` | `list\|clear` | 列表 / 清空 |
| `item` | `add` | 手持物品加入白名单 |
| `item` | `gui [页码]` | 打开 GUI 白名单管理器 |
| `item` | `remove <id>\|list\|clear` | 移除 / 列表 / 清空 |
| `chunk` | `add` | 当前区块加入白名单 |
| `chunk` | `remove <id>\|list\|clear` | 移除 / 列表 / 清空 |
| `area` | `add <x1> <z1> <x2> <z2> [世界名]` | 区域加入白名单 |
| `area` | `remove <id>\|list\|clear` | 移除 / 列表 / 清空 |
| `res` | `add <插件名> <区域名> [世界名]` | 领地区域加入白名单 |
| `res` | `remove <id>\|list\|clear` | 移除 / 列表 / 清空 |
| `world` | `add [世界名]` | 添加世界白名单 |
| `world` | `remove [世界名]\|list\|clear` | 移除 / 列表 / 清空 |

### 3.9 监控列表 (`/is watchlist`)

权限: `illegalscanner.admin` — 按 Material ID 标记监控物品，无论 NBT 如何都会被标记

| 命令 | 说明 |
|---|---|
| `/is watchlist add <materialID>` | 添加 Material 到监控列表 |
| `/is watchlist remove <materialID>` | 从监控列表移除 Material |
| `/is watchlist list` | 列出所有监控的 Material |
| `/is watchlist clear` | 清空监控列表 |

### 3.10 其他

| 命令 | 说明 |
|---|---|
| `/is give <item_hash>` | 根据 hash 获取物品（调试用） |
| `/is reload` | 重载配置文件和消息 |
| `/is status` | 查看服务状态 |

---

## 4. 权限节点

| 权限 | 包含内容 | 默认 |
|---|---|---|
| `illegalscanner.admin` | 所有命令（父权限） | OP |
| `illegalscanner.scan` | 扫描命令 (`/is scan ...`) | OP |
| `illegalscanner.report` | 报告/查看/历史 (`/is report`, `/is view`, `/is history`) | OP |
| `illegalscanner.inspect` | 快速检测 (`/is check ...`) | OP |
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
  max_area_chunks: 10000            # 区域扫描最大区块数
  thread_pool_size: 4               # 线程池大小
  console_only: false               # 仅控制台输出扫描进度

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
  enchant_max_levels: {}            # 自定义附魔等级上限

  # 数据组件检查
  flag_unbreakable: true            # 无法破坏标签
  unbreakable_action: flag          # flag=标记 / remove=移除
  unbreakable_restore: true         # 登出时恢复移除的 unbreakable
  flag_custom_model_data: true      # 自定义模型数据
  flag_adventure_tags: true         # can_place_on / can_destroy
  flag_lore_presence: true          # lore存在即非法（原版生存无来源）
  flag_item_name_presence: true     # item_name存在即非法
  custom_name_max_length: 50        # 铁砧限制
  flag_non_italic_custom_name: true # 非斜体名称=命令专属
  enforce_stack_limits: true        # 堆叠限制检测
  stack_auto_fix: false             # 自动修正堆叠数量
  default_max_stack: 64             # 默认堆叠上限
  enforce_attribute_limits: true    # 属性修饰限制检测
  attribute_mode: all               # all=全面检测 / threshold=阈值检测
  attribute_limits: {}              # 自定义属性上限

  # 药水
  potion_checks_enabled: true
  potion_max_amplifier: 1           # 最大效果等级（0=I级, 1=II级）
  potion_max_duration_ticks: 9600   # 最大持续时间（9600=8分钟）
  potion_max_effects: 5             # 最大药水效果数量

  # 书册
  book_max_pages: 100

  # 烟花
  firework_max_flight: 3
  firework_max_explosions: 10
```

### 5.3 监测设置

```yaml
monitor:
  enabled: false                    # 实时监测开关
  interval_seconds: 5               # 扫描间隔（秒）
  flush_seconds: 300                # 去重窗口（秒）
  retention_days: 7                 # 数据保留天数
```

### 5.4 告警设置

```yaml
alerts:
  console_log: true                 # 检测到非法物品时控制台输出
  console_log_severity: "ILLEGAL"   # ILLEGAL / SUSPICIOUS / ALL
  admin_notify: false               # 通知在线管理员
```

### 5.5 忽略物品

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

### 7.1 illegal_items 表

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

### 7.2 白名单 / 监控列表表

| 表名 | 用途 |
|---|---|
| `player_whitelist` | 玩家白名单 |
| `item_whitelist` | 物品白名单 |
| `chunk_whitelist` | 区块白名单 |
| `area_whitelist` | 区域白名单 |
| `world_whitelist` | 世界白名单 |
| `scan_sessions` | 扫描会话记录 |
| `monitor_records` | 监测引擎记录 |
| `watch_materials` | 监控列表（Material ID） |

### 7.3 查询示例

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

-- 查看扫描会话
SELECT id, scan_type, target, status, total_chunks, scanned_chunks
FROM scan_sessions ORDER BY started_at DESC;

-- 查看监控列表
SELECT material_id FROM watch_materials;
```

---

## 8. 常见场景

### 场景 1: 管理员自查

手持可疑物品，执行：

```
/is check item
```

### 场景 2: 新玩家加入时自动扫描

配置中开启 `online_player_scan_on_join: true`（默认开启），玩家加入后自动扫描。

也可用监测引擎持续监控：

```
/is monitor enable
/is config monitor interval 10
```

### 场景 3: 定期全服扫描

```
/is scan full
```

查看扫描进度：

```
/is view scan
```

### 场景 4: 发现非法物品后处理

1. **GUI 查看**: `/is view chunk` 或 `/is view player <名字>`
2. **文本报告**: `/is report chunk` 或 `/is report player <名字>`
3. **定位物品**:
   - `PLAYER` → 找到对应玩家，手动清除其背包
   - `BLOCK` → 传送至坐标，打开对应容器清除
   - `ENTITY` → 找到展示框/盔甲架，破坏或清除物品
4. **获取物品**: `/is give <item_hash>` 可生成指定物品副本用于核验
5. **加入白名单**: 如果确认某物品/玩家/区域合法：
   ```
   /is whitelist item add         # 手持物品加入白名单
   /is whitelist player add <名>  # 玩家加入白名单
   /is whitelist chunk add        # 区块加入白名单
   ```

### 场景 5: 只关心 ILLEGAL 不关心 SUSPICIOUS

在 `config.yml` 中设置：

```yaml
alerts:
  console_log_severity: "ILLEGAL"
```

### 场景 6: 服务器用插件给物品添加了 lore（允许 lore）

在 `config.yml` 中关闭:

```yaml
validation:
  flag_lore_presence: false
```

或通过命令：

```
/is config rules unbreakable disable
/is config rules attribute mode threshold
```

---

## 9. FAQ

### Q: 插件会影响服务器 TPS 吗？

全量扫描默认每 tick 处理 1 个区块，实测 TPS 影响 < 5%。可通过调整 `full_scan_chunks_per_tick` 控制负载。可通过 `/is config scan thread_pool <size>` 调整线程池。事件监听中的单物品校验 < 1ms。

### Q: 离线玩家的物品怎么扫描？

插件读取 `world/playerdata/<uuid>.dat` 文件中的玩家 NBT 数据，提取背包、末影箱物品。Phase 3 在后台执行不阻塞主线程，也可通过 `/is scan player -offline <玩家名>` 按需扫描。

### Q: 物品快照能用于恢复物品吗？

可以。`item_snapshot` 字段包含 JSON 格式的完整物品数据（含 Base64 NBT），通过 `/is give <item_hash>` 可完整还原物品，包括所有附魔、属性、名称、lore 等。

### Q: 如果服务器允许某些"超标"物品（比如某些插件赋予的），如何避免误报？

方式一：通过 `/is config rules ...` 动态调整规则
方式二：通过 `/is whitelist item add` 将特定物品加入白名单
方式三：通过 `/is whitelist player add <玩家名>` 跳过特定玩家
方式四：通过 `/is watchlist add <Material>` 反向仅监控特定物品

### Q: 监控列表和物品白名单有什么区别？

- **物品白名单**: 特定 hash 的物品完全跳过检查
- **监控列表**: 仅按 Material 类型标记（如所有 `NETHERITE_SWORD`），无论 NBT 如何都会被扫描并标记，用于追踪特定物品的分布

### Q: 数据库会越来越大吗？

定期执行数据库清理（通过 `/is config monitor retention <天数>` 设置自动保留期）。手动清理可通过 SQLite 工具操作。

### Q: 支持哪些 Minecraft 版本？

编译目标为 Paper 1.21.1，使用 paperweight userdev。1.21.0 和 1.21.3 可能也能运行，但未测试。

### Q: 可以在 BungeeCord/Velocity 网络中使用吗？

这是 Bukkit/Paper 插件，安装在每个子服上即可。不支持 BungeeCord/Velocity 端安装。
