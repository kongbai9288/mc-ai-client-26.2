# MCAI — 让 AI 真正操控 Minecraft 26.2

一个 **Fabric 客户端 MOD**，把「AI 玩 Minecraft」拆成三层：感知（Scanner）→ 决策（LLM）→ 执行（Actuator）。
AI 只下发**意图**，本地执行器把意图持续执行几百 tick，不再每帧打扰模型。

---

## 为什么这样设计

LLM 是秒级决策，Minecraft 是 20 tick/s。让模型去管每一次按键，必然卡死、必然出错。
真正的解法是**频率分层**：

```
AI（秒级，只发意图）
   ↓  "去挖 20 个钻石"
本地执行器（tick 级，自主跑几百 tick）
   ↓  移动 / 挖 / 打 / 搭桥
真实输入管线（服务器看到的就是"一个在按 W 的玩家"）
```

---

## 硬性约束（都已实现）

| 你的要求 | 实现方式 |
|---|---|
| 独立运行，不依赖 Fabric API | 只依赖 `fabric-loader` + Mixin，`fabric.mod.json` 里没有任何 API 依赖 |
| 用别人的代码，不当依赖 | Baritone / Litematica / ViaFabricPlus 全部**反射软探测**：装了就用，没装就降级，缺失不崩 |
| 支持 Via（一个客户端打天下） | `ViaLink` 读 ViaFabricPlus 目标版本，自动给出战斗/反作弊差异建议 |
| OpenAI 兼容接口 | DeepSeek / OpenAI / 智谱 / Ollama / vLLM 均可，改 `api.base` 即可 |
| 手机启动器自动关闭 | `Env` 探测 Pojav/FCL/Amethyst/Zalith/android.os.Build，命中则**整体不启动** |
| 开放 API 给别的程序 | 本地 REST（11 个端点）+ MCP（JSON-RPC，Claude Desktop / Harness 零适配接入） |
| 防止被反作弊拉回 | `AntiCheat` 拟人化 + **拉回自动检测** + 自适应降速 + AI 可调参 |
| 缓存 | `SenseCache` 按 chunk 缓存方块直方图，实体/血量不缓存（缓存它们就是"打空气"的根源） |
| DeepSeek Harness 模式 | `agent.mode=harness`：**不注入任何系统提示词**，完全由 AI 自己管理 |

---

## 26.2 的真实坑（已逐一核实并规避）

26.1 起 MC 发布**未混淆**版本，大量 API 改名。以下都是实测 javap 确认过的：

| 旧写法（会编译失败） | 26.2 实际 |
|---|---|
| `ResourceLocation` | **`Identifier`** |
| `Minecraft.world` | `Minecraft.level` |
| `ResourceKey.location()` | `ResourceKey.identifier()` |
| `level.getDayTime()` / `isDay()` / `isNight()` | `getOverworldClockTime()` / `getSkyDarken()` |
| `level.getMinBuildHeight()` | `getMinY()` / `getMaxY()` |
| `Inventory.selected` | `getSelectedSlot()` / `setSelectedSlot(int)` |
| `Direction.getNormal()` | `new Vec3(getStepX(), getStepY(), getStepZ())` |
| `LivingEntity.getTarget()` | 定义在 `Mob` 上，需向下转型 |
| `BuildInRegistries.X.getKey()` | 可用，但类型签名变了 |
| Yarn mappings | **26.1 起停止维护**，写了直接报错 |

---

## 编译

```bash
# 环境：JDK 25（26.1 起要求）
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

产物：`build/libs/mcai-mc26.2-<version>.jar`

> 26.1 起**不要再写** `mappings`，也不要用 `remapJar` / `withSourcesJar()` —— 没有 intermediary 命名空间了，产物直接用 `jar` 任务。

---

## 配置

首次启动生成 `config/mcai/mcai.properties`。**API Key 用环境变量，不要写进配置文件**：

```bash
export MCAI_API_KEY=sk-xxxx
```

关键项：

```properties
api.base=https://api.deepseek.com/v1
api.model=deepseek-chat
agent.mode=standard          # standard | harness
agent.autonomous=true
bridge.port=8791
anticheat.enabled=true
```

---

## 使用

### 1. 给它一个目标

```bash
curl -X POST http://127.0.0.1:8791/goal \
  -H 'Content-Type: application/json' \
  -d '{"goal":"去挖 20 个钻石，缺镐子就先做一把"}'
```

然后 AI 就自己干了：观察 → 判断没镐子 → 先砍树 → 做工作台 → 造镐 → 下矿 → 遇到怪自己打或跑 → 血量低自动吃食。

### 2. 外部程序调用

**REST**
```
GET  /health                 环境、Baritone/Litematica/Via 状态、缓存命中率
GET  /observe                一次完整世界快照
POST /act                    {"tool":"go_to","args":{"x":100,"y":64,"z":100}}
POST /goal                   设置目标并启动
GET  /tools                  列出全部 AI 工具
GET  /trace                  决策轨迹，排查"AI 为什么这么干"
POST /config/anticheat       查看 / 调整拟人化参数
```

**MCP**（任何支持 MCP 的客户端直接接）

```json
POST /mcp
{"jsonrpc":"2.0","id":1,"method":"tools/list"}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"observe","arguments":{}}}
```

### 3. AI 可用工具（18 个）

感知：`observe`
移动：`go_to` `mine` `flee` `look_at` `wait`
战斗：`attack` `stop_all`
建造：`place_block` `break_block` `stop_break` `use_item` `select_slot`
投影：`load_schematic` `schematic_diff` `printer`
调优：`anticheat_tune` `action_status` `baritone_setting` `baritone_command`
收尾：`finish`

每个工具的 description 都自带触发条件 —— **在 harness 模式下没有系统提示词，工具描述就是唯一说明书**。

---

## 可选增强（都是软探测）

| Mod | 作用 | 缺失时 |
|---|---|---|
| **Baritone** (26.2 fork) | 真实寻路：会挖、会搭桥、会爬 | 用内置直线导航（遇障碍会跳） |
| **Litematica** | 投影 + 打印机（Easy Place） | 用 `schematic_diff` 拿坐标手工 `place_block` |
| **ViaFabricPlus** 5.0+ | 一个客户端连 1.8 ~ 26.2 任意服务器 | 只能连 26.2，行为按原生处理 |

---

## 反作弊：为什么会被"拉回"

服务器不会直接封你，而是**撤销你的动作** —— 表现为走过去又被弹回来。
AI 如果不知道这件事，会以为自己没走到，于是反复重试，越试越像机器人。

`AntiCheat` 做三件事：
1. **拟人化**：攻击间隔随机化、视角限速、动作抖动
2. **拉回检测**：识别"位移被服务器拽回"的特征
3. **自适应降速**：拉回越多动作越保守

并暴露为 `anticheat_tune` 工具 —— **AI 可以自己调**。这是一个能自动调整的工具，专门用来防止被反作弊拉回。

连旧版服务器时还会按版本自动建议参数（1.8 无攻击冷却但反插件更敏感，1.9+ 有冷却天然更像人）。

---

## 已知限制

- 内置导航是**直线**的，遇到复杂地形（峡谷、岩浆湖）会绕不过去 —— 装 Baritone 解决
- 内置挖矿只支持**原地挖**，远程采集需要 Baritone
- 手机启动器上**完全不启动**（这是刻意的，防止崩溃）
- 投影差异比对只扫玩家附近区域，超大投影不会全量解码

---

## License

MIT
