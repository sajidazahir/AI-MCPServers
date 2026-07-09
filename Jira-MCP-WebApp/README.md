# Jira MCP WebApp

Ask Jira questions in plain English from a browser page. A local Ollama model reads your question, decides which Jira tool to call, and calls it over [MCP](https://modelcontextprotocol.io) against a Jira Cloud-backed server — either the one deployed on Fly.io, or one running on your own machine.

## Project layout

```
jira-mcp-server/          MCP server — talks to Jira Cloud. Deployed at https://jira-mcp-server-sajida.fly.dev
jira-ollama-client/       Ollama model + MCP client. Runs as a local web server (port 8090) that also serves the hub page.

1-build-jira-client.bat            Compiles jira-ollama-client. Needed for both options below.

Option A - Fly.io (default, day-to-day):
  2-start-jira-backend.bat           Runs jira-ollama-client pointed at the deployed Fly.io server.
  3-open-jira-hub.bat                Opens http://localhost:8090 in your browser.

Option B - local (fallback, or when developing jira-mcp-server itself):
  2a-start-jira-mcp-server-local.bat Builds and runs jira-mcp-server on your own machine (port 8080).
  2b-start-jira-backend-local.bat    Runs jira-ollama-client pointed at that local server instead of Fly.io.
  3-open-jira-hub.bat                Same hub page, same URL - works with either option.
```

For how the pieces fit together and why, see [TECHNICAL_DOCUMENTATION.md](TECHNICAL_DOCUMENTATION.md).

## Prerequisites

- JDK 21, Maven
- [Ollama](https://ollama.com) running locally, with `qwen2.5:3b` pulled: `ollama pull qwen2.5:3b`
- Only for Option B (running `jira-mcp-server` locally), or redeploying it: Docker Desktop, [flyctl](https://fly.io/docs/flyctl/install/), a Jira Cloud API token, a Fly.io account

For Option A (the default) you only need Ollama — `jira-mcp-server` is already deployed, no Docker/flyctl/Jira token needed on your end.

## Two ways to run it

Both options end up in the same place — the hub page at `http://localhost:8090` — the only difference is which `jira-mcp-server` your questions are answered by.

|  | Option A: Fly.io (default) | Option B: Local |
|---|---|---|
| `jira-mcp-server` runs on | Fly.io (already deployed) | Your machine, port 8080 |
| Use when | Normal day-to-day use | Fly.io is down/unreachable, or you're developing the server itself |
| Extra prerequisites | None | Docker Desktop *or* Maven+JDK (either builds it), a Jira API token |
| Batch files | `2-start-jira-backend.bat` | `2a-start-jira-mcp-server-local.bat` + `2b-start-jira-backend-local.bat` |

### Option A: Fly.io (default)

Double-click, in order:

1. `1-build-jira-client.bat` — builds `jira-ollama-client`. Wait for `Build OK`.
2. `2-start-jira-backend.bat` — starts the backend, pointed at Fly.io. Leave this window open. Wait for `Jira assistant ready...`.
3. `3-open-jira-hub.bat` — opens the hub page at `http://localhost:8090`. Click the **Jira** card, type a question, press **Ask**.

### Option B: Local

Double-click, in order:

1. `1-build-jira-client.bat` — builds `jira-ollama-client`. Wait for `Build OK`.
2. `2a-start-jira-mcp-server-local.bat` — builds and starts `jira-mcp-server` on your own machine (port 8080), loading credentials from `jira-mcp-server/.env`. Leave this window open. Wait for `Started JiraMcpServerApplication` and `Registered tools: 8`.
3. `2b-start-jira-backend-local.bat` — starts the backend, pointed at `http://localhost:8080` instead of Fly.io. Leave this window open too. Wait for `Jira assistant ready...`.
4. `3-open-jira-hub.bat` — opens the hub page at `http://localhost:8090`, exactly the same as Option A.


************************************************ MANUAL INSTEAD OF BATCH FILE ****************
## Running it manually, step by step

If you'd rather run each command yourself instead of using the batch files:

**1. Compile `jira-ollama-client`** (needed either way):
```bash
cd jira-ollama-client
mvn clean package
```
Outcome: no errors, produces `target/jira-ollama-client-0.1.0.jar`.

**2a. (Option B only) Compile and start `jira-mcp-server` locally:**
```bash
cd jira-mcp-server
mvn clean package
set -a; source .env; set +a        # loads JIRA_BASE_URL / JIRA_EMAIL / JIRA_API_TOKEN etc.
java -jar target/jira-mcp-server-0.1.0.jar
```
Outcome: starts an HTTP server on `localhost:8080`, logs `Started JiraMcpServerApplication` and `Registered tools: 8`, and stays running until you Ctrl+C it.

Alternative for this step, using Docker instead of a local JDK/Maven build:
```bash
cd jira-mcp-server
docker compose build
docker compose up
```

**2b. Start the backend**, pointed at whichever server you're using:
```bash
set OAUTH_CLIENT_SECRET=GWelzg1xhch7aNWW4oDgCni0cbzrstnbVQ3dO4oLeQ
set JIRA_MCP_SERVER_URL=https://jira-mcp-server-sajida.fly.dev
:: or, for Option B:  set JIRA_MCP_SERVER_URL=http://localhost:8080
java -jar target/jira-ollama-client-0.1.0.jar
```
Outcome: prints `Jira assistant ready...` and starts listening on `http://localhost:8090` — serving both the hub page and its `/api/ask` endpoint. Leave this terminal running; it's a server, not a one-shot command.

**3. Open the hub page** at `http://localhost:8090` in your browser. The Jira card's status dot turns green once it can reach the backend from the previous step.

**4. Ask a question** in the hub page's input box, e.g. `What projects exist in Jira?`, and press **Ask** or Enter. On Option A, the first question of a session may take 10–20s if the Fly.io server has gone idle and needs to wake up.

## If the Fly.io server seems unresponsive

**It's probably just idle** — Fly.io auto-sleeps the machine after a few minutes of no traffic. Ask your question again; the first request after idle takes ~10-20s while it wakes up.

**To check if it's actually broken, not just asleep:**
```bash
flyctl status --app jira-mcp-server-sajida
flyctl logs --app jira-mcp-server-sajida --no-tail
```
Look for `Started JiraMcpServerApplication` near the bottom of the logs (healthy) vs. a stack trace/crash loop (actually broken). If `flyctl status` itself errors with something like `trial has ended, please add a credit card`, that's a Fly.io account/billing issue, not an app problem — the deployment is unreachable until that's resolved on [fly.io](https://fly.io).

**If it's genuinely stopped and won't wake on its own:**
```bash
flyctl machine list --app jira-mcp-server-sajida
flyctl machine start <machine-id> --app jira-mcp-server-sajida
```
Or, if the code/config hasn't changed, redeploy the existing build to force a fresh restart:
```bash
cd jira-mcp-server
flyctl deploy
```

**Either way, use Option B (local) above as a fallback** while Fly.io is sorted out — no code changes needed, just a different set of batch files pointed at your own machine instead.

## Redeploying `jira-mcp-server` after changes

**Compile first, to catch errors before deploying:**
```bash
cd jira-mcp-server
mvn clean package
```

**Deploy:**
```bash
flyctl secrets set JIRA_BASE_URL=... JIRA_EMAIL=... JIRA_API_TOKEN=... PUBLIC_URL=... OAUTH_CLIENT_SECRET=...   # only if a secret value changed
flyctl deploy
```
Outcome: builds a fresh Docker image from the same `Dockerfile`, remotely on Fly's builder, and rolls it out to `https://jira-mcp-server-sajida.fly.dev`.

## Everything else

Architecture, request flow, the OAuth2 setup, and known gotchas live in **[TECHNICAL_DOCUMENTATION.md](TECHNICAL_DOCUMENTATION.md)**.
