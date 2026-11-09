-- ============================================
-- V90 — a line's tax_amount and final_price are derived by one function (ADR-0002 decision 3 and 6; S3/S5 of
--        odd/tasks/product-tax-code.md, T3)
-- ============================================
-- `V81` created the line's parts — `subtotal`, `tax_code`, `tax_rate`, `tax_amount` — and derived only the
-- first: `tax_amount` has had **no writer** since, so every row carries the `0` default and `ck_item_tax` holds
-- trivially. `V82` seeded the catalog and `V85` restated `final_price` as the line's total, derived from
-- `tax_amount`. This file is the missing derivation, and it is one function instead of two.
--
-- WHY ONE FUNCTION AND NOT A THIRD TRIGGER, MEASURED AND NOT ASSUMED (`V85:69-71` records the same
-- measurement, and it is the whole shape of this file). PostgreSQL fires same-event triggers in **name
-- order**. The two triggers this table already carries sort like this:
--
--     trg_sales_order_items_final_price   (V85)  — sorts first
--     trg_sales_order_items_subtotal      (V81)
--
-- so a trigger named `trg_sales_order_items_tax_amount` would sort **after** `final_price` and the total would
-- be derived from the amount the row had before the rate was applied — silently, and only on the rows nobody
-- would think to check. The function below therefore sets `tax_amount` and then `final_price`, in that order,
-- and the trigger is named `..._tax_and_total` so the **name** says what it writes: a function called
-- `_final_price` that also writes `tax_amount` is the kind of lie the comments in this tree exist to prevent.
--
-- WHY THE TRIGGER LISTENS TO `tax_rate` AND NOT TO `tax_amount`. `tax_amount` is the **derived** column now,
-- so naming it would make the derivation an input of itself: `NEW.tax_amount` on an edit of the rate would be
-- the value the row carried before, and the total would be computed from it. `V85:69-71` says the same thing
-- about `NEW.subtotal` for the same reason. `tax_rate` is the snapshot the writer states (T4 copies it from
-- `tax_rate` at write time, decision 6), and it is the only one of the two that is a fact instead of a
-- consequence.
--
-- WHY THE EXPRESSIONS ARE THE CONSTRAINTS' OWN, CHARACTER FOR CHARACTER. `ck_item_tax` (`V81:169-170`) states
-- `tax_amount = round((subtotal - discount_applied) * tax_rate, 4)` and `ck_item_total` (`V85:106`) states
-- `final_price = subtotal - discount_applied + tax_amount`. The function writes those two expressions, with
-- `subtotal` expanded to `round(list_price * quantity, 4)` — which is exactly what `trg_sales_order_items_subtotal`
-- writes and what `ck_item_subtotal` enforces — so there is one definition of each number in the schema and
-- two ways of spelling it cannot drift apart. It evaluates the subtotal itself rather than reading
-- `NEW.subtotal` for `V85`'s reason: the trigger sorts after `trg_sales_order_items_subtotal` today, but a
-- derivation that depended on that ordering would break in silence the day a migration renamed either one.
--
-- WHY THE OLD PAIR IS DROPPED RATHER THAN LEFT ALONE. `trg_sales_order_items_final_price` and
-- `sales_order_item_final_price()` write `final_price` from `NEW.tax_amount`, which is the number this file
-- stops trusting. Two BEFORE triggers writing one column is an argument the reader cannot settle from the file
-- that created either, and only insert statements fire both (`OF` lists apply to `UPDATE` alone) — so the
-- ambiguity would show up on inserts only. The new trigger is created **before** the old one is dropped, so
-- there is no instant in this transaction where the column has no writer, and the function is dropped after
-- its trigger, so nothing is orphaned.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT DO, named so it is not discovered in the diff:
--   * **No backfill.** Every existing row carries `tax_rate = 0` and `tax_amount = 0` — no writer has ever
--     stated a rate — so `ck_item_tax` already holds on all of them and re-deriving would write the number
--     they already carry. A row's rate is a snapshot of the moment it was sold (decision 6), and a migration
--     that invented one for a past sale would restate history, which is the one thing decision 6 forbids.
--   * **No new constraint.** `ck_item_tax` and `ck_item_total` are the constraints this derivation answers;
--     adding a third that restates either would be a second definition of the same number.
--   * **No Java change.** The service does not state `tax_rate` until T4 (the copy), and the mapping for the
--     derived columns is already `insertable = false, updatable = false`. This file is the schema's half.
--   * **No change to `subtotal`, `tax_rate` or `tax_code`** — the three columns nobody in this slice writes.

-- ─── 1. the one derivation: tax_amount first, then the total that is made of it ───────────────────
CREATE FUNCTION sales_order_item_tax_and_total() RETURNS trigger AS $$
BEGIN
    NEW.tax_amount := round((round(NEW.list_price * NEW.quantity, 4) - NEW.discount_applied) * NEW.tax_rate, 4);
    NEW.final_price := round(NEW.list_price * NEW.quantity, 4) - NEW.discount_applied + NEW.tax_amount;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sales_order_items_tax_and_total
    BEFORE INSERT OR UPDATE OF list_price, quantity, discount_applied, tax_rate ON sales_order_items
    FOR EACH ROW EXECUTE FUNCTION sales_order_item_tax_and_total();

COMMENT ON FUNCTION sales_order_item_tax_and_total() IS
    'ADR-0002 decision 3 and 6: sets tax_amount = round((round(list_price * quantity, 4) - '
    'discount_applied) * tax_rate, 4) and then final_price = round(list_price * quantity, 4) - '
    'discount_applied + tax_amount, the two expressions ck_item_tax and ck_item_total state. The two live in '
    'one function because same-event triggers fire in name order and a separate tax_amount trigger would sort '
    'after trg_sales_order_items_final_price and be read stale; the trigger names tax_rate rather than the '
    'derived tax_amount for the same reason.';

-- ─── 2. V85's pair, dropped now that one function writes both columns ────────────────────────────
-- The trigger first and the function second, in that order: a function its trigger still calls cannot be
-- dropped, and dropping them in the other order would be a file that only works once.
DROP TRIGGER trg_sales_order_items_final_price ON sales_order_items;

DROP FUNCTION sales_order_item_final_price();

-- ─── 3. the column comment, which told the truth about a column with no writer ────────────────────
-- `V81` documented `tax_amount` as «0 until a rate row exists (T4)», which was the honest state of a column no
-- statement wrote. It has a writer now, and the comment below states which one — a column whose comment
-- describes a past it no longer has is how the next reader concludes the column is dead.
COMMENT ON COLUMN sales_order_items.tax_amount IS
    'ADR-0002 decision 3: round((subtotal - discount_applied) * tax_rate, 4), derived by '
    'trg_sales_order_items_tax_and_total from the rate copied onto the line at write time (decision 6), so a '
    'later rate change cannot restate a past sale. ck_item_tax states the meaning; a value written by hand '
    'that disagrees with it is still refused, because the derivation does not name tax_amount as an input.';

COMMENT ON COLUMN sales_order_items.tax_rate IS
    'ADR-0002 decision 6: the rate as it stood when the line was written, copied from tax_rate by the writer '
    'and never restated. It is the input of trg_sales_order_items_tax_and_total: an edit of this column '
    're-derives both tax_amount and final_price.';

-- ─── 4. the self-check: the constraints still hold, on the catalog and on every row ───────────────
-- **This check is legitimate here and would not be on `products`.** `sales_order_items` carries no `company_id`
-- and no policy (`V81:40-44` says so in as many words), so this file reads it as the role delivery migrates
-- with and every row is in the count. `products` is armed `ENABLE` + `FORCE` (`V53:122-123`), so the owner
-- reads zero rows there and a count of zero is indistinguishable from "nothing wrong" — the defect `V64:30-42`
-- records and the reason `V89`'s section 2 checks the column and never a product row.
--
-- The two halves are one answer and neither is enough alone. The catalog half asserts the two constraints the
-- derivation answers are still there and still validated after the swap: a `ck_item_*` added `NOT VALID` or
-- dropped out of band would leave the row count below meaningless. The count half asserts every stored row
-- satisfies both expressions — the honest question «did the old pair leave a row the new derivation disagrees
-- with», which is worth asking even though the constraints make a non-zero answer unreachable from a write.
DO $$
DECLARE
    v_unvalidated text;
    v_bad_tax     bigint;
    v_bad_total   bigint;
BEGIN
    SELECT string_agg(conname, ', ' ORDER BY conname)
      INTO v_unvalidated
      FROM pg_constraint
     WHERE conrelid = 'sales_order_items'::regclass
       AND conname IN ('ck_item_tax', 'ck_item_total')
       AND NOT convalidated;

    IF v_unvalidated IS NOT NULL THEN
        RAISE EXCEPTION
            'V90 found % unvalidated on sales_order_items. The derivation below answers those two checks, and '
            'one that does not judge the rows that exist is a check nothing proves: validate it before this '
            'file adds a writer it cannot hold to.', v_unvalidated;
    END IF;

    SELECT count(*) INTO v_bad_tax
      FROM sales_order_items
     WHERE tax_amount <> round((subtotal - discount_applied) * tax_rate, 4);

    IF v_bad_tax > 0 THEN
        RAISE EXCEPTION
            'V90 read % lines whose tax_amount is not round((subtotal - discount_applied) * tax_rate, 4), the '
            'state ck_item_tax exists to refuse. This file backfills nothing — every existing rate is 0 — so a '
            'non-zero count means the check is not the one this tree thinks it installed.', v_bad_tax;
    END IF;

    SELECT count(*) INTO v_bad_total
      FROM sales_order_items
     WHERE final_price <> subtotal - discount_applied + tax_amount;

    IF v_bad_total > 0 THEN
        RAISE EXCEPTION
            'V90 read % lines whose final_price is not subtotal - discount_applied + tax_amount, the state '
            'ck_item_total exists to refuse (V85 restated every row from its own parts). A non-zero count '
            'means a row V85 never reached or a check that is not enforced.', v_bad_total;
    END IF;
END $$;
