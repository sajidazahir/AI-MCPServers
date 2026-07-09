@echo off
REM Step 3 of 3 - opens the hub page in your default browser. The page is served
REM by jira-ollama-client itself, so 2-start-jira-backend.bat must already be
REM running before this will load anything.

start "" "http://localhost:8090/"
