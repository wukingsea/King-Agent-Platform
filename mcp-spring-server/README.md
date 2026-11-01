# MCP Spring 测试服务（mcp-spring-server）

用平台后端同款技术栈（Spring Boot 3.5.16 + Java 17）实现的 MCP Streamable HTTP 测试服务，
用于验证 King-AI-Platform 的 MCP 工具接入能力。与 `mcp-test-server/`（Python 版）行为对齐，
可互相印证。零额外依赖：只用 `spring-boot-starter-web`（Jackson 随附），协议实现参照平台
`DemoMcpController` 与 MCP 2025-03-26 规范。

## 运行方式（任选其一）

| 方式 | 命令 |
|---|---|
| IDEA（推荐，和后端一致） | 打开项目后直接运行 `McpSpringServerApplication` |
| Maven | `mvn spring-boot:run` |
| 可执行 jar | `mvn package` 后 `java -jar target/mcp-spring-server-1.0.0.jar` |
| Windows 脚本 | 双击 `start-mcp-spring-server.bat`（需 PATH 里有 mvn） |

服务地址：`http://127.0.0.1:9093/mcp`（Streamable HTTP，仅 POST）。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MCP_TEST_PORT` | `9093` | 监听端口 |
| `MCP_TEST_TOKEN` | 未设置 | 设置后启用 Bearer 鉴权，请求需带 `Authorization: Bearer <token>` |

## 平台接入参数（MCP 服务页面）

| 配置项 | 值 |
|---|---|
| 名称 | MCP测试服务(Spring) |
| 传输方式 | HTTP（Streamable HTTP） |
| URL | `http://127.0.0.1:9093/mcp` |
| 请求头 | 留空（启用 MCP_TEST_TOKEN 时填 `{"Authorization": "Bearer <token>"}`） |

> 注意：平台默认 `king-ai.outbound.block-private-networks=true` 会拦截 localhost 目标，
> 需用 local profile（`--spring.profiles.active=local`，已默认关闭该限制）启动后端。

## 测试工具（5 个，与 Python 版对齐）

| 工具 | 参数 | 行为 |
|---|---|---|
| `echo` | `text` (string, 必填) | 返回 `echo: <text>`，支持中文 |
| `add` | `a`, `b` (number, 必填) | 返回 `a + b = <和>` |
| `get_current_time` | 无 | 返回服务器当前日期/时间/星期（Asia/Shanghai） |
| `slow_echo` | `text` (必填), `seconds` (默认 3, 最大 120) | 延迟后回显，用于测慢响应/超时 |
| `simulate_error` | `message` (可选) | 恒定失败，返回 `isError=true` |

## 协议覆盖点（供测试参考）

- initialize：协议版本协商（支持 2024-11-05 / 2025-03-26 / 2025-06-18），响应下发 `Mcp-Session-Id`
- `notifications/*` → 202 Accepted
- tools/list：5 个工具，含 JSON Schema（additionalProperties=false）
- tools/call：`content[].text` + `isError`；入参不合法 → `isError=true` 结果；未知工具 → JSON-RPC `-32602`
- 未知方法 → `-32601`；非法 JSON → HTTP 400 + `-32700`
- JSON-RPC 批量（数组）：逐条处理，全通知 → 202
- GET /mcp → 405（不支持 SSE 流）；DELETE /mcp → 204
- 会话无状态：不校验 Mcp-Session-Id（宽松模式，便于测试）

## 与 Python 版（mcp-test-server, 端口 9092）的差异

| 项 | Python 版 | Spring 版 |
|---|---|---|
| 端口 | 9092 | 9093 |
| 运行 | 独立 python 进程 | JVM（与后端同栈） |
| 会话 | 内存会话 | 无状态（宽松） |
| 依赖 | 零依赖 | spring-boot-starter-web |

> 平台调用超时为 60 秒；`slow_echo` 最大 120 秒，可用来验证平台超时行为。

## Windows 手工测试提示

在 Windows 终端里用 curl 直接发中文请求体会被按 GBK 编码发出（服务端会正确拒绝并返回
`-32700`，这是服务端边界校验在起作用）。中文请求体请写入 UTF-8 文件后发送：

```bash
curl -s -X POST http://127.0.0.1:9093/mcp -H "Content-Type: application/json" \
  --data-binary @body.json
```
