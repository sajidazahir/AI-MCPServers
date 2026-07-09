@echo off
REM Step 1 of 3 - compiles jira-ollama-client (the web backend + CLI).
REM Run this first, and again any time you change its source code.

cd /d "%~dp0jira-ollama-client"
call mvn -q -B clean package -DskipTests

if errorlevel 1 (
    echo.
    echo BUILD FAILED - see errors above.
    pause
    exit /b 1
)

echo.
echo Build OK: target\jira-ollama-client-0.1.0.jar
pause
