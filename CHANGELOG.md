# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Relicensed from MIT to Apache-2.0.** Apache-2.0 adds an explicit patent
  grant and a trademark clause, which is what enterprise legal review expects
  from a library that sits next to their authorization code. Source files now
  carry the Apache header; see `NOTICE`.
- Maven coordinates moved to `io.github.tujincode`, packages to
  `io.github.tujincode.agentbridge.*`. Nothing was published under the old
  coordinates, so this is not a breaking change for anyone.

### Planned for v0.5.0

- Identity pass-through: on-behalf-of delegation built on Spring Security, so
  existing `@PreAuthorize` rules apply to agent calls.
- Idempotency: `requestId` deduplication window backed by Redis.
- Audit persistence and query by request id / caller / tool.
- Streamable HTTP transport sharing the same registry.
- Timeouts and three-level rate limiting.

## [0.1.0] - 2026-10-29

First cut: the vertical slice that proves "annotation is the contract".

### Added

- `@AgentTool` and `@AgentParam` annotations, plus the `RiskLevel` enum, in a
  zero-dependency module.
- JSON Schema derivation from method signatures: primitives, enums, collections,
  maps, arrays, `Optional`, `UUID`, `LocalDate`/`Instant` and nested objects.
- Bean Validation mapping into schema constraints (`@Size`, `@Min`, `@Max`,
  `@Pattern`, `@Email`, `@NotBlank`, `@NotEmpty`, `@Positive`).
- `ToolInvocationContext` injection: caller identity reaches the business method
  without ever appearing in the schema.
- Risk ceiling on the gateway (`max-auto-risk`), defaulting to `READ`.
- Audit seam: one `ToolCallRecord` per call via `ToolCallListener`.
- MCP stdio adapter implementing `initialize`, `ping`, `tools/list` and
  `tools/call`, with `readOnlyHint` / `destructiveHint` / `idempotentHint`.
- Spring Boot starter with auto-configuration and `agent-bridge.*` properties.
- Runnable work-order example with `READ`, `WRITE` and `DESTRUCTIVE` tools.
- `scripts/smoke.sh` end-to-end protocol check.

### Known limitations

- No identity delegation, idempotency, audit persistence or HTTP transport yet.
- Startup scans every bean; use `include-packages` on large applications.
- JSON Schema composition (`oneOf` / `anyOf`) is not expressible from a method
  signature.

[Unreleased]: https://github.com/tujinCode/agent-bridge/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/tujinCode/agent-bridge/releases/tag/v0.1.0
