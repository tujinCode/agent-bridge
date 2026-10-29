## What this changes

<!-- One or two sentences. Link the requirement id if there is one. -->

Requirement: FR-

## Why

<!-- The problem this solves. If it is a behaviour change, say what the old behaviour was. -->

## How

<!-- The approach, and the alternatives you rejected. -->

## Risk

<!-- What could break in a user's application? What did you NOT handle? -->

- [ ] I considered backward compatibility of the public API
- [ ] I considered the stdio constraint (nothing new writes to stdout)
- [ ] `agent-bridge-core` still has no Spring imports

## Tests

<!-- Which tests prove this works. New behaviour without a test is treated as incomplete. -->

- [ ] `mvn verify` passes locally
- [ ] `./scripts/smoke.sh` passes
- [ ] Added / updated unit tests

## Checklist

- [ ] CHANGELOG.md updated under `Unreleased`
- [ ] README or docs updated if a config option or public API changed
- [ ] Commit messages follow Conventional Commits
