<!--
PR body template for LifeControl.

The "PR Type" section is machine-relevant: the checkbox you tick pairs with the one
`type:*` label this pull request must carry, and `.github/workflows/pr-label-guard.yml`
fails the PR when that label is missing, duplicated or not in the declared vocabulary.
The convention and its history are documented in
`.agents/skills/project-conventions/references/labels.md`.

Do not tick a checkbox you have not verified. An unticked box is a pending item, an
unticked box that later gets ticked without evidence is a false attestation.
-->

## Linked Issue (REQUIRED)

<!--
The issue MUST carry the `status:approved` label before the PR is opened.

The last slice of a chain, and an ordinary pull request, use a closing keyword: `Closes #`.
A slice that is NOT the last one keeps `Refs #` and adds, on its own line, the chain
declaration `Part of a chain: #` naming the same issue. Only the last slice carries the
closing keyword, because the issue has to stay open until it lands, and the declaration is
what keeps the reference valid once the slice is retargeted to the default branch.
`CONTRIBUTING.md` §1 states the rule; `references/pr-chains.md` states the states a slice
passes through.
-->

Closes #

## PR Type (REQUIRED — tick exactly ONE)

<!--
Tick the row that describes the diff, then add the label in the parentheses to the PR.
One, never two: a PR that is genuinely two change types is two PRs. Area labels
(`java`) and triage labels are orthogonal and may accompany it.
-->

- [ ] Bug fix (`type:bug`)
- [ ] New feature (`type:feature`)
- [ ] Documentation only (`type:docs`)
- [ ] Code refactoring (`type:refactor`)
- [ ] Maintenance / tooling (`type:chore`)
- [ ] Breaking change (`type:breaking-change`)

## Summary

<!-- 1-3 bullets: what this PR does, and what it deliberately does not do. -->

-

## Changes

| File | Change |
| --- | --- |
| `path/to/file` | What changed |

## Test Plan

<!--
Record the commands actually run and their outcomes: unit tests, lint/build, shellcheck
on modified scripts, manual checks of the affected functionality. Mark a check that does
not apply as N/A — never as passed.
-->

- [ ] `<command>` — <result>

## Contributor Checklist

<!-- Tick only after the evidence exists. -->

- [ ] The linked issue is approved (`status:approved`).
- [ ] The PR carries exactly one `type:*` label, matching the box above.
- [ ] Tests cover the changed behavior, and they were run.
- [ ] Shellcheck passes on modified scripts (when any `.sh` changed).
- [ ] Docs updated when behavior, options or messages changed.
- [ ] Commits follow conventional commits, with no `Co-Authored-By` trailers.
