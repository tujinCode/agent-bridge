# Contributing to agent-bridge

Thanks for taking the time to contribute. This project is small enough that a
badly scoped PR costs more than it saves, so there is exactly **one hard rule**:

> **Every feature must start as an issue that states the problem it solves.**
> Not the solution — the problem, in one paragraph, with the failure it causes
> today. PRs that add behaviour nobody asked for will be converted into issues
> rather than merged.

## Ways to contribute that are genuinely useful

- **Report a broken tool exposure.** Annotate a real method, see what schema comes
  out, tell us where it is wrong. This is the highest-value feedback right now.
- **Tell us your failed integration.** Which agent client, which error, what you
  expected. Reproductions beat opinions.
- **Port the idea to a non-Spring stack.** The core has no Spring dependency on
  purpose; a Quarkus sample would be welcome.
- **Improve the docs.** If a sentence made you read it twice, it is a bug.

## Development setup

Requirements: JDK 17+ (21 recommended), Maven 3.6+.

```bash
git clone https://github.com/tujinCode/agent-bridge.git
cd agent-bridge
mvn verify          # build + unit tests
./scripts/smoke.sh  # end-to-end protocol check against the example app
```

The build must be green before you open a PR. CI runs on JDK 17 and 21.

## Coding conventions

- Java 17 language level. No preview features.
- `agent-bridge-core` must stay **free of Spring imports**. That constraint is
  what keeps the library usable outside Spring; a PR that breaks it will be rejected
  even if the tests pass.
- New dependencies need a justification in the PR description. The current set is
  deliberately thin: Jackson, slf4j, jakarta.validation-api.
- Public API changes need a note in `CHANGELOG.md` under `Unreleased`.
- Comments explain *why*, not *what*. A comment restating the code will be asked
  to be removed.

## Commit messages

Conventional Commits, in English:

```
feat(core): derive enum values into the schema
fix(starter): route logs to stderr in stdio mode
docs(readme): clarify the -parameters requirement
```

## Pull requests

1. One logical change per PR. Refactors go in their own PR.
2. Fill in the PR template — especially *what could go wrong*.
3. Add or update tests. A behaviour change without a test is treated as incomplete.
4. If you add a configuration option, update the README table **and** the properties class.

## Reporting bugs

Use the bug report template and include:

- the annotated method (redact anything sensitive),
- the generated `inputSchema` (from `tools/list`),
- the client you used and its version,
- the JDK and Spring Boot versions.

## Security

Do not open a public issue for a vulnerability. Email the maintainer listed in
`pom.xml` and give us a reasonable window to fix it before disclosure.
