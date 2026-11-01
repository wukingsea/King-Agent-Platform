#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""King-AI MCP 测试服务（MCP Streamable HTTP 传输，零依赖）。

用于验证 King-AI-Platform 的 MCP 接入能力（king-ai-api 的 McpClient）。
覆盖协议面：initialize / notifications/initialized / ping / tools/list /
tools/call，协议版本协商，Mcp-Session-Id 会话头，JSON-RPC 批量消息、
通知（202）、标准错误码（-32700/-32600/-32601/-32602），工具执行报错。

启动：python mcp_test_server.py [端口]     默认端口 9092
环境变量：
  MCP_TEST_HOST   监听地址，默认 127.0.0.1
  MCP_TEST_PORT   监听端口，默认 9092（命令行参数优先）
  MCP_TEST_TOKEN  设置后要求请求头 Authorization: Bearer <token>
"""
import json
import os
import sys
import threading
import time
import uuid
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:  # GBK 控制台兜底：中文日志不可编码时替换而不是崩溃
    sys.stdout.reconfigure(errors="replace")
    sys.stderr.reconfigure(errors="replace")
except Exception:
    pass

HOST = os.environ.get("MCP_TEST_HOST", "127.0.0.1").strip() or "127.0.0.1"
DEFAULT_PORT = int(os.environ.get("MCP_TEST_PORT", "9092"))
AUTH_TOKEN = os.environ.get("MCP_TEST_TOKEN", "").strip()
PROTOCOL_VERSION = "2025-03-26"
SUPPORTED_PROTOCOLS = {"2024-11-05", "2025-03-26", "2025-06-18"}
SERVER_NAME = "king-ai-mcp-test"
MAX_SLEEP_SECONDS = 120.0

_print_lock = threading.Lock()
_sessions = set()
_sessions_lock = threading.Lock()


def log(message):
    line = "%s %s" % (datetime.now().strftime("%H:%M:%S.%f")[:-5], message)
    with _print_lock:
        print(line, flush=True)


class ToolExecutionError(Exception):
    """工具执行失败：以 isError=true 的工具结果返回（不是协议错误）。"""


class RpcError(Exception):
    """协议层错误：以 JSON-RPC error 响应返回。"""

    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def _require(args, key, expected):
    value = args.get(key)
    if isinstance(value, bool) or not isinstance(value, expected):
        raise ToolExecutionError("参数 %s 必须是 %s" % (key, expected.__name__))
    return value


def tool_echo(args):
    return "echo: " + _require(args, "text", str)


def tool_add(args):
    a = _require(args, "a", (int, float))
    b = _require(args, "b", (int, float))
    total = a + b
    if isinstance(total, float) and total.is_integer():
        total = int(total)
    return "a + b = %s" % total


def tool_get_current_time(args):
    now = datetime.now()
    weekdays = "一二三四五六日"
    return now.strftime("%Y-%m-%d %H:%M:%S") + " 星期" + weekdays[now.weekday()] + "（服务器本地时区）"


def tool_slow_echo(args):
    text = str(args.get("text", "slow"))
    seconds = args.get("seconds", 3)
    if isinstance(seconds, bool) or not isinstance(seconds, (int, float)) or seconds < 0:
        raise ToolExecutionError("参数 seconds 必须是非负数字")
    seconds = min(float(seconds), MAX_SLEEP_SECONDS)
    time.sleep(seconds)
    return "等待 %.1f 秒后回显: %s" % (seconds, text)


def tool_simulate_error(args):
    raise ToolExecutionError(str(args.get("message", "模拟工具执行失败（用于测试错误处理）")))


def _schema(properties, required):
    return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}


TOOLS = [
    {
        "name": "echo",
        "description": "原样回显输入文本。验证 MCP 连通性与字符串参数传递。",
        "inputSchema": _schema({"text": {"type": "string", "description": "要回显的文本"}}, ["text"]),
        "handler": tool_echo,
    },
    {
        "name": "add",
        "description": "计算两个数字之和。验证数字类型参数。",
        "inputSchema": _schema({
            "a": {"type": "number", "description": "第一个加数"},
            "b": {"type": "number", "description": "第二个加数"},
        }, ["a", "b"]),
        "handler": tool_add,
    },
    {
        "name": "get_current_time",
        "description": "获取服务器当前日期、时间与星期。验证无参工具调用。",
        "inputSchema": _schema({}, []),
        "handler": tool_get_current_time,
    },
    {
        "name": "slow_echo",
        "description": "先等待指定秒数（默认 3，上限 120）再回显文本。验证长耗时调用与超时行为。",
        "inputSchema": _schema({
            "text": {"type": "string", "description": "要回显的文本"},
            "seconds": {"type": "number", "description": "等待秒数，默认 3"},
        }, []),
        "handler": tool_slow_echo,
    },
    {
        "name": "simulate_error",
        "description": "始终执行失败的模拟工具。验证平台对工具报错（isError）的处理。",
        "inputSchema": _schema({"message": {"type": "string", "description": "自定义错误信息"}}, []),
        "handler": tool_simulate_error,
    },
]
_TOOL_MAP = {tool["name"]: tool for tool in TOOLS}


def _rpc_result(message_id, result):
    return {"jsonrpc": "2.0", "id": message_id, "result": result}


def _rpc_error(message_id, code, message):
    return {"jsonrpc": "2.0", "id": message_id, "error": {"code": code, "message": message}}


def _text_result(text, is_error=False):
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


def _initialize_result(params):
    requested = str(params.get("protocolVersion") or "")
    negotiated = requested if requested in SUPPORTED_PROTOCOLS else PROTOCOL_VERSION
    return {
        "protocolVersion": negotiated,
        "capabilities": {"tools": {"listChanged": False}},
        "serverInfo": {"name": SERVER_NAME, "version": "1.0.0"},
        "instructions": "King-AI MCP 测试服务：echo / add / get_current_time / slow_echo / simulate_error。",
    }


def _call_tool(params):
    if not isinstance(params, dict):
        raise RpcError(-32602, "tools/call 缺少参数")
    tool = _TOOL_MAP.get(params.get("name"))
    if tool is None:
        raise RpcError(-32602, "未知工具: %r" % (params.get("name"),))
    arguments = params.get("arguments")
    if arguments is None:
        arguments = {}
    if not isinstance(arguments, dict):
        raise RpcError(-32602, "工具参数 arguments 必须是对象")
    started = time.monotonic()
    try:
        text = tool["handler"](arguments)
    except ToolExecutionError as ex:
        return _text_result(str(ex), is_error=True)
    finally:
        log("工具 %s 调用完成，耗时 %.0f ms" % (tool["name"], (time.monotonic() - started) * 1000))
    return _text_result(str(text))


def _dispatch(method, params):
    if method == "initialize":
        return _initialize_result(params)
    if method == "ping":
        return {}
    if method == "tools/list":
        tools = [{"name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"]} for t in TOOLS]
        return {"tools": tools}
    if method == "tools/call":
        return _call_tool(params)
    raise RpcError(-32601, "Method not found: %s" % method)


def _handle_message(message):
    """处理一条 JSON-RPC 消息，返回 (响应体或 None, 新会话 ID 或 None)。"""
    if not isinstance(message, dict) or ("method" not in message
                                         and "result" not in message and "error" not in message):
        message_id = message.get("id") if isinstance(message, dict) else None
        return _rpc_error(message_id, -32600, "Invalid Request"), None
    method = message.get("method")
    message_id = message.get("id")
    if method is None:
        return None, None  # 客户端发来的 JSON-RPC 响应，无需回复
    params = message.get("params")
    if params is not None and not isinstance(params, dict):
        if message_id is None:
            return None, None
        return _rpc_error(message_id, -32602, "params 必须是对象"), None
    if message_id is None:
        return None, None  # 通知（如 notifications/initialized）：202，无响应体
    try:
        result = _dispatch(method, params if isinstance(params, dict) else {})
    except RpcError as ex:
        return _rpc_error(message_id, ex.code, str(ex)), None
    new_session = uuid.uuid4().hex if method == "initialize" else None
    return _rpc_result(message_id, result), new_session


class McpStreamableHttpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = SERVER_NAME + "/1.0"

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS")
        self.send_header(
            "Access-Control-Allow-Headers",
            "Content-Type, Accept, Authorization, MCP-Protocol-Version, Mcp-Session-Id")

    def _send(self, status, payload=None, extra=None):
        body = b""
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self._cors()
        if status == 204:
            self.end_headers()
            return
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _authorized(self):
        if not AUTH_TOKEN:
            return True
        header = self.headers.get("Authorization") or ""
        return header.strip() == "Bearer " + AUTH_TOKEN

    def _session_extra(self, new_session):
        if new_session:
            with _sessions_lock:
                _sessions.add(new_session)
            return {"Mcp-Session-Id": new_session}
        current = (self.headers.get("Mcp-Session-Id") or "").strip()
        return {"Mcp-Session-Id": current} if current else {}

    def do_POST(self):
        if not self._authorized():
            self._send(401, {"error": "unauthorized: 缺少或错误的 Bearer Token"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length > 0 else b""
        except (ValueError, OSError):
            self._send(400, _rpc_error(None, -32700, "无法读取请求体"))
            return
        if not raw.strip():
            self._send(400, _rpc_error(None, -32700, "Parse error: 请求体为空"))
            return
        try:
            messages = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send(400, _rpc_error(None, -32700, "Parse error: 请求体不是合法 JSON"))
            return
        batch = isinstance(messages, list)
        targets = messages if batch else [messages]
        responses = []
        new_session = None
        for message in targets:
            response, issued = _handle_message(message)
            new_session = issued or new_session
            if response is not None:
                responses.append(response)
        extra = self._session_extra(new_session)
        if not responses:
            self._send(202, None, extra=extra)
        else:
            self._send(200, responses if batch else responses[0], extra=extra)

    def do_GET(self):
        self._send(405, {"error": "此测试服务不提供 GET SSE 流，请使用 POST 发送 JSON-RPC 消息"},
                   extra={"Allow": "POST, DELETE, OPTIONS"})

    def do_DELETE(self):
        current = (self.headers.get("Mcp-Session-Id") or "").strip()
        if current:
            with _sessions_lock:
                _sessions.discard(current)
        self._send(204)

    def do_OPTIONS(self):
        self._send(204)

    def log_message(self, fmt, *args):
        log("%s %s" % (self.command, self.path))


def main():
    port = DEFAULT_PORT
    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print("端口参数无效: %s" % sys.argv[1], file=sys.stderr)
            return 2
    server = ThreadingHTTPServer((HOST, port), McpStreamableHttpHandler)
    log("MCP 测试服务已启动: http://%s:%d/mcp （Streamable HTTP）" % (HOST, port))
    log("可用工具: %s" % ", ".join(tool["name"] for tool in TOOLS))
    if AUTH_TOKEN:
        log("已启用 Bearer Token 鉴权（环境变量 MCP_TEST_TOKEN）")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log("收到 Ctrl+C，正在退出")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
