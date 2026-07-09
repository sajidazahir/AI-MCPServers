# Building a Jira MCP Server in Java — Technical Walkthrough

This document is a phase-by-phase record of how `jira-mcp-server` and `jira-ollama-client` were designed and built: what each phase did, why, the architecture behind it, the actual code, and — because this is real bleeding-edge tooling (Spring AI 2.0 / Spring Boot 4.0, mid-2026 releases) — the real bugs hit along the way and how they were diagnosed. Reference implementations: [`sooperset/mcp-atlassian`](https://github.com/sooperset/mcp-atlassian) (self-hosted pattern) and [`atlassian/atlassian-mcp-server`](https://github.com/atlassian/atlassian-mcp-server) (official tool/schema reference).

## Goal and constraints

Build a self-hosted MCP server, in Java, exposing Jira Cloud (`sajidauop.atlassian.net`) operations as tools — usable by any MCP client, and specifically tested end-to-end with a **local Ollama model** acting as the AI client, so the whole loop runs with no external API dependency.

Decisions made up front (via clarifying questions, since they shape every later phase):
- **Language/framework**: Java, via Spring AI's MCP server support — the closest thing Java has to Python's FastMCP.
- **Ollama's role**: local test client/host — a small app that uses Ollama as the reasoning engine calling our tools, not an AI feature baked into the server itself.
- **Auth**: Jira API token (email + token, Basic Auth) — simplest option for a personal/single-user tool, versus OAuth 2.0 which is what Atlassian's official *remote* server uses and is overkill here.

Environment confirmed before starting: Java 21.0.7, Maven 3.9.10, Docker 29.5.3, Ollama 0.30.11 (daemon not yet running).

## Project structure — why two Maven projects

The repo contains **two independent Maven projects**, each with its own `pom.xml`:

```
VS-WS-Jira-MCP-Test/
├── jira-mcp-server/        Project 1 — the MCP server (deployed, always reachable)
└── jira-ollama-client/     Project 2 — a CLI test harness (runs on-demand, local only)
```

A `pom.xml` marks one buildable Java program with its own dependency list and its own compiled output (`target/*.jar`). These two projects share almost no dependencies — the server needs Spring Web/Security/a Jira REST client; the client needs the Ollama SDK and an MCP *client* library — so bundling them into one project would mean shipping unused dependencies both ways. This is the same "service" vs. "a client that calls the service" split you'd see in any client-server system; they're connected only over HTTP/MCP, never by shared code or a shared build.

---

## Phase 1 — Project Creation

**What/why:** Scaffold `jira-mcp-server` as a Spring Boot Maven project before writing any Jira-specific logic, so later phases slot into an architecture already understood.

**Architecture:**

```
┌─────────────────┐   MCP (Streamable-HTTP)   ┌──────────────────┐   REST + Basic Auth   ┌─────────────┐
│  MCP Client/Host │ ────────────────────────▶ │  jira-mcp-server │ ─────────────────────▶│  Jira Cloud │
│ (Ollama client)  │ ◀──────────────────────── │  (Spring Boot)   │ ◀──────────────────────│  REST API   │
└─────────────────┘      tool calls/results     └──────────────────┘      JSON responses    └─────────────┘
```

MCP standardizes the left-hand connection so the Jira integration (right-hand side) is built once and becomes usable by any MCP client — Claude Desktop, Cursor, or a custom Ollama client.

**Files created:**
- `pom.xml` — initially `spring-boot-starter-parent` 3.5.0 + `spring-ai-bom` 2.0.0 + `spring-ai-starter-mcp-server-webmvc`.
- `JiraMcpServerApplication.java` — standard `@SpringBootApplication`.
- `application.yml` — placeholder (`server.port: 8080`).
- `.gitignore` — `target/`, `.env`, IDE folders.

**First real bug — dependency version mismatch:**

`mvn clean package` succeeded, but running the jar threw:

```
NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs
```

Root cause: Spring AI 2.0's MCP server internals use **Jackson 3** (`tools.jackson.databind`), which requires a newer `jackson-annotations` than Spring Boot 3.5.0 manages. Spring AI 2.0 is actually built against **Spring Boot 4.0**, which ships Jackson 3 natively. Fix: bump the parent POM to `spring-boot-starter-parent` **4.0.2**. Rebuilt clean — worked immediately.

*Lesson: when a new major version of a framework (Spring AI 2.0) is paired with an old assumption about its runtime (Boot 3.5), don't guess the compatible version — let the first real run tell you, then fix and move on.*

---

## Phase 2 — MCP Server Setup

**What/why:** Turn the Spring Boot app into a real MCP server by adding MCP config and one throwaway tool (`ping`), to prove the transport/tool-registration plumbing works *before* any Jira complexity is added.

**Architecture:** With `spring-ai-starter-mcp-server-webmvc` on the classpath, Spring Boot auto-configures an embedded MCP server that scans beans for `@McpTool`-annotated methods, generates a JSON Schema per method from `@McpToolParam` annotations, and serves it all over MCP's Streamable-HTTP transport (the current MCP spec transport, successor to SSE) on the app's normal HTTP port.

**Code — `application.yml` additions:**
```yaml
spring:
  ai:
    mcp:
      server:
        name: jira-mcp-server
        version: 0.1.0
        type: SYNC
        protocol: STREAMABLE
        annotation-scanner:
          enabled: true
```

**Code — `tools/PingTool.java`:**
```java
@Component
public class PingTool {
    @McpTool(name = "ping", description = "Health check tool...")
    public String ping(@McpToolParam(description = "Message to echo back", required = false) String message) {
        return "pong: " + (message == null ? "hello" : message);
    }
}
```

**Second bug — wrong import package:** the web search that suggested `org.springframework.ai.mcp.server.annotation.McpTool` was wrong. Verified the real package by unzipping the actual jar (`jar tf ... | grep McpTool`) and finding it under **`org.springframework.ai.mcp.annotation`**. Fixed the import, rebuilt, ran — startup log confirmed `Registered tools: 1`.

*Lesson: when documentation/search results disagree with the compiler, trust the jar contents — `jar tf` + `javap` are faster and more reliable than searching for bleeding-edge library internals.*

---

## Phase 3 — Jira REST Client Integration

**What/why:** Build the Java wrapper around Jira Cloud's REST API v3 and expose it as MCP tools.

**Two Jira-specific things worth knowing:**
1. **Search endpoint migration** — the old `/rest/api/3/search` has been **fully removed** by Atlassian. Current endpoint: `/rest/api/3/search/jql`, using `nextPageToken`-based pagination instead of `startAt`/`total`.
2. **ADF (Atlassian Document Format)** — `description` and comment bodies aren't plain strings; they're a JSON document tree. Wrote `AdfUtil` to convert plain text ↔ ADF so the rest of the code only deals with strings.

**Architecture (layers):**
```
JiraTools (@McpTool methods) → JiraClient (REST calls) → RestClient bean (base URL) → Jira Cloud REST API v3
```

**Code — `jira/AdfUtil.java`** (excerpt):
```java
public static Map<String, Object> fromPlainText(String text) {
    return Map.of("type", "doc", "version", 1, "content", List.of(
        Map.of("type", "paragraph", "content", List.of(
            Map.of("type", "text", "text", text == null ? "" : text)))));
}
```

**Code — `jira/JiraClient.java`** (one representative method; full file has 7):
```java
public JiraSearchResult searchIssues(String jql, int maxResults) {
    Map<String, Object> body = Map.of(
        "jql", jql, "maxResults", maxResults,
        "fields", List.of("summary", "status", "issuetype", "assignee"));
    SearchResponse response = restClient.post()
        .uri("/rest/api/3/search/jql")
        .body(body)
        .retrieve()
        .body(SearchResponse.class);
    return new JiraSearchResult(
        response.issues().stream().map(this::toSummary).toList(),
        response.isLast());
}
```

Jira's raw wire-format JSON is mapped with **private nested records** inside `JiraClient` (`IssueRaw`, `IssueFields`, `StatusRaw`, etc., all `@JsonIgnoreProperties(ignoreUnknown = true)`) — kept private since nothing outside `JiraClient` needs Jira's actual JSON shape. Public DTOs (`jira/dto/JiraIssueSummary.java`, `JiraSearchResult.java`, `JiraProjectSummary.java`, `TransitionOption.java`) are small, flattened records — only the fields actually used.

**Code — `tools/JiraTools.java`** (one of 7 tools):
```java
@McpTool(name = "jira_search_issues",
    description = "Search Jira issues using JQL... Returns matching issues with key, summary, status, type and assignee.")
public JiraSearchResult searchIssues(
        @McpToolParam(description = "JQL query string", required = true) String jql,
        @McpToolParam(description = "Maximum number of issues to return (default 25)", required = false) Integer maxResults) {
    return jiraClient.searchIssues(jql, maxResults == null ? 25 : maxResults);
}
```

Tool descriptions are written carefully — they're what the LLM reads later to decide which tool to call and with what arguments.

Verified by rebuilding and confirming the startup log: `Registered tools: 8` (ping + 7 Jira tools).

---

## Phase 4 — Authentication

**What/why:** Wire real credentials so the server can call the actual Jira site, verifying the token *outside* the app first so an auth problem is never confused with an application bug.

**Steps taken:**
1. Generated an API token at `id.atlassian.com/manage-profile/security/api-tokens`.
2. Created `jira-mcp-server/.env` (gitignored) with `JIRA_BASE_URL`, `JIRA_EMAIL`, `JIRA_API_TOKEN`.
3. Sanity-checked with PowerShell `Invoke-RestMethod` against `/rest/api/3/myself` using Basic auth — confirmed identity (`Sajida Begum`, `sajidauop@gmail.com`) before touching Java.
4. Wired `JiraClientConfig` to build the real header:

```java
@Bean
RestClient jiraRestClient(JiraProperties properties) {
    String credentials = properties.email() + ":" + properties.apiToken();
    String basicAuth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basicAuth)
        .build();
}
```

### The big bug of this phase: `/mcp` returned 404

With credentials wired, calling any tool via a raw MCP handshake (`curl` doing `initialize` → `tools/call`, since Node/npx — and therefore the official MCP Inspector — wasn't available on this machine) consistently returned:

```json
{"timestamp":"...","status":404,"error":"Not Found","path":"/mcp"}
```

**Diagnosis process:**
1. Confirmed the default MCP endpoint really is `/mcp` by decompiling the actual autoconfiguration class (`javap` on `McpServerStreamableHttpProperties` — bytecode showed `ldc "/mcp"` as the literal default).
2. Added a temporary `CommandLineRunner` that dumped every registered `RouterFunction` bean's `toString()`. Output confirmed the route was built *correctly*:
   ```
   (GET && /mcp) -> ...
   (POST && /mcp) -> ...
   (DELETE && /mcp) -> ...
   ```
3. Turned on `logging.level.org.springframework.web=DEBUG` and watched a live request. The trace showed `DispatcherServlet` matching `POST /mcp` to **`SimpleUrlHandlerMapping`** (Spring Boot's default static-resource handler, mapped to `/webjars/**` and `/**`) *before* the correctly-configured MCP router function ever got a chance — the resource handler unconditionally claims `/**`, looks for a static file named `mcp`, doesn't find one, and 404s. The real MCP handler was never reached.

**Fix:** narrow the static resource pattern so it stops shadowing API routes:
```yaml
spring:
  mvc:
    static-path-pattern: /static/**
```
(Tried `spring.web.resources.add-mappings: false` first — it did *not* fix this particular case, even though it's the "textbook" way to disable static resource handling. `static-path-pattern` was the property that actually worked.)

Rebuilt, restarted, retried the handshake — `200 OK` with a session ID, then `jira_list_projects` and `jira_search_issues` both returned **real data** from the live Jira site (projects `AI`, `DEMO`, `KAN`; real issues from `KAN`).

*Lesson: a correctly-built route can still 404 if a broader, earlier-priority `HandlerMapping` claims the request first. When a route "should obviously work" but doesn't, check `HandlerMapping` order and static-resource catch-alls before suspecting the route itself.*

---

## Phase 5 — Docker Setup

**What/why:** Package the server the way `mcp-atlassian` itself ships — a generic, stateless image, with real credentials injected at container start via env vars, never baked into a layer.

**Architecture:** multi-stage Dockerfile — stage 1 has the full JDK + Maven to compile; stage 2 is JRE-only and just runs the jar. Keeps the final image small and avoids shipping build tooling.

**Code — `Dockerfile`:**
```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/jira-mcp-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Code — `docker-compose.yml`:**
```yaml
services:
  jira-mcp-server:
    build: .
    env_file: .env
    ports:
      - "8080:8080"
```

Docker Desktop wasn't running when this phase started — it was launched and polled until ready (took a couple of minutes to spin up its WSL2 backend), then `docker compose build` and `docker compose up` were run for real. The same MCP handshake test (`initialize` → `jira_list_projects`) was repeated against the containerized server and returned the same real project data — confirming the container behaves identically to the local jar.

---

## Phase 6 — Build and Run End-to-End (Ollama)

**What/why:** Build `jira-ollama-client`, a minimal CLI that uses a local Ollama model as the reasoning engine, with Spring AI's MCP client auto-discovery handing it the Jira tools — so a plain-English question resolves to a real tool call against the live Jira site.

**Architecture:**
```
You (stdin) → ChatClient (Ollama model) → tool-calling decision → MCP client → jira-mcp-server (Streamable-HTTP) → Jira Cloud
```

**Code — `pom.xml` dependencies:** `spring-ai-starter-model-ollama` + `spring-ai-starter-mcp-client`.

**Code — `application.yml`:**
```yaml
spring:
  main:
    web-application-type: none
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen2.5:3b
    mcp:
      client:
        streamable-http:
          connections:
            jira:
              url: http://localhost:8080
```

**Code — `JiraChatRunner.java`:**
```java
@Component
public class JiraChatRunner implements CommandLineRunner {
    private final ChatClient chatClient;

    public JiraChatRunner(ChatClient.Builder builder, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = builder.defaultToolCallbacks(toolCallbackProvider).build();
    }

    @Override
    public void run(String... args) throws Exception {
        // reads stdin in a loop, calls chatClient.prompt(line).call().content()
    }
}
```

Verified via `javap` that Spring AI's auto-configured `SyncMcpToolCallbackProvider` implements `ToolCallbackProvider` (so it can be injected directly), and that `ChatClient.Builder.defaultToolCallbacks(ToolCallbackProvider...)` is a real overload — no manual tool-callback wiring needed at all.

### The model-selection saga

Initial attempt used **`gemma4`** (8B, already pulled locally, advertised `tools` capability) — Ollama failed to load it:
```
ggml_backend_cpu_buffer_type_alloc_buffer: failed to allocate buffer of size 7213501760
```
This machine has **7.8GB total RAM**, often under 1GB free with normal apps open — nowhere near enough for an 8B model (~7.2GB just for weights).

Freed memory by stopping the Docker container and shutting down Docker Desktop's WSL2 VM (~800MB+ reclaimed), then tried progressively smaller Ollama models, testing actual tool-calling behavior (not just the advertised `"capabilities":["tools"]` flag) at each size:

| Model | Result |
|---|---|
| `qwen2.5:0.5b` | Responded conversationally, never actually invoked the tool |
| `qwen2.5:1.5b` | Described what it *would* call, output pseudo-JSON, still didn't make a real tool call |
| `qwen2.5:3b` | **Correctly invoked `jira_list_projects` and `jira_search_issues`**, returned accurate natural-language answers |

*Lesson: a model's advertised tool-calling "capability" flag doesn't guarantee reliable tool-calling behavior in practice, especially at very small parameter counts — verify empirically with a real prompt, not just the metadata.*

### Final verified runs

Two live end-to-end questions, asked in plain English at the CLI prompt, both resolved correctly through the full stack (Ollama → MCP client → jira-mcp-server → Jira Cloud → back):

- **"What projects exist in Jira?"** → correctly listed `AI-Integration (AI)`, `Demo service space (DEMO)`, `jirai-vsintegration (KAN)`.
- **"What issues are in the KAN project? List their keys and statuses."** → correctly listed all 6 real issues (`KAN-1` through `KAN-6`) with accurate statuses, including noticing `KAN-1` is assigned to Sajida Begum.

---

## Phase A — Deploy Publicly (Fly.io)

**What/why:** everything so far only ran on `localhost`. To make `jira-mcp-server` usable from anywhere, it needed a real host and a stable public HTTPS URL. Chosen host: **Fly.io** — deploys straight from the existing `Dockerfile`, gives free automatic HTTPS, no reverse-proxy/cert setup needed.

**Steps:**
1. Installed `flyctl` (`iwr https://fly.io/install.ps1 -useb | iex` on Windows).
2. User ran `fly auth login` themselves (an account/browser action, not something to automate).
3. `fly launch --name jira-mcp-server-sajida --region iad --no-deploy --yes` — detected the Dockerfile, created `fly.toml`, provisioned the app shell without deploying yet.
4. `fly secrets set JIRA_BASE_URL=... JIRA_EMAIL=... JIRA_API_TOKEN=...` — pushed the same values as the local `.env` into Fly's encrypted secret store.
5. `fly deploy`.

**Bug — Fly spun up 2 machines, and MCP sessions broke:** the very first post-deploy test failed with `"Session not found: <id>"` on the second request. Diagnosis: `fly launch` provisions 2 machines by default (for HA/zero-downtime deploys), and our MCP server keeps session state in memory *per instance*. A session created by machine A wasn't visible when Fly's load balancer routed the follow-up request to machine B.

**Fix:** `fly scale count 1` — correct call anyway for a personal, single-user tool with in-memory session state. Retested: `initialize` → `jira_list_projects` returned real data (`AI`, `DEMO`, `KAN`) over the public internet.

*Lesson: stateful in-memory session data and horizontally-scaled/load-balanced deployments don't mix without sticky routing or shared session storage — for a small personal tool, just run one instance.*

---

## Phase B — Add OAuth2 (Authorization + Resource Server)

**What/why:** a publicly reachable MCP server with no auth means anyone who finds the URL can use *your* Jira account through it. Decision made up front: **OAuth 2.0 Client Credentials grant**, scoped to just the one `jira-ollama-client` (not the fuller Authorization Code + PKCE + dynamic client registration flow interactive clients like Claude Desktop's remote-MCP connector would need — deliberately out of scope, since only one known client needs to connect).

**Architecture:** both the **Authorization Server** (issues tokens) and **Resource Server** (validates them) run in the *same* Spring Boot app — two separate `SecurityFilterChain` beans, one scoped to the AS's own endpoints (`/oauth2/**`), one scoped to `/mcp/**`.

**Dependencies added:** `org.springaicommunity:mcp-server-security`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-oauth2-authorization-server`.

### Bug 1 — `applyDefaultSecurity(http)` doesn't exist

A widely-repeated doc snippet suggested `OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http)` as a one-line way to wire the AS's security filter chain. It doesn't exist as a real framework API in this version — it turns out to be a copy-pasted helper from Spring's own *sample* projects, not a shipped class. Found the real equivalent by decompiling Boot's own auto-configured filter chain bean (`javap -c` on `OAuth2AuthorizationServerWebSecurityConfiguration`) and reading the bytecode instruction-by-instruction to see exactly what it does:

```java
@Bean
@Order(1)
SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
    http.securityMatcher(configurer.getEndpointsMatcher());
    http.with(configurer, Customizer.withDefaults());
    http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
    http.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
    http.exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
        new LoginUrlAuthenticationEntryPoint("/login"),
        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
    return http.build();
}
```
(Note: Boot's own auto-configured version of this bean backs off entirely once *any* custom `SecurityFilterChain` bean exists in the app — which ours does — so it had to be hand-written, not left to auto-configuration.)

Client registration itself, however, *is* fully property-driven — no Java needed for that part:
```yaml
spring.security.oauth2.authorizationserver:
  issuer: ${PUBLIC_URL}
  client:
    jira-ollama-client:
      registration:
        client-id: jira-ollama-client
        client-secret: "{noop}${OAUTH_CLIENT_SECRET}"
        client-authentication-methods: [client_secret_basic]
        authorization-grant-types: [client_credentials]
        scopes: [mcp.tools]
```

### Bug 2 — self-referential issuer deadlock (the big one)

With the Resource Server side configured the "obvious" way —
```java
mcpAuthorization.authorizationServer(issuerUri); // issuerUri = our own PUBLIC_URL
```
— the app hung for **~54 seconds** on every startup, then crashed. Root cause, found by watching the stack trace during the hang: Spring's OAuth2 Resource Server support does **OIDC discovery** — it makes a blocking HTTPS call to `{issuer}/.well-known/oauth-authorization-server` to find out how to validate tokens. Since our issuer *is* this same app, that means: the app needs to already be serving that endpoint in order to finish starting — but it can't serve anything until it finishes starting. A dependency cycle.

**Fix:** stop using the network path entirely. Since the Authorization Server and Resource Server live in the same JVM, they can share the actual signing key material directly, in-process:
```java
@Bean
@Order(2)
SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http, JWKSource<SecurityContext> jwkSource) throws Exception {
    JwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
    http.securityMatcher("/mcp/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> {
            mcp.authorizationServer(issuerUri);   // still needed for challenge/metadata construction
            mcp.jwtDecoder(jwtDecoder);            // but skips the HTTP round-trip for actual validation
            mcp.validateAudienceClaim(false);
        });
    return http.build();
}
```
Confirmed via `javap -c` on `McpServerOAuth2Configurer.getJwtDecoder(String)` that passing an explicit `jwtDecoder` short-circuits the network-fetching `NimbusJwtDecoder.withIssuerLocation(...)` path entirely. `JWKSource<SecurityContext>` itself comes for free — it's a bean Boot's own `OAuth2AuthorizationServerJwtAutoConfiguration` already publishes for the Authorization Server's own token signing.

**Verified:**
```bash
curl -X POST https://jira-mcp-server-sajida.fly.dev/mcp ...        # no token → 403
curl -X POST https://jira-mcp-server-sajida.fly.dev/oauth2/token \
  -u "jira-ollama-client:<secret>" -d "grant_type=client_credentials&scope=mcp.tools"  # → real JWT
curl -X POST https://jira-mcp-server-sajida.fly.dev/mcp -H "Authorization: Bearer <jwt>" ...  # → real Jira data
```

*Lesson: when a server is configured to trust itself as its own OAuth2 issuer, never let it validate tokens via a network call back to its own not-yet-ready endpoint — share the signing material in-process instead.*

---

## Phase C — Update the Ollama Client for OAuth2, and Verify End-to-End Over the Internet

**What/why:** point `jira-ollama-client` at the real deployed, now-protected server, and make it authenticate automatically.

**Dependencies added:** `org.springaicommunity:mcp-client-security`, `spring-boot-starter-oauth2-client`.

**Design:** Spring Security's `AuthorizedClientServiceOAuth2AuthorizedClientManager` (a client-credentials-flavored, non-web-context authorized-client manager) feeds a token into `OAuth2ClientCredentialsSyncHttpRequestCustomizer`, which the MCP HTTP transport builder calls on every outgoing request. In theory this is all config + two small beans. In practice, three separate, layered bugs showed up only once real network conditions were involved — a good reminder that "compiles and looks right" and "reliably works" are different bars entirely for anything networked.

### Bug 1 — intermittent OAuth2 token-fetch timeouts

The very first live run failed with `io.netty.handler.timeout.ReadTimeoutException` fetching the token — but a plain `curl` to the same endpoint, at the same time, succeeded in ~1 second. Spring Security's default token-response client builds its own internal Reactor-Netty-backed `RestClient` with a fairly short default timeout, which occasionally missed on this machine's network path (most likely first-connection TLS/JIT warm-up cost on a cold JVM). First attempt: give it a longer timeout by supplying a custom `ReactorClientHttpRequestFactory`.

### Bug 2 — the "fix" broke token parsing

Supplying a bare `RestClient.builder().requestFactory(longerTimeoutFactory).build()` did fix the timeout, but introduced a *new*, 100%-reproducible failure: `IllegalArgumentException: accessToken cannot be null`. The HTTP call was succeeding, but the JSON response wasn't turning into a proper token object.

**Diagnosis:** decompiled `AbstractRestClientOAuth2AccessTokenResponseClient`'s own no-arg constructor (`javap -c`) to see exactly what converters its *default* internal `RestClient` registers — and found it does something a plain `RestClient.builder()` does not: it **clears the default converter list** and replaces it with exactly two converters — `FormHttpMessageConverter` (for the request) and `org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter` (for the response) — plus a dedicated `OAuth2ErrorResponseErrorHandler`. Our hand-built RestClient, lacking that specific converter, silently produced a mostly-empty token response instead of failing loudly.

**Fix:** replicate that exact recipe, changing only the request factory:
```java
RestClient restClient = RestClient.builder()
        .requestFactory(reactorFactoryWithLongerTimeout)
        .messageConverters(converters -> {
            converters.clear();
            converters.add(new FormHttpMessageConverter());
            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
        })
        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
        .build();
```

### Bug 3 — a hardcoded ~20-second ceiling inside the MCP SDK itself

Even with both fixes above, occasional failures remained: `TimeoutException: Did not observe any item or terminal signal within 20000ms in 'map'`. This is **not** configurable via any of our properties — it comes from the MCP Java SDK's own sync-to-async bridge (`McpAsyncHttpClientRequestCustomizer.fromSync(...)`), which wraps the *entire* per-request `customize()` callback — including a first-time OAuth2 token fetch, if the token isn't cached yet — in a fixed ~20-second reactive timeout. On a cold JVM, first-time TLS negotiation plus a token fetch can occasionally exceed that budget even with generous client-side timeouts configured, because the ceiling wrapping the whole thing is fixed regardless.

**Fix:** don't let the *first* MCP call be the one that also has to fetch a token. Pre-warm the token during normal (blocking, no artificial ceiling) Spring bean creation, using the exact same cache key (principal name) the customizer looks up later:
```java
manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID)
        .principal("mcp-client-service")   // must match the literal string the customizer uses internally
        .build());
```
(Confirmed the exact principal string by decompiling `OAuth2ClientCredentialsSyncHttpRequestCustomizer.customize()` — it's a hardcoded literal, not derived from configuration.) With the token already cached by the time the MCP client makes its first real call, that call's contribution to the 20-second budget becomes a cache hit instead of a fresh network round-trip.

**Verified, repeatedly:** multiple consecutive full runs — "What projects exist in Jira?", "What issues are in the KAN project?" — each correctly resolving through the complete chain: local Ollama model (`qwen2.5:3b`) → pre-warmed OAuth2 token → Bearer-authenticated MCP call → Fly.io-deployed, OAuth2-protected server → live Jira Cloud data → correct natural-language answer.

*Lesson: "it fails intermittently over the network but works over curl" is a real, diagnosable class of bug (client library defaults, missing converters, hardcoded framework timeouts) — not something to shrug off as generic flakiness. Each layer needed decompiling actual library bytecode to find, since the observable symptom (a timeout, or a null field) was several steps removed from its real cause.*

---

## Final tech stack

- Java 21, Maven
- Spring Boot **4.0.2**, Spring AI **2.0.0** (`@McpTool` annotation API — requires Jackson 3, hence Boot 4 not 3.5)
- MCP Streamable-HTTP transport
- Spring Authorization Server + Resource Server (OAuth2 Client Credentials grant), `org.springaicommunity:mcp-server-security` / `mcp-client-security`
- Jira Cloud REST API v3 (`/rest/api/3/search/jql`, ADF for rich text)
- Ollama (`qwen2.5:3b`) for the local test client
- Docker (multi-stage build) + Fly.io for public deployment

## Recap of every real bug hit (and the general lesson each teaches)

1. **Jackson3/`NoClassDefFoundError`** → Spring AI 2.0 needs Boot 4.0, not 3.5. *Let the runtime tell you the real compatible version.*
2. **Wrong `@McpTool` import package** from a search result → verified via `jar tf` / `javap` against the actual dependency. *Trust the jar over documentation for bleeding-edge libraries.*
3. **`/mcp` 404 despite a correctly-registered route** → a broader-priority static-resource `HandlerMapping` was shadowing it. *Check handler-mapping order/precedence, not just route correctness, when a "should work" route 404s.*
4. **Ollama OOM on an 8B model** on a 7.8GB machine → sized the model to the hardware, and verified tool-calling empirically at each size rather than trusting the "supports tools" label.
5. **Fly.io's default 2-machine HA setup broke in-memory MCP sessions** → scaled to 1 machine, the right call for a stateful single-instance personal tool anyway.
6. **A framework helper method (`applyDefaultSecurity`) that doesn't actually exist** → found the real recipe by decompiling Boot's own auto-configured bean bytecode instruction-by-instruction.
7. **Self-referential OAuth2 issuer caused a ~54s startup hang/crash-loop** → shared the in-process `JWKSource` directly instead of validating tokens via a network call back to itself.
8. **Three layered client-side OAuth2 bugs** (short default timeout → fix broke response parsing by dropping a required converter → a separate hardcoded ~20s SDK-level ceiling still bit occasionally) → each required decompiling the actual library bytecode, since the symptom was always several steps removed from the real cause. Fixed by precisely replicating the library's own default converter setup, and by pre-warming the OAuth2 token outside of the SDK's fixed timeout window.
