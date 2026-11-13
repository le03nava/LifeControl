-- ============================================
-- V98 — the accounting starter catalog: a closed chart of accounts and the three posting rules
--        (S9 of odd/tasks/derived-accounting.md, T4; ADR-0008 decision 4, decision 10 and
--        `## Migration from the current tree` row 2)
-- ============================================
-- `V97__accounting_book.sql` created the book and left it empty, deliberately: a schema slice has no rows to
-- write and a catalog of eight accounts in the middle of six `CREATE TABLE`s buries the question a reviewer
-- has to answer first. This file writes the data. It is the same split `V19`/`V35` made for the product
-- category tree, and for the same reason — "is the schema right?" and "is this the catalog the tenant
-- should receive?" are two questions, read separately and answered separately.
--
-- WHY THE CATALOG IS SEEDED AND NOT USER-INVENTED (S9). A chart of accounts that arrives empty is a book
-- nobody can post to: every tenant would have to invent `1101` and decide what a receivable is before its
-- first sale, and the posting rules of the next slice would have nothing to name. So every tenant receives
-- the minimum chart and the three anchor rules, and both halves of "every" are written: this file reaches the
-- companies that exist when it runs, and `StarterAccountingCatalogListener` reaches the companies created
-- afterwards, from the same list this file states. A tenant added tomorrow therefore does not arrive without
-- a book. The parity between the two copies is pinned by `StarterAccountingCatalogTest`, which parses the
-- value rows of this file and compares them to the Java catalog in both directions.
--
-- WHY THE VOCABULARY IS CLOSED, IN THE SAME FILE THAT FILLS IT. `posting_rule.source_type` and
-- `posting_rule.posting` are `VARCHAR(40)` with no `CHECK` in `V97`, so a tenant could spell the same anchor
-- `STATUS_TRANSITION` and `status_transition`, or invent `REVENUE_2`, and `S10`'s startup assertion — a table
-- that moves money has a rule — would be meaningless: the assertion would find a row and the posting code
-- would look for a different one. Two named `CHECK`s close that, and they are the "closed minimum vocabulary"
-- the ADR's step 2 already claims rather than a new decision. They also state the four postings the book
-- reads, `REVERSAL` included, even though no `REVERSAL` rule is seeded: the vocabulary says what a rule may
-- be, and the seed says which rules exist.
--
-- WHY `IVA trasladado` IS A LIABILITY AND `IVA acreditable` AN ASSET. Tax collected on a sale is not the
-- tenant's money: it is collected and owed, so it sits on the liability side (`2101`). Tax paid on a purchase
-- is money the tenant can credit against what it owes, so it sits on the asset side (`1202`). The two are not
-- the same kind and collapsing them into one "IVA" account would make the trial balance lie by sign while
-- still summing to zero — which is exactly the kind of error a balanced book cannot detect by itself.
--
-- WHY `COLLECTION` DEFAULTS TO CASH, AND THAT THIS IS A LIMITATION. `SALE_PAYMENT`/`COLLECTION` debits
-- `1101 Caja`. A card, a transfer or a cheque has no rule of its own, because `sale_payment` records the
-- amount and not the instrument's account, so cash is the one account every payment can be presumed to reach
-- until the collection rule of B3 learns to split by payment method. Seeding `1102 Bancos` instead would be
-- the same guess with a worse default. This is stated rather than derived: a book that puts every card sale
-- in the drawer reconciles with nothing.
--
-- WHY THERE IS NO `REVERSAL` RULE. A reversal mirrors the lines of the entry it names — debit what was
-- credited, credit what was debited — so it needs no pair of accounts of its own. Seeding one would create a
-- rule nothing reads, and a rule nothing reads is worse than an absent one: it would satisfy S10's assertion
-- for a table nobody posts from and hide the gap.
--
-- WHY THERE IS A `NO FORCE` WINDOW AND `V35` DID NOT NEED ONE. `V97:315-320` arms `account` and
-- `posting_rule` with `ENABLE` **and `FORCE`**, and `FORCE` is what makes the policy apply to the table's
-- **owner** — the role Flyway runs as, in delivery and in this suite. An `INSERT … SELECT … FROM companies`
-- under that policy matches no row of the target catalog and reports success, so the seed would silently
-- create nothing and the self-check below would be the only thing that noticed. The window is the shape
-- `V57:85-141` established for the same trap: open it, seed, read the result back while it is open, close
-- it, and re-read that it closed. `V54:50-58` and `V64:30-42` record the failure mode in full.
--
-- WHY THE RULES JOIN THE CHART BY CODE AND NEVER BY A HARDCODED UUID. The accounts are inserted by this
-- file, so their ids are `gen_random_uuid()` values that did not exist when it was written — and a literal
-- id would be wrong for every tenant but one, which is the shape `V35`'s header refuses for categories. The
-- seed therefore inserts the rules with `debit_account_id`/`credit_account_id` resolved through
-- `JOIN account ON (company_id, code)`, which is the unique key `V97` wrote (`uq_account_company_code`).
--
-- WHAT THIS FILE DOES NOT DO, named so it is not discovered in the diff:
--   * **No posting.** Nothing fires on a sale, a payment or an allocation (T6–T8). This file makes the rules
--     and the accounts exist; the slice that reads them is B3.
--   * **No startup assertion.** S10's refusal belongs to T5 and lives in Java, where a boot can fail.
--   * **No editable catalog screen.** No catalog of this tree has one, and the ADR refuses one here
--     explicitly: the chart is seeded and closed until a consumer justifies a screen.
--   * **No backfill and no history.** There are no entries to attribute, which is the one cheap consequence
--     of a pre-production tree (ADR-0008 `## Migration`, first limit).
-- ============================================

-- ─── 1. the two closed vocabularies S10's assertion depends on ─────────────────────────────────────
-- Named `CHECK`s and not `CREATE TYPE … AS ENUM`: no enumeration type exists anywhere in this tree, and the
-- closed vocabularies it does have are inline `CHECK (x IN (…))` (`V67:60-62`, `V97:98`). The names matter as
-- much as the values — a migration that has to be read months later, and a refusal that names the constraint
-- an operator met, both need something to call it.
ALTER TABLE posting_rule
    ADD CONSTRAINT ck_posting_rule_source_type
        CHECK (source_type IN ('STATUS_TRANSITION', 'SALE_PAYMENT', 'STOCK_ALLOCATION')),
    ADD CONSTRAINT ck_posting_rule_posting
        CHECK (posting IN ('REVENUE', 'COLLECTION', 'COST_OF_SALES', 'REVERSAL'));

COMMENT ON CONSTRAINT ck_posting_rule_source_type ON posting_rule IS
    'The three append-only anchors ADR-0008 decision 3 posts from: the transition to a terminal state, a '
    'payment row, an allocation row. Closed because S10 asserts a money-moving table has a rule, and one '
    'anchor spelled two ways makes that assertion find a row the posting code does not look for.';

COMMENT ON CONSTRAINT ck_posting_rule_posting ON posting_rule IS
    'The four pieces a source can post. REVERSAL is part of the vocabulary and carries no seeded rule: a '
    'reversal mirrors the lines of the entry it names and needs no pair of accounts of its own.';

-- ─── 2. the window both seeds need, opened before the first write ───────────────────────────────────
-- The policy of an armed table filters its owner too, so without this both `INSERT … SELECT` statements
-- below would match zero rows and raise nothing. `companies` is in the window as well, and that is not
-- symmetry: the seed reads it, `V53` arms it, and a `CROSS JOIN companies` under the policy would iterate over
-- **no tenant at all** — the shape `V57:85` opened the same window for, and `V93:78` repeated for its own
-- backfill. The check below is the reason this file cannot fail silently at this point: it states that all
-- three windows are really open, which a bare `NO FORCE` statement does not.
ALTER TABLE account NO FORCE ROW LEVEL SECURITY;
ALTER TABLE posting_rule NO FORCE ROW LEVEL SECURITY;
ALTER TABLE companies NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_open integer;
BEGIN
    SELECT count(*) INTO v_open
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('account', 'posting_rule', 'companies')
       AND c.relkind = 'r'
       AND NOT c.relforcerowsecurity;

    IF v_open <> 3 THEN
        RAISE EXCEPTION
            'V98 could not open its NO FORCE window on all three tables it reads and writes (it found % of 3 '
            'without FORCE). The seed reads `companies` and writes two armed tables, so under the policy the '
            'owner is filtered by, the SELECT would iterate over no tenant and the INSERT would match no row '
            '— reporting success while leaving every existing tenant without a chart of accounts and without '
            'the rules that name it. See V57 section 3, V93 section 2 and ADR-0005 correction 3.', v_open;
    END IF;
END $$;

-- ─── 3. the chart of accounts, stated once in one temporary table ───────────────────────────────────
-- A temporary table and not an `INSERT … VALUES` per company for the reason `V35:41-46` gives: the values
-- are written once and read by both the seed and the self-check, and a plain `CREATE TEMPORARY TABLE` rather
-- than `ON COMMIT DROP` so the file does not depend on Flyway's transactional mode. It is dropped in
-- section 8, and section 9 fails if it is still there.
--
-- The names are Spanish because they are the tenant's data in a Mexican fiscal domain — the same convention
-- `tax_code`'s `TASA_0`/`EXENTO` and the ADR's own wording already follow. The codes are what a rule and a
-- report address; the name is presentation.
CREATE TEMPORARY TABLE starter_account (
    code VARCHAR(20)  NOT NULL,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(10)  NOT NULL
);

INSERT INTO starter_account (code, name, kind) VALUES
    ('1101', 'Caja', 'ASSET'),
    ('1102', 'Bancos', 'ASSET'),
    ('1201', 'Cuentas por cobrar', 'ASSET'),
    ('1202', 'IVA acreditable', 'ASSET'),
    ('1301', 'Inventario', 'ASSET'),
    ('2101', 'IVA trasladado', 'LIABILITY'),
    ('4101', 'Ingresos por ventas', 'REVENUE'),
    ('5101', 'Costo de ventas', 'EXPENSE')
;

-- ─── 4. the seed, for every company that exists ─────────────────────────────────────────────────────
-- `INSERT … SELECT … FROM companies` and not a list of ids: the catalog is per tenant, so the seed is one row
-- per company and the cross join is what states that. The `NOT EXISTS` guard is on the **code**, which is the
-- tenant's key (`uq_account_company_code`): a company that already has `1101` — by hand, by a replay, by a
-- restored dump — keeps its row and receives only the accounts it lacks. `ON CONFLICT` is deliberately not
-- used: it would need the inference to match the exact unique key and it says nothing about which row was
-- skipped, while the guard reads the same way the applier's Java half does.
INSERT INTO account (company_id, code, name, kind, enabled)
SELECT c.id, s.code, s.name, s.kind, true
FROM companies c
CROSS JOIN starter_account s
WHERE NOT EXISTS (
    SELECT 1 FROM account a
    WHERE a.company_id = c.id
      AND a.code = s.code
);

-- ─── 5. the three posting rules, stated once ────────────────────────────────────────────────────────
-- One row per anchor ADR decision 3 names, each with the pair of accounts it moves. The four columns are
-- `source_type, posting, debit_code, credit_code`: the two account columns carry a **code**, never an id, so
-- the value rows are readable and the seed below resolves them inside each tenant.
CREATE TEMPORARY TABLE starter_posting_rule (
    source_type VARCHAR(40) NOT NULL,
    posting     VARCHAR(40) NOT NULL,
    debit_code  VARCHAR(20) NOT NULL,
    credit_code VARCHAR(20) NOT NULL
);

INSERT INTO starter_posting_rule (source_type, posting, debit_code, credit_code) VALUES
    ('STATUS_TRANSITION', 'REVENUE', '1201', '4101'),
    ('SALE_PAYMENT', 'COLLECTION', '1101', '1201'),
    ('STOCK_ALLOCATION', 'COST_OF_SALES', '5101', '1301')
;

-- ─── 6. the rule seed, resolving both account ids by code inside the tenant ─────────────────────────
-- The two `JOIN`s are the whole point: `d`/`k` are the company's own rows, found by `(company_id, code)`, so a
-- rule can never name another tenant's account and no id is hardcoded. The `NOT EXISTS` guard is the rule's
-- own key, `uq_posting_rule_company_source_posting` — a replay of this file, or a company that already wired
-- one anchor, keeps what it has.
INSERT INTO posting_rule (company_id, source_type, posting, debit_account_id, credit_account_id, enabled)
SELECT c.id, s.source_type, s.posting, d.id, k.id, true
FROM companies c
CROSS JOIN starter_posting_rule s
JOIN account d ON d.company_id = c.id AND d.code = s.debit_code
JOIN account k ON k.company_id = c.id AND k.code = s.credit_code
WHERE NOT EXISTS (
    SELECT 1 FROM posting_rule r
    WHERE r.company_id = c.id
      AND r.source_type = s.source_type
      AND r.posting = s.posting
);

-- ─── 7. the seed, read back while the window is still open ──────────────────────────────────────────
-- A row count is only possible here, inside the window: after section 9 forces both tables again, the owner
-- reads zero rows of them and this block would report success over no data at all. Three refusals, because
-- there are three ways this file does nothing — the counts are short (a guard matched nothing), a code the
-- catalog names is absent (the guard skipped a row that was not there), or a rule does not resolve to the
-- pair the catalog states (the id lookup found the wrong account).
DO $$
DECLARE
    v_expected_accounts integer;
    v_expected_rules    integer;
    v_wrong             text;
    v_missing           text;
    v_unwired           text;
BEGIN
    SELECT count(*) INTO v_expected_accounts FROM starter_account;
    SELECT count(*) INTO v_expected_rules FROM starter_posting_rule;

    SELECT string_agg(
               s.company_key || ' (' || s.accounts || ' accounts, ' || s.rules || ' rules)',
               ', ' ORDER BY s.company_key)
      INTO v_wrong
      FROM (SELECT co.company_key,
                   (SELECT count(*) FROM account a WHERE a.company_id = co.id)      AS accounts,
                   (SELECT count(*) FROM posting_rule r WHERE r.company_id = co.id) AS rules
              FROM companies co) s
     WHERE s.accounts <> v_expected_accounts
        OR s.rules <> v_expected_rules;

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION
            'V98 left these companies with a book that does not match the catalog: %. Every tenant receives '
            'exactly % accounts and % posting rules, so a short count means the seed matched no row — check '
            'the NO FORCE window at the top of this file — and a long one means this file ran against a '
            'catalog that already had rows it did not write.', v_wrong, v_expected_accounts, v_expected_rules;
    END IF;

    SELECT string_agg(c.company_key || ':' || s.code, ', ' ORDER BY c.company_key, s.code)
      INTO v_missing
      FROM companies c
      CROSS JOIN starter_account s
     WHERE NOT EXISTS (
           SELECT 1 FROM account a
            WHERE a.company_id = c.id
              AND a.code = s.code);

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V98 did not seed % of the chart of accounts. A tenant with a hole in its chart cannot post the '
            'rule that names that code, and nothing downstream would report the absence: the posting would '
            'simply find no rule and the books would come out short.', v_missing;
    END IF;

    SELECT string_agg(c.company_key || ':' || s.source_type || '/' || s.posting,
                      ', ' ORDER BY c.company_key, s.source_type)
      INTO v_unwired
      FROM companies c
      CROSS JOIN starter_posting_rule s
     WHERE NOT EXISTS (
           SELECT 1
             FROM posting_rule r
             JOIN account d ON d.company_id = r.company_id AND d.id = r.debit_account_id
             JOIN account k ON k.company_id = r.company_id AND k.id = r.credit_account_id
            WHERE r.company_id = c.id
              AND r.source_type = s.source_type
              AND r.posting = s.posting
              AND d.code = s.debit_code
              AND k.code = s.credit_code);

    IF v_unwired IS NOT NULL THEN
        RAISE EXCEPTION
            'V98 seeded % but the rule does not debit and credit the accounts the catalog names. The ids are '
            'resolved by joining `account` on (company_id, code); a rule pointing anywhere else would post '
            'the right amount to the wrong side and still balance.', v_unwired;
    END IF;
END $$;

-- ─── 8. the temporary tables go, and the file says so out loud in section 9 ─────────────────────────
-- `DROP TABLE` rather than the session end, for the reason `V35:159` records: the table would otherwise
-- outlive the migration in whatever session Flyway used, and nothing would notice a stray `starter_account`
-- carrying a catalog nobody owns.
DROP TABLE starter_account;
DROP TABLE starter_posting_rule;

-- ─── 9. the window closes, and the shape this file depends on is read back ──────────────────────────
-- All three tables go back to `ENABLE` + `FORCE`: a window left open is a catalog its owner reads across
-- tenants without a scope, which is the state ADR-0005 correction 3 refuses. The re-check is a separate block
-- from section 7 on purpose — that one could read rows, this one cannot, and the two facts are measured by
-- the two blocks that can observe them.
ALTER TABLE account FORCE ROW LEVEL SECURITY;
ALTER TABLE posting_rule FORCE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    v_unarmed text;
    v_check   text;
    v_temp    text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO v_unarmed
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relname IN ('account', 'posting_rule', 'companies')
       AND c.relkind = 'r'
       AND NOT (c.relrowsecurity AND c.relforcerowsecurity);

    IF v_unarmed IS NOT NULL THEN
        RAISE EXCEPTION
            'V98 opened a NO FORCE window and did not close it on: %. That table is both seeded and readable '
            'by its owner without a tenant scope, which is the state ADR-0005 correction 3 and `V57` section 4 '
            'refuse.', v_unarmed;
    END IF;

    -- The two closed vocabularies, read back from the constraint definitions rather than counted by name: a
    -- `CHECK` that exists with the wrong values would otherwise pass this block and refuse nothing useful.
    SELECT string_agg(e.name || ' without ' || e.value, ', ' ORDER BY e.name, e.value)
      INTO v_check
      FROM (VALUES
            ('ck_posting_rule_source_type', 'STATUS_TRANSITION'),
            ('ck_posting_rule_source_type', 'SALE_PAYMENT'),
            ('ck_posting_rule_source_type', 'STOCK_ALLOCATION'),
            ('ck_posting_rule_posting', 'REVENUE'),
            ('ck_posting_rule_posting', 'COLLECTION'),
            ('ck_posting_rule_posting', 'COST_OF_SALES'),
            ('ck_posting_rule_posting', 'REVERSAL')
           ) AS e(name, value)
     WHERE NOT EXISTS (
           SELECT 1
             FROM pg_constraint k
            WHERE k.conname = e.name
              AND k.conrelid = 'posting_rule'::regclass
              AND k.contype = 'c'
              AND strpos(pg_get_constraintdef(k.oid), '''' || e.value || '''') > 0);

    IF v_check IS NOT NULL THEN
        RAISE EXCEPTION
            'V98 reads % — a closed vocabulary is missing a value, or the constraint is not a CHECK on '
            'posting_rule at all. S10 asserts that a money-moving table has a posting rule, and an anchor '
            'the vocabulary does not admit cannot have one.', v_check;
    END IF;

    SELECT string_agg(c.relname, ', ')
      INTO v_temp
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname LIKE 'pg_temp%'
       AND c.relname IN ('starter_account', 'starter_posting_rule');

    IF v_temp IS NOT NULL THEN
        RAISE EXCEPTION
            'V98 left its scratch table(s) % behind. The catalog is the migration''s, not the session''s: a '
            'table that survives it carries a set of values nothing owns and every later read would have to '
            'know to ignore.', v_temp;
    END IF;
END $$;
