@echo off
rem King-AI MCP 测试服务启动脚本（MCP Streamable HTTP，默认端口 9092）
cd /d "%~dp0"
set "PY=C:\Users\qykin\AppData\Local\Programs\Python\Python313\python.exe"
if not exist "%PY%" set "PY=python"
"%PY%" mcp_test_server.py %*
pause
