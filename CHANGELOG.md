# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Caller identity delegation.** `agent-bridge.security.mode` selects how a call
  acquires a caller: `NONE` (default, no behaviour change), `ASSERTED` (trusts what the
  client claims — a demo mode, named honestly), or `DELEGATED` (verifies an HS256 JWT and
  turns its subject into the `Authentication` that `@PreAuthorize` already evaluates).
  Tokens come from `_meta.authorization` per request, or from
  `agent-bridge.security.static-token` for the stdio case of one process per client.
- `ToolCallInterceptor` in the core: an invocation seam between the risk gate and the
  business method. The core stays free of Spring; the starter plugs identity in here
  rather than having it baked into the registry.
- `ToolCallExchange`: one call in flight, and the reason it is mutable. The transport can
  describe what the client *claimed*; only the identity layer can say who the caller
  actually is, and the business method must see the second one.
- `JwtVerifier`: narrow on purpose. HS256 only, `exp` required, `sub` required, `alg`
  rejected unless it is the one algorithm this deployment accepts — including `none`.
  Written against the primitives rather than pulling in a dependency, so an adopter has
  one less tree to audit in a library that sits next to their authorisation code.
- Startup warning when method security is in play but the gateway is in `NONE` mode,
  naming the tools that will therefore be denied. That exact confusion already cost this
  project a debugging session; it should not cost an adopter one.

### Fixed

- **The verified caller used to lose to the claimed one.** The first version of the
  interceptor established the `SecurityContext` correctly but left
  `ToolInvocationContext.callerId()` holding whatever name the client typed, so the
  business method and the audit record both reported an unverified identity while the
  authorisation check had used the real one. Worse than having no identity, because it
  looks correct. Found by asserting on the *output* of the tool rather than only on
  whether it was allowed.
- **Beans carrying `@PreAuthorize` were silently skipped by the scanner.** Spring hands
  back the CGLIB subclass, which carries no annotations of its own, so the cheap
  `hasAgentTool` check found nothing and the catalogue came up empty — no error, no
  warning, just zero tools. `scan()` already unwrapped proxies; the check did not.

### Changed

- `ToolCallInterceptor.invoke` now receives a `ToolCallExchange` instead of
  `(context, descriptor, invocation)`, so an interceptor can replace the caller on the
  way through. Pre-release, no consumers.
- `@AgentTool(scopes = ...)` is enforced in `DELEGATED` mode: a token missing a declared
  scope is refused before the method runs, even with no `@PreAuthorize` present. The
  attribute used to be a comment nothing read.
- `ToolInvocationContext` gains `ATTR_IDENTITY_VERIFICATION` and `callerVerified()`.
  A record that names a caller without saying whether that name was checked is a rumour
  with a timestamp.
- **`spring-security-core` is an optional dependency of the starter**, not a transitive
  one. The security auto-configuration is guarded by `@ConditionalOnClass`, so an
  application that has never used Spring Security does not acquire it by adding this
  starter.
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
