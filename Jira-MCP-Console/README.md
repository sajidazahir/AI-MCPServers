# Jira MCP Server (Java / Spring AI)

A self-hosted [Model Context Protocol](https://modelcontextprotocol.io) server exposing Jira Cloud as tools, deployed on Fly.io behind OAuth2, plus a local Ollama-based CLI to test it. For architecture, design decisions, and every bug hit along the way, see [TECHNICAL_DOCUMENTATION.md](TECHNICAL_DOCUMENTATION.md).

## Project layout — two independent projects, and why

```
jira-mcp-server/        The MCP server — deployed at https://jira-mcp-server-sajida.fly.dev
jira-ollama-client/     CLI that asks a local Ollama model your Jira questions
ask-jira.bat            Run the client against the deployed server
start-jira-mcp-server.bat   Run the server locally instead (optional)
```

`jira-mcp-server` is the **service** — it does the actual work (talks to Jira). `jira-ollama-client` is a separate **consumer** of that service — a CLI that asks an Ollama model which Jira tool to call, then calls the service over the network. They're independent Maven projects (each with its own `pom.xml`) because:

- They share almost no dependencies (server needs Spring Security/Web/a Jira REST client; client needs the Ollama SDK/an MCP client library).
- Only one of them runs continuously (the server); the other you run once per question session and it exits.
- They only ever talk to each other over HTTP/MCP — never by sharing code — so there's no reason to force them into one build.

Same shape as "a website" and "a browser": two separate programs, connected only by a network protocol.

## *******How to execute *********
(1) >Started JiraMcpServerApplicatio - run Batch file 
 > (../VS-WS-Jira-MCP-Console/start-jira-mcp-server.bat)  
                -- MCP Server starts Outcome: starts an HTTP server on `localhost:8080`, logs `Started JiraMcpServerApplication` and `Registered tools: 8`, and just sits there listening — like any web server, it doesn't "finish"; it stays running until you Ctrl+C it.
(2) Open another Terminal  --- Run (Docker, alternative to the jar above):**
   > C:\00-Sajida\VS-WS-AI-MCPServer\Jira-MCP-Console\jira-mcp-server>docker compose build
        ``bash
   > docker compose build   -- this will open the Docker desktop and build ✔ Image jira-mcp-server-jira-mcp-server Built  [open docker destop manually]
   > docker compose up
```Outcome: same as above, but running inside a container on port 8080.
(3)  ### Compile `jira-ollama-client` on its own
     Not a server — a CLI you run whenever you want to ask a question; it exits when you're done.
     **Compile:**
        ```bash
        cd jira-ollama-client
        C:\00-Sajida\VS-WS-Jira-MCP-Test\jira-ollama-client>

        mvn clean package
        ```
        Outcome: no errors, produces `target/jira-ollama-client-0.1.0.jar`.
(4) **Run:**  Jira batch file
   C:\00-Sajida\VS-WS-AI-MCPServer\Jira-MCP-Console\jira-ollama-client>ask-jira.bat  
              Batch file has the following:
                  ```bash
                set OAUTH_CLIENT_SECRET=<the shared secret>
                set JIRA_MCP_SERVER_URL=https://jira-mcp-server-sajida.fly.dev
                java -jar target/jira-ollama-client-0.1.0.jar
                ```
          Outcome: prints `Jira assistant ready...` and a `>` prompt, waits for a question, prints the answer, loops back to `>` — until you type `exit`.
          What happened: jira-ollama-client called an MCP tool on jira-mcp-server

  ******** End of test******

## ************ SUMMARIZED WHAT IS DONE IN THIS PROJECT ****************
The .bat file (start-jira-mcp-server.bat) is an optional, local way to run jira-mcp-server as a plain jar on your own machine — it loads Jira credentials from .env and runs java -jar jira-mcp-server-0.1.0.jar. The comment on line 4 you selected is telling you: you almost never need this, because the server is already running elsewhere (see next point).   Step 1 is optional

fly.dev / Fly.io is where the server actually lives day-to-day. jira-mcp-server is a Spring Boot app packaged via the Dockerfile and deployed to Fly.io (config in fly.toml) at https://jira-mcp-server-sajida.fly.dev. Fly.io is a hosting platform — it keeps that server reachable over the internet, auto-sleeps it when idle (to save cost) and auto-wakes it on the next request (~10-20s cold start). So normally you don't run anything locally for the server — ask-jira.bat just points at that URL.

Why you're running an MCP server at all: the whole point of this project (per the README) is to expose Jira Cloud as a set of tools ("8 tools" per your log) via the Model Context Protocol, so an LLM (here, a local Ollama model qwen2.5:3b, driven by jira-ollama-client) can answer questions like "What projects exist in Jira?" by calling those tools instead of you querying Jira's API by hand. The split is:

jira-mcp-server = the service that actually talks to Jira (needs Jira REST client, OAuth2, stays running).
jira-ollama-client = the CLI consumer — asks Ollama which tool to call, calls the server over MCP/HTTP, prints the answer, then exits.
You'd only ever run the .bat file you selected if Fly.io was down/misbehaving and you wanted a local fallback (README's "Case 4"), or if you're actively developing/testing server changes before deploying.
*****END**************









## *************  IN DETAIL ***********************
### Running `jira-mcp-server` on its own

Already deployed on Fly.io — you don't need to start it for normal use. To run/test it locally instead:
server's deployed and OAuth2-protected on Fly.io, ask-jira.bat is ready to go, and both README.md and TECHNICAL_DOCUMENTATION.mdpytho

**Compile:**
```bash
cd jira-mcp-server
mvn clean package
```
Outcome: no errors, produces `target/jira-mcp-server-0.1.0.jar`.

**Run (plain jar):**
```bash
set -a; source .env; set +a                # load JIRA_BASE_URL / JIRA_EMAIL / JIRA_API_TOKEN etc.
java -jar target/jira-mcp-server-0.1.0.jar
```
Outcome: starts an HTTP server on `localhost:8080`, logs `Started JiraMcpServerApplication` and `Registered tools: 8`, and just sits there listening — like any web server, it doesn't "finish"; it stays running until you Ctrl+C it.

**Run (Docker, alternative to the jar above):**
```bash
docker compose build   -- this will open the Docker desktop and build ✔ Image jira-mcp-server-jira-mcp-server Built  [if stuck open docker destop manually]
docker compose up
```
Outcome: same as above, but running inside a container on port 8080.
  
  ****** REREUN if MCP server is STOPPED

        Case 1: It just went idle (the normal case)
        Fly.io auto-sleeps the machine after a few minutes of no traffic (that's expected, not a failure). You don't need to do anything 
        — just run ask-jira.bat and ask your question again. The first request after idle takes ~10-20s while it wakes up, then it's fast again.

        Case 2: You want to check if it's actually broken (not just asleep)
        Check its status:
        flyctl status --app jira-mcp-server-sajida
        Outcome: should show STATE: started (or stopped, which is normal/idle — it'll wake on the next request).

        Check recent logs if something seems wrong:


        flyctl logs --app jira-mcp-server-sajida --no-tail
        Look for Started JiraMcpServerApplication near the bottom (healthy) vs. a stack trace/crash loop (actually broken).

        Case 3: It's genuinely stopped and won't wake on its own

        flyctl machine list --app jira-mcp-server-sajida
        flyctl machine start <machine-id> --app jira-mcp-server-sajida
        Or, if the code/config hasn't changed, just redeploy the existing build to force a fresh restart:
        cd jira-mcp-server
        flyctl deploy

       ***   FALLBACK ****
        Case 4: Fallback — run it on your own machine instead
        If Fly.io itself is having problems (or you just want to work offline from your deployed instance), run the server locally and point the client at it:
        "fallback" just means: temporarily swap the server's address from the internet (Fly.io) to your own computer (localhost).   
        1. Start the server locally (in one terminal window, leave it running):
                cd jira-mcp-server
                set -a; source .env; set +a
                java -jar target/jira-mcp-server-0.1.0.jar
                Then set JIRA_MCP_SERVER_URL=http://localhost:8080 before running the client instead of the Fly.io URL.
     2. Tell the client to use that local address instead of Fly.io (in a second, separate terminal window):


                cd jira-ollama-client
                set OAUTH_CLIENT_SECRET=<the shared secret>
                set JIRA_MCP_SERVER_URL=http://localhost:8080
                java -jar target/jira-ollama-client-0.1.0.jar
                The only thing that changed from the normal ask-jira.bat run is that one line — JIRA_MCP_SERVER_URL now points at your own machine instead of https://jira-mcp-server-sajida.fly.dev.

    3 . Use it exactly the same way — type your question at the > prompt, get your answer.
        For 95% of cases, it's just Case 1 — try again and it wakes up on its own.

When you're done
Close the client normally (exit), then go back to the server's terminal window and press Ctrl+C to stop it. Once Fly.io is working again, just go back to plain ask-jira.bat — no code changes needed, only that one URL swap.

### Running `jira-ollama-client` on its own

Not a server — a CLI you run whenever you want to ask a question; it exits when you're done.

**Compile:**
```bash
cd jira-ollama-client
C:\00-Sajida\VS-WS-Jira-MCP-Test\jira-ollama-client>

mvn clean package
```
Outcome: no errors, produces `target/jira-ollama-client-0.1.0.jar`.

**Run:**
```bash
set OAUTH_CLIENT_SECRET=<the shared secret>
set JIRA_MCP_SERVER_URL=https://jira-mcp-server-sajida.fly.dev
java -jar target/jira-ollama-client-0.1.0.jar
```
Outcome: prints `Jira assistant ready...` and a `>` prompt, waits for a question, prints the answer, loops back to `>` — until you type `exit`.

The dependency direction only goes one way: the client needs `JIRA_MCP_SERVER_URL` pointing at a server that's *already reachable* — either the deployed Fly.io one (default, nothing else to start) or, if you set `JIRA_MCP_SERVER_URL=http://localhost:8080` instead, a locally-running server you started first per above. The server never needs the client running; the client always needs the server running.

`ask-jira.bat` is just the client block above with both env vars already filled in — the one you'll actually use day-to-day.

## Prerequisites

- JDK 21, Maven
- [Ollama](https://ollama.com) running locally, with `qwen2.5:3b` pulled (`ollama pull qwen2.5:3b`)
- Only if rebuilding/redeploying the server: Docker Desktop, [flyctl](https://fly.io/docs/flyctl/install/), a Jira Cloud API token, a Fly.io account

## Run it (day to day)

The server is already deployed — nothing to start except Ollama.

```
.\ask-jira.bat
```
Wait for `Jira assistant ready...`, then type a question, e.g. `What projects exist in Jira?`. Type `exit` to quit.

> Run this from a real terminal (VS Code's integrated Terminal, `` Ctrl+` ``, or a Command Prompt window) — not an output-only panel like the Code Runner extension, since it needs to read your typed input.
>
> The first question of a session may take 10–20s if the Fly.io server has gone idle and needs to wake up.

## Redeploy the server after changes

**Compile first, to catch errors before deploying:**
```bash
cd jira-mcp-server
mvn clean package
```
Outcome: no errors, produces `target/jira-mcp-server-0.1.0.jar`.

**Deploy:**
```bash
flyctl secrets set JIRA_BASE_URL=... JIRA_EMAIL=... JIRA_API_TOKEN=... PUBLIC_URL=... OAUTH_CLIENT_SECRET=...   # only if a secret value changed
flyctl deploy
```
Outcome: `flyctl deploy` builds a fresh Docker image (from the same `Dockerfile`, remotely on Fly's builder) and rolls it out to `https://jira-mcp-server-sajida.fly.dev`. Verify with the same `curl` check from [TECHNICAL_DOCUMENTATION.md](TECHNICAL_DOCUMENTATION.md) — unauthenticated should get `403`, and a request with a valid Bearer token should return real Jira data.

## Everything else

Architecture diagrams, the full tool list, every design decision, and a phase-by-phase account of every bug encountered (and how it was diagnosed) live in **[TECHNICAL_DOCUMENTATION.md](TECHNICAL_DOCUMENTATION.md)**.


•	What's running where table — clarifies the server lives on Fly.io (auto-wakes), only Ollama needs to be running locally, and the client is on-demand, not a server.
•	Prerequisites table with version-check commands.
•	Day-to-day usage — the 4 steps you'll actually use (ask-jira.bat), each with its expected outcome.
•	Full step-by-step build-from-scratch — 7 numbered steps from getting a Jira token through deploying to Fly.io and running the client, each with the exact command and what success looks like.
•	Architecture diagram updated to show the OAuth2 token flow.
•	Known gotchas — all 5 real bugs we hit and fixed, written as a reference so future-you doesn't rediscover them the hard way.

The output appears directly in the console window itself — nothing gets written to a log file.
•	If you double-click ask-jira.bat in File Explorer, a black Command Prompt window pops up and stays open. That window is the output — you'll see the Jira assistant ready... prompt, and your answers print right there after each question.
•	If you run it from a terminal (PowerShell/Command Prompt) instead, the output prints inline in that same terminal window you ran it from.
Either way, it's an interactive session: type your question, press Enter, watch the answer appear right below it in that same window. Type exit when done and the window closes (if double-clicked) or returns to your prompt (if run from a terminal).
If you want to keep a record of the conversation instead of just watching it, you could redirect output to a file, e.g. run ask-jira.bat > output.txt from a terminal — though then you won't see it live, only after you exit.


##  GITHB
…or create a new repository on the command line
echo "# JiraAssistant-MCPServer" >> README.md
git init
git add README.md
git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/sajidazahir/JiraAssistant-MCPServer.git
git push -u origin main

…or push an existing repository from the command line
git remote add origin https://github.com/sajidazahir/JiraAssistant-MCPServer.git
git branch -M main
git push -u origin main
