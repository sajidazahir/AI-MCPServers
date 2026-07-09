@echo off
REM Runs the Ollama-based Jira chat client against the deployed jira-mcp-server on Fly.io.
REM If the server has been idle, the first request may take a few extra seconds to wake it up.

set OAUTH_CLIENT_SECRET=GWelzg1xhch7aNWW4oDgCni0cbzrstnbVQ3dO4oLeQ
set JIRA_MCP_SERVER_URL=https://jira-mcp-server-sajida.fly.dev

cd /d "%~dp0jira-ollama-client"
java -jar target\jira-ollama-client-0.1.0.jar
