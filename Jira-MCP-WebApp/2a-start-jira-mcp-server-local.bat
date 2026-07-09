@echo off
REM Local option, step 1 of 2 - builds and starts jira-mcp-server on your own
REM machine (port 8080), loading credentials from jira-mcp-server\.env, instead
REM of using the Fly.io deployment. Leave this window open. Use this only when
REM Fly.io is unreachable/down, or you're developing the server itself.

title JIRA MCP SERVER - LOCAL (:8080)
color 0E

cd /d "%~dp0jira-mcp-server"
call mvn -q -B clean package -DskipTests

if errorlevel 1 (
    echo.
    echo BUILD FAILED - see errors above.
    pause
    exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    set "%%A=%%B"
)

echo.
echo ================================================================
echo   JIRA MCP SERVER  (jira-mcp-server)
echo   Running LOCALLY - talks directly to Jira Cloud
echo   Listening on http://localhost:8080
echo ================================================================
echo.

java -jar target\jira-mcp-server-0.1.0.jar
