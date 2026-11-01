@echo off
cd /d "%~dp0"
where mvn >nul 2>nul
if %errorlevel%==0 (
    mvn spring-boot:run
) else (
    echo [!] PATH 中未找到 mvn，请在 IDEA 中直接运行 McpSpringServerApplication
)
pause
