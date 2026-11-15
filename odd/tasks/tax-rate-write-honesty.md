# tax-rate-write-honesty

**Status**: implemented — the unit makes the tax-rate write path say what it did: a write cannot land where the
operator saw a dismissal, the save is not reachable before the scope options exist, and the primary `POST` has
its own service-level test. Issue **#922**. Base `origin/main` @ `9e7c03e5`, branch
`fix/tax-rate-write-honesty`. **Risk**: none — Angular only: no endpoint, no field, no migration, and every rule
carries a test that fails on the previous tree.

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
| T4 | S1–S3 | parent | `npm run lint`, the focused tax suite, `npm run test:coverage:check`, and the native review of the candidate |

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
