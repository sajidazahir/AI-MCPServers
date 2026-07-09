@echo off
REM Starts jira-mcp-server locally (as a plain jar) with credentials loaded from .env.
REM Only needed if you want to run/test the server on your own machine instead of
REM using the one already deployed at https://jira-mcp-server-sajida.fly.dev

cd /d "%~dp0jira-mcp-server"

for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    set "%%A=%%B"
)

java -jar target\jira-mcp-server-0.1.0.jar
