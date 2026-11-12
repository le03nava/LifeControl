# purchase-order-refused-create-number

**Status**: implemented — a purchase order's document number is allocated after every line has been resolved,
so a create refused inside the line loop leaves no value taken from the sequence. Issue #857. Branch
`fix/purchase-order-refused-create-document-number`, base `origin/main` @ `e6e1695b`. Nothing is deferred: the
one window that cannot be closed by ordering — a crash between the `nextval` and the commit — is named as a
property PostgreSQL does not offer (ADR-0003 decision 8), not as pending work.
**Created**: 2026-11-12 · **Risk**: none — no stored data changes shape, no credential, guard or concurrency
model moves, and no public contract changes: the same requests succeed with the same numbers and the same
refusals answer 400 with the same message. The observable difference is the one the issue asks for, a refusal
that no longer costs a number, and it is pinned by a test that fails on the previous tree (`S1`).

## Specs

- **S1** — "A create refused by the tax resolution leaves the sequence untouched (the existing test flips from
  `verify(...)` to `verify(..., never())`)." The reservation is the assertion itself: the case in
  `PurchaseOrderServiceTest` that refuses a taxable code with no rate carried a deliberate `verify(...)` with a
  comment saying it documented a gap; it now asserts `never()`. Observed **RED** first
  (`NeverWantedButInvoked` at `PurchaseOrderServiceTest.java:2354`), green after the reorder.
- **S2** — "The success cases keep the number they get today, pinned by the existing `CreatePurchaseOrderTests`
  expectations." Not just kept: the whole `PurchaseOrderServiceTest` class (85 cases, including the two that
  assert `PO-…-00001` on a created order) passes on both trees.
- **S3** — "ADR-0003 decision 4 and the comment at `:213-216` are reconciled with the new order rather than
  silently contradicted." Decision 4 gained the paragraph that states where "the last write before commit" lands
  for a document that needs its number at INSERT time; the service comment that described the lock as "held
  while the lines are resolved" was rewritten. `AGENTS.md`'s row that said a refusal "deja un número salteado"
  was false as of this commit and was rewritten with it.
- **S4** — "`./gradlew test --tests 'com.lifecontrol.api.purchaseorder.*'` green."

## Tasks

| ID | Spec | Route | Evidence |
| --- | --- | --- | --- |
| T1 | S1 | inline | the assertion flips and fails on the old tree, `NeverWantedButInvoked` |
| T2 | S1, S2 | inline | resolution moved above the allocation in `PurchaseOrderService.createPurchaseOrder` |
| T3 | S2 | inline | the four cases that stubbed the allocator only to be refused no longer stub it |
| T4 | S3 | inline | ADR-0003 decision 4, the service comment and the `AGENTS.md` row |
| T5 | S4 | inline | full `./gradlew test` and `spotlessCheck` on this worktree |

## Log

**L1 (2026-11-12) — the request, and the second issue it arrived with.** «ok dale con 857by 852». #857 is this
record. #852 was answered as a decision rather than as code — «el tax code se define a nivel producto no hay
proveedor excento» — and is recorded where the gap it names already lives
(`odd/tasks/purchase-order-line-tax.md`, "The two gaps this unit carries").

**L2 (2026-11-12) — what the code actually did, read rather than trusted.** `createPurchaseOrder` read the
clock, derived the period, allocated, rendered the number and built the order, and only then entered the line
loop that calls `validateProductExists`, `resolveProductVariant` and `lineTaxFor` — three reads that can all
refuse. So the refusal taken as the example in `L10` of the tax record was one of three ways to pay for a
number that was never used.

**L3 (2026-11-12) — the shape of the fix, and why a record for it.** The loop split in two: a pass that
resolves each line into a `ResolvedLine(product, productVariant, request, detailState, tax)` with no document
in hand, then the allocation, then the rows built from what the first pass produced. The order's builder is
untouched, so nothing about the row's identity changed — only what runs before it exists. `L10` of the tax
record had already named this as "its own small unit with its own review"; this is that unit, and the pointer
back is `L16` there.

**L4 (2026-11-12) — the four tests that broke, and why that is the evidence rather than noise.** Once the
allocation moved, four refusal cases failed with `UnnecessaryStubbingException` on
`documentSequenceAllocator.allocate(...)`: they had stubbed the allocator precisely because it ran too early.
Three stubs were removed and the fourth — a variant from another store — now asserts `never()`, which is the
same reservation `S1` asks for on the tax path. The shared `stubCreateOf` helper lost its allocator stub for the
reason its own sibling already gave: a stub a refused path never reaches fails the case for the stub instead of
for the behaviour.

**L5 (2026-11-12) — what is still not promised.** `nextval` is non-transactional by design, so a crash between
the allocation and the commit still leaves a hole. This commit narrows the window from "the whole line
resolution" to "between the number and the INSERT", and it says so in the ADR instead of letting the next
reader infer "no consumption" from a green test.
