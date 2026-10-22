# ODD feature: fix-zones-form-enabled-dead-control

**Repository**: LifeControl — `life-control-app-angular` only. The zones form
(`src/features/companies/zones/components/zones-form/`), its model and its spec. No API code, no DTO, no
strictness property, no schema, no stored data.
**Status**: implemented — the dead `enabled` toggle is gone, the form holds exactly the four controls its
DTOs declare, and the two cases that pin it are on the tree. Nothing is deferred.
**Created**: 2026-10-22 · **Base**: `main` @ `1e8217d` · **Branch**:
`fix/zones-form-enabled-dead-control` · **Worktree**:
`~/workspace/LifeControl-worktrees/fix-zones-form-enabled-dead-control` · **Issue**: **#525**
**Risk**: **none**. The wire contract does not move: the form already emitted exactly `zoneCode`,
`zoneName`, `description` and `displayOrder` — the four keys `CreateCompanyZoneRequest` and
`UpdateCompanyZoneRequest` declare — so nothing the API accepted stops being accepted. What goes away is a
UI control and a `FormControl` that no endpoint ever read. No stored data, no secret, no permission, no
concurrency, no pipeline, no dependency. Two spec cases fail on the pre-fix tree, so a regression is
detectable rather than silent.

## Specs

**S1** — The user's request, verbatim:

```
puedes continuar con El control enabled del form de zonas (zones-form.html:100, zones-form.ts:92): el hermano
   exacto del defecto que fix-regions-form-enabled-400 cerró para regiones.
```

It is a continuation instruction, not a new decision: it names the sibling unit and asks for the same
remedy, at the file and line it points to.

**S2** — The decision this unit inherits, verbatim from `odd/tasks/fix-regions-form-enabled-400.md`
(L4): «ok saca el toggle del form». That answered the two options on offer — fix the caller and remove the
control (A), or extend the API contract to accept `enabled` (B) — and chose A. Applied here unchanged: the
control goes, the API is untouched. It is the same reason as there: `enabled` is response-only on this tree.

**S3** — The defect, measured rather than inferred, and **not** the same failure mode as the sibling:

- `zones-form.html:100` rendered `<mat-slide-toggle formControlName="enabled">Activo</mat-slide-toggle>`,
  and `zones-form.ts:92` declared `enabled: new FormControl(true, { nonNullable: true })`.
- Edit mode patched it — `zones-form.ts:143` did `enabled: zone.enabled` — and `onSave` never read it: the
  emitted body is built from `{ zoneCode, zoneName, description, displayOrder }` only.
- The API never reads it either. `CompanyZoneService.createZone` always stores `enabled(true)`
  (`CompanyZoneService.java:132`); `updateZone` writes `zoneCode`, `zoneName`, `description` and
  `displayOrder` and nothing else (`:160-175`). The state is owned by the server and moves through
  `DELETE /{id}` (`setEnabled(false)`, `:183`) and `PATCH /{id}` (`setEnabled(true)`, `:200`).
- Those two verbs already have callers: `zones-list.ts:49-50`, `zones-card.ts:45-48` and
  `zones-page.ts:227,237,256` disable and re-enable from the list, the card and the page.

So the control was **a promise no endpoint kept, and it failed silently**: unlike the regions form — which
sent the key and collected a `400 Malformed or unreadable request body` — a zone toggle flipped a local
control, told the user the zone was now inactive, and reverted on the next load with no error anywhere.
Same class of defect, worse symptom: the 400 at least named itself.

**S4** — The contract after the fix, one body per operation, every key exactly what its `*Request` DTO
declares:

| Operation | Keys the form holds | Keys the body carries | Keys it must never carry |
| --- | --- | --- | --- |
| `POST .../zones` | `zoneCode`, `zoneName`, `description`, `displayOrder` | the four, with the two optional ones omitted when empty | `enabled`, `id`, `createdAt`, `updatedAt` |
| `PUT .../zones/{id}` | same four | same four | `enabled`, `id`, `createdAt`, `updatedAt` |

`ZoneControl` (the typed control map) no longer names `enabled`, so reintroducing the control is a
compile error rather than a silent no-op, and `CompanyZoneRequest` carries the reason in a doc comment, the
way `CompanyRegionRequest` does.

**S5** — What must keep working, and what covers it — the pre-existing cases of
`zones-form.spec.ts`, `zones-card.spec.ts`, `zones-list.spec.ts`, `zones-edit.spec.ts`,
`zones-page.spec.ts` and `company-zone.service.spec.ts` all still pass unchanged (147 of them):

- Create mode: the company/country/region drill-down, the three `mat-select`s, and the query-param
  pre-selection of each level.
- Edit mode: the title, the `Actualizar` label, the pre-fill of `zoneCode`, `zoneName`, `description` and
  `displayOrder`, and the pre-selection of company, country and region from the zone.
- Validation: required / maxLength / pattern / min messages, and the server-error mapping with its
  clear-on-change behaviour.
- The list, the card and the page keep disabling (`DELETE`) and re-enabling (`PATCH`) zones, keep hiding
  disabled zones by default, and keep revealing them with `showDisabled`.

**S6** — What this unit deliberately does **not** do: it does not touch the API, the DTOs, the strictness
property (`spring.jackson.deserialization.fail-on-unknown-properties=true`) or the test that pins it; it
does not add `enabled` to `CompanyZoneRequest`; it does not add cascade or parent-enabled enforcement to
`company→country→region→zone`; it does not touch `store-zone-form`, which never had the control; and it
does not touch `companies-form` or `stores-form`, whose toggles are a different question because
`CompanyRequest` accepts `enabled` in a body.

**S7** — The flow that governs the delivery is this repository's own, not this record's: an issue before
the code (`CONTRIBUTING.md` §1), exactly one `type:*` label (§2 — `type:bug`, the diff fixes broken
behaviour), the ~400-line review budget (§3), one branch in one worktree (§4), this record (§5), the gates
(§6) and a merge commit, never a squash (§7). The issue is **#525**; its approval (`status:approved`) was
applied on the owner's explicit instruction, which is the decision §1 requires.

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S7 | Open the issue that states the defect, the expected outcome and its acceptance criteria (**#525**) | — (issue, not a commit) |
| T2 | S7 | Worktree and branch `fix/zones-form-enabled-dead-control` off `main` @ `1e8217d` | — |
| T3 | S3, S4 | RED: the no-toggle case and the no-enabled-control case in `zones-form.spec.ts`, plus the exact-key-set case for the body | (lands with T4) |
| T4 | S4, S5 | GREEN: the toggle, the `FormControl`, the edit-mode patch, `MatSlideToggleModule`, the `.toggle-container` rule and the `ZoneControl` field removed; `CompanyZoneRequest` documented | `fix(zones)` |
| T5 | S7 | `npm run lint`, `npm run build`, the zones specs and the full unit suite | — (checks, not a commit) |
| T6 | S7 | This record, with the evidence it cites | `docs(odd)` |
| T7 | S7 | The pull request against `main`, with `type:bug` | — (pull request, not a commit) |

## Log

**L1** *(the user's request, verbatim — `S1`)*: the continuation instruction quoted above, anchored at
`zones-form.html:100` and `zones-form.ts:92` and naming `fix-regions-form-enabled-400` as the sibling.

**L2** *(the decision inherited — `S2`)*: «ok saca el toggle del form», from the sibling record. Nothing
new was decided here; the remedy was already chosen for the same defect class on the adjacent feature, and
the user's instruction says "el hermano exacto".

**L3** *(the diagnosis — `S3`)*: the two anchors the user named are the symptom, and the cause needed
three reads to state precisely: the control exists, the form patches it, `onSave` drops it, and no endpoint
reads it. The zone form never produced the sibling's `400`; it produced nothing at all, which is why its
spec was green while the UI lied.

**L4** *(the owner's delivery decision, verbatim, answering a three-way choice)*: "Issue + PR, como el
hermano de regiones" — open the issue, approve it, commit the fix and the record, and open the pull request
with `type:bug`. The approval was applied on that instruction, not self-assessed.

## Evidence log

**2026-10-22 — RED, observed before any source edit.** `zones-form.spec.ts` with the new cases against the
untouched tree: `Test Files 1 failed | 5 passed (6)`, `Tests 2 failed | 147 passed (149)`. Both failures
name the defect — `expected null to be truthy`… inverted: the toggle was still rendered
(`should NOT render a slide-toggle for enabled`) and `expected true to be false` for
`formGroup.contains('enabled')`. The third case, the exact key set of the emitted body, passes on the
pre-fix tree on purpose: it is the guard that keeps the payload from growing the way the regions payload
had grown.

**2026-10-22 — GREEN, focused.** The zones specs after the fix: `Test Files 6 passed`,
`Tests 149 passed`, covering the form, the card, the list, the edit page, the zones page and the service.

**2026-10-22 — the gates.** `npm run lint` → *All files pass linting*, exit 0. `npm run build` →
*Application bundle generation complete* in 9.3s, exit 0. `npx ng test --no-watch --coverage=false` →
`Test Files 185 passed`, `Tests 3729 passed`, exit 0.

**2026-10-22 — the diff.** Five files, +48/−22, all under
`life-control-app-angular/src/features/companies/zones/`: the form class, its template, its styles, its
spec and the feature's models. Nothing under `life-control-api/` moved, which `S6` requires and
`git diff --stat` shows.
