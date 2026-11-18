# ODD feature: remove-dead-code

**Repository**: LifeControl — `life-control-app-angular/src/shared/ui/`, `life-control-app-angular/src/core/layout/header/header.spec.ts`, `life-control-api/src/main/java/com/lifecontrol/api/store/`, `purchaseorder/dto/`, `goodsreceipt/dto/`, `odd/tasks/`
**Branch**: `chore/remove-dead-code` · **Worktree**: `~/workspace/LifeControl-worktrees/chore-remove-dead-code`
**Issue**: #898 — `chore: delete the Angular components and the three endpoints nothing calls`
**Status**: implemented — T1–T7 are the branch's content: the five dead UI components, the three nested single-read endpoints and their service twins, the two test-only filter constants and the six suites that read through the removed verb. Nothing is deferred. One number moved the wrong way and is stated in the evidence log rather than smoothed over: two of the four coverage percentages fell 0.01pp, because the deleted components were marginally better covered than the repository average.
**Created**: 2026-11-18
**Risk**: none. Item 3 was considered and is the one this unit is made of, so it is stated instead of waved away: three HTTP routes stop existing, and a route is a contract. What the issue asks for is exactly that removal, and the evidence is the tree — `grep` finds no caller outside each component's own spec and the barrel, and the client that exists (`store-area.service.ts`, `store-zone.service.ts`, `store-location.service.ts`) already calls only the flat routes. The limit is stated too: the nested Javadoc says the route was «retained for contract compatibility», so a consumer **outside** this repository would not show up in any measurement here; that is the one thing this unit can neither see nor prove. Not item 1 (no stored data moves), not item 2 (no role, guard or permission changes; the nested controllers keep every other endpoint and their `@PreAuthorize` blocks), not item 5 (no image, workflow or dependency changes), not item 6 (the removal is pinned by a contract test, which is task T4's whole purpose).

## Problem

Production ships code whose only remaining consumers are a test file or a barrel re-export, and an endpoint announced for removal has not been removed.

- **Five shared UI components are dead.** `shared/ui/input/input.ts`, `shared/ui/modal.ts`, `shared/ui/field/field.ts`, `shared/ui/form-input/form-input.ts` and `shared/ui/company-selector/company-selector.ts` are imported by nothing but their own specs, and `field.html` imports `form-input`, which is itself dead. They stay alive only because `shared/ui/index.ts` re-exports them.
- **Three deprecated endpoints have no client.** `StoreAreaController.java`, `StoreZoneController.java` and `StoreLocationController.java` each carry a `@Deprecated` single-read endpoint whose Javadoc says the flat route is canonical and that the nested one is «scheduled for removal in a later slice». The frontend already calls only the flat route. Their service twins are the 6-, 7- and 8-argument `getAreaById` / `getZoneById` / `getLocationById` overloads that sit beside the 1-argument flat lookups which stay.
- **Two filter constants are test-only.** `PurchaseOrderFilter.NONE` and `GoodsReceiptFilter.NONE` are referenced exclusively under `src/test`.

## Specs

- **S1 — the Angular half is gone, measured by grep.** `grep -rn 'app-input\|app-modal\|app-field\|app-form-input\|app-company-selector' life-control-app-angular/src` returns no match, the five component directories and their specs are gone, and `shared/ui/index.ts` no longer exports them. The barrel itself **stays**: it is the largest import surface in the app.
- **S2 — the Angular suites pass and the coverage floor does not move.** `npm run lint` and `npm run test:coverage:check` pass, and the coverage percentages do not fall. The percentages are measured on the untouched tree **before** the first deletion (T1) and compared after it (T3), because "does not fall" is a comparison and not a number.
- **S3 — the three nested single-read endpoints no longer exist.** The `get` operation is gone from the nested area, zone and location paths — from the mappings and from the published document — and a contract test records it so a later change cannot silently re-add them. **The request half answers 405, never 404**, and this is the corrected form of the issue's acceptance criterion, not a relaxation of it: the same nested path keeps `PUT /{id}`, `DELETE /{id}` and `PATCH /{id}/enable` (`StoreAreaController.java:87,107,124`), whose removal is the issue's own non-goal («not the nested/flat controller split»), so Spring answers `405 Method Not Allowed` for the verb that is gone instead of `404`. A 404 is only reachable by deleting the whole nested controller. The three route shapes are `…/stores/{storeId}/areas/{areaId}`, `…/areas/{areaId}/store-zones/{storeZoneId}` and `…/store-zones/{storeZoneId}/store-locations/{storeLocationId}`; every other endpoint of those three controllers, and their flat twins, are untouched.
- **S4 — `Filter.NONE` is either used by production code or removed from both DTOs.** It is not used by production code (measured), so it goes, and the test call sites state the unfiltered filter explicitly instead of borrowing a constant that only tests read.
- **S5 — the API suite passes.** `./gradlew test` passes in `life-control-api`.

**Deliberately out of scope**, each for a stated reason: the duplicated store-tree services and the nested/flat controller split (the issue's own non-goals — this unit removes the single-read verbs, not the split); the `jsdom`, `@types/node` and Backstage findings (issue #895); the Gradle version catalog (issue #894); and any change to `PageHeader`, `ErrorBanner`, `ConfirmDialog`, `Hyperlink`, `Button`, `AddressForm`, `Spinner`, `LoadingIndicator`, `NotFound`, `Unauthorized` or `NotificationToast`, which are the barrel's live exports.

## Tasks

| ID | Specs | Task | Commit |
| --- | --- | --- | --- |
| T1 | S2 | The baseline: `npm run test:coverage:check` on the untouched worktree, with the percentages recorded, because S2 is a comparison | — |
| T2 | S1 | Delete the five components (`input/`, `modal.ts` + `modal.spec.ts`, `field/`, `form-input/`, `company-selector/`), prune their five exports from `shared/ui/index.ts`, and drop the `app-company-selector` negative assertion in `header.spec.ts` — otherwise S1's grep still matches | pid `44551c0b9867` |
| T3 | S2 | `npm run lint` and `npm run test:coverage:check` again, compared against T1's numbers | pid `44551c0b9867` |
| T4 | S3 | The RED half of S3: a contract test that fetches `/api-docs` and asserts no `get` operation survives on the three nested paths, plus the 405 the nesting cannot avoid, observed failing while the endpoints still existed | pid `9c956bc1d3bc` |
| T5 | S3 | Delete the three `@Deprecated` endpoints, their three service twins, the six `@Nested` suites that exercised them, and repoint the six integration-test readers to the flat route; T4 turns green | pid `9c956bc1d3bc` |
| T6 | S4 | `NONE` out of both filter records, with each of the five test call sites declaring the unfiltered value it passes | pid `42a68f453997` |
| T7 | S5 | `./gradlew test` in `life-control-api`, then this record's evidence log and its final header | this record |

## Log

**L1 — the request.** «puedes revisar los issues de chore y ver con cual podemos continuar»

**L2 — the choice.** The four open chore issues were compared (risk, verification cost, open decisions) and the maintainer picked «#898 — borrar lo muerto (recomendado)», authorizing `type:chore` + `status:approved` on the issue and the start of the unit in its own worktree.

**L3 — the issue's premises, checked against the tree, one of them wrong.** Measured on 2026-11-18 with `grep` before any edit:

- **Wrong: «The single real consumer of that barrel is `core/layout/header/header.ts:18`, which imports `{ Button, Hyperlink }`».** The barrel is imported by **125** call sites: `PageHeader` 47, `ErrorBanner` 43, `ConfirmDialog` 31, `ConfirmDialogData` 2, `Hyperlink` 1, `Button` 1. Deleting the barrel — or treating it as a dead-code artifact — would have broken 121 of them. What is true is the narrower claim the issue needs: none of the five named components' symbols (`Input`, `Modal`, `Field`, `FormInput`, `CompanySelector`) is imported anywhere outside its own directory. The barrel stays; five of its exports go.
- **Confirmed, with a moved line.** The negative assertion lives at `header.spec.ts:1056-1064`, not `:914`: it queries `app-company-selector` and asserts it is null. It is the selector's only reference outside the component, so it goes with the component or S1's grep cannot come back empty.
- **Confirmed.** `field/` ships no spec (only `.ts`/`.html`/`.scss`), and `field.html:10` uses `app-form-input`, so the two die together and in one commit.
- **Confirmed, with exact shapes.** The three nested single-read endpoints are overlays on controllers that otherwise stay: `@RequestMapping` at `StoreAreaController.java:36`, `StoreZoneController.java:40`, `StoreLocationController.java:41`, the `@Deprecated` GET at `:76`, `:81`, `:83`. The service twins are `StoreAreaService.getAreaById` 6-arg (`:146`) beside the 1-arg flat lookup (`:172`), `StoreZoneService.getZoneById` 7-arg (`:202`) beside `:234`, and `StoreLocationService.getLocationById` 8-arg (`:238`) beside `:272`. Removing the 6/7/8-argument overloads cannot touch the flat ones: they are distinct signatures, not a rename.
- **Confirmed, and it sizes T5.** The role matrix that proves each nested route is repeated per role in the three security tests' own `@Nested` class — `StoreAreaControllerSecurityTest.java:217`, `StoreZoneControllerSecurityTest.java:219`, `StoreLocationControllerSecurityTest.java:231`; `StoreLocationControllerTest.java:213` holds the two behavior cases (200 and 404). Those blocks are the ones that go. `ReceivingRoleSecurityGuardTest.java:87` and `StoreLocationVersionPreconditionIntegrationTest.java:68` name the nested **collection** path, which stays, so they are expected to compile and pass untouched — T5 verifies that rather than assuming it.
- **Confirmed.** `PurchaseOrderFilter.NONE` (`:20`) and `GoodsReceiptFilter.NONE` (`:25`) appear only in `src/test`, in six files: `GoodsReceiptScopeAgreementTest`, `GoodsReceiptServiceReadTest`, `GoodsReceiptControllerTest`, `PurchaseOrderServiceTest`, `PurchaseOrderControllerTest` and the two controller tests' mocks.
- **The pattern T4 copies.** `SalesOrderOpenApiContractTest` is the repository's only OpenAPI contract test: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`, reading the document at `/api-docs` (`springdoc.api-docs.path`) through an injected `ObjectMapper`. The new test is a second instance of that shape, not a new harness.

**L5 — the measure that corrected S3 instead of being papered over.** The issue's criterion says the nested `GET` «answers 404». Measured after the first deletion round, on 2026-11-18: it answers **405**. The nested path keeps `PUT /{id}`, `DELETE /{id}` and `PATCH /{id}/enable` on the same segment, so the route still exists for other verbs and Spring refuses the missing one with `405 Method Not Allowed` — and the document likewise still publishes the path, minus its `get` operation. S3 was rewritten to claim what the tree can hold, and the contract test asserts the two facts that are true and checkable: `paths[<nested>].get` is absent while `paths[<nested>].put` remains, and a `GET` on the path answers 405. The same measurement found consumers **inside** the repository that had to move to the canonical route — the store integration tests read the resource back through the nested `GET` — which is exactly the migration a real client would owe, and it is the reason those suites belong to this unit.

**L4 — the outer budget.** The review budget of ~400 authored changed lines is exceeded by construction: this unit is a **deletion** of roughly 550 lines of production code plus the test blocks that only the removed endpoints and components kept alive. Deletions are read one by one but they cannot hide behavior, which is why the unit ships as one pull request with the work split into three commits (Angular, Java endpoints, filter constants) instead of chained slices: each commit is independently reviewable and the record travels with the last one.

## Evidence log

Measured on 2026-11-18 in `~/workspace/LifeControl-worktrees/chore-remove-dead-code`, branched from `origin/main` at `9b121608`.

| # | Spec | Measurement | Result |
| --- | --- | --- | --- |
| E1 | S2 | `npm run test:coverage:check` **before** the first deletion | statements 87.23% (21168/24265) · branches 86.62% (7115/8214) · functions 81.42% (3647/4479) · lines 90.08% (16021/17785) |
| E2 | S1 | `grep -rn 'app-input\|app-modal\|app-field\|app-form-input\|app-company-selector' life-control-app-angular/src` | exit 1, no match; `shared/ui/index.ts` keeps its eleven live exports |
| E3 | S2 | `npm run lint` → «All files pass linting»; then `npm run test:coverage:check` **after** the deletion | statements 87.27% (+0.04pp) · branches 86.61% (**−0.01pp**) · functions 81.41% (**−0.01pp**) · lines 90.13% (+0.05pp). The four floors (`scripts/check-coverage.mjs:9-12`: 80/60/75/80) pass. **The two falls are arithmetic, not lost coverage of what remains**: the deleted files were 87.2% branch and 82.4% function covered against a 86.62% / 81.42% global, so removing them pulls the average down; no surviving file changed a number. |
| E4 | S3 | `./gradlew test --tests '*StoreTreeSingleReadRemovalContractTest*'` **before** the deletion | 6 tests, 6 failures, both causes as predicted: 3 × `Status expected:<404> but was:<500>` (the handler still existed) and 3 × `… is not published` |
| E5 | S3 | the same target **after** the deletion | 6/6 green — `paths[<nested>].get` absent, `paths[<nested>].put` present, the flat read still published, `GET` on the nested path → **405** |
| E6 | S3, S5 | `./gradlew test --tests '*Store*'` | 847 tests, 0 failures, 0 errors, 0 skipped; the RED run before the repointing was 15 failures, all of them `405` or `… is not published` |
| E7 | S5 | `./gradlew test` (whole module) | **5109 tests, 0 failures, 0 errors, 0 skipped**, 1160 suites, 16m29s |
| E8 | S4 | `grep -rn 'Filter\.NONE' life-control-api/src` | no match |

Provenance by **content**, per rule 8 — no branch SHA stands as evidence here:

| Subject | `git patch-id --stable` |
| --- | --- |
| the Angular deletion | `44551c0b9867e80a5b4297c54d959a7627d3be32` |
| the nested single-read removal | `9c956bc1d3bc6bd621b1732b1c15a84095fa914e` |
| the two filter constants | `42a68f45399770ae093ea2787295bdcd382fe492` |

A patch-id is **content**, and it resolves as no object at all, so it is not a citation and needs no entry in
`odd-citations.test.sh`'s frozen list: it says what arrived, and a rewrite of the branch cannot move it.

**Slice boundaries, recorded because the harness measures them.** The delivery is four slices on one branch, and its first slice is 1165 authored changed lines on its own — past the ~400-line review budget — because it is a deletion: ~2200 lines leave the tree and ~250 arrive. The decision is stated in L4 and repeated here rather than hidden: one branch with ordered commits and no chained PRs, because a deletion cannot hide behavior the way an addition can, and because the record ships in the pull request that carries the work (rule 4).

**What these checks do not prove, stated so nobody reads more into them.** Nothing here ran against a live stack: the 405 is a routing answer and the contract document is a generated artifact, both measured in-process through `MockMvc`. The Angular E2E suite was not run — it is not required by S2 and no E2E file references any of the five selectors (measured with `grep` over `life-control-app-angular/e2e`), so the removal cannot move it.
