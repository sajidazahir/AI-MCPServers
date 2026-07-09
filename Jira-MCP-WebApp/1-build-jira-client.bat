@echo off
REM Step 1 - compiles jira-ollama-client (the web backend). Run this first
REM (either option below needs it), and again any time you change its source.

title JIRA CLIENT - BUILD
color 0F

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
