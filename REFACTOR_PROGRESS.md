# illegal-scanner 重构进度

开始时间: 2026-06-30 | 完成时间: 2026-06-30

---

## 重构总结

按照 TODO.md 完成了 6 个 Phase 的彻底重构。最终编译: **BUILD SUCCESSFUL**。

### 已完成的核心变更

| 模块 | 变更 |
|------|------|
| 数据库 | 新 schema: scan_records, monitor_records, item_index, scan_sessions, plugin_settings + whitelist表 |
| Item Hash | SHA-256 NBT哈希 (排除耐久) 直接作为主键, LRU内存缓存 |
| 命令系统 | CommandRouter → 8个子命令组处理器 (scan/check/view/report/history/monitor/config/whitelist) |
| Scan | ScanService + ScanSessionManager, scan chunk/player/area/res/world/full |
| View/Report | GUI翻页 + 文本翻页, chunk/player/item/scan/record 统一查询 |
| History | chunk/player 历史记录查询 |
| Monitor | MonitorEngine 替代旧event listeners, 5种事件类型可配置 |
| Config | rules (enchant/potion/stack/attribute/unbreakable) + monitor + scan 配置子树 |
| Whitelist | player/item/chunk/area/res/world 白名单 CRUD |
| 清理 | 删除11个旧文件 (ScanManager, ESCommand, 5个Listener, 3个GuiHolder, ItemRecord) |

### 保留/微调的文件

| 文件 | 说明 |
|------|------|
| `validator/*` (7 files) | 验证逻辑基本不变 |
| `scanner/ChunkScanner.java` | 回调化重构 |
| `scanner/PlayerScanner.java` | 回调化重构 |
| `scanner/ContainerUtil.java` | 无变更 |
| `scanner/NbtUtil.java` | 新增 itemStackFromJson |
| `scanner/ItemAccessor.java` | NMS 访问无变更 |
| `whitelist/*` (3 files) | 适配新 DB API |
| `config/ConfigManager.java` | 无变更 |
| `config/Messages.java` | 无变更 |
| `show/ChunkHighlightManager.java` | 适配 UnifiedRecord |

### 新建的文件 (26个)

- `database/DatabaseManager.java` (重写)
- `scanner/ItemHashService.java`
- `scanner/ScanService.java`
- `scanner/ScanSessionManager.java`
- `command/SubCommandHandler.java`
- `command/CommandRouter.java`
- `command/ESTabCompleter.java` (重写)
- `command/ScanCommandHandler.java`
- `command/CheckCommandHandler.java`
- `command/ViewCommandHandler.java`
- `command/ReportCommandHandler.java`
- `command/HistoryCommandHandler.java`
- `command/MonitorCommandHandler.java`
- `command/ConfigCommandHandler.java`
- `command/WhitelistCommandHandler.java`
- `monitor/MonitorEventType.java`
- `monitor/MonitorEngine.java`
- `query/UnifiedQueryService.java`

### TODO / 未来工作

- [x] `whitelist * clear` — 批量清空白名单操作 ✅ Phase 6
- [x] `whitelist world` — 世界级白名单持久化 ✅ Phase 6
- [x] `view res/full` — 扩展查询视图 ✅ Phase 6
- [x] `report area/res/world` — 扩展文本报告 ✅ Phase 6
- [x] `scan full` — 全服扫描 ✅ Phase 6
- [x] ItemWhitelistManager 清理逻辑 (基于新schema重写) ✅ Phase 6
- [x] 扫描区块去重 (ChunkScanDedupCache) — 基于内存 ConcurrentHashMap，避免重叠扫描命令重复扫描同一区块 ✅ 2026-07-01
- [ ] 离线玩家 .dat 文件 NBT 解析 (PlayerScanner) — 已实现，待验证

### Phase 6 完成总结 (2026-07-01)

| 变更 | 说明 |
|------|------|
| DatabaseManager | 新增 world_whitelist 表 + 6个clear方法 + world whitelist CRUD |
| RegionWhitelistManager | world whitelist cache + isWorldWhitelisted() + CRUD |
| WhitelistCommandHandler | 所有6个 clear 操作 + world add/remove/list/clear |
| ReportCommandHandler | area/res/world 文本报告（含分页） |
| ViewCommandHandler | res + full GUI视图 (ViewType.RES/FULL) |
| ViewGuiListener | 新增 RES/FULL 导航和点击处理 |
| ScanCommandHandler | 新增 full 子命令 |
| ScanService | 新增 scanFull() — 跨所有世界批量扫描 |
| ItemWhitelistManager | cleanupAfterWhitelistAdd() 实现 — 扫描已有记录匹配白名单 |
| ISTabCompleter | 更新所有补全列表 (res + full)
