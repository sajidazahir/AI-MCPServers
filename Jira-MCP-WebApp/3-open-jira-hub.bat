@echo off
REM Final step - opens the hub page in your default browser. The page is served
REM by jira-ollama-client itself, so either 2-start-jira-backend.bat (Fly.io) or
REM 2b-start-jira-backend-local.bat (local) must already be running before this
REM will load anything.

start "" "http://localhost:8090/"
