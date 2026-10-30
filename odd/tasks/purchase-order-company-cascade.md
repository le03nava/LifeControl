# ODD feature: purchase-order-company-cascade

**Repository**: LifeControl — `life-control-app-angular/src/features/purchases/purchase-orders/`: the cascade service, the company section, the order edit page and their specs. Issue #690.
**Status**: **implemented** — the supplier picker of a purchase order queries the order company's catalog again, because the company now travels on the same kind of channel the store already uses instead of a `valueChanges` stream the cascade never emits. The three reads that depend on that value — supplier search, the supplier's products and the variant picker — and the edit-mode supplier detail are pinned by specs that select the company the way the UI does. Nothing is deferred.
**Created**: 2026-10-30 · **Risk**: none — no stored data, credential, guard, public contract or delivery surface changes; `CompanyCascadeService` is provided locally by this screen (`providers: [CompanyCascadeService]` in `company-info-section.ts`), so no other screen inherits the change, and the new member is additive.

## Specs

S1 — **"cuando ingreso texto en el proveedor no veo que lance ningur request"**.
Typing in the **Proveedor** autocomplete of the purchase order form must issue `GET /api/companies/{companyId}/suppliers` with the typed term 300 ms after the last keystroke, and offer only that company's enabled suppliers. Measured cause: the picker refuses to query without a company (`supplier-info-section.ts:146-151`), and the value it receives is `orderCompanyId()`, a `toSignal` over `company.controls.companyId.valueChanges` (`purchase-order-edit.ts:91-96`) — a stream the cascade never emits on, because every write is `setValue(..., { emitEvent: false })` (`company-cascade.service.ts:204,397`). The company is written and never observed.

S2 — **"apruebalo y has el PR"**.
The repository flow is issue-first: the approved issue #690, the `fix/` worktree, this record, the RED, the fix and the pull request carrying `Closes #690` with exactly one `type:*` label.

S3 — **the fix reads the value from the channel that actually carries it**.
`CompanyCascadeService` already publishes the store it applied as `storeId` plus a `storeResolved` output, and documents why (`company-cascade.service.ts:66-77`, `company-info-section.ts:88-96`, `purchase-order-edit.ts:152-158`). The company gets the same treatment — a `companyId` signal in the service and a `companyResolved` output in the section — instead of emitting on `valueChanges`, which would mark the form dirty on every programmatic patch and break the unsaved-changes guard.

## Tasks

| # | Task | S# | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Issue #690 with the five acceptance criteria, approved by the maintainer; worktree `fix/purchase-order-company-cascade` through `new-unit.sh`; this record | S2 | done — `status:approved` applied and read back with `bug` preserved | `b0af9501` |
| T2 | RED: specs that select the company **through the cascade** (the silent channel the UI uses) and fail on today's code | S1, S3 | done — three assertion failures on the untouched code: `orderCompanyId()` stayed `null` after `onCompanyChange` and after `reconstructFromOrder`, and `getProductsBySupplier` had 0 calls; 50 other tests in the file passed | `b0af9501` |
| T3 | `CompanyCascadeService`: `companyId` signal plus a single `patchCompany` owner of the control write, used by `advanceCascade` and `onCompanyChange` | S3 | done — both silent writes now go through the one owner, so the signal cannot drift from the control, mirroring `patchStore`/`storeId` | `b0af9501` |
| T4 | `CompanyInfoSection`: `companyResolved` output mirroring the signal; `PurchaseOrderEdit`: `orderCompanyId` becomes a signal fed by that output | S1, S3 | done — `toSignal`/`startWith` dropped from the page; `(companyResolved)="onCompanyResolved($event)"` is the single source, next to the existing store channel | `b0af9501` |
| T5 | `npm test` in `life-control-app-angular`: the new specs green, the corrected canal-pin specs green, nothing else changed | S1 | done — `purchase-order-edit.spec.ts` 53/53; full suite `npx ng test --no-watch` exit 0; `npx prettier --check "src/**/*.{ts,html,scss}"` clean after one formatting pass; `npm run lint` clean | `b0af9501` |
| T6 | Pull request with `type:bug` and `Closes #690`, CI green | S2 | done — PR #691, `type:bug`, `Closes #690`, linked to the approved issue | `b0af9501` |

## Log

L1 — Request that opened the work, verbatim: *"cuando ingreso texto en el proveedor no veo que lance ningur request"*.

L2 — 2026-10-30, authorization for the delivery, verbatim: *"apruebalo y has el PR"*.

L3 — 2026-10-30, cause measured before any edit. `supplier-info-section.ts:146-151` returns without a request when `companyId` is falsy; the binding is `[companyId]="orderCompanyId()"` (`purchase-order-edit.html:52-58`); `orderCompanyId` is `toSignal` over `company.controls.companyId.valueChanges` (`purchase-order-edit.ts:91-96`); and `company-cascade.service.ts` writes `companyId` with `{ emitEvent: false }` at `:204` (`advanceCascade`) and `:397` (`onCompanyChange`). `valueChanges` does not emit for a silent patch, so the signal stays `null` in the real UI. The same defect reaches `getProductsBySupplier` (`purchase-order-edit.ts:209-214`), the `app-detail-table [companyId]` binding and, in edit mode, the supplier detail card (`reconstructFromOrder` is silent too).

L4 — 2026-10-30, why the specs did not catch it: `purchase-order-edit.spec.ts:311,325` set the control with an emitting `setValue`, a channel the application never uses, so the assertion passed while the app stayed broken. T2 replaces that measurement with the real channel.

L5 — 2026-10-30, scope decision. Adding the output and the signal is additive and the service is provided by the purchase-order screen alone (`grep -rln CompanyCascadeService` returns the service and `company-info-section.ts`, nothing else), so no sibling screen inherits the change. Rejected alternative: emitting on `valueChanges` for `companyId`, because `headerForm().valueChanges` drives `formDirty` and every programmatic patch would mark a pristine order dirty.

L6 — 2026-10-30, RED observed before the fix, on the untouched implementation: `should expose the order company the variant picker is scoped to` → expected `'company-1'`, received `null`; `should load supplier products for a company chosen through the cascade` → `getProductsBySupplier` called 0 times; `should follow the company an order reconstruction resolves` → expected `'company-1'`, received `null`. 50 tests in the file passed, so the failure was the reported defect and not a broken fixture.

L7 — 2026-10-30, GREEN and checks. The three specs pass and the file reports 53/53. Full suite `npx ng test --no-watch` exits 0 (statements 86.44 %, lines 89.39 %); `npx prettier --check "src/**/*.{ts,html,scss}"` passes after one `--write` on the spec (my multi-line expectations); `npm run lint` reports "All files pass linting". A second pre-existing spec, `should load supplier products when supplier changes, scoped to the order company`, also measured the direct `setValue` channel and was moved to the cascade in T2 — it failed as soon as the page stopped reading `valueChanges`, which is the same coupling the report names.

L8 — 2026-10-30, the emitter needs a detection cycle: a spec that calls `cascade.onCompanyChange(...)` must `fixture.detectChanges()` before asserting, because `companyResolved` is emitted from an `effect`. The existing "silent store resolution" describe already does this for the store, and the new specs follow it.

L9 — 2026-10-30, delivery evidence. One work-unit commit `b0af9501` on `fix/purchase-order-company-cascade` (188 authored changed lines including this record), pushed, and **PR #691** opened against `main` with `type:bug` and `Closes #690`; the linked issue carried `status:approved` before the PR existed. The commit hook re-ran `eslint --fix` and `prettier --write` on the staged files and reported no further changes.
