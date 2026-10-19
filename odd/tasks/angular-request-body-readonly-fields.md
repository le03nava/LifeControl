# ODD feature: angular-request-body-readonly-fields

**Repository**: LifeControl — `life-control-app-angular` only. The three edit pages that build a request body
(`src/features/companies/companies/pages/company-edit/`, `src/features/products/pages/product-edit/`,
`src/features/products/suppliers/pages/supplier-edit/`), the three forms that feed them
(`.../components/companies-form/`, `.../components/products-form/`, `.../components/suppliers-form/`), the three
`models/` files and the parameter types of `company.service.ts` and `product.service.ts`. No API code, no schema,
no endpoint, no dependency, no stored data.
**Status**: **implemented** — the fix, its three page specs, the `supplier-edit.spec.ts` that did not exist and this
record are the branch's content. This header makes no claim about push or PR state; see the evidence log.
**Created**: 2026-10-18 · **Base**: `main` @ `f16d7ae` · **Branch**: `fix/angular-request-body-readonly-fields` ·
**Worktree**: `~/workspace/LifeControl-worktrees/fix-angular-request-body-readonly-fields` (herdr workspace `w4J`) ·
**Issue**: **#444**
**Risk**: **none**. The wire contract does not move: `CompanyRequest`, `ProductRequest` and `SupplierRequest` already
declare exactly the fields the fixed bodies carry, so this removes keys the API has rejected since `0dfd20c` — not
keys it consumes. No stored data, no secret, no permission, no concurrency, no pipeline. The three page specs added
here fail on the pre-fix tree, so a regression is detectable rather than silent.

## Specs

**S1** — The user's report, verbatim. The pasted request carried an `Authorization: Bearer …` JWT; its value is
redacted here on purpose, because a credential does not belong in a versioned record. Everything else is as
reported:

```
PUT /api/companies/ecd3cf82-fabd-481f-aafd-16ec797125c5 -- 400 Bad Request

{"companyKey":"EMPRESA_PRUEBA","companyName":"Empresa Pruebas","tipoPersonaId":1,
 "razonSocial":"NAAL870131RH2","rfc":"NAAL870131RH2","email":"leo3nava@gmail.com",
 "emailDomain":"gmail.com","phone":"+528120017609","enabled":true,
 "createdAt":"","updatedAt":"",
 "address":{"street":"Janitzio","streetNumber":"66414","internalNumber":null,
            "neighborhood":"Res SN","zipCode":"66414","city":"San Nicolas de los Garza",
            "state":"Nuevo León","countryId":"583578dd-8d49-44c1-8a87-8c52c8408c58"}}
```

**S2** — The user's authorization, verbatim: *«ok dale a la a, genera todo el flujo de crear el issue, y hacer los
cambios en su worktree propio»*. Option **A** as it was offered, verbatim: *"Que los 3 forms armen el payload
explícitamente en vez de esparcir el modelo de respuesta. Los campos de solo-lectura (`createdAt`, `updatedAt`,
`id`) no tienen por qué viajar en un request, y conservás la detección de typos"*. The issue and the private
worktree are part of the instruction, and they are this repository's own flow, not this record's invention.

**S3** — The defect, measured rather than inferred. The API refuses the body before field validation runs, and the
evidence is the dev container's own DEBUG line, three hits on the reported path:

```
Unreadable request body: JSON parse error: Unrecognized property "createdAt"
(class com.lifecontrol.api.company.dto.CompanyRequest), not marked as ignorable
```

The strictness is `spring.jackson.deserialization.fail-on-unknown-properties=true`
(`life-control-api/src/main/resources/application.properties:29`), added by `0dfd20c` — *"migrate life-control-api
to Spring Boot 4.1.1 with Jackson 3"* — and pinned by `MeasureUnitControllerSecurityTest.unknownFieldInBodyIsRejected`
(line 153, `400 Malformed or unreadable request body`). It is a deliberate decision: the request DTO is the wire
contract and an undeclared key is a caller defect.

The callers are the defect. All three pages built the body by spreading the *response* model and casting it —
`const { id: _id, address, ...rest } = data; const payload = { ...rest, ...(hasAddress ? { address } : {}) };
this.service.updateX(id, payload as unknown as X)` — and the forms fabricate two server-owned keys on the way in
(`createdAt: ''`, `updatedAt: ''`). The bodies therefore carry `createdAt`, `updatedAt`, and for products also `id`
and `enabled`.

**S4** — The fixed contract, one body per feature, and every key is exactly what its `*Request` DTO declares:

| Feature | Keys the body must carry | Keys it must never carry |
| --- | --- | --- |
| companies (POST + PUT) | `companyKey`, `companyName`, `tipoPersonaId`, `razonSocial`, `rfc`, `phone`, `email`, `emailDomain`, `enabled`, and `address` when S5 allows it | `id`, `createdAt`, `updatedAt`, `countries` |
| products (POST + PUT) | `sku`, `name`, `shortName`, `satCode`, `productType`, `attributes` | `id`, `enabled`, `createdAt`, `updatedAt` |
| suppliers (POST + PUT) | `supplierName`, `razonSocial`, `rfc`, `email`, `phoneNumber`, `internalNumber`, `enabled`, and `address` when S5 allows it | `id`, `createdAt`, `updatedAt` |

The page builds that object as a written-out literal typed against the request interface, so a future extra key is a
compile error instead of a 400. The services take the request type as their parameter (`supplier.service.ts` already
did; `company.service.ts` and `product.service.ts` now do too), and `CompanyRequest` is added to
`company.models.ts` because only that one was missing.

Each form emits its domain model minus the two server-owned keys — a named `CompanyFormValue`,
`ProductFormValue` and `SupplierFormValue` alias built with `Omit<…, 'createdAt' | 'updatedAt'>` in the feature's
`models/` file — instead of fabricating `createdAt: ''` and `updatedAt: ''`. That fabrication is where the defect's
invisibility started: it made the spread look complete, and no type complained. The three form specs' subscription
callbacks are retyped to the alias; no assertion changes.

**S5** — What must keep working, and is pinned by a case of its own:

- The address block travels **only** when at least one of its fields is non-empty (`hasAddress`); a form whose
  address is entirely empty must not create an all-null `Address` row. A non-empty address still travels field by
  field.
- The company body still carries `emailDomain`, blank collapsing to `""`, which the API normalizes to `null`
  (the same full-replacement convention `CompanyService.updateCompany` documents).
- The product body still carries `attributes` as the object the form parsed from the JSON textarea, and omits it
  when the textarea is blank.
- The suppliers body still carries `enabled` — `SupplierRequest` declares it.
- The create-versus-update branch (`id === ''`) and every navigation stay exactly as they are.

**S6** — What this unit deliberately does **not** do: it does not touch the API, the strictness property, or the
test that pins it; it does not relax the contract on the server side, which is the other option that was offered as
*"B — Relajar el backend"* and was not chosen; it does not add `enabled` support to `ProductRequest`; and it does not
re-litigate `0dfd20c`'s claim that Boot 3.5 answered 400 for these bodies, which the pre-migration tree does not
support (no `spring.jackson.*` key and no `ignoreUnknown` anywhere under `life-control-api/src/main`) — that
discrepancy is stated in issue **#444** and left as the maintainer's call.

**S7** — The flow that governs the delivery is this repository's own, not this record's: an issue before the code
(`CONTRIBUTING.md` §1, opened as **#444**; `status:approved` is a human decision and must exist before the pull
request is opened), exactly one `type:*` label (§2 — `type:bug`, the diff fixes broken behavior), the ~400-line
review budget (§3), one branch in one worktree (§4), this record (§5), the gates (§6) and a merge commit, never a
squash (§7).

## Tasks

| ID | Spec | Task | Commit |
| --- | --- | --- | --- |
| T1 | S2, S7 | Open the issue that states the defect, the expected outcome and its acceptance criteria (**#444**) | — (issue, not a commit) |
| T2 | S2, S7 | Branch and worktree `fix/angular-request-body-readonly-fields` off `main` @ `f16d7ae`, registered in the session | — |
| T3 | S3, S6 | RED: the strict payload cases in the three page specs, observed failing before the fix | `—` (observed in the worktree; the cases land with T4–T6) |
| T4 | S4 | GREEN, companies: `CompanyRequest` + the explicit body in `company-edit` + `company.service.ts` parameter type | `8dbd897` |
| T5 | S4, S5 | GREEN, products: the explicit body in `product-edit` + `product.service.ts` parameter type | `a301702` |
| T6 | S4, S5 | GREEN, suppliers: the explicit body in `supplier-edit` and the removal of both `as unknown as` casts | `294c57c` |
| T7 | S5, S7 | `npm run lint`, `npm run build` and the three specs green | `—` (checks, not a commit) |
| T8 | S7 | This record, with the hashes it cites | `docs(odd)`, this commit |

## Log

**L1** *(the user, verbatim — `S1`)*: the pasted browser request and status (`PUT`, 400, the JSON body quoted in
`S1`), preceded by «puedes revisar». It arrived as a review request, not as an instruction to change code.

**L2** *(the diagnosis this unit is built on — `S3`)*: the container's DEBUG line names the property, the DTO and
the refusal, so the cause needed no reproduction: `docker logs lifecontrol-dev-lifecontrol-api` carried all three
hits, on the same path the user pasted, inside the same minute range. The migration commit's own message and its
pinning test settle the other half — the strictness was chosen on purpose, which is what moved the fix to the
callers.

**L3** *(why the blast radius is three features and not one — `S3`)*: the three pages share one construction shape.
`products-form.ts:134` and `suppliers-form.ts:121` fabricate the same two keys as `companies-form.ts:153`, and
`product-edit.ts:248` passes the whole model to `updateProduct` — including `id` and `enabled`, which
`ProductRequest` does not declare either. Only companies appears in the 72-hour log, because the other two were not
exercised end-to-end after the migration; the defect in them is static, not observed.

**L4** *(the user's decision, verbatim — `S2`)*: «ok dale a la a, genera todo el flujo de crear el issue, y hacer los
cambios en su worktree propio». The unit is the fix plus the issue plus the worktree.

**L5** *(the issue, and the one decision this unit does not take — `S7`)*: **#444** quotes the reported body, the
container line and the strictness property, and its acceptance criteria are the ones the tasks execute, including
the end-to-end one that can only be verified against the running stack. `status:approved` is deliberately **not**
applied by this unit: `CONTRIBUTING.md` §1 makes it a human decision recorded on the issue, and the label guard's
`A linked, approved issue` job reads it.

## Evidence log

**2026-10-18 — RED, observed before any source edit.** The three new page specs against the untouched tree:
`npx ng test --no-watch --coverage=false --include …company-edit.spec.ts …product-edit.spec.ts …supplier-edit.spec.ts`
→ `Test Files 3 failed (3)`, `Tests 8 failed | 71 passed (79)`. Every diff named the defect:
`+ "createdAt": ""` and `+ "updatedAt": ""` on the received body — the property `CompanyRequest` does not declare.
The product update case was failing for the **wrong** reason, because `updateProduct(id, data)` takes the id first
and it compared argument 0; it was corrected to `mock.calls[0][1]` and re-observed against the pre-fix product files
alone (`git stash push` over the four `products/` sources) → `Tests 2 failed | 57 passed (59)`, with `+createdAt`,
`+enabled`, `+updatedAt` and, on the update, `+id`.

**2026-10-18 — GREEN, focused.** The same three specs after the fix → `Test Files 3 passed (3)`,
`Tests 79 passed (79)`, exit 0.

**2026-10-18 — the full gate, twice, because the pre-commit hook rewrote the tree.** `npm run lint` (*All files pass
linting.*) → exit 0; `npm run build` (*Application bundle generation complete.*) → exit 0;
`npm run test:coverage:check` → `Test Files 183 passed (183)`, `Tests 3652 passed (3652)`, four thresholds OK,
exit 0. The first run pinned a defect the other two gates cannot see, because neither `ng lint` nor `ng build`
compiles spec files: `TS2345: Argument of type 'Company' is not assignable to parameter of type 'CompanyRequest'` at
`company.service.spec.ts:134` and `:147`, from `emailDomain` having been declared required. It is optional in the API
DTO, so it became optional here, the page's `?? ''` keeps the key on the wire, and a fourth case pins that. The husky
`pre-commit` hook then ran `eslint --fix` and `prettier --write` over each commit's staged files, so the committed
revision is not byte-identical to the one the first run verified: the whole gate was re-run on the committed
revision with the same result. The hook's change was formatting only — one long chained call rewrapped in
`supplier-edit.spec.ts`.

**2026-10-18 — the commits.** `8dbd897` companies, `a301702` products, `294c57c` suppliers, each carrying its own
feature's spec cases; this record is the fourth.

**Open, and not this record's to close.** `status:approved` on **#444** is a human decision (`CONTRIBUTING.md` §1;
the label guard's `A linked, approved issue` job reads it), and push, pull request and merge are separate decisions.
The branch's diff is **+459/−102 across 15 files**, over §3's advisory ~400 authored lines; the three commits are
already one cohesive slice each, so the chain those sections prescribe needs no reshaping of the work — companies
(178) and products (70) inside the budget, suppliers (211) as its own slice.
