-- ============================================
-- V100 — the three rules that post: revenue from the completion, collection from the payment, cost from the
--        allocation sum, and the analytic axis they carry (S11–S15; T6, T7, T8 of
--        odd/tasks/derived-accounting.md)
-- ============================================
-- `V97` created the book and the rules that say who balances against whom, `V98` seeded the chart and the
-- three anchors, and `V99` turned a rule into N lines because a document posts three parts and a pair of
-- accounts cannot say the tax. Nothing fired. This file is the half that fires: one shared PL/pgSQL function
-- that resolves a rule's lines for `(company, source_type, posting)` and writes one `account_entry` plus its
-- lines, called by three ordinary triggers on the three append-only facts ADR-0008 decision 3 names.
--
-- THE SHAPE, AND WHY IT IS ONE FUNCTION AND THREE TRIGGERS.
--   * `AFTER INSERT ON status_transition WHEN (NEW.document_type = 'SALES_ORDER' AND NEW.to_status =
--     'COMPLETED')` → `REVENUE` with source `('STATUS_TRANSITION', NEW.id)`. S11 anchors revenue on the
--     transition and not on the order: the transition is append-only and the document is editable until
--     terminal, so the document cannot be the source of an entry that cannot be amended.
--   * `AFTER INSERT ON sale_payment` → `COLLECTION` with source `('SALE_PAYMENT', NEW.id)`. S12 anchors a
--     collection on the payment row, and a `REFUND` row is the mirror of a charge.
--   * `AFTER INSERT ON stock_allocation`, **`DEFERRABLE INITIALLY DEFERRED`** → `COST_OF_SALES` with source
--     `('STOCK_ALLOCATION', NEW.out_movement_id)` and the amount `Σ quantity × unit_cost` over **every**
--     allocation of that movement as the transaction commits. The deferral is what buys the sum: allocations
--     are written row by row inside the sale's own transaction, `V61`'s writer returns one row per consumed
--     inbound row, and an immediate trigger would post the first row's cost and then have to correct itself.
--     It is the shape `V60`/`V97` already chose for the balance and the minimum-line rules, for the same
--     reason: the state judged is the state committed.
--
-- WHY A MISSING RULE IS A REFUSAL AND NEVER A SILENT NO-OP (L11(b), S10).
-- This is the failure the whole slice exists to refuse. `posting_rule` and `posting_rule_line` are
-- `ENABLE`+`FORCE`d, so a poster with no `app.company_id` scope reads **zero lines** — and a poster that read
-- "no lines" as "nothing to do" would write nothing and report success while the books came out short. That
-- error is invisible at read time: a tenant with no revenue posting and a quiet period print the same report.
-- So the function raises, naming the company, the source type and the posting, and the sale, the payment or
-- the allocation that triggered it is refused. The alternative — inventing the rule inside the trigger, or
-- writing a rule-less entry — would put configuration in the book's own writer and give the same fact two
-- sources of truth.
--
-- WHY THE AMOUNTS COME FROM THE SELECTOR AND THE ENTRY CANNOT BE UNBALANCED.
-- `V99` closed the selector vocabulary to five values, and each names one amount of one fact:
--   `TOTAL`          = `sales_orders.total_amount`
--   `REVENUE`        = `total_amount − tax_total`   (`L13(b)`: the rounding is folded in here)
--   `TAX`            = `sales_orders.tax_total`
--   `SOURCE_AMOUNT`  = `sale_payment.amount`
--   `ALLOCATED_COST` = `Σ quantity × unit_cost` over the movement's allocations
-- The revenue rule is `(DEBIT TOTAL, 1201)`, `(CREDIT REVENUE, 4101)`, `(CREDIT TAX, 2101)`, so
-- `ck_order_total` guarantees `TOTAL = REVENUE + TAX` and the entry balances by construction rather than by a
-- rounding line — the fourth line `L13(b)` refused, which would go negative on a discount-heavy document and
-- violate `ck_account_entry_line_non_negative`.
--
-- WHY A REFUND SWAPS THE SIDES AND NEVER THE SIGN.
-- `ck_account_entry_line_non_negative`, `ck_account_entry_line_single_side` and `ck_account_entry_line_positive`
-- state that every amount on the book is money: one side is zero, the other is greater than zero. A refund is
-- therefore the mirror of the charge — `(DEBIT 1201, CREDIT 1101)` — and the flip happens on the side, inside
-- this function, where the rule's own `side` is read. A signed selector would move the flip into the poster's
-- arithmetic and out of reach of every `CHECK`.
--
-- WHY THE DATES AND THE CURRENCY COME FROM THE FACT AND NOT FROM `now()`.
-- Revenue dates on `sales_orders.order_date::date` — the **document's** date, per S11 — and not on
-- `occurred_at`, which moves a late completion into another month and another `accounting_period`. Collection
-- dates on `sale_payment.paid_at::date`, the day the money arrived. Cost dates on
-- `stock_movement.occurred_at::date`, the day the goods left. The currency is `sales_orders.currency` for the
-- first two — `sale_payment` carries no currency column of its own, which is `V83`'s shape, so it is read from
-- the order the payment names — and for the cost it is `coalesce(stock_movement.currency, companies.currency)`.
--
-- THAT `coalesce` IS A MEASURED CORRECTION AND NOT A CONVENIENCE.
-- `stock_movement.currency` is nullable and `ck_movement_inbound_cost` requires it only on a row that adds
-- quantity. The only writer of a sale row in this tree, `StockLedgerWriter.bookSale`, passes **NULL** for both
-- `unit_cost` and `currency` (`StockLedgerWriter.java:280-282`, `:414-431`), because a sale row's cost lives in
-- `stock_allocation` and its money is the company's. Denominating the cost from `stock_movement.currency` alone
-- would therefore raise a NOT NULL violation on every real sale, and the whole rule would be dead on arrival.
-- The company's operating currency is the same value `V61:292` uses to denominate `stock_valuation` and the one
-- ADR-0002 decision 2 makes the only currency a document can be in — so this is a derivation, not a default.
--
-- WHY THE IDEMPOTENCY IS ASYMMETRIC, AND WHY THE COST'S IS THE INTERESTING HALF (S6).
-- `ON CONFLICT (company_id, source_type, source_id, posting) DO NOTHING` for revenue and collection: a replay
-- of the same fact is the same fact, and the rows behind both are append-only, so their amounts cannot move. A
-- cost is different, because its amount is a **sum that can still grow**: the trigger fires once per allocation
-- row, so two allocations in one transaction fire it twice, and both firings must agree. A conflict on the
-- cost path therefore recomputes the sum and compares it with the entry already stored: equal is a no-op, and
-- different is a refusal that names both amounts. A silently short cost is precisely the failure S13 exists to
-- prevent, and it is the one this book could never detect afterwards — a trial balance balances whatever the
-- cost says.
--
-- WHY THE ANALYTIC AXIS IS PRE-FILLED AND ALMOST ALWAYS NULL (S14).
-- `cost_center_id` is filled on every line of the entry **only** when the fact's store resolves to exactly one
-- enabled `cost_center`: zero centers leave it null, and several leave it null. That is not laziness, it is the
-- spec: `cost_center.department_id` is `NOT NULL` and the department is *the one value on this book a person
-- supplies* — `V98` seeds no department and no center, so in practice every line of every posting is null
-- today. A guess would be worse than the absence: picking one of two centers splits a report with nothing
-- raising, which is the defect ADR-0008 decision 10 refuses one layer up. When a person creates the store's
-- center, every posting of that store starts carrying it with no code change here.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No period close and no refusal of a closed period (S16).** Nothing writes `accounting_period`: closing
--     a month and refusing a posting into one is T11, and the self-check below asserts this file's code names
--     no such column at all.
--   * **No reversal (S17).** `reverses_entry_id` stays null. A voided payment posts nothing that undoes it —
--     `sale_payment_void` has no writer in this tree today (`SalePaymentRepository` reads it, nothing writes
--     it) and the reversal path is T12, so S12's "net of its void" is only half true here: a payment posts its
--     collection when it is taken, and a void written later leaves that entry standing. Recorded as a gap
--     rather than guessed at.
--   * **No read model (S18).** The journal, the trial balance and the gross margin are T9 and T10; this file
--     only makes the entries exist.
--   * **No split of the collection by payment method.** The seeded `COLLECTION` rule debits `1101 Caja`, which
--     `L10(a)` recorded as a stated limitation: a card or transfer payment posts to cash until a later slice
--     teaches the rule to split.
--   * **No `NO FORCE` window and no row written.** This file creates code and triggers and reads no table, so
--     there is no migration-time read for the policy to filter — the only rows any of this touches are the ones
--     a later transaction's facts produce.
-- ============================================

-- ─── 1. the shared poster: one rule, its lines, one entry ─────────────────────────────────────────
-- One function for all three anchors, because the difference between them is a call and not a body: the
-- trigger gathers the fact, this function states the rule. Three copies of the resolution below would be three
-- places for the refusal of L11(b) to be softened, and two of them would be the copies nobody read again.
--
-- `SECURITY INVOKER` (the default, stated so a later edit cannot make it explicit without deleting a line):
-- every row it writes is written as the caller, under the caller's tenant scope, and therefore under ADR-0005's
-- policies — the same rule `V61:109-120` states for the ledger and refuses by name for a definer function.
-- `SECURITY DEFINER` here would let a posting read another tenant's rule lines and write an entry no policy
-- could see, which is the one thing this book must not be able to do.
CREATE FUNCTION accounting_post_entry(
        p_company_id       uuid,
        p_source_type      varchar,
        p_source_id        uuid,
        p_posting          varchar,
        p_entry_date       date,
        p_currency         char(3),
        p_company_store_id uuid,
        p_actor            varchar,
        p_amounts          jsonb,
        p_flip_sides       boolean DEFAULT false)
RETURNS uuid
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    -- `v_*` and not `l_*`: PL/pgSQL resolves an identifier that matches both a parameter and a column of the
    -- query to the COLUMN, so a variable named like one of the three rule columns would silently read itself.
    v_accounts  uuid[]    := '{}';
    v_sides     text[]    := '{}';
    v_amounts   numeric[] := '{}';
    v_rule_lines integer  := 0;
    v_amount    numeric;
    -- The entry's own total: the sum of what lands on the DEBIT side after the refund flip, which is what
    -- `account_entry_line.debit` sums to and therefore the only number the stored entry and a recomputation can
    -- be compared by. Summing **every** line instead would count the credit side too — for the cost rule, whose
    -- two lines move the same selector, that reads 108 for a cost of 54.
    v_total     numeric   := 0;
    v_side      text;
    v_centers   integer   := 0;
    v_center    uuid;
    v_entry     uuid;
    v_existing  uuid;
    v_stored    numeric;
    v_line      record;
    v_index     integer;
BEGIN
    -- A fact the book cannot date or denominate is refused here rather than by the column's NOT NULL, because
    -- the operator needs to know which fact it was. `p_actor` is in the same group: `account_entry.actor` is
    -- the column a drawer report cites, and an entry with no author is not a fact anybody can stand behind.
    IF p_company_id IS NULL OR p_source_type IS NULL OR p_source_id IS NULL OR p_posting IS NULL THEN
        RAISE EXCEPTION
            'accounting posting: the anchor is incomplete (company %, source type %, source id %, posting %). An entry is derived from one fact of one posting, and a posting that cannot name its source cannot be replayed or reversed (S6).',
            p_company_id, p_source_type, p_source_id, p_posting;
    END IF;

    IF p_entry_date IS NULL OR p_currency IS NULL OR p_actor IS NULL THEN
        RAISE EXCEPTION
            'accounting posting % of % for company %: the fact states no business date, no currency or no actor (date %, currency %, actor %). An entry takes the document''s own date and currency — never `now()`, which would report a late posting in the month it ran — so the fact is refused instead of being dated by the clock.',
            p_posting, p_source_type, p_company_id, p_entry_date, p_currency, p_actor;
    END IF;

    -- ── the rule's lines, read under the caller's scope ───────────────────────────────────────────
    -- `r.enabled` is part of the resolution and not a filter on the read: a disabled rule is a rule the tenant
    -- retired, and a retired rule that posts nothing in silence is the exact failure L11(b) records. It is
    -- therefore the same refusal as a missing one, and the message says both.
    FOR v_line IN
        SELECT l.account_id, l.side, l.amount_selector
          FROM posting_rule r
          JOIN posting_rule_line l
            ON l.company_id = r.company_id
           AND l.rule_id = r.id
         WHERE r.company_id = p_company_id
           AND r.source_type = p_source_type
           AND r.posting = p_posting
           AND r.enabled
         ORDER BY l.side, l.amount_selector
    LOOP
        v_rule_lines := v_rule_lines + 1;

        -- A selector the rule states and the fact cannot compute is a rule the poster cannot execute: raising
        -- here is what keeps `V99`'s closed vocabulary from becoming an amount nobody notices is missing.
        IF NOT (p_amounts ? v_line.amount_selector) THEN
            RAISE EXCEPTION
                'accounting posting % of % for company %: the rule''s line names amount selector % and this fact states no such amount. The selector vocabulary of V99 exists so a rule cannot name an amount its anchor cannot compute, and a line posted with a wrong or absent amount is a book that balances and lies.',
                p_posting, p_source_type, p_company_id, v_line.amount_selector;
        END IF;

        v_amount := (p_amounts ->> v_line.amount_selector)::numeric;

        IF v_amount IS NULL THEN
            RAISE EXCEPTION
                'accounting posting % of % for company %: selector % resolved to NULL. A missing amount is not zero — zero is a stated value and NULL is an unstated one — and the book records only what the fact says.',
                p_posting, p_source_type, p_company_id, v_line.amount_selector;
        END IF;

        IF v_amount < 0 THEN
            RAISE EXCEPTION
                'accounting posting % of % for company %: selector % measures %. A negative part would be refused by ck_account_entry_line_non_negative anyway, and the refusal names the selector so the operator reads which part of the document went below zero instead of which side of a row.',
                p_posting, p_source_type, p_company_id, v_line.amount_selector, v_amount;
        END IF;

        -- A part of zero is not a missing part: a tax-exempt document states `TAX = 0` and means it. The line
        -- is omitted because `ck_account_entry_line_positive` refuses a zero line, and a line whose amount is
        -- zero moves nothing — `TOTAL = REVENUE + TAX` still holds when the tax is zero. What the rule states
        -- is still asserted above: a rule with **no** line is a refusal, and it is a different fact from a
        -- rule whose line is worth nothing.
        CONTINUE WHEN v_amount = 0;

        -- The side after the flip is what the row states, so it is resolved once here and not twice at insert
        -- time: a refund is the mirror of the charge and the flip is on the side, never on the sign (a negative
        -- amount is refused by ck_account_entry_line_non_negative).
        v_side := CASE WHEN (v_line.side = 'DEBIT') <> p_flip_sides THEN 'DEBIT' ELSE 'CREDIT' END;

        v_accounts := v_accounts || v_line.account_id;
        v_sides := v_sides || v_side;
        v_amounts := v_amounts || v_amount;

        IF v_side = 'DEBIT' THEN
            v_total := v_total + v_amount;
        END IF;
    END LOOP;

    -- L11(b), stated once for the whole book: the policy makes an absent line set look exactly like a tenant
    -- that has nothing to post, so this is the only place the two can be told apart.
    IF v_rule_lines = 0 THEN
        RAISE EXCEPTION
            'accounting posting % of % for company % posts nothing: no enabled posting rule names it, or the rule it names has no line for any side. This is a refusal and not a no-op, because `posting_rule` and `posting_rule_line` are armed and FORCEd — with no tenant scope, or with the rule deleted or disabled, a poster reads zero lines and writes nothing, and the book comes out short while every report still prints. S10 of odd/tasks/derived-accounting.md makes a tenant with no book refuse the deploy; this makes a fact with no rule refuse the fact.',
            p_posting, p_source_type, p_company_id;
    END IF;

    -- Every part the fact states is zero: nothing moved, so no entry is written. A zero entry is not a smaller
    -- fact, it is a row that says nothing and that `ck_account_entry_line_positive` refuses anyway — and the
    -- rule's presence was already asserted above, so this cannot hide a missing rule.
    IF coalesce(array_length(v_accounts, 1), 0) = 0 THEN
        RETURN NULL;
    END IF;

    -- ── S14: the analytic axis, only when the store's center is unambiguous ───────────────────────
    -- The store comes from the fact and the department is the value a person supplies, so this resolves the
    -- one row that already exists. `enabled` is part of the question: a retired center is not a guess the book
    -- should still make.
    IF p_company_store_id IS NOT NULL THEN
        SELECT count(*) INTO v_centers
          FROM cost_center c
         WHERE c.company_id = p_company_id
           AND c.company_store_id = p_company_store_id
           AND c.enabled;

        IF v_centers = 1 THEN
            SELECT c.id INTO v_center
              FROM cost_center c
             WHERE c.company_id = p_company_id
               AND c.company_store_id = p_company_store_id
               AND c.enabled;
        END IF;
    END IF;

    -- ── the entry: one per fact, and a replay is the same fact ────────────────────────────────────
    INSERT INTO account_entry (company_id, entry_date, source_type, source_id, posting, actor)
    VALUES (p_company_id, p_entry_date, p_source_type, p_source_id, p_posting, p_actor)
    ON CONFLICT (company_id, source_type, source_id, posting) DO NOTHING
    RETURNING id INTO v_entry;

    IF v_entry IS NULL THEN
        SELECT e.id INTO v_existing
          FROM account_entry e
         WHERE e.company_id = p_company_id
           AND e.source_type = p_source_type
           AND e.source_id = p_source_id
           AND e.posting = p_posting;

        -- The asymmetry, and the reason it exists: a document's money and a payment's amount are frozen at
        -- their own row (both are append-only), so a second attempt cannot disagree. An allocation sum can
        -- still grow — that is what the deferral is for — so a disagreement here is a real one.
        IF p_posting = 'COST_OF_SALES' THEN
            SELECT coalesce(sum(l.debit), 0) INTO v_stored
              FROM account_entry_line l
             WHERE l.company_id = p_company_id
               AND l.entry_id = v_existing;

            IF v_stored <> v_total THEN
                RAISE EXCEPTION
                    'account entry % already records a cost of % for stock allocation movement % of company %, and the allocations of that movement now sum to %. A cost that arrives twice with one amount is the same fact and posts nothing further; a cost that arrives again with another amount is refused here, because a cost silently left at the smaller number is a book that balances and reports a margin nobody earned (S13 of odd/tasks/derived-accounting.md). The correction is the reversal path of S17, never a second entry under the same key.',
                    v_existing, v_stored, p_source_id, p_company_id, v_total;
            END IF;
        END IF;

        RETURN v_existing;
    END IF;

    -- ── the lines: one per part, the side the rule states, flipped for a refund ───────────────────
    FOR v_index IN 1 .. array_length(v_accounts, 1) LOOP
        INSERT INTO account_entry_line
            (company_id, entry_id, account_id, cost_center_id, debit, credit, currency)
        VALUES (p_company_id,
                v_entry,
                v_accounts[v_index],
                v_center,
                CASE WHEN v_sides[v_index] = 'DEBIT' THEN v_amounts[v_index] ELSE 0 END,
                CASE WHEN v_sides[v_index] = 'DEBIT' THEN 0 ELSE v_amounts[v_index] END,
                p_currency);
    END LOOP;

    RETURN v_entry;
END;
$$;

COMMENT ON FUNCTION accounting_post_entry(uuid, varchar, uuid, varchar, date, char, uuid, varchar, jsonb, boolean)
IS
    'S11–S15 of odd/tasks/derived-accounting.md: resolves the posting rule of (company, source_type, posting) '
    'into its lines (V99), computes each line''s amount from its closed selector, and writes one account_entry '
    'with its lines. A missing or disabled rule is a refusal that names the anchor, never a silent no-op '
    '(L11(b)); a repeated fact is a no-op; a repeated cost whose sum moved is a refusal that names both amounts. '
    'SECURITY INVOKER on purpose, so every row it writes meets ADR-0005''s policies as the caller.';

-- ─── 2. the fact reader: what each anchor states, and nothing about how it posts ──────────────────
-- One trigger function with a branch per table rather than three near-identical ones: the three anchors differ
-- in how the fact is read and in which selector values it states, and in nothing else. The `ELSE` branch
-- refuses instead of assuming the cost, because a function attached to a fourth table would otherwise post a
-- cost for a fact nobody described.
CREATE FUNCTION accounting_post_fact() RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_total    numeric(19,4);
    v_tax      numeric(19,4);
    v_currency char(3);
    v_date     date;
    v_store    uuid;
    v_actor    varchar;
    v_cost     numeric(19,4);
BEGIN
    IF TG_TABLE_NAME = 'status_transition' THEN
        -- The document's own money, read from the document because the transition carries none: the anchor is
        -- append-only, but the amounts it states are the document's, frozen by `ck_order_total` at whatever the
        -- completion was worth.
        SELECT o.total_amount, o.tax_total, o.currency, o.order_date::date, o.company_store_id
          INTO v_total, v_tax, v_currency, v_date, v_store
          FROM sales_orders o
         WHERE o.id = NEW.document_id
           AND o.company_id = NEW.company_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'accounting: status transition % of company % names sales order % and the book cannot read it. A posting reads its fact under the caller''s tenant scope, so an order it cannot see is a sale whose revenue would be silently unrecorded — refused instead (ADR-0004 decision 6, S11).',
                NEW.id, NEW.company_id, NEW.document_id;
        END IF;

        PERFORM accounting_post_entry(
            NEW.company_id,
            'STATUS_TRANSITION',
            NEW.id,
            'REVENUE',
            v_date,
            v_currency,
            v_store,
            NEW.actor,
            jsonb_build_object('TOTAL', v_total, 'REVENUE', v_total - v_tax, 'TAX', v_tax),
            false);

    ELSIF TG_TABLE_NAME = 'sale_payment' THEN
        -- `sale_payment` states no currency and no store (`V83`), so both come from the order the payment names.
        -- The deferral/void question is deliberately absent: nothing in this tree writes `sale_payment_void`
        -- yet, and a void's correction is the reversal path of S17 (T12).
        SELECT o.currency, o.company_store_id
          INTO v_currency, v_store
          FROM sales_orders o
         WHERE o.id = NEW.sales_order_id
           AND o.company_id = NEW.company_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'accounting: payment % of company % names sales order % and the book cannot read it, so the collection it records would be denominated in a currency nobody stated. Refused instead (S12).',
                NEW.id, NEW.company_id, NEW.sales_order_id;
        END IF;

        PERFORM accounting_post_entry(
            NEW.company_id,
            'SALE_PAYMENT',
            NEW.id,
            'COLLECTION',
            NEW.paid_at::date,
            v_currency,
            v_store,
            NEW.actor,
            jsonb_build_object('SOURCE_AMOUNT', NEW.amount),
            NEW.direction = 'REFUND');

    ELSIF TG_TABLE_NAME = 'stock_allocation' THEN
        -- The movement the allocation belongs to, which is the entry's source and its date. `companies` is
        -- joined here instead of read in a second statement so that "the movement and its company are both
        -- visible" is one question, and its currency is the fallback the header explains.
        SELECT m.occurred_at::date,
               coalesce(m.currency, c.currency),
               m.company_store_id,
               coalesce(m.actor, 'unknown:ledger')
          INTO v_date, v_currency, v_store, v_actor
          FROM stock_movement m
          JOIN companies c ON c.id = m.company_id
         WHERE m.id = NEW.out_movement_id
           AND m.company_id = NEW.company_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'accounting: stock allocation % of company % names outbound movement % and the book cannot read it, so the cost it consumed would post with no date and no currency. Refused instead (S13).',
                NEW.id, NEW.company_id, NEW.out_movement_id;
        END IF;

        -- The sum over every allocation of the movement **as committed**, rounded once at the document boundary
        -- the way ADR-0001 says cost is rounded. `stock_allocation.quantity` and `unit_cost` are both
        -- `NUMERIC(19,4)` and their product carries eight decimals, while the line column carries four: without
        -- this rounding a second firing would compare 8 decimals against the 4 the book stored and refuse an
        -- amount that is the same money.
        SELECT round(coalesce(sum(a.quantity * a.unit_cost), 0), 4)
          INTO v_cost
          FROM stock_allocation a
         WHERE a.company_id = NEW.company_id
           AND a.out_movement_id = NEW.out_movement_id;

        PERFORM accounting_post_entry(
            NEW.company_id,
            'STOCK_ALLOCATION',
            NEW.out_movement_id,
            'COST_OF_SALES',
            v_date,
            v_currency,
            v_store,
            v_actor,
            jsonb_build_object('ALLOCATED_COST', v_cost),
            false);

    ELSE
        RAISE EXCEPTION
            'accounting_post_fact was attached to % and has no branch for it. The book posts from the three anchors ADR-0008 decision 3 names — status_transition, sale_payment and stock_allocation — and a fourth table would need its own fact, its own selector values and its own rule before anything here could mean it.',
            TG_TABLE_NAME;
    END IF;

    RETURN NULL;
END;
$$;

COMMENT ON FUNCTION accounting_post_fact() IS
    'The trigger half of S11–S13: reads one anchor''s fact (a completion transition, a payment row, an '
    'outbound movement and the sum of its allocations) and hands the anchor, its date, its currency, its actor '
    'and its selector amounts to accounting_post_entry. Reads as the caller, so the fact is only posted from '
    'when its own tenant scope can see it.';

-- ─── 3. the three triggers, and why the third one is deferred ─────────────────────────────────────
-- Ordinary row triggers for the two immediate facts: the transition and the payment row each state everything
-- the entry needs at the instant they exist, and there is nothing later in the transaction that could change
-- them.
CREATE TRIGGER trg_status_transition_post_revenue
    AFTER INSERT ON status_transition
    FOR EACH ROW
    WHEN (NEW.document_type = 'SALES_ORDER' AND NEW.to_status = 'COMPLETED')
    EXECUTE FUNCTION accounting_post_fact();

-- No `WHEN` clause on the payment: `direction` decides which side each line takes, not whether anything posts,
-- and a refund that posted nothing would be a collection the book never reverses.
CREATE TRIGGER trg_sale_payment_post_collection
    AFTER INSERT ON sale_payment
    FOR EACH ROW
    EXECUTE FUNCTION accounting_post_fact();

-- `DEFERRABLE INITIALLY DEFERRED`, and a constraint trigger because that is the only kind PostgreSQL lets be
-- deferred. It is the whole of S13's "as committed": the writer inserts one allocation per consumed inbound
-- row, so an immediate trigger would post the first row's cost and then be refused by its own second firing.
-- Deferred, every firing reads the same committed set and the second one is a no-op.
CREATE CONSTRAINT TRIGGER trg_stock_allocation_post_cost
    AFTER INSERT ON stock_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting_post_fact();

COMMENT ON TRIGGER trg_status_transition_post_revenue ON status_transition IS
    'S11: a sale that reaches COMPLETED posts its revenue, IVA trasladado and receivable. Anchored on the '
    'transition and not on the order, because the transition is append-only and the document is editable.';
COMMENT ON TRIGGER trg_sale_payment_post_collection ON sale_payment IS
    'S12: a payment row posts its collection, and a REFUND posts the mirror by swapping the rule''s sides.';
COMMENT ON TRIGGER trg_stock_allocation_post_cost ON stock_allocation IS
    'S13: the outbound movement posts its cost as the sum of every allocation it has at commit. Deferred, so '
    'the sum is the committed one and a movement with several allocations posts once.';

-- ─── 4. the self-check: the shape the next slice and the reports depend on ────────────────────────
-- Catalog reads only, and this file writes no row of any table, so there is no `NO FORCE` window here and none
-- is needed. Five refusals, because there are five ways this file can report success and have installed
-- something other than what its header says: a function missing, a function that runs as its owner, a role
-- that cannot execute it, a trigger missing, disabled or of the wrong timing/deferral, and the period being
-- written by a slice that has not been decided yet.
DO $$
DECLARE
    v_functions text[] := ARRAY['accounting_post_entry', 'accounting_post_fact'];
    v_missing   text;
    v_definer   text;
    v_denied    text;
    v_shape     text;
    v_period    text;
BEGIN
    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_missing
      FROM unnest(v_functions) AS t(name)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_proc p
             JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE p.proname = t.name
              AND n.nspname = current_schema());

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V100 did not create these functions in schema %: %. The triggers below call them by name, and a trigger whose function is missing would have refused this file at creation — but a rename is exactly what would leave the two anchors posting and the third silent.',
            current_schema(), v_missing;
    END IF;

    -- The one thing this book must not be able to do: read and write across tenants. `SECURITY DEFINER` is how
    -- that would happen, and `V61:109-120` refuses it for the ledger by name.
    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_definer
      FROM unnest(v_functions) AS t(name)
      JOIN pg_proc p ON p.proname = t.name
      JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = current_schema()
     WHERE p.prosecdef;

    IF v_definer IS NOT NULL THEN
        RAISE EXCEPTION
            'V100 left these accounting functions SECURITY DEFINER: %. A poster runs as the role that wrote the fact so that ADR-0005''s policies apply to the rule it reads and the entry it writes; a definer function would read another tenant''s lines and write an entry no policy can see, which is the failure this whole book is armed against.',
            v_definer;
    END IF;

    SELECT string_agg(t.name, ', ' ORDER BY t.name)
      INTO v_denied
      FROM unnest(v_functions) AS t(name)
     WHERE NOT has_function_privilege('lifecontrol_app', t.name::regproc, 'EXECUTE');

    IF v_denied IS NOT NULL THEN
        RAISE EXCEPTION
            'lifecontrol_app cannot execute these accounting functions: %. PostgreSQL grants EXECUTE to PUBLIC by default, so this means something revoked it — and then every sale, payment and allocation would fail its own trigger with a permission error instead of posting.',
            v_denied;
    END IF;

    -- Each trigger, by table, timing and function, plus the deferral that is the whole of S13. Read from
    -- `pg_trigger` and not from the file: a trigger recreated elsewhere with the same name and another timing
    -- is exactly the edit this clause exists to catch. The alias is `is_deferrable` and not the column's own
    -- name, because `DEFERRABLE` is a reserved keyword and cannot be an unquoted column alias.
    WITH expected (name, table_name, is_deferrable, has_when) AS (
        VALUES ('trg_status_transition_post_revenue', 'status_transition', false, true),
               ('trg_sale_payment_post_collection', 'sale_payment', false, false),
               ('trg_stock_allocation_post_cost', 'stock_allocation', true, false)
    )
    SELECT string_agg(e.name, ', ' ORDER BY e.name)
      INTO v_shape
      FROM expected e
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_trigger tg
             JOIN pg_class c ON c.oid = tg.tgrelid
            WHERE tg.tgname = e.name
              AND c.relname = e.table_name
              AND tg.tgfoid = 'accounting_post_fact'::regproc
              AND tg.tgenabled = 'O'
              AND (tg.tgtype & 1) = 1                       -- FOR EACH ROW
              AND (tg.tgtype & 2) = 0                       -- AFTER, not BEFORE
              AND (tg.tgtype & 4) = 4                       -- INSERT
              AND (tg.tgtype & 8) = 0                       -- never DELETE
              AND (tg.tgtype & 16) = 0                      -- never UPDATE
              AND tg.tgdeferrable = e.is_deferrable
              AND tg.tginitdeferred = e.is_deferrable
              AND (e.has_when = false OR strpos(pg_get_triggerdef(tg.oid), 'COMPLETED') > 0));

    IF v_shape IS NOT NULL THEN
        RAISE EXCEPTION
            'V100 reads these triggers as missing, disabled, attached to another table, of the wrong timing, or '
            'with the wrong deferral: %. The two immediate ones post a fact that is complete when it is written, '
            'and the cost one has to be DEFERRABLE INITIALLY DEFERRED or it posts a movement''s first allocation '
            'and is then refused by its own second firing.',
            v_shape;
    END IF;

    -- S16 is a later slice, so nothing here may write the period: not a trigger on it, and not a line in either
    -- function. Asserted by reading the code back, because the day someone adds the refusal early is the day a
    -- posting into any month on a book with no periods closes for every tenant.
    IF EXISTS (SELECT 1
                 FROM pg_trigger tg
                WHERE tg.tgrelid = 'accounting_period'::regclass
                  AND NOT tg.tgisinternal) THEN
        RAISE EXCEPTION
            'V100 left a trigger on accounting_period. Closing a month and refusing a posting into a closed one is S16/T11, and a period rule installed before the slice that decides its semantics is a refusal nobody asked for.';
    END IF;

    SELECT string_agg(p.proname, ', ' ORDER BY p.proname)
      INTO v_period
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = current_schema()
       AND p.proname = ANY (v_functions)
       AND strpos(pg_get_functiondef(p.oid), 'accounting_period') > 0;

    IF v_period IS NOT NULL THEN
        RAISE EXCEPTION
            'V100 reads accounting_period inside: %. Nothing in this slice reads or writes the period — S16 is T11 — and a poster that started refusing a closed month here would refuse every posting on a book whose periods nobody has defined yet.',
            v_period;
    END IF;
END $$;
