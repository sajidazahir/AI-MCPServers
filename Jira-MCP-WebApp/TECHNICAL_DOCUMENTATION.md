# Jira MCP WebApp — How It Works

This document explains the architecture of Jira MCP WebApp: what each piece is, how a question typed into the hub page turns into a real answer from Jira, and the non-obvious design decisions and gotchas behind it. For compile/run commands, see [README.md](README.md).

## What this is, in one paragraph

You open `http://localhost:8090` in a browser, click the **Jira** card, and type a question like "What projects exist in Jira?" That page and the question you type are both served by a small local web server (`jira-ollama-client`), which hands the question to a local **Ollama** language model. The model decides which Jira operation answers the question, and calls it as an **MCP tool** against `jira-mcp-server` — a separate service that actually talks to Jira Cloud's REST API. That service can be the one deployed on Fly.io (the default), or a copy running on your own machine on port 8080 — same code either way, just a different `JIRA_MCP_SERVER_URL` pointed at it (see README's "Two ways to run it"). The result flows back through the same chain and the model turns it into a plain-English answer, which lands back in the browser.

## The pieces

| Piece | What it is | Where it runs |
|---|---|---|
| Hub page (`static/index.html`) | Browser UI — project launcher + Jira Q&A panel | Served by `jira-ollama-client`, opened in your browser at `http://localhost:8090` |
| `jira-ollama-client` | Spring Boot app: Ollama chat client + MCP client + web server. Serves the hub page and `POST /api/ask` | Your machine, `localhost:8090` |
| Ollama | Local LLM runtime, model `qwen2.5:3b` | Your machine, `localhost:11434` |
| `jira-mcp-server` | Spring Boot app: exposes Jira Cloud as MCP tools, protected by OAuth2 | Fly.io (`jira-mcp-server-sajida.fly.dev`), or optionally `localhost:8080` |
| Jira Cloud | The actual Jira site | Atlassian's servers |

`jira-mcp-server` and `jira-ollama-client` are two independent Maven projects (each its own `pom.xml`), because they share almost no dependencies — the server needs Spring Web/Security/a Jira REST client, the client needs the Ollama SDK and an MCP *client* library — and only one of them (the server) needs to run continuously. They talk to each other only over HTTP/MCP, never shared code. Same shape as "a website" and "a browser."

## Architecture

```
┌──────────────┐  fetch POST /api/ask   ┌────────────────────┐   chat prompt    ┌────────┐
│  hub page    │ ─────────────────────▶ │  jira-ollama-client │ ───────────────▶│ Ollama │
│  (browser)   │ ◀───────────────────── │  (localhost:8090)   │ ◀───────────────│ (LLM)  │
└──────────────┘     {"answer": "..."}  └──────────┬──────────┘   tool-call decision
                                                    │
                                                    │ MCP (Streamable-HTTP)
                                                    │ Authorization: Bearer <OAuth2 token>
                                                    ▼
                                         ┌────────────────────┐   REST + Basic Auth   ┌────────────┐
                                         │   jira-mcp-server   │ ─────────────────────▶│ Jira Cloud │
                                         │  (Fly.io / :8080)   │ ◀──────────────────────│  REST API  │
                                         └────────────────────┘      JSON responses    └────────────┘
```

Two independent trust boundaries exist here, easy to conflate:

1. **`jira-ollama-client` → `jira-mcp-server`**: OAuth2 **Client Credentials** grant. `jira-mcp-server` acts as both the Authorization Server (issues tokens at `/oauth2/token`) and the Resource Server (validates them on `/mcp/**`) — both roles live in the same JVM.
2. **`jira-mcp-server` → Jira Cloud**: plain HTTP Basic Auth using a Jira email + API token. Jira Cloud has no idea OAuth2 is involved at all — that layer only exists between our own two services.

## Walkthrough: clicking "Jira" and asking a question

1. **Hub page loads** at `http://localhost:8090` — Spring Boot serves [static/index.html](jira-ollama-client/src/main/resources/static/index.html) automatically as the welcome page, no separate web server involved. Its JS polls `GET /api/health` every 10s to show a green/red status dot — this is how you know whether `jira-ollama-client` is up.
2. **You click the Jira card**, type a question, and the page does `fetch("/api/ask", { method: "POST", body: JSON.stringify({ question }) })`. Because the page and the API are served by the same app on the same origin, no CORS handling is needed at all — see "A gotcha hit building the web layer" below for why that wasn't always true.
3. [AskController.java](jira-ollama-client/src/main/java/com/example/jiraclient/AskController.java) receives the POST, rejects it with `400` if the question is blank, and otherwise calls `chatClient.prompt(question).call().content()`.
4. That `ChatClient` (built in `AskController`'s constructor from Spring AI's auto-configured `ChatClient.Builder` + `ToolCallbackProvider`) sends the question to the local Ollama model along with the list of Jira tools `jira-mcp-server` advertises (project list, issue search, create/update issue, etc.).
5. The model decides which tool answers the question and with what arguments — e.g. "What projects exist in Jira?" → call `jira_list_projects` with no arguments.
6. Spring AI's MCP client turns that into a real MCP `tools/call` request over Streamable-HTTP to `jira-mcp-server`. Before sending, [McpSecurityConfig.java](jira-ollama-client/src/main/java/com/example/jiraclient/McpSecurityConfig.java) attaches a cached OAuth2 bearer token (fetched once at startup — see "Known gotchas" — not re-fetched per request).
7. `jira-mcp-server`'s Resource Server filter chain validates the JWT in-process (no network round-trip — the signing key is shared directly since the AS and RS are the same app), then routes the call to the matching `@McpTool` method in `JiraTools`.
8. `JiraTools` delegates to `JiraClient`, which calls the real Jira Cloud REST API v3 (e.g. `/rest/api/3/search/jql`) with Basic Auth, and maps the raw Jira JSON into small DTOs.
9. The result travels back: Jira → `JiraClient` → `JiraTools` → MCP response → Spring AI's tool-calling loop → the Ollama model, which turns the structured result into a natural-language sentence.
10. That sentence is `chatClient...content()`'s return value, wrapped as `{"answer": "..."}` by `AskController`, and rendered in the hub page's answer log.

## What's running where

| Component | Location | Port | How it's started |
|---|---|---|---|
| Hub page | Your browser | — | `3-open-jira-hub.bat` opens `http://localhost:8090`, or just navigate there yourself |
| `jira-ollama-client` | Your machine | 8090 | `2-start-jira-backend.bat` (→ Fly.io) or `2b-start-jira-backend-local.bat` (→ local server) |
| Ollama | Your machine | 11434 | The Ollama app/service — must already be running, either way |
| `jira-mcp-server` | Fly.io (Option A, normal case) | 443 (HTTPS) | Already deployed — nothing to start |
| `jira-mcp-server` | Your machine (Option B, fallback) | 8080 | `2a-start-jira-mcp-server-local.bat` |

`jira-ollama-client` doesn't know or care which `jira-mcp-server` it's talking to — `JIRA_MCP_SERVER_URL` (set by whichever batch file you ran) is the only thing that differs between Option A and Option B. Everything downstream of that env var — the OAuth2 flow, the MCP calls, the tool list — behaves identically against either target.

## The web backend's HTTP surface

```
GET  /                  → the hub page (static/index.html)
GET  /api/health        → "ok"                              (used by the hub page's status dot)
POST /api/ask           → {"answer": "..."}                 body: {"question": "..."}
```

The `/api/**` endpoints are unauthenticated on purpose — `jira-ollama-client` is a local, single-user dev tool bound to `localhost`, not something exposed to the network. The OAuth2 machinery in this codebase secures the *outbound* call to `jira-mcp-server`, not these inbound endpoints.

## A gotcha hit building the web layer

**Adding a web server silently locked every endpoint behind a login page.** `jira-ollama-client` already depended on `spring-boot-starter-oauth2-client` (needed for the outbound token-fetching described above). The moment `spring-boot-starter-web` was added so the app could serve HTTP at all, Spring Boot's security auto-configuration activated and started 302-redirecting every request — including `/api/health` — to `/login`. The OAuth2-client dependency and a servlet web context together are exactly the combination that triggers Spring Security's default "secure everything" filter chain. Fix: [WebSecurityConfig.java](jira-ollama-client/src/main/java/com/example/jiraclient/WebSecurityConfig.java) defines an explicit `SecurityFilterChain` that permits all requests and disables CSRF, since this app's own endpoints don't need login-based protection — only the token-fetching client behavior (a separate concern) is meant to be secured.

Earlier versions of the hub page were a standalone HTML file opened as `file://` from disk, which meant the browser sent `Origin: null` and every `/api` call needed a wildcard CORS config to succeed. Moving the page into `src/main/resources/static/` so `jira-ollama-client` serves it itself removed that entire problem — page and API now share one origin (`http://localhost:8090`), so no CORS configuration exists in the codebase at all anymore.

## Other design decisions worth knowing

- **Model choice — `qwen2.5:3b`.** Smaller models (`0.5b`, `1.5b`) described what they *would* call instead of actually invoking a tool, even though Ollama advertised a `tools` capability for them. `3b` was the smallest size that reliably made real tool calls on this hardware (7.8GB RAM). An 8B model OOM'd outright. Lesson generalized: a model's advertised tool-calling capability flag doesn't guarantee it reliably calls tools in practice — verify empirically.
- **Fly.io is scaled to 1 machine.** `jira-mcp-server` keeps MCP session state in memory. Fly's default 2-machine HA setup broke sessions outright (a session created on machine A didn't exist on machine B, so a follow-up request could 404 with "Session not found"). For a personal, single-user tool, one machine is simply correct, not a workaround.
- **The OAuth2 token is pre-warmed at startup**, not fetched lazily on the first MCP call. The MCP Java SDK wraps each outbound `customize()` callback (which is where the bearer token gets attached) in a hardcoded ~20-second timeout that isn't configurable via any Spring property. If the first real MCP call also had to do a cold first-time token fetch, occasionally the combined cost (TLS negotiation + token fetch) exceeded that budget. `McpSecurityConfig` fetches and caches the token during normal (unbounded) Spring bean startup instead, so by the time the first MCP call happens, attaching the token is a cache hit.
- **`spring.ai.mcp.client.request-timeout` defaults to 20 seconds** and is currently *not* overridden anywhere in this codebase. If `jira-mcp-server` is slow to respond (cold start, a large JQL search, etc.), calls can time out with `TimeoutException: Did not observe any item or terminal signal within 20000ms`. Worth knowing this property exists in [application.yml](jira-ollama-client/src/main/resources/application.yml) if that error shows up again.
- **The `/mcp` endpoint can 404 even when correctly registered**, if Spring's default static-resource `HandlerMapping` (which claims `/**` looking for static files) gets first crack at the request. Fixed via `spring.mvc.static-path-pattern: /static/**` on the server side. Worth remembering if a route that "should obviously work" 404s anyway — check handler-mapping precedence before suspecting the route itself.
- **The Authorization Server and Resource Server share signing material in-process**, rather than the Resource Server validating tokens via a network call back to its own `/.well-known/oauth-authorization-server` endpoint. Since `jira-mcp-server` is its own issuer, that network path is a dependency cycle (the app can't finish starting until it can serve that endpoint, but can't serve anything until it finishes starting) — it hung ~54s then crashed on every startup until fixed.
- **A Fly.io trial expiring looks exactly like a network/TLS bug, not a billing message.** When the free trial period ended, every request to `jira-mcp-server-sajida.fly.dev` — even a plain `curl`, nothing to do with this codebase — failed with a raw TLS handshake error (`schannel: failed to receive handshake`), not an HTTP error of any kind. The real cause only showed up via `flyctl status --app jira-mcp-server-sajida`, which returned `trial has ended, please add a credit card`. If Fly.io connections start failing at the TLS layer with no HTTP response at all, check `flyctl status` before assuming an app or network bug — and use Option B (local) from the README as an immediate workaround.

## Tech stack

- Java 21, Maven
- Spring Boot 4.0.2, Spring AI 2.0.0 (`@McpTool` annotation API, needs Jackson 3 → Boot 4, not 3.5)
- MCP Streamable-HTTP transport
- Spring Authorization Server + Resource Server (OAuth2 Client Credentials grant), `org.springaicommunity:mcp-server-security` / `mcp-client-security`
- Jira Cloud REST API v3 (`/rest/api/3/search/jql`, ADF for rich text bodies)
- Ollama (`qwen2.5:3b`) as the local reasoning model
- Docker (multi-stage build) + Fly.io for `jira-mcp-server`'s public deployment
- Plain HTML/CSS/JS for the hub page — no build step, no framework, served as a Spring Boot static resource
