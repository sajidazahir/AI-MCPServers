@echo off
REM Local option, step 2 of 2 - starts jira-ollama-client pointed at the LOCAL
REM jira-mcp-server (localhost:8080) instead of Fly.io. Run
REM 2a-start-jira-mcp-server-local.bat first, in its own window, and leave it
REM running. Leave this window open too - closing it stops the backend.

set OAUTH_CLIENT_SECRET=GWelzg1xhch7aNWW4oDgCni0cbzrstnbVQ3dO4oLeQ
set JIRA_MCP_SERVER_URL=http://localhost:8080

cd /d "%~dp0jira-ollama-client"
java -jar target\jira-ollama-client-0.1.0.jar
