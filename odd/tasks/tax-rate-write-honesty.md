# tax-rate-write-honesty

**Status**: implemented — the unit makes the tax-rate write path say what it did: a write cannot land where the
operator saw a dismissal, the save is not reachable before the scope options exist, and the primary `POST` has
its own service-level test. Issue **#922**. Base `origin/main` @ `9e7c03e5`, branch
`fix/tax-rate-write-honesty`. **Risk**: none — Angular only: no endpoint, no field, no migration. Two of the
three rules carry a test that fails on the previous tree; the third is the missing assertion `L3` names, and
its test passes on the old tree on purpose.

## Specs

Quoted from issue **#922**, which was opened from the native review of `#919`/`#920`/`#921` (lineage
`review-0c5ba9ec6046e44a`, findings `R3-001`, `R3-003`, `R3-005`). Each spec is one of those findings, and the
acceptance criteria are the issue's own words.

- **S1** — *"A dismissal during an in-flight write either is refused until the write settles, or is not reported
  as 'no write': the list ends up reflecting what actually landed on the server."* The defect it closes:
  `MatDialogRef.afterClosed()` answers `undefined` for ESC, a backdrop click and the close button, and the page
  reads anything falsy as "no write", so a slow `POST`/`PUT` that settles after dismissal appended the row and
  the list kept showing a book that no longer existed. Measured in `MatDialogRef`'s own constructor
  (`node_modules/@angular/material/fesm2022/dialog.mjs:288`): the ESC/backdrop subscription reads
  `this.disableClose` **on the ref**, so mutating it at runtime is what refuses the two interactions — the
  container's input is not the one consulted.
- **S2** — *"With a region read pending or rejected, the save action is unavailable and the failure is visible;
  the empty-scope state is never presented as a complete list."* The defect it closes: the cascade started with
  only `Toda la compañía`, was not part of form validity, the save button was gated by `saving()` alone, and a
  failing per-country region read was swallowed by `catchError(() => of([]))` — so a company-wide rate could be
  saved by an operator who never saw the region options.
- **S3** — *"`tax-rate.service.spec.ts` asserts the POST URL, method, request body and the error message, and
  fails on the current tree."* The defect it closes: `declareTaxRate` was the only write of the three without a
  service-level test — GET, PUT and PATCH were covered and the dialog tests mock the service, so the primary
  write's wiring was asserted nowhere. **Measured, and it corrects the criterion's last clause**: the new POST
  tests pass on the pre-change tree, because `declareTaxRate` was already correct — the missing artifact was
  the assertion, not a behaviour. S3 therefore has no RED witness, which `L3` records; the suite run measured
  it (6 failures, none in `tax-rate.service.spec.ts`; 62 passing included both new tests).

## Tasks

| ID | Spec | Route | Commit |
| --- | --- | --- | --- |
| T1 | S1 | parent | `fix(config): refuse to dismiss a tax-rate dialog while its write is in flight` — `disableClose` flipped around the write in both dialogs, Cancel disabled while `saving()`, the error path restoring dismissal, and one test per dialog |
| T2 | S2 | parent | `fix(config): the declare dialog will not save before its scope options exist` — a `scopeReady` gate over the cascade, per-country failures named instead of swallowed, a retry that makes the block actionable, and the tests |
| T3 | S3 | parent | `test(config): pin the declare POST at the service level` — URL, method, exact body with the converted fraction, and the readable error message |
| T4 | S1–S3 | parent | `73be3998` — `npm run lint` clean, the focused tax suite 68/68, `test:coverage:check` inside all four thresholds, and the native review of the candidate approved and burned (`L4`) |

## Log

**L1 (2026-11-15) — the authorization, verbatim.** «si abrelos», after the review of #919/#920/#921 was relayed
with its ten non-blocking findings; then «de los issues con cual seguimos con 888?» and the choice of **#922**
over #888 and #889. The issue is the specification; nothing here adds a requirement it does not state.

**L2 (2026-11-15) — what was measured before the first edit, and the one decision the issue leaves open.** The
issue allows two shapes for S1: refuse the dismissal, or make the dismissal not hide the write. Chosen: **refuse
the dismissal while the write is in flight**, because the second shape needs the page to know a write was
started — state the dialog and the page would have to share, which is a worse seam than one property flipped
around one call. Blocking it without an exit is a dead end, so the error path restores dismissal; and for S2 the
block is given a way forward (a retry on the cascade) rather than leaving the operator with a disabled button and
a closed door, because "nothing is impossible to save" is the rule this repository already applies to the
attribute form.

**L3 (2026-11-15) — the one rule that had no RED, and why that is the finding rather than a gap in the work.**
S3's criterion ends with "fails on the current tree", and that clause is false as written: the `POST` was
correct before this unit, so the new service tests pass against the old tree. A test that cannot fail proves
nothing about a fix, but it is exactly the right artifact for a write nobody had pinned — the defect S3 names is
*absent evidence*, not broken behaviour, and the honest evidence for it is that the suite counted 68 tests where
the previous tree counted 58. The other two rules do carry RED witnesses, measured before the fix: `disableClose`
still `false` with a write in flight in both dialogs, the save reachable with a failed region read, with a
pending one, and with a failed catalog, and `regionsError` `null` after a swallowed per-country failure — which
is also the proof that no `Reintentar` could have existed.

**L4 (2026-11-15) — `T4` ran, the native review approved and burned, and the unit is one commit of 585 lines.**
Evidence, in the order it ran: `npm run lint` → *All files pass linting*; the focused suite
(`ng test --include src/features/tax/**/*.spec.ts`) → 5 files, **68 tests, 0 failures**, the same count `L3`
measured; `npm run test:coverage:check` → statements 86.96 %, branches 86.53 %, functions 81.32 %, lines
89.89 %, all four inside their threshold. The native review of the candidate (`review-842c0d700f68c768`, medium
tier, lenses resilience/readability/reliability, 9 paths including this record) **approved, and its
acknowledgement burned**: the committed tree `caa56572` is byte-identical to the reviewed candidate tree, so the
receipt and the commit describe the same bytes. Delivered as **one** commit, `73be3998`, of 585 authored lines —
past the ~400-line slice budget, and said here rather than split: the three rules are one write path (a
dismissal that hides a write, a save over a scope the operator never saw, and the assertion for the write
itself), they were reviewed as one candidate, and splitting them after the receipt would have shipped bytes it
does not cover.

**L5 (2026-11-15) — the review approved with eight non-blocking findings, recorded instead of applied.** The
receipt is terminal and this candidate is not reviewed again, so these are follow-ups, not corrections.
`R3-001`: a `regionId` chosen from a partial cascade survives a `Reintentar` whose clean walk no longer offers
it, and `scopeReady` then lets that stale id be saved. `R3-002`/`R3-003`: the error branch assigns
`dialogRef.disableClose = false` instead of restoring the value it found, so a dialog opened with `disableClose:
true` becomes dismissible after a failed write. `R4-001`: `Reintentar` re-walks the scope but not the tax-code
catalog, so a double failure leaves the save disabled behind a retry button that cannot fix it. `R4-002`: the
region error is written only when the aggregate emits, so a country whose read never settles keeps the save
disabled with nothing on screen. Three are readability notes: `R2-001`, the header sentence claiming every rule
fails on the previous tree contradicted `L3` and **was corrected in this record** — documentation only, the one
edit made after the receipt; `R2-002`, `regionsError` now also carries a country-list failure its name no longer
describes; `R2-003`, `scopeReady` also gates on the tax-code catalog. The two behaviour findings that also have
the smallest fix (`R3-002`/`R3-003`) are the ones to pick up first.

**L6 (2026-11-17) — the two citations above were corrected, and the correction is recorded rather than
hidden.** `T4` and `L4` cited `22b9531e`, the commit as it existed before this branch was rebased twice. It
still resolves as an object, which is exactly why it read as evidence while naming nothing that is in `main`.
The commits that are in `main` are `73be3998` (the fix) and `1920276d` (this record), and both citations now
name the fix one. Nothing else in this record changed: rule 5 of `references/feature-records.md` repairs a
claim that is false *now* and records the repair, so the paragraph that made it stays the narrative it always
was. The superseded commit is named here on purpose instead of being dropped — it is the evidence of the
defect, not the claim. The repair arrived with `.github/scripts/tests/odd-citations.test.sh`, the guard that
fails on the next citation of a commit that is no longer in `main` (`odd/tasks/odd-citation-provenance.md`).
