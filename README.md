<div align="center">

# agent-bridge

**Turn existing Spring Boot services into governed AI-agent tools — one annotation, no rewrite.**

[![CI](https://github.com/tujinCode/agent-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/tujinCode/agent-bridge/actions/workflows/ci.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-17%2B-blue.svg)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/spring--boot-3.x-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![Status](https://img.shields.io/badge/status-MVP-orange.svg)](#status)

[中文文档](README.zh-CN.md) · [What works](#status) · [Roadmap](#roadmap) · [Contributing](CONTRIBUTING.md)

</div>

---

Most "give your data to an LLM" projects ask you to rewrite your API as tools. In a real enterprise system that means hundreds of endpoints, a second copy of the contract that rots within a quarter, and — worst of all — a service account with god-mode permissions whose calls cannot be traced back to a human.

`agent-bridge` takes the opposite route: **the tools already exist.** You add an annotation to the method you already have. The JSON Schema is derived from the signature, so it cannot drift. The caller's identity flows through to your existing `@PreAuthorize`, so the permission model you spent ten years building still applies.

## Why

| The usual approach | What breaks |
|---|---|
| Hand-write a tool schema per endpoint | Two copies of the contract. Rename a parameter and the model silently starts sending garbage. |
| Convert HTTP → MCP in a gateway | The gateway does not know *who* is calling. Identity is lost at the conversion point, so RBAC and audit die there. |
| Give the agent its own service account | Every audit record says `svc_agent_01`. When something goes wrong, nobody can tell who asked for it. |

Plus three failure modes every backend engineer already knows: **retries that double-charge**, **audit trails split across three systems**, and **no separation between "read it" and "delete it"**.

## What it looks like

```java
@Service
public class WorkOrderService {

    @AgentTool(name = "workorder.escalate",
               description = "Raise the priority of an open work order. Never escalate a closed one.",
               risk = RiskLevel.WRITE,
               scopes = "workorder:write")
    public WorkOrder escalate(
            @AgentParam(description = "Work order id", example = "WO-2001") String workOrderId,
            @AgentParam(description = "New priority: 1 is highest, 5 is lowest")
            @Min(1) @Max(5) int priority,
            ToolInvocationContext context) {        // injected, never visible to the model

        WorkOrder current = require(workOrderId);
        if (current.status() == CLOSED) {
            throw new IllegalStateException("work order is closed"); // reaches the model as text
        }
        log.info("escalated {} by {}", workOrderId, context.callerId());
        return store.put(workOrderId, current.withPriority(priority));
    }
}
```

What the model receives, generated from that signature:

```json
{
  "name": "workorder.escalate",
  "description": "Raise the priority of an open work order. Never escalate a closed one.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "workOrderId": { "type": "string", "description": "Work order id", "examples": ["WO-2001"] },
      "priority":    { "type": "integer", "minimum": 1, "maximum": 5,
                       "description": "New priority: 1 is highest, 5 is lowest" }
    },
    "required": ["workOrderId", "priority"],
    "additionalProperties": false
  },
  "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true }
}
```

No YAML. No adapter class. No second contract.

## How it works

```
Agent (Claude Desktop / Cursor / your own)
   │  MCP over stdio (JSON-RPC 2.0) — _meta carries a caller id and, optionally, a token
   ▼
McpServer      transport adapter: describes the request, decides nothing about proof
   ▼
ToolRegistry   risk ceiling → interceptor chain → audit listener → result rendering
   │                  │
   │                  └── the starter installs the identity interceptor here
   ▼
ToolInvoker    argument binding; caller identity is injected, never part of the schema
   ▼
Your existing Spring Boot service — @PreAuthorize unchanged
```

The core deliberately has **zero Spring dependencies**. The starter is one adapter among possible others: it plugs into the interceptor chain instead of being baked into the registry.

## Identity: who is calling

Two credentials exist in a tool call, and only one of them is a credential.

| Source | What it is | Who can forge it |
|---|---|---|
| `_meta.callerId` | a claim the client types | anyone with a socket |
| `_meta.authorization` / `agent-bridge.security.static-token` | a signed token | nobody without the secret |

So the gateway has three modes, and the default is the one that changes nothing:

| Mode | Behaviour | Use it for |
|---|---|---|
| `NONE` *(default)* | Establishes no caller. Tools without access rules run; tools with them are denied. | upgrading an existing application without changing its behaviour |
| `ASSERTED` | Trusts `_meta.callerId` and `_meta.authorities` verbatim. | a laptop demo, never anything reachable |
| `DELEGATED` | Verifies an HS256 JWT, and its `sub` becomes the authenticated caller. | anything an auditor will look at |

In `DELEGATED` mode a call runs like this:

```java
@PreAuthorize("hasAuthority('workorder:write')")   // unchanged, written for humans
@AgentTool(name = "workorder.escalate", risk = RiskLevel.WRITE, scopes = "workorder:write")
public WorkOrder escalate(String workOrderId, int priority, ToolInvocationContext context) {
    // context.callerId() is the token's subject, not what the client claimed
    // SecurityContextHolder also holds it, so @PreAuthorize above already ran
}
```

Three properties worth stating explicitly, because each one is a place where a naive
implementation goes wrong:

- **The verified name wins.** If the request claims `callerId: ignored` and the token says
  `zhang.cong`, the business method and the audit record both see `zhang.cong`. Keeping the
  claimed name alongside a verified one is worse than having no identity at all, because it
  looks correct.
- **Identity travels with the call, not with the thread.** The context is established on
  whichever thread runs the method, so a pooled or virtual thread works. Thread-local
  inheritance is rejected on purpose: it hands the caller to threads that should never see it.
- **`scopes` on the annotation is enforced, not decorative.** A token missing a declared
  scope is refused before the method runs, even when there is no `@PreAuthorize` at all.

### What this does not give you

stdio has no HTTP headers, so a token can only arrive inside `_meta`. That means the token is
visible to the MCP client and to whatever that client logs. For stdio, prefer
`agent-bridge.security.static-token` bound to an environment variable — one process, one
caller, nothing in the message stream. Genuine multi-caller isolation needs the HTTP
transport and a real OAuth 2.1 flow, which is on the roadmap rather than in this version.

Also note that the audit record still reports a single `callerId` field. `context.callerVerified()`
tells a listener whether that name was checked; a record that names a caller without that
flag is a rumour with a timestamp.

## Quick start

**1. Build it locally** (not on Maven Central yet):

```bash
git clone https://github.com/tujinCode/agent-bridge.git
cd agent-bridge
mvn install
```

**2. Run the example** — a small work-order service with 5 tools:

```bash
java -jar agent-bridge-example-workorder/target/agent-bridge-example-workorder-0.1.0-SNAPSHOT.jar
```

**3. Point an MCP client at it.** In `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "workorders": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/agent-bridge-example-workorder-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

**4. Ask.** Restart the client, then:

> *Which work orders does customer C-1001 have, and escalate the login issue to priority 1?*

The agent lists the orders, picks `workorder.escalate`, and the call is logged with `callerId` intact. Ask it to delete one and it will be refused — see below.

**Verify without a client:**

```bash
./scripts/smoke.sh
# PASS - 5 requests, 5 responses, risk gate enforced, logs kept off stdout
```

## The risk model

Every tool declares how much damage it can do. The gateway has a ceiling.

| Level | Meaning | Default behaviour |
|---|---|---|
| `READ` | reads only | runs automatically |
| `WRITE` | mutates, human-recoverable | runs when ceiling is `WRITE` |
| `DESTRUCTIVE` | irreversible: delete, transfer, charge | always needs an explicit ceiling raise |

The default ceiling is `READ`. A library that can write to production on install is a library nobody installs.

When a call exceeds the ceiling the model does not get a stack trace, it gets an instruction:

```
approval required: 'workorder.purge' is classified DESTRUCTIVE but this gateway runs
at most WRITE automatically. Explain the intended effect to the user and ask for confirmation.
```

## Configuration

```yaml
agent-bridge:
  enabled: true
  transport: stdio          # stdio | none
  server-name: workorder-tools
  server-version: 0.1.0
  max-auto-risk: WRITE      # READ | WRITE | DESTRUCTIVE
  include-packages:         # optional scan filter, empty = every bean
    - com.example.billing

  security:
    mode: DELEGATED                       # NONE (default) | ASSERTED | DELEGATED
    shared-secret: ${AGENT_BRIDGE_SECRET:} # required when mode is DELEGATED
    issuer: https://idp.example.com        # optional, pins the iss claim
    static-token: ${AGENT_BRIDGE_TOKEN:}   # stdio: one process, one caller
    require-token: true                    # DELEGATED only: refuse calls with no token
    clock-skew-seconds: 60                 # tolerance on exp and nbf
```

The security block only takes effect when Spring Security is already on your classpath.
Adding this starter to an application that has never used it will not pull an authorisation
framework in.

## Modules

| Module | Purpose |
|---|---|
| `agent-bridge-annotations` | `@AgentTool`, `@AgentParam`, `RiskLevel`. Zero dependencies. |
| `agent-bridge-core` | Scanning, schema derivation, invocation, risk gate, MCP adapter. No Spring. |
| `agent-bridge-spring-boot-starter` | Auto-configuration and stdio lifecycle. |
| `agent-bridge-example-workorder` | Runnable demo: `READ` / `WRITE` / `DESTRUCTIVE` tools. |

## Two gotchas worth knowing

**Compile with `-parameters`.** Without it, parameter names are erased to `arg0` and the generated schema is useless. The Spring Boot parent POM sets this by default; if you manage your own build, add it.

**stdout belongs to the protocol.** In stdio mode, a single `System.out.println` in your code corrupts the JSON-RPC stream and the client disconnects with a parse error. Route logs to stderr — the example ships a `logback-spring.xml` that does exactly that.

## Compatibility

- JDK 17 and 21
- Spring Boot 3.x (the starter). The core runs on plain Java.
- MCP protocol revision `2025-06-18` (server echoes whatever the client requests)

## Status

Early, but the headline claim is now true rather than planned.

What works today: annotation-driven tools, schema derivation, risk gate, audit listener,
stdio transport, MCP tool annotations, and **caller identity delegation** — a verified token
becomes the `Authentication` your `@PreAuthorize` already evaluates.

What does **not** work yet, and we will not pretend otherwise:

- **No idempotency.** A retried `tools/call` runs twice. Do not point this at a tool that charges money or creates records.
- **No audit persistence.** You get one `ToolCallListener` callback per call; where it is written is your decision, not the library's.
- **No HTTP transport.** stdio only, which means one process per client — and that is why tokens have to travel in `_meta` for now.
- **HS256 only.** No rotation, no JWKS, no asymmetric keys. Enough to be correct inside one trust domain, not enough to be a general identity provider client.
- **The scan touches every bean at startup.** Use `include-packages` on a large application.
- **No `oneOf` / `anyOf`.** Schema composition is not expressible from a method signature.

## Roadmap

Ordered by what blocks production adoption, not by what is interesting to build.

- **v0.5.0** *(in progress)* — identity delegation ✅; still open: `requestId` deduplication, audit persistence and query, streamable HTTP.
- **v0.9.0** — multi-tenancy, tool versioning and deprecation, per-tool rate limits, response redaction, OpenTelemetry metrics, JWKS and key rotation.
- **v1.0.0** — API freeze, adapters for non-Spring stacks, a documentation site.

Nothing here has a date attached. A feature lands when its tests are green, and the version number moves when the previous sentence is true.

## Contributing

Issues and PRs are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first — it explains the one rule that matters: *a feature without a stated problem is a feature nobody asked for*.

## License

[Apache License 2.0](LICENSE). Copyright 2026 Zhang Cong — see [NOTICE](NOTICE).
