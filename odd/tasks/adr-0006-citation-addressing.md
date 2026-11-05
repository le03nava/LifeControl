# ADR-0006 citations name a section, never a line range

**Status**: implemented — the six editable citations, the ADR's `## Referring to this ADR` section and
the guard suite are this branch's content. Nothing is deferred; the three citations inside `V58` are
exempt by decision, because a shipped migration's comments cannot be edited without changing its
Flyway checksum, and what each of them intended to say is recorded in the ADR.
**Created**: 2026-11-05 · **Risk**: none — comments, one ADR section, and a new offline suite. No
runtime code, no contract, no schema and no data changes: every changed Java/SQL line is inside a
comment, and the suite only reads.

## Specs

Quoted from issue #785 (`status:approved`, approved 2026-11-05 here).

**S1.** A grep for `0006-catalog-identity\.md:[0-9]` across `life-control-api/src` and `docs` returns
only the three shipped-migration citations, and a comment in the ADR (or in the issue that closes this)
states why those three are exempt and what they intended to say.

**S2.** Each converted citation names the thing it points at in words a reader can grep for —
`ADR-0006 decision 6`, `ADR-0006 migration step 5`, `ADR-0006 ## Schema` — not a re-measured line
number.

**S3.** A check that fails when a new `0006-catalog-identity\.md:<line>` citation appears, so the rule
survives the next author. A shell assertion in `.github/scripts/tests/` or a test over the sources is
acceptable; the repo already has both kinds.

**S4.** No ADR prose changes meaning: this issue is about how the ADR is addressed, not about what it
decides.

**S5.** Editing `V58__variant_listing.sql`, `V76__product_variant_mode_immutable.sql` or any other
shipped migration is a non-goal: their checksums are frozen by design.

## Tasks

| ID | Spec | Route | What it is | Commit |
| --- | --- | --- | --- | --- |
| T1 | S2, S4 | inline | The six editable citations name a decision, an invariant id or a migration step; the ADR gains the addressing section, its rule table and the three intents (S1's "comment in the ADR" half) | `81d56902` |
| T2 | S1, S3, S5 | inline | `.github/scripts/tests/adr-line-citations.test.sh`: scans the two directories, allows exactly the three frozen citations inside `V58`, re-reads the exemption against the file, and fails a tree it cannot read | `cc381346` |
| T3 | all | inline | This record | `docs(odd)` slice of this PR |

## Log

**L1** (user, verbatim): `si 785 apruebalo y genera las correcciones`

**L2** (measurement): the issue's prose says "the eight that can be edited are converted", but its own
table lists nine rows, three of them inside `V58`. Measured with the suite that ships here: **9
citations, 3 frozen, 6 editable**. The six were converted; the issue text is off by two, and its table
is right.

**L3** (evidence, 2026-11-05): the suite was written before the conversion and observed red on the
tree — `FAIL: this tree carries 9 line-range citation(s) and the frozen list has 3`, plus one failure
per editable site naming `file:line`. After T1 it reads `checked 3 citation(s) across
life-control-api/src and docs; 3 frozen — 11 passed, 0 failed`.

**L4** (evidence, 2026-11-05): `for s in .github/scripts/tests/*.test.sh; do "$s"; done` → **12 suites,
0 failures**; `shellcheck --severity=warning` (pinned `koalaman/shellcheck:stable`) on the new suite →
clean; `life-control-api` `compileJava compileTestJava` → exit 0, which is the whole of what a
comment-only change can move.

**L5** (decision): the two relative citations in editable files — `VariantListing.java`'s ``(`:60-64`)``
and the test's ``(`:153-156`)`` — were converted too. They are the same defect in a form a grep for the
path cannot find, they sit in lines this unit was already rewriting, and the issue's non-goal only
exempts the ones inside shipped migrations (`V76`'s `:46-47` and `:165-167` stay untouched, frozen).
