# MCP 测试服务（Streamable HTTP）

独立的零依赖 MCP 测试服务器，用于验证 King-AI-Platform 的 MCP 工具接入
（`king-ai-api` 的 `McpClient` / `McpClientFactory`，仅支持 Streamable HTTP 传输）。

## 启动

双击 `start-mcp-test-server.bat`，或命令行执行：

    python mcp_test_server.py [端口]     # 默认 127.0.0.1:9092

环境变量：`MCP_TEST_HOST`（监听地址）、`MCP_TEST_PORT`（端口）、
`MCP_TEST_TOKEN`（设置后要求 `Authorization: Bearer <token>`，用于测试自定义请求头鉴权）。

## 平台接入

平台「MCP 服务」页面新建：

| 字段 | 值 |
|------|-----|
| 传输方式 | HTTP（Streamable HTTP） |
| URL | `http://127.0.0.1:9092/mcp` |
| 自定义请求头 | 留空；若启动时设置了 `MCP_TEST_TOKEN`，填 `{"Authorization": "Bearer <token>"}` |

注意：平台需以 local profile 运行（`KING_AI_BLOCK_PRIVATE_NETWORKS=false`），
否则出站防护会拦截 localhost/127.0.0.1 目标。

## 测试工具

| 工具 | 参数 | 用途 |
|------|------|------|
| echo | text（必填） | 字符串参数与基本连通性 |
| add | a、b（数字） | 数字参数 |
| get_current_time | 无 | 无参调用 |
| slow_echo | text、seconds（默认 3，上限 120） | 长耗时/超时（平台调用超时 60s） |
| simulate_error | message（可选） | 工具执行报错（isError=true） |

## 协议要点

- 端点：任意路径均接受 POST（推荐 `/mcp`），JSON-RPC 单条或批量
- `initialize` 协商协议版本（支持 2024-11-05 / 2025-03-26 / 2025-06-18），下发 `Mcp-Session-Id`
- 通知返回 `202 Accepted`；响应为 `application/json`（平台客户端支持 JSON 与 SSE）
- `GET /mcp` 返回 405（不提供服务器 initiates 的 SSE 流）；`DELETE` 结束会话
