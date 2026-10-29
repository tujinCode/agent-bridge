<div align="center">

# agent-bridge

**Turn existing Spring Boot services into governed AI-agent tools — one annotation, no rewrite.**

[![CI](https://github.com/agentbridge/agent-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/agentbridge/agent-bridge/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-17%2B-blue.svg)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/spring--boot-3.x-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![Status](https://img.shields.io/badge/status-MVP-orange.svg)](docs/03-阶段路线图.md)

[中文文档](README.zh-CN.md) · [Roadmap](docs/03-阶段路线图.md) · [Architecture](docs/04-架构设计.md) · [Contributing](CONTRIBUTING.md)

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
   │  MCP over stdio (JSON-RPC 2.0)
   ▼
McpServer  ── transport adapter, protocol-agnostic core
   ▼
ToolRegistry ─── risk ceiling ─── audit listener ─── result rendering
   ▼
ToolInvoker ─── argument binding ─── identity injection
   ▼
ToolScanner + JsonSchemaBuilder   (annotation → contract)
   ▼
Your existing Spring Boot service — unchanged
```

The core deliberately has **zero Spring dependencies**. The starter is one adapter among possible others.

## Quick start

**1. Build it locally** (not on Maven Central yet):

```bash
git clone https://github.com/agentbridge/agent-bridge.git
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
```

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

MVP. What works today: annotation-driven tools, schema derivation, risk gate, audit listener, stdio transport, MCP tool annotations.

What does **not** work yet, and we will not pretend otherwise: no real identity delegation, no idempotency, no audit persistence, no HTTP transport. See [docs/04-架构设计.md § Known limitations](docs/04-架构设计.md#6-已知限制v010) and the [roadmap](docs/03-阶段路线图.md).

## Contributing

Issues and PRs are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first — it explains the one rule that matters: *every feature needs a requirement id in [docs/02-需求清单.md](docs/02-需求清单.md)*.

## License

[MIT](LICENSE).
