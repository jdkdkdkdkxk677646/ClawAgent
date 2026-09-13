# T-501 · MCP 多服务器

- 难度:⭐⭐
- 状态:todo
- 波次:**Wave 1(可与 T-502/T-503 并行)**——文件集与两者不相交。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/agent/McpServers.kt`(新建,多服务器编排)
  - `app/src/main/java/com/openclaw/clawagent/provider/SecurePrefs.kt`(**仅** MCP 相关存取)
  - `app/src/main/java/com/openclaw/clawagent/ui/SettingsDialog.kt`(**仅** MCP 区块)
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatViewModel.kt`(**仅** MCP 连接段,约 L130-180 与 mcpStatus 派生处)
  - `app/src/test/java/com/openclaw/clawagent/agent/McpServersTest.kt`(新建)
- 禁改:`core-agent/mcp/**`(`McpClient` 是单实例类,多服务器=多实例,原样复用)、`AgentWiring.kt`(归 T-504)、`MainActivity.kt`。

## 背景

v4.2 的 MCP 只支持**单服务器**:`SecurePrefs.mcpEndpoint/mcpAuthToken` 一个端点,`ChatViewModel` 直接 `McpClient(endpoint, token)` 挂工具(见 ChatViewModel L159-174)。远程工具集往往不止一台(文档服务器 + 数据服务器),本卡把配置与连接升级为**列表**。

## 任务

1. `McpServers.kt`(纯逻辑,单测友好,不碰 Android UI):
   - 配置模型 `McpServerConfig(name, endpoint, authToken)`;
   - `parseConfigs(json: String): List<McpServerConfig>`——解析 JSON 数组,坏条目跳过不抛,重名追加序号;
   - `connectAll(configs, clientFactory, onLog): McsSession`——逐台 connect + `tools/list`,**单台失败降级**(该台 0 工具,不阻断其他,失败计入汇总);**跨服务器工具名冲突**时后到者改为 `mcp_<server别名>_<tool>`,保证无碰撞;
   - `clientFactory: (endpoint, token) -> McpClient` 构造参数是测试缝。
2. `SecurePrefs`:新增 `mcpServersJson: String`(默认 `"[]"`);**读取兼容旧 key**——新 key 为空而旧 `mcpEndpoint` 非空时,视作单条配置并写回新格式(一次性迁移,端点名取 host)。
3. `SettingsDialog` MCP 区块:改为多行列表(名称/端点/token,可增删行),保存时序列化为 `mcpServersJson`;原端点/token 单输入框移除。
4. `ChatViewModel` MCP 段:改调 `McpServers.connectAll`;`mcpStatus` 汇总为"N 台 · M 工具 · K 台失败"。
5. `McpServersTest` ≥ 6 用例:解析容错(坏 JSON/缺字段/重名)/冲突命名/工厂抛异常降级/全部失败返回空集不抛/旧配置迁移读取。

## 验收标准

- CI 全绿;既有 MCP 相关行为(单台)不回归;
- 旧单服务器配置升级后行为不变(兼容用例覆盖);
- 真机(可选):配两台服务器,两台的工具同时出现且可调用、互不重名。

## 交付记录

(领取时填:领取时间;完成后填:完成时间 / commit / 关键决策 / 测试结果)
- 领取时间:—
- 完成时间:—
- commit:—
- 关键决策/测试结果:—
