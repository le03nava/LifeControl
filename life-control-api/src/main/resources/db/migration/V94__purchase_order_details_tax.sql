-- ============================================
-- V94 — a purchase-order line states its own tax, and the document sums it
--        (S1, S6 and S7 of odd/tasks/purchase-order-line-tax.md, T1)
-- ============================================
-- `V92` gave the line one definition of its money and `V93` gave the document its own. Neither states a tax:
-- `purchase_order_details` has no tax column at all, `purchase_order_detail_money()` derives `total :=
-- subtotal + 0` with a comment that says the term arrives here, and `purchase_order_money()` derives
-- `tax_total := 0` with the same note. The document's four columns exist from `V93` so that this unit could
-- add a **term** instead of re-deriving money that was already derived once; this file adds it.
--
-- WHY THE THREE COLUMNS ARE ON THE LINE AND NOT COMPUTED AT READ TIME. A read-time computation answers with
-- today's catalog every time it is asked, so the same purchase order would cost a different amount next
-- month than the invoice it was matched against, and nothing would record which answer the document was
-- created with — the shape `V57` and issue #829 already had to repair once for a company's currency. The
-- number a report filters and a supplier is paid against has to be a stored fact of the row, which is why
-- `V80`/`V81` put a sale line's money and tax on the line. `tax_amount` is derived rather than accepted from a
-- client — the derivation is the schema's, exactly as `subtotal` and `total` already are — but it is
-- **stored**, so history keeps the number that was true when the line was written.
--
-- WHY `tax_rate` IS A COLUMN AND NOT A JOIN TO `tax_rate`. The catalog (`V82:52-61`) is append-only per
-- (company_id, tax_code, effective_from) and a resolver takes the latest rate whose `effective_from` is not
-- after the document's date. A join would therefore restate every past document the moment the company
-- published a new rate, and S3 is explicit that the rate is a **snapshot taken at creation**: a later change
-- to the catalog must not restate a historical document. This column is that snapshot — the writer copies the
-- resolved rate onto the row (T3) and the derivation below reads the copy — and `tax_code` sits beside it for
-- the same reason and because a code is what a report filters. `NA` is the default because `V82:45-49` seeds
-- it and every row that exists is, by construction, untaxed.
--
-- WHY THE ARITHMETIC EXTENDS `purchase_order_detail_money()` INSTEAD OF ADDING A THIRD TRIGGER (D5).
-- Same-event triggers fire in **name order**, and `V85:69-71` is the measurement: a separate
-- `trg_purchase_order_details_tax_amount` would sort after `trg_purchase_order_details_money`, so the money
-- trigger would derive `total` from the `tax_amount` the row carried *before* the rate was applied —
-- silently, and on exactly the rows nobody would think to check. The three assignments therefore live in one
-- function in one order: the base, the tax on the base, and the total that is the two of them.
--
-- WHY THE TRIGGER'S COLUMN LIST GROWS, AND WHY IT NAMES `tax_rate` RATHER THAN `tax_amount`. The list is the
-- set of **facts** this derivation reads: `unit_price`, `quantity` and — now — `tax_rate`. A statement that
-- moved only the rate would otherwise leave `tax_amount` and `total` at the numbers the old rate produced,
-- which is the staleness `V90` was written to end for sales. `tax_amount` is the opposite kind of column: a
-- consequence of this function, so naming it would make the derivation an input of itself — `V92`'s header
-- states that for `total` and it applies unchanged one line up.
--
-- WHY NO `SET CONSTRAINTS ALL IMMEDIATE` AND NO ROW LEVEL SECURITY WINDOW. Both are read off this file's
-- statements rather than copied from its predecessors, because a window that does not need to be open is a
-- hole, not a habit (`V82`'s note on the same subject):
--   * `V92` and `V93` had to discharge the deferred judgements because they **update rows**:
--     `trg_purchase_order_details_transition_recorded` and `trg_purchase_orders_transition_recorded`
--     (`V69:84-87`, `:79-82`) are `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`, and PostgreSQL
--     refuses an `ALTER TABLE` while their events are pending (`SQLSTATE 55006`). This file writes no row —
--     `ADD COLUMN`s born full from their defaults, two functions replaced, one trigger recreated and a
--     read-only self-check — so it queues nothing and `V92:33-34`'s case does not arise. The `ALTER TABLE` it
--     does run is its **first** statement, before anything that could queue an event anyway.
--   * `purchase_order_details` carries no `company_id`, so `apply_tenant_isolation` never armed it and there
--     is no policy to open — `V69:31` and `V92`'s header say so in as many words. `purchase_orders` is
--     `ENABLE`+`FORCE` since `V53` and `V93`, and this file neither reads nor writes it, so `V93`'s two
--     windows have no counterpart here. The self-check reads **lines only**, and on an unarmed table a count
--     of zero means what it says; it asserts that premise instead of assuming it.
--
-- WHY THE EXISTING ROWS NEED NO `UPDATE`. `tax_amount` arrives `NOT NULL DEFAULT 0` and every existing row's
-- `total` is, from `V92`, `subtotal + 0`. So a row already satisfies `tax_amount = round(subtotal *
-- tax_rate, 4)` with `tax_rate = 0`, and `total = subtotal + tax_amount` — the derivation that now holds it
-- states the numbers it already carries, exactly as `V90`'s «no backfill» did for sales. A backfill would
-- restate every line of stored money to write the values it already has, which is a `Risk: item 1` event with
-- no question behind it. The self-check at the end proves that claim instead of asserting it, and the
-- migration suite's old-row case is what keeps it true.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No tax resolution and no 400.** S2/S3/S5: the code comes from `products.tax_code` through the variant
--     and the rate is resolved with the document's date by `TaxRateService`; this file only stores what the
--     writer copied. «A taxable code with no enabled rate» is a refusal in the service, not a constraint here
--     — the column cannot tell a catalog with no rate apart from a rate that is legitimately zero.
--   * **No new document column and no second derivation path.** S7: `purchase_orders.tax_total` and
--     `ck_po_order_total` (`total_amount = subtotal + tax_total`) already exist from `V93:115-120`; this file
--     fills them in the one function that derives the document, and adds no trigger for the parent.
--   * **No `ck_po_detail_tax`.** S1 states the three columns, their defaults and their reference, not an
--     equality check, and `V92` left `subtotal`/`total` without one for the same reason: the derivation is
--     the only writer, and a constraint restating it is a second definition of the same number (`V90`'s
--     argument against a third check). `V1:347`'s `total >= 0` stays and keeps holding: the base is
--     non-negative because `unit_price >= 0` (`V1:346`), and the tax term inherits its sign from the rate the
--     writer copies, which `tax_rate.rate` constrains to `0..1` (`V82:56`).
--   * **No change to the document's currency, to `enabled`, to the Draft guard or to any route.** The only
--     observable change is the line's stored tax and a `tax_total` that stops being always 0.
--   * **No new index.** `tax_code` filters nothing yet, and `purchase_order_details` is read by
--     `idx_pod_order` (`V1:356`) alone.

-- ─── 1. the line's tax: the code it declares, the rate it was written with, the amount those two make ───
-- The three arrive `NOT NULL DEFAULT`, so the `ALTER` rewrites no row: PostgreSQL fills an added column with
-- its default for the rows that exist, and validates the foreign key against that same fill — `NA` is seeded
-- by `V82:45-49`, so a catalog without it fails this migration instead of leaving a column nothing can
-- insert into (`V89`'s argument one table over).
--
-- The reference is new for **this** line and deliberately not the shape `sales_order_items.tax_code`
-- (`V81:87`) has: that column was added bare, so a sale line can hold a code the catalog never declared.
-- Referencing the `tax_code` **platform** catalog is safe where referencing a tenant table would not be — a
-- referential check cannot depend on who is inserting, so it bypasses row security by design, and this catalog
-- has no tenant to leak (`V89`) — and it is named, because a violation has to say which rule broke.
-- `NUMERIC(9,6)` is `tax_rate.rate`'s own width (`V82:56`) and `sales_order_items.tax_rate`'s (`V81:88`): a
-- narrower type would round the copy, and every amount derived from it would then disagree with the rate the
-- document was written with.
ALTER TABLE purchase_order_details
    ADD COLUMN tax_code   VARCHAR(20)   NOT NULL DEFAULT 'NA',
    ADD COLUMN tax_rate   NUMERIC(9,6)  NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_purchase_order_details_tax_code
        FOREIGN KEY (tax_code) REFERENCES tax_code (code);

-- ─── 2. the derivation: the base, then the tax on it, then the total the two make ──────────────────────
-- `CREATE OR REPLACE` and not a new name: `trg_purchase_order_details_money` (`V92`) calls this function, and
-- replacing the body keeps the binding while the arithmetic grows a term. The order of the three assignments
-- is the whole section: `tax_amount` reads the `subtotal` assigned one line above it — the read of its own
-- assignment, which is exactly why the three live in one function instead of two triggers (`V92`'s header,
-- D5).
--
-- The expression is `ck_item_tax`'s (`V81:170-171`), `round((subtotal - discount_applied) * tax_rate, 4)`, with
-- the discount at zero. That is what D4 means by reusing `lineTaxAmount` rather than re-expressing it:
-- purchases has no discount column (`V1:341-355`), so `subtotal - discount_applied` **is** `subtotal`, and
-- `tax_rate` is the snapshot copied onto the row (S3), never a join.
CREATE OR REPLACE FUNCTION purchase_order_detail_money() RETURNS trigger AS $$
BEGIN
    NEW.subtotal   := round(NEW.unit_price * NEW.quantity, 4);
    NEW.tax_amount := round(NEW.subtotal * NEW.tax_rate, 4);
    NEW.total      := NEW.subtotal + NEW.tax_amount;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Dropped and recreated, and not re-pointed: a trigger's `UPDATE OF` list cannot be altered in place —
-- `ALTER TRIGGER` renames and toggles, it does not retype — and the list is the half of this file that keeps
-- the derivation from going stale. Both statements are in this one transaction, so no write can land in the
-- gap; an `INSERT` would derive either way, because an `OF` list applies to `UPDATE` alone (`V90` says so).
DROP TRIGGER trg_purchase_order_details_money ON purchase_order_details;

CREATE TRIGGER trg_purchase_order_details_money
    BEFORE INSERT OR UPDATE OF unit_price, quantity, tax_rate ON purchase_order_details
    FOR EACH ROW EXECUTE FUNCTION purchase_order_detail_money();

-- V92's comment on this function described `total = subtotal + 0` and «the tax term arrives with
-- odd/tasks/purchase-order-line-tax.md». It arrives here, so the comment is replaced rather than left
-- standing beside a body that contradicts it (`V90` rewrote `V81`'s `tax_amount` comment for the same reason).
COMMENT ON FUNCTION purchase_order_detail_money() IS
    'S6 of odd/tasks/purchase-order-line-tax.md: sets subtotal = round(unit_price * quantity, 4), then '
    'tax_amount = round(subtotal * tax_rate, 4), then total = subtotal + tax_amount — the base, the tax on it '
    'and the line''s cost — in that order and in one function, because same-event triggers fire in name order '
    'and a separate tax_amount trigger would be read stale by the money trigger that sorts after it. It '
    'listens to unit_price, quantity and tax_rate — the facts, the last of them the rate snapshotted at '
    'creation — and never to a column it derives, so the derivation cannot become its own input.';

-- ─── 3. the document sums the tax it already has a column for ──────────────────────────────────────────
-- The shape is `V93`'s, unchanged: the parent's lock taken **before** the aggregate that depends on it, the
-- `enabled` filter that keeps a soft-deleted line from being charged to the document, and one statement that
-- reads both parts and then writes them. Only the second term is new — `SUM` over no enabled lines is `NULL`,
-- so the coalesce is per aggregate and an order with no lines still costs nothing and taxes nothing.
CREATE OR REPLACE FUNCTION purchase_order_money() RETURNS trigger AS $$
DECLARE
    v_order_id uuid;
    v_subtotal NUMERIC(19, 4);
    v_tax      NUMERIC(19, 4);
BEGIN
    -- One expression for both events: a delete has no NEW, an insert has no OLD.
    v_order_id := COALESCE(NEW.purchase_order_id, OLD.purchase_order_id);

    -- The lock, before the aggregate that depends on it. Under READ COMMITTED two concurrent line writes on
    -- one order would otherwise each sum a snapshot missing the other's line. See V93's header.
    PERFORM 1 FROM purchase_orders WHERE id = v_order_id FOR UPDATE;

    SELECT COALESCE(SUM(d.subtotal), 0),
           COALESCE(SUM(d.tax_amount), 0)
      INTO v_subtotal, v_tax
      FROM purchase_order_details d
     WHERE d.purchase_order_id = v_order_id
       AND d.enabled = true;

    UPDATE purchase_orders
       SET subtotal     = v_subtotal,
           tax_total    = v_tax,
           total_amount = v_subtotal + v_tax
     WHERE id = v_order_id;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- The trigger is **not** touched: `trg_purchase_order_details_document_money` (`V93`) is `AFTER INSERT OR
-- UPDATE OR DELETE` with no column list, so it already fires on every event that can move a line's tax, and
-- `AFTER` means the line's own `BEFORE` derivation above has written `tax_amount` before this reads it —
-- safe by timing, not by name order. A second document trigger would be a second derivation path over the
-- same columns, which is what S7 forbids.
COMMENT ON FUNCTION purchase_order_money() IS
    'S7: recomputes subtotal = sum(details.subtotal), tax_total = sum(details.tax_amount) and total_amount = '
    'subtotal + tax_total on the parent order, under that order''s row lock taken before the aggregate so two '
    'concurrent line writers cannot each compute a total missing the other''s line. Only enabled lines count, '
    'because deletePurchaseOrderDetail is a soft delete. ck_po_order_total states the equality it writes.';

-- ─── 4. the columns, stated in the voice of the ones beside them ──────────────────────────────────────
COMMENT ON COLUMN purchase_order_details.tax_code IS
    'S1 of odd/tasks/purchase-order-line-tax.md: the fiscal code this line was written with, copied from '
    'products.tax_code (V89) at creation and never re-read. `NA` means no tax applies — a code with a meaning, '
    'not a missing value — and is the default, so every line that predates V94 reads as untaxed. '
    'fk_purchase_order_details_tax_code refuses a code the platform catalog does not have; unlike '
    'sales_order_items.tax_code (V81:87) this copy cannot hold a code nobody declared.';

COMMENT ON COLUMN purchase_order_details.tax_rate IS
    'S1/S3: the rate as it stood on the document''s date when the line was created, resolved through '
    'TaxRateService against tax_rate (V82) and copied here rather than joined, so a later rate change cannot '
    'restate a historical purchase. It is an **input** of trg_purchase_order_details_money: an edit of this '
    'column re-derives tax_amount and then total. Changing quantity or unit price keeps it, which is what '
    'makes the rate a snapshot rather than a lookup.';

COMMENT ON COLUMN purchase_order_details.tax_amount IS
    'S1/S6: round(subtotal * tax_rate, 4), derived by trg_purchase_order_details_money from the stored rate '
    'and the stored base — purchases has no discount, so this is ck_item_tax''s expression with the discount '
    'at zero. It is the term purchase_orders.tax_total sums, and the row keeps its value forever: a change to '
    'the catalog appends a rate, it never rewrites this number.';

-- `V92` documented `total` as «subtotal + 0 today, subtotal + tax_amount once
-- odd/tasks/purchase-order-line-tax.md lands». That sentence is now the past of the column, and a comment
-- that describes a past the column no longer has is how the next reader concludes the tax is still missing.
COMMENT ON COLUMN purchase_order_details.total IS
    'S6: subtotal + tax_amount, the line''s base plus the tax it carries, derived by '
    'trg_purchase_order_details_money and by nothing else. It was written by PurchaseOrderService.lineTotal at '
    'two decimals until V92 restated every row at four, and equalled the base until V94 added the tax term.';

-- ─── 5. the self-check: are the promise and the history both what this file claims? ───────────────────
-- Three questions, because there are three ways this file can report success and have installed nothing:
--   * the trigger is the one declared above — BEFORE, FOR EACH ROW, and `UPDATE OF unit_price, quantity,
--     tax_rate`. A trigger whose list still names only the first two would leave `tax_amount` stale on an
--     edit of the rate, which is the defect D5 exists to prevent and which no constraint here would catch.
--   * every **existing** line already satisfies the extended derivation. This is the claim «no history is
--     rewritten» answered instead of asserted: `V92` stored `total = subtotal`, `tax_rate` arrives at 0, so a
--     row that disagrees can only mean a row whose stored money was not the derived one to begin with.
--   * nothing is armed that would make the question above a lie, and no `FORCE` window stayed open.
--     `purchase_order_details` is unarmed today (`V69:31`), which is what makes the scan below read every
--     row; if that ever stops being true inside this migration the count would report «nothing wrong» over
--     zero rows — the defect `V64:30-42` records for `products`. `purchase_orders` is deliberately **not**
--     scanned: it is FORCEd, so an owner-only count there means nothing, and `ck_po_order_total` is the
--     enforced statement that the document's parts add up.
DO $$
DECLARE
    v_enabled    "char";
    v_definition text;
    v_bad        bigint;
    v_armed      boolean;
    v_force_open boolean;
BEGIN
    SELECT t.tgenabled, pg_get_triggerdef(t.oid)
      INTO v_enabled, v_definition
      FROM pg_trigger t
     WHERE t.tgname = 'trg_purchase_order_details_money'
       AND t.tgrelid = 'purchase_order_details'::regclass
       AND NOT t.tgisinternal;

    IF v_definition IS NULL THEN
        RAISE EXCEPTION
            'V94 did not find trg_purchase_order_details_money on purchase_order_details, so the tax columns '
            'it just added are never derived and every line states the defaults it was born with.';
    END IF;

    IF v_enabled <> 'O' THEN
        RAISE EXCEPTION
            'V94 left trg_purchase_order_details_money with tgenabled = %: a disabled trigger is a derivation '
            'that exists and is switched off, which reads exactly like the missing one.', v_enabled;
    END IF;

    IF strpos(v_definition, 'BEFORE INSERT OR UPDATE OF unit_price, quantity, tax_rate ON') = 0 THEN
        RAISE EXCEPTION
            'V94 left a trigger that is not the one it declares: %. The column list has to name tax_rate, or '
            'an edit of the rate leaves tax_amount and total at the numbers the previous rate produced.',
            v_definition;
    END IF;

    SELECT c.relrowsecurity AND c.relforcerowsecurity
      INTO v_armed
      FROM pg_class c
     WHERE c.relname = 'purchase_order_details'
       AND c.relkind = 'r'
       AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema());

    IF v_armed IS NULL THEN
        RAISE EXCEPTION
            'V94 could not read purchase_order_details from the catalog, so it cannot state whether the scan '
            'below sees every row.';
    END IF;

    IF v_armed THEN
        RAISE EXCEPTION
            'V94 found purchase_order_details armed with ENABLE + FORCE ROW LEVEL SECURITY. This file claims '
            'no window is needed because the lines carry no company_id and have no policy; if that changed, '
            'the count below reads zero rows and reports «nothing wrong» over none of them.';
    END IF;

    SELECT count(*) INTO v_bad
      FROM purchase_order_details
     WHERE tax_amount <> round(subtotal * tax_rate, 4)
        OR total <> subtotal + tax_amount;

    IF v_bad > 0 THEN
        RAISE EXCEPTION
            'V94 read % lines whose stored money is not round(subtotal * tax_rate, 4) and subtotal + '
            'tax_amount. This file backfills nothing because every existing rate is 0 and V92 stored total = '
            'subtotal, so a non-zero count means the rows V92 and V93 left behind are not the state this '
            'derivation was written for.', v_bad;
    END IF;

    SELECT NOT (c.relrowsecurity AND c.relforcerowsecurity)
      INTO v_force_open
      FROM pg_class c
     WHERE c.relname = 'purchase_orders'
       AND c.relkind = 'r'
       AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema());

    IF v_force_open IS NULL OR v_force_open THEN
        RAISE EXCEPTION
            'V94 found purchase_orders without ENABLE + FORCE ROW LEVEL SECURITY. This file opens no window '
            'and must leave none behind: an aborted deploy that dropped FORCE lets the owner of every armed '
            'table read across tenants (V64:50-58).';
    END IF;
END $$;
