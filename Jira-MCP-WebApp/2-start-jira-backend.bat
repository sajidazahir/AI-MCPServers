@echo off
REM Step 2 of 3 - starts jira-ollama-client as the local web backend for the hub
REM page, listening on http://localhost:8090. Talks to jira-mcp-server on Fly.io.
REM Leave this window open - closing it stops the backend. Run 1-build-jira-client.bat
REM first if you haven't built the jar yet (or changed its code since).

title JIRA BACKEND - FLY.IO (:8090)
color 0A

set OAUTH_CLIENT_SECRET=GWelzg1xhch7aNWW4oDgCni0cbzrstnbVQ3dO4oLeQ
set JIRA_MCP_SERVER_URL=https://jira-mcp-server-sajida.fly.dev

cd /d "%~dp0jira-ollama-client"

echo.
echo ================================================================
echo   JIRA BACKEND  (jira-ollama-client)
echo   Talking to jira-mcp-server on FLY.IO
echo   Hub page + API on http://localhost:8090
echo ================================================================
echo.

java -jar target\jira-ollama-client-0.1.0.jar
