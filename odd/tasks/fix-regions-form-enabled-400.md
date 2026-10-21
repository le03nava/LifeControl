# ODD feature: fix-regions-form-enabled-400

**Repository**: LifeControl — `life-control-app-angular` only. The regions form and its model
(`src/features/companies/regions/components/regions-form/`, `.../regions/models/region.models.ts`) and the
form's spec. No API code, no DTO, no endpoint, no strictness property, no schema, no stored data.
**Status**: implemented — the form sends the two keys its DTOs declare, the toggle is gone, and the two
cases that pin it are on the tree. Nothing is deferred; the zones-form control named in S6 is separate work.
**Created**: 2026-10-21 · **Base**: `main` @ `e44e7d7` · **Branch**: `fix/regions-form-enabled-400` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-regions-form-enabled-400` (herdr workspace `w5A`) ·
**Issue**: **#503**
**Risk**: **none**. The wire contract does not move: `CreateCompanyRegionRequest` and
`UpdateCompanyRegionRequest` already declare exactly `regionCode` and `regionName`, so this removes a key the
API has rejected since `0dfd20c` — not a key it consumes. No stored data, no secret, no permission, no
concurrency, no pipeline, no dependency. The enable/disable capability stays where it worked: the regions
list, through `DELETE` and `PATCH`. Two spec cases fail on the pre-fix tree, so a regression is detectable
rather than silent.

## Specs

**S1** — The user's report, verbatim. The pasted request carried an `Authorization: Bearer …` JWT; its value
is redacted here on purpose, because a credential does not belong in a versioned record. Everything else is as
reported:

```
http://localhost:9000/api/companies/ecd3cf82-fabd-481f-aafd-16ec797125c5/countries/fb50c1d8-0ef2-4928-8a08-a33fa3b15fab/regions
POST · 400 Bad Request
{"regionCode":"r0121","regionName":"11332","enabled":true}
```

**S2** — The user's authorization, verbatim: *«ok saca el toggle del form»*. It answered the two options
that had been offered — fix the caller and remove the control (A), or extend the API contract to accept
`enabled` (B) — and it chose A without the alternative of wiring the toggle to the state verbs.

**S3** — The defect, measured rather than inferred. The API refuses the body before field validation runs,
and the evidence is the dev container's own DEBUG line, on the reported path, at 2026-10-21T05:27:34Z:

```
Unreadable request body: JSON parse error: Unrecognized property "enabled"
(class com.lifecontrol.api.company.dto.CreateCompanyRegionRequest), not marked as ignorable
{"status":400,"message":"Malformed or unreadable request body","path":"/api/companies/ecd3cf82-.../countries/fb50c1d8-.../regions"}
```

The strictness is `spring.jackson.deserialization.fail-on-unknown-properties=true`
(`life-control-api/src/main/resources/application.properties:29`), added by `0dfd20c` — *"migrate
life-control-api to Spring Boot 4.1.1 with Jackson 3"* — and pinned by
`MeasureUnitControllerSecurityTest.unknownFieldInBodyIsRejected`. It is a deliberate decision: the request DTO
is the wire contract and an undeclared key is a caller defect. Same class as issue #444 / PR #447
(`angular-request-body-readonly-fields`), where the decision was also to fix the callers.

The caller is the defect, and the toggle was never a feature: `CompanyRegionService.createRegion` always
stores `enabled = true`, so a create-mode toggle cannot be expressed; `updateRegion` writes only `regionCode`
and `regionName`, so an edit-mode toggle would have done nothing even if the key had been accepted. The state
is owned by the server and moves through `DELETE /{id}` (`setEnabled(false)`) and `PATCH /{id}`
(`setEnabled(true)`) — the two calls the regions list already makes from `regions-page.ts:onToggleRegion`.

**S4** — The fixed contract, one body per operation, and every key is exactly what its `*Request` DTO
declares:

| Operation | Keys the body must carry | Keys it must never carry |
| --- | --- | --- |
| `POST .../regions` | `regionCode`, `regionName` | `enabled`, `id`, `createdAt`, `updatedAt` |
| `PUT .../regions/{id}` | `regionCode`, `regionName` | `enabled`, `id`, `createdAt`, `updatedAt` |

The form holds exactly those two controls, and `onSave` builds the request from them, so a future extra key
is a compile error under `RegionControl` instead of a 400 in production.

**S5** — What must keep working, and each is covered by a case that already existed and still passes:

- The regions list keeps disabling and re-enabling: `removeRegion` → `DELETE`, `enableRegion` → `PATCH`, and
  `onToggleRegion` chooses between them from `region.enabled`.
- The list keeps hiding disabled regions by default and revealing them with `showDisabled`.
- The drill-down selectors (company → country), the create-versus-edit branch, the `409` duplicate-code
  message and the field validation messages are untouched.
- The edit page keeps pre-filling `regionCode` and `regionName` from the region carried in router state.

**S6** — What this unit deliberately does **not** do: it does not touch the API, the DTOs, the strictness
property or the test that pins it; it does not add `enabled` to `CompanyRegionRequest`; it does not add
cascade or parent-enabled enforcement to `company→country→region→zone`; and it does not touch the zones form,
which carries the same dead control — `zones-form.ts` patches `enabled` but never sends it, so it produces no
400 today and is not the reported defect.

**S7** — The flow that governs the delivery is this repository's own, not this record's: an issue before the
code (`CONTRIBUTING.md` §1, opened as **#503**), exactly one `type:*` label (§2 — `type:bug`, the diff fixes
broken behavior), the ~400-line review budget (§3), one branch in one worktree (§4), this record (§5), the
gates (§6) and a merge commit, never a squash (§7).

**S8** — The user's delivery instruction, verbatim: *«genera el issue y apruebalo y has el PR»*. Approval
(`status:approved`) is a human decision recorded on the issue, not a label the author applies to their own
request; here it was applied on the owner's explicit instruction, which is the decision §1 requires.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S7 | Open the issue that states the defect, the expected outcome and its acceptance criteria (**#503**) | — (issue, not a commit) |
| T2 | S7 | Branch and worktree `fix/regions-form-enabled-400` off `main` @ `e44e7d7` | — |
| T3 | S3, S4 | RED: the strict-key case in the form spec and the no-toggle case, observed failing before the fix | (lands with T4) |
| T4 | S4, S5 | GREEN: the body limited to the two declared keys, the toggle removed, `RegionControl` and `CompanyRegionRequest` narrowed | `fix(regions)` |
| T5 | S7 | `npm run lint`, `npm run build`, the regions specs and the full unit suite | — (checks, not a commit) |
| T6 | S7 | This record, with the evidence it cites | `docs(odd)` |

## Log

**L1** *(the user, verbatim — `S1`)*: the pasted browser request and status (`POST`, 400, the JSON body
quoted in `S1`), preceded by «puedes revisar». It arrived as a review request, not as an instruction to change
code.

**L2** *(the diagnosis — `S3`)*: the container's DEBUG line names the property, the DTO and the refusal, so the
cause needed no reproduction. What the report did not say, and the review added, is that the toggle had no
server-side meaning in either mode, which is what made "remove it" a fix rather than a workaround.

**L3** *(the user's question, verbatim)*: «la region tiene algun campo para habilitarse y deshabilitarse=?».
The answer is yes, and it is not a body field: `company_regions.enabled` is a column, `enabled` is in
`CompanyRegionResponse`, and the transition rides on `DELETE`/`PATCH`. The same answer applies to zones and
stores, and only `CompanyRequest`/`SupplierRequest` accept `enabled` in a body — which is why option B would
have created a second writer for the same state.

**L4** *(the user's decision, verbatim — `S2`)*: «ok saca el toggle del form».

**L5** *(the user's delivery instruction, verbatim — `S8`)*: «genera el issue y apruebalo y has el PR».
Approval applied on that instruction, not self-assessed.

## Evidence log

**2026-10-21 — RED, observed before any source edit.** The form spec with the two new cases against the
untouched tree: `Tests 2 failed | 27 passed (29)`. Both failures name the defect —
`expected [ 'enabled', 'regionCode', …(1) ] to deeply equal [ 'regionCode', 'regionName' ]` and a toggle that
was present when the case requires it absent. The pre-existing case `should emit saveRegion with valid data`
asserted `expect(emitted!.request.enabled).toBe(false)`: it pinned the buggy payload instead of comparing it to
the DTO, which is the reason a green suite coexisted with a 400 in the browser.

**2026-10-21 — GREEN, focused.** The same specs after the fix: `Test Files 6 passed`, `Tests 132 passed`,
covering the form, the service, the list, the card and the edit page.

**2026-10-21 — the gates.** `npm run lint` → *All files pass linting.*, exit 0. `npm run build` →
*Application bundle generation complete* in 59.3s, exit 0. `npx ng test --no-watch --coverage=false` →
`Test Files 184 passed`, `Tests 3677 passed`, exit 0.

**2026-10-21 — the diff.** Five files, +31/−27, all under
`life-control-app-angular/src/features/companies/regions/`: the form class, its template, its styles, its spec
and the feature's models. Nothing under `life-control-api/` moved, which S6 requires and `git diff --stat`
shows.
