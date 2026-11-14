-- ============================================
-- V99 — a posting rule is N lines: the table, the backfill of the three seeded rules into it, and the two
--        account columns it replaces (S11 and L13 of odd/tasks/derived-accounting.md; the schema half of
--        T6, T7 and T8)
-- ============================================
-- `V97__accounting_book.sql` gave a rule one debit account and one credit account, and `V98` seeded three of
-- those pairs. S11 asks the entry a sale produces to carry three parts of the document — the revenue, the
-- `IVA trasladado` and the receivable — and a pair of accounts cannot express three. Measured before writing:
-- `2101 IVA trasladado` sits in the seeded chart named by no rule, so the tax of a sale had nowhere to post
-- and the document's own `tax_total` was unreachable from the book. This file is the maintainer's answer,
-- recorded as L13: a rule is **N lines**, each one naming a side, an account and which amount of the source it
-- moves.
--
-- WHY N LINES AND NOT A THIRD ACCOUNT COLUMN. A column is one more place per new part, and the parts are not a
-- fixed set of three — the cost rule moves one amount, the collection rule moves one amount, and a reversal
-- moves whatever the entry it reverses moved. A line is the shape a book already has: an entry has lines
-- because one fact can split across accounts, and a rule that produces an entry should be able to say the same.
--
-- WHY THE SELECTORS ARE CLOSED AND GENERIC (L13(a)). `side` says which way the money goes; `amount_selector`
-- says which amount of the source is moved. The vocabulary is one set of five — `TOTAL`, `REVENUE`, `TAX` for
-- the document, `SOURCE_AMOUNT` for a payment, `ALLOCATED_COST` for the ledger — and it is closed by a named
-- `CHECK` for the reason `V98` closed `source_type` and `posting`: a per-anchor enum would triple the
-- constraints, and an open vocabulary would let a rule name an amount no poster can compute, which posts
-- nothing and raises nothing.
--
-- WHY THE DOCUMENT'S REVENUE SELECTOR IS `REVENUE` AND NOT `BASE` (L13(b)). The rounding is folded into
-- revenue, so the selector means `total_amount − tax_total` and not the bare base. `ck_order_total` states
-- `total = subtotal − discount + tax + rounding`, so a fourth line carrying `rounding_adjustment` goes
-- **negative** on a discount-heavy document and `ck_account_entry_line_non_negative` (`V97`) refuses the line;
-- and a signed selector would move the flip inside the poster, where no `CHECK` can see it. Folding it keeps
-- the entry balanced by construction — `TOTAL = REVENUE + TAX` — and is the reason the revenue rule has three
-- lines and not four.
--
-- WHY THE TWO ACCOUNT COLUMNS ARE DROPPED, IN THE SAME FILE THAT REPLACES THEM (L13). They were the ADR's
-- first shape and `V98` seeded against them, so they are not a mistake to be kept "for continuity": a second
-- copy of the pair is a second thing that can disagree with the lines, and the poster would have to pick one.
-- The pair's own foreign keys go with the columns, because a pointer to the chart that nothing reads is the
-- more dangerous half of a dropped column. `L11(a)` had already replaced the id-only keys `V97` wrote with
-- composite ones over `account (company_id, id)`; S7's premise — a referential check bypasses row security —
-- moves to the lines and is stated there.
--
-- WHY THE LINES ARE NOT APPEND-ONLY, AND WHY THEY ARE ARMED ANYWAY. The ADR exempts the two catalogs from the
-- append-only pair on purpose: `posting_rule` and `account` are configuration, and an entry is a fact. A line
-- of a rule is configuration in the same sense, so it carries neither the trigger nor the `REVOKE`, and it is
-- still a tenant table — `company_id` of its own, armed here — because a rule is per tenant and the line has
-- to be refused across tenants exactly as the rule is.
--
-- WHY THE POINTERS ARE COMPOSITE (S7). `(company_id, rule_id)` and `(company_id, account_id)`, each over a
-- `UNIQUE (company_id, id)` on the target — the `V88:51-52` shape. `account` already had
-- `uq_account_company_id`; `posting_rule` does not, so this file adds `uq_posting_rule_company_id`. An id-only
-- pointer would let one tenant's rule line name another tenant's account, and the referential check that
-- would catch it bypasses row security, so no policy on this table can.
--
-- WHY THERE IS A `NO FORCE` WINDOW AND `V98` ALREADY SHOWED WHY IT IS NEEDED. `V97` arms `account`,
-- `posting_rule` and `companies` with `ENABLE` **and `FORCE`**, and `FORCE` reaches the owner — the role Flyway
-- runs as. The backfill is an `INSERT … SELECT … FROM companies / posting_rule / account`, so without the
-- window it matches no row and reports success, leaving every existing tenant with rules that have no lines
-- and a self-check that was the only thing that could have noticed. `V54:50-58` and `V64:30-42` record the
-- failure mode; `V57:85` opened the same window for the same trap.
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No posting.** No trigger, no poster and no writer: the lines are the configuration the next task
--     reads, and this file adds no code that fires on a sale, a payment or an allocation.
--   * **No reversal rule and no void handling.** `sale_payment_void` has no writer in this tree, and the
--     reversal of a posted entry is T12 by S17 (L13(c)).
--   * **No screen.** No catalog of this tree has one, and the ADR refuses one here explicitly.
--   * **No index of its own.** The unique key leads with `(company_id, rule_id)`, which is the only read this
--     slice makes — the lines of one rule. An index on the account side would serve the report that does not
--     exist yet, and a second one without a reader only costs writes.
-- ============================================

-- ─── 1. the key a composite pointer needs on the rule ──────────────────────────────────────────────
-- Not a new fact: `id` is the primary key, so the pair is unique by construction. It exists because
-- `REFERENCES` has no way to say "the id, and also the column beside it" — the sentence `V88` wrote for
-- `tax_rate` and `V97` for `departments`.
ALTER TABLE posting_rule
    ADD CONSTRAINT uq_posting_rule_company_id UNIQUE (company_id, id);

COMMENT ON CONSTRAINT uq_posting_rule_company_id ON posting_rule IS
    'Target of fk_posting_rule_line_rule (L13 of odd/tasks/derived-accounting.md). `id` is already unique; the '
    'pair exists so a rule line cannot name another tenant''s rule, which a referential check would otherwise '
    'allow because it bypasses row security.';

-- ─── 2. the lines ──────────────────────────────────────────────────────────────────────────────────
-- `side` is `VARCHAR(6)` because `DEBIT` and `CREDIT` are the words a book uses, not a boolean: a boolean
-- named `is_debit` reads as a question at every call site and `side` is what the entry columns already mean.
-- `created_at` is present for the reason `account` has it — a catalog row has no other timestamp — and it is
-- not the document's date, which the posting takes from its source.
CREATE TABLE posting_rule_line (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id      UUID NOT NULL REFERENCES companies (id),
    rule_id         UUID NOT NULL,
    account_id      UUID NOT NULL,
    side            VARCHAR(6)  NOT NULL,
    amount_selector VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One line per side and selector is what makes a posting deterministic: a second line for the same part
    -- of the same entry would make the stored amount depend on which one the poster read first.
    CONSTRAINT uq_posting_rule_line_company_rule_side_selector
        UNIQUE (company_id, rule_id, side, amount_selector),
    CONSTRAINT fk_posting_rule_line_rule FOREIGN KEY (company_id, rule_id)
        REFERENCES posting_rule (company_id, id),
    CONSTRAINT fk_posting_rule_line_account FOREIGN KEY (company_id, account_id)
        REFERENCES account (company_id, id),
    CONSTRAINT ck_posting_rule_line_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_posting_rule_line_selector
        CHECK (amount_selector IN ('TOTAL', 'REVENUE', 'TAX', 'SOURCE_AMOUNT', 'ALLOCATED_COST'))
);

COMMENT ON TABLE posting_rule_line IS
    'L13 of odd/tasks/derived-accounting.md: one part of one posting rule — a side, an account and which '
    'amount of the source is moved. A rule is N lines because a document posts three parts (revenue, IVA '
    'trasladado, receivable) and a payment or an allocation moves one; a pair of columns on the rule could not '
    'say the tax at all. Editable on purpose: a rule is configuration and an entry is a fact.';
COMMENT ON COLUMN posting_rule_line.side IS
    'DEBIT or CREDIT, closed by CHECK. The side a router would otherwise infer from the position of a column: '
    '`DEBIT` is the receivable of a sale and `CREDIT` is the revenue and the tax collected, and the same '
    'selector appears once per side at most.';
COMMENT ON COLUMN posting_rule_line.amount_selector IS
    'Which amount of the source this line moves, from the closed five-value vocabulary L13(a) chose so one set '
    'of selectors serves the three anchors: TOTAL/REVENUE/TAX of the document, SOURCE_AMOUNT of a payment, '
    'ALLOCATED_COST of the ledger. REVENUE is `total_amount − tax_total` and not the bare base, because the '
    'rounding is folded into it (L13(b)).';
COMMENT ON CONSTRAINT ck_posting_rule_line_side ON posting_rule_line IS
    'The two sides of a double-entry line and nothing else, so a typo cannot store a line the poster silently '
    'skips.';
COMMENT ON CONSTRAINT ck_posting_rule_line_selector ON posting_rule_line IS
    'The closed selector vocabulary. A value outside it names an amount no poster can compute, which posts '
    'nothing and raises nothing — the failure shape S10 exists to refuse one layer up.';

-- ─── 3. tenancy, in the file that creates the table ────────────────────────────────────────────────
-- `apply_tenant_isolation` is the only way a table gets a policy (ADR-0005): it enables and forces row-level
-- security, creates `p_tenant_posting_rule_line` with the same predicate for reads and writes, and records the
-- table in `tenant_table`. No `GRANT` line: `V52:196-199`'s ALTER DEFAULT PRIVILEGES already hands the pool's
-- role all four privileges on every table created afterwards — which is also why nothing here narrows them: a
-- catalog is edited.
SELECT apply_tenant_isolation('posting_rule_line');

-- ─── 4. the window the backfill needs, opened before the first read ────────────────────────────────
-- Four tables and not one, and each for its own reason: `posting_rule_line` is the target of the insert,
-- `posting_rule` and `account` are what the catalog joins to, and `companies` is the tenant list the backfill
-- iterates. All four are armed, so all four filter their owner. The check right after states that the windows
-- are really open, which a bare `NO FORCE` statement does not.
ALTER TABLE posting_rule_line NO FORCE ROW LEVEL SECURITY;
ALTER TABLE posting_rule NO FORCE ROW LEVEL SECURITY;
ALTER TABLE account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE companies NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_open integer;
BEGIN
    SELECT count(*) INTO v_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('posting_rule_line', 'posting_rule', 'account', 'companies')
       AND c.relkind = 'r'
       AND NOT c.relforcerowsecurity;

    IF v_open <> 4 THEN
        RAISE EXCEPTION
            'V99 could not open its NO FORCE window on all four tables it reads and writes (it found % of 4 '
            'without FORCE). The backfill joins `companies`, `posting_rule` and `account` and writes an armed '
            'table, so under the policy the owner is filtered by, the SELECT would iterate over no tenant and '
            'the INSERT would match no row — reporting success while every existing tenant kept three rules '
            'with no lines at all. See V54, V57 section 3, V93 and V98.', v_open;
    END IF;
END $$;

-- ─── 5. the lines of the three seeded rules, stated once ───────────────────────────────────────────
-- `source_type` and `posting` identify the rule rather than its id, because the id is a per-tenant
-- `gen_random_uuid()` and this table has to be readable: the backfill joins the catalog to each tenant's rule
-- by the pair `uq_posting_rule_company_source_posting` already makes unique, and `account_code` names the
-- account the same way `V98` named the rules' pair — a literal id would be wrong for every tenant but one.
CREATE TEMPORARY TABLE starter_posting_rule_line (
    source_type     VARCHAR(40) NOT NULL,
    posting         VARCHAR(40) NOT NULL,
    side            VARCHAR(6)  NOT NULL,
    amount_selector VARCHAR(20) NOT NULL,
    account_code    VARCHAR(20) NOT NULL
);

INSERT INTO starter_posting_rule_line (source_type, posting, side, amount_selector, account_code) VALUES
    ('STATUS_TRANSITION', 'REVENUE', 'DEBIT', 'TOTAL', '1201'),
    ('STATUS_TRANSITION', 'REVENUE', 'CREDIT', 'REVENUE', '4101'),
    ('STATUS_TRANSITION', 'REVENUE', 'CREDIT', 'TAX', '2101'),
    ('SALE_PAYMENT', 'COLLECTION', 'DEBIT', 'SOURCE_AMOUNT', '1101'),
    ('SALE_PAYMENT', 'COLLECTION', 'CREDIT', 'SOURCE_AMOUNT', '1201'),
    ('STOCK_ALLOCATION', 'COST_OF_SALES', 'DEBIT', 'ALLOCATED_COST', '5101'),
    ('STOCK_ALLOCATION', 'COST_OF_SALES', 'CREDIT', 'ALLOCATED_COST', '1301')
;

-- The tenants this file has to reach, captured while the window is open. The closing self-check re-reads each
-- one's lines **after** the policy is back, and no runtime credential can enumerate tenants then — that is
-- `L11(b)` of the record, measured against this very table family. Capturing the ids is what lets the last
-- block ask a per-tenant question under the policy that owns the answer.
CREATE TEMPORARY TABLE verified_posting_rule_company (id UUID PRIMARY KEY);

INSERT INTO verified_posting_rule_company (id)
SELECT id FROM companies;

-- ─── 6. the backfill, for every company that exists ────────────────────────────────────────────────
-- Driven from `companies` so the statement says what it means: the catalog is per tenant, one set of lines per
-- company. The `NOT EXISTS` guard is the line's own key, `(company_id, rule_id, side, amount_selector)`, so a
-- replay of this file — or a company that already has one of the lines by hand — keeps what it has. `ON
-- CONFLICT` is deliberately not used: it says nothing about which row was skipped, while the guard reads the
-- same way the applier's Java half does.
INSERT INTO posting_rule_line (company_id, rule_id, account_id, side, amount_selector)
SELECT r.company_id, r.id, a.id, s.side, s.amount_selector
FROM companies c
JOIN posting_rule r ON r.company_id = c.id
JOIN starter_posting_rule_line s
    ON s.source_type = r.source_type
   AND s.posting = r.posting
JOIN account a ON a.company_id = r.company_id AND a.code = s.account_code
WHERE NOT EXISTS (
    SELECT 1 FROM posting_rule_line l
    WHERE l.company_id = r.company_id
      AND l.rule_id = r.id
      AND l.side = s.side
      AND l.amount_selector = s.amount_selector
);

-- ─── 7. the pair goes, and its two keys with it ────────────────────────────────────────────────────
-- After the backfill and not before, so the order reads as the replacement it is: the lines are written, then
-- the copy they replaced is removed. The two foreign keys are dropped by name first — a `DROP COLUMN` takes the
-- constraint with it, and naming it says which column's pointer is going and not just that one vanished. The
-- table is empty of them for good: a reader that still looks for `debit_account_id` fails loudly at its first
-- query instead of silently reading a column a second writer can disagree with.
ALTER TABLE posting_rule
    DROP CONSTRAINT posting_rule_debit_account_id_fkey,
    DROP CONSTRAINT posting_rule_credit_account_id_fkey,
    DROP COLUMN debit_account_id,
    DROP COLUMN credit_account_id;

-- ─── 8. the backfill, read back while the window is still open ─────────────────────────────────────
-- A row count is only possible here: after section 10 forces the four tables again, the owner reads zero rows
-- of them and this block would report success over no data at all. Two refusals, because there are two ways
-- the backfill does nothing — a line the catalog states that no rule received, and a line that resolved to an
-- account the catalog does not name for that part.
DO $$
DECLARE
    v_expected integer;
    v_missing  text;
    v_wrong    text;
BEGIN
    SELECT count(*) INTO v_expected FROM starter_posting_rule_line;

    SELECT string_agg(c.company_key || ':' || s.source_type || '/' || s.posting || ' ' || s.side || ' '
                      || s.amount_selector, ', ' ORDER BY c.company_key, s.source_type, s.side, s.amount_selector)
      INTO v_missing
      FROM companies c
      CROSS JOIN starter_posting_rule_line s
     WHERE NOT EXISTS (
           SELECT 1
             FROM posting_rule r
             JOIN posting_rule_line l
               ON l.company_id = r.company_id
              AND l.rule_id = r.id
              AND l.side = s.side
              AND l.amount_selector = s.amount_selector
            WHERE r.company_id = c.id
              AND r.source_type = s.source_type
              AND r.posting = s.posting);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 did not backfill % of the starter catalog. A rule with no lines is a rule that posts nothing: '
            'the book keeps recording the fact and no entry appears, and no report can tell that from a quiet '
            'period. The backfill joins each rule by (source_type, posting) and each account by '
            '(company_id, code) — check the NO FORCE window at the top of this file, and that the accounts of '
            'section 5 are the chart V98 seeded.', v_missing;
    END IF;

    SELECT string_agg(c.company_key || ':' || s.posting || ' ' || s.side || ' ' || s.amount_selector
                      || ' resolved to ' || a.code, ', '
                      ORDER BY c.company_key, s.source_type, s.side, s.amount_selector)
      INTO v_wrong
      FROM companies c
      CROSS JOIN starter_posting_rule_line s
      JOIN posting_rule r ON r.company_id = c.id AND r.source_type = s.source_type AND r.posting = s.posting
      JOIN posting_rule_line l ON l.company_id = r.company_id AND l.rule_id = r.id AND l.side = s.side
                              AND l.amount_selector = s.amount_selector
      JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id
     WHERE a.code <> s.account_code;

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 resolved % to the wrong account. The ids are found by joining `account` on (company_id, code), '
            'so a line that names anything else posts the right amount to the wrong side and still balances — '
            'which is the one error a balanced book cannot detect.', v_wrong;
    END IF;

    -- The count is asserted against the catalog, not against a literal: seven lines now, and the day a fourth
    -- rule or a fourth part of the document arrives this block follows the table above instead of lying.
    SELECT string_agg(co.company_key || ' (' || co.lines || ' lines)', ', ' ORDER BY co.company_key)
      INTO v_wrong
      FROM (SELECT c.company_key,
                   (SELECT count(*) FROM posting_rule_line l WHERE l.company_id = c.id) AS lines
              FROM companies c
             WHERE EXISTS (SELECT 1 FROM posting_rule r WHERE r.company_id = c.id)) co
     WHERE co.lines <> v_expected;

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 left % in a state that does not match the catalog: every company with a posting rule receives '
            'exactly % lines. A short count means a guard matched nothing, and a long one means this file ran '
            'against rules that already had lines it did not write.', v_wrong, v_expected;
    END IF;

    DROP TABLE starter_posting_rule_line;
END $$;

-- ─── 9. the window closes ──────────────────────────────────────────────────────────────────────────
-- All four tables go back to `ENABLE` + `FORCE`: a window left open is a catalog its owner reads across
-- tenants without a scope, which is the state ADR-0005 correction 3 refuses. The re-check is section 10, on
-- purpose separate — that one can read rows, this one cannot, and the two facts are measured by the blocks
-- that can observe them.
ALTER TABLE posting_rule_line FORCE ROW LEVEL SECURITY;
ALTER TABLE posting_rule FORCE ROW LEVEL SECURITY;
ALTER TABLE account FORCE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;

-- ─── 10. the self-check: every tenant's lines, per tenant, and the shape the file depends on ───────
-- The row clause runs **first**, and that ordering is the point: `posting_rule_line` is armed again, so the
-- only way to read a tenant's lines as the owner is to state that tenant's scope — the same statement the pool
-- makes per request. `verified_posting_rule_company` holds the ids captured while the window was open, because
-- no runtime credential can enumerate tenants once `FORCE` is back, and asking a scoped question per company is
-- stronger than counting rows over a filtered table: it also proves the policy admits the tenant's own rows.
--
-- A `set_config(…, true)` is `SET LOCAL`, so the scope dies with Flyway's transaction and cannot follow the
-- migration's connection into anything else.
DO $$
DECLARE
    v_company   uuid;
    v_signature text;
    v_wrong     text := '';
    v_check     text;
    v_columns   text;
    v_pointer   text;
    v_unarmed   text;
    v_temp      text;
BEGIN
    FOR v_company IN SELECT id FROM verified_posting_rule_company ORDER BY id LOOP
        PERFORM set_config('app.company_id', v_company::text, true);

        -- 3/2/2 and the exact selector set of each posting, in one question. The signature is
        -- `side:selector` per line, grouped by posting, so a missing part, an extra part, a line that moved to
        -- the other side or a rule that lost its lines are all the same disagreement. A rule whose posting the
        -- catalog does not know may carry no line at all (a `REVERSAL` rule mirrors the entry it names), and a
        -- line on one is refused: it would be a selector a posting cannot mean.
        SELECT string_agg(s.posting || ' as ' || coalesce(s.signature, 'no lines') || ' (' || s.lines || ')',
                          '; ' ORDER BY s.posting)
          INTO v_wrong
          FROM (SELECT r.posting,
                       string_agg(l.side || ':' || l.amount_selector, ',' ORDER BY l.side, l.amount_selector)
                           AS signature,
                       count(l.id) AS lines
                  FROM posting_rule r
                  LEFT JOIN posting_rule_line l
                         ON l.company_id = r.company_id
                        AND l.rule_id = r.id
                 WHERE r.company_id = v_company
                 GROUP BY r.posting) s
         WHERE (s.posting = 'REVENUE'
                AND s.signature IS DISTINCT FROM 'CREDIT:REVENUE,CREDIT:TAX,DEBIT:TOTAL')
            OR (s.posting = 'COLLECTION'
                AND s.signature IS DISTINCT FROM 'CREDIT:SOURCE_AMOUNT,DEBIT:SOURCE_AMOUNT')
            OR (s.posting = 'COST_OF_SALES'
                AND s.signature IS DISTINCT FROM 'CREDIT:ALLOCATED_COST,DEBIT:ALLOCATED_COST')
            OR (s.posting NOT IN ('REVENUE', 'COLLECTION', 'COST_OF_SALES') AND s.lines > 0);

        IF v_wrong IS NOT NULL THEN
            RAISE EXCEPTION
                'V99 left company % with lines that are not the catalog''s: %. Every seeded rule carries 3/2/2 '
                'lines — the revenue rule its TOTAL, REVENUE and TAX parts, and the other two one amount on one '
                'selector from one account to another. A missing part posts the entry short and still balances.',
                v_company, v_wrong;
        END IF;
    END LOOP;

    -- The two closed vocabularies of the new table, read back from the constraints rather than counted by name:
    -- a `CHECK` under the right name holding the wrong values passes every reader and refuses nothing useful.
    SELECT string_agg(e.name || ' without ' || e.value, ', ' ORDER BY e.name, e.value)
      INTO v_check
      FROM (VALUES
            ('ck_posting_rule_line_side', 'DEBIT'),
            ('ck_posting_rule_line_side', 'CREDIT'),
            ('ck_posting_rule_line_selector', 'TOTAL'),
            ('ck_posting_rule_line_selector', 'REVENUE'),
            ('ck_posting_rule_line_selector', 'TAX'),
            ('ck_posting_rule_line_selector', 'SOURCE_AMOUNT'),
            ('ck_posting_rule_line_selector', 'ALLOCATED_COST')
           ) AS e(name, value)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint k
            WHERE k.conname = e.name
              AND k.conrelid = 'posting_rule_line'::regclass
              AND k.contype = 'c'
              AND strpos(pg_get_constraintdef(k.oid), '''' || e.value || '''') > 0);

    IF v_check IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 reads % — a closed vocabulary is missing a value, or the constraint is not a CHECK on '
            'posting_rule_line at all. The selector vocabulary is what lets one rule shape serve three anchors, '
            'and a value it does not admit is an amount the poster cannot compute.', v_check;
    END IF;

    -- The two columns are gone, and so is the pointer that used to carry the chart: a rule that still had a
    -- `credit_account_id` would be a second answer to the question the lines now answer once.
    SELECT string_agg(a.attname, ', ' ORDER BY a.attname)
      INTO v_columns
      FROM pg_attribute a
     WHERE a.attrelid = 'posting_rule'::regclass
       AND a.attname IN ('debit_account_id', 'credit_account_id')
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF v_columns IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 left % on posting_rule. The pair was the ADR''s first shape and L13 replaced it with lines: two '
            'copies of one fact are two things that can disagree, and the poster would have to choose which one '
            'is true.', v_columns;
    END IF;

    SELECT string_agg(k.conname, ', ' ORDER BY k.conname)
      INTO v_pointer
      FROM pg_constraint k
     WHERE k.conrelid = 'posting_rule'::regclass
       AND k.contype = 'f'
       AND k.confrelid = 'account'::regclass;

    IF v_pointer IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 left the foreign key(s) % from posting_rule to account. The rule no longer names an account; '
            'its lines do, through fk_posting_rule_line_account, and a surviving pointer would let a rule name '
            'an account that its lines never mention.', v_pointer;
    END IF;

    -- The pointers and the key of the new table, read back from `pg_get_constraintdef` because a foreign key
    -- over the wrong pair is exactly the defect S7 describes: it exists, it is enforced, and it does not carry
    -- the tenant.
    SELECT string_agg(e.name, ', ' ORDER BY e.name)
      INTO v_pointer
      FROM (VALUES
            ('fk_posting_rule_line_rule', 'FOREIGN KEY (company_id, rule_id)'),
            ('fk_posting_rule_line_account', 'FOREIGN KEY (company_id, account_id)')
           ) AS e(name, fragment)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint k
            WHERE k.conname = e.name
              AND k.conrelid = 'posting_rule_line'::regclass
              AND k.contype = 'f'
              AND strpos(pg_get_constraintdef(k.oid), e.fragment) > 0);

    IF v_pointer IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 reads % as missing or as stated over the wrong columns. Every pointer of this book is composite '
            'over (company_id, <the id>): a referential check bypasses row security, so an id-only pointer lets '
            'one tenant name another''s row and no policy can refuse it (S7).', v_pointer;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint k
         WHERE k.conname = 'uq_posting_rule_line_company_rule_side_selector'
           AND k.conrelid = 'posting_rule_line'::regclass
           AND k.contype = 'u'
           AND strpos(pg_get_constraintdef(k.oid),
                      '(company_id, rule_id, side, amount_selector)') > 0) THEN
        RAISE EXCEPTION
            'V99 reads uq_posting_rule_line_company_rule_side_selector as missing or as a key over other '
            'columns. One line per side and selector is what makes a posting deterministic: without it a second '
            'line for the same part is accepted and the amount posted depends on which row the poster read '
            'first.';
    END IF;

    -- Armed, with the one policy and the registry row `apply_tenant_isolation` writes — the row every startup
    -- assertion and every inventory test reads.
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relname = 'posting_rule_line'
           AND c.relkind = 'r'
           AND c.relrowsecurity
           AND c.relforcerowsecurity
           AND (SELECT count(*) FROM pg_policies p
                 WHERE p.schemaname = 'public'
                   AND p.tablename = c.relname
                   AND p.policyname = 'p_tenant_posting_rule_line') = 1
           AND EXISTS (
               SELECT 1 FROM tenant_table t
                WHERE t.table_name = c.oid
                  AND t.policy_kind = 'tenant'
                  AND t.key_column = 'company_id')) THEN
        RAISE EXCEPTION
            'V99 did not leave posting_rule_line as an armed tenant table: ENABLE + FORCE, exactly one '
            'p_tenant_posting_rule_line policy, and its tenant_table row with kind `tenant` and key company_id. '
            'A catalog whose policy is only half there reads every tenant''s rows for the role that owns it and '
            'reports as empty for the one that does not — both silently.';
    END IF;

    -- The four windows really closed. This is the clause the whole file depends on being true at the end: an
    -- open window is a catalog the migration's own role reads across tenants without a scope.
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('posting_rule_line', 'posting_rule', 'account', 'companies')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 opened a NO FORCE window and did not close it on: %. That table is both seeded and readable by '
            'its owner without a tenant scope, which is the state ADR-0005 correction 3 and V57 section 4 '
            'refuse.', v_unarmed;
    END IF;

    -- The scratch table of tenant ids goes last: it is the migration's, not the session's, and the assertion
    -- after it is what keeps a later edit from leaving it behind for every read to have to ignore.
    DROP TABLE verified_posting_rule_company;

    SELECT string_agg(c.relname, ', ')
      INTO v_temp
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname LIKE 'pg_temp%'
       AND c.relname IN ('starter_posting_rule_line', 'verified_posting_rule_company');

    IF v_temp IS NOT NULL THEN
        RAISE EXCEPTION
            'V99 left its scratch table(s) % behind. The catalog is the migration''s, not the session''s: a '
            'table that survives it carries a set of values nothing owns.', v_temp;
    END IF;
END $$;
