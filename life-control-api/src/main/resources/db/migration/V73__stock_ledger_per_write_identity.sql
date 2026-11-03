-- ============================================
-- V73 — ADR-0001: a movement's identity gains the write, so one document line can book more than one movement
-- ============================================
-- `V60` created the ledger and keyed a movement on `(source_type, source_id, movement_type)`; `V63` widened it
-- with `store_location_id`, which let one line that draws from two locations book one movement per location.
-- What neither could express is one sale line that books the same direction at the same location **more than
-- once**, which `SalesOrderService` really writes: raising a line's quantity sells the difference, and lowering
-- it reverses and re-sells the whole new quantity, always under the same `SALES_ORDER_ITEM` reference
-- (`SalesOrderService:961-981`). The identity of such a movement is therefore not the line — it is the write
-- that produced it, and this file is the two columns and the fifth key column that state it.
--
-- It is T1 of `odd/tasks/ledger-per-write-identity.md`. The receipt's identity (a document handed once per
-- line), the reversal's own flip, the drops and the deletion of `InventoryService` are that record's S8, i.e.
-- `odd/tasks/inventory-ledger.md`'s remaining T5d slices, and nothing here touches them.
--
-- WHAT IS MEASURED IN THE TREE, opened rather than guessed
--   * the key being replaced — `V63:129-131`: `CREATE UNIQUE INDEX uq_movement_source ON stock_movement
--     (source_type, source_id, movement_type, store_location_id) WHERE reverses_movement_id IS NULL`. This file
--     states the same four columns, the same predicate byte for byte, and one column appended.
--   * the table gaining the counter — `V1__baseline_schema.sql:496-509`: `sales_order_items` has no `version`
--     and no write identity of any kind. Its only moving column is `updated_at`, maintained application-side by
--     `Auditable`'s `@PreUpdate LocalDateTime.now()` — a clock reading, not an identity, so keying on it would
--     make a legal re-sale depend on how long the operator took.
--   * the table gaining the column — `V60:113-136`: `stock_movement` is append-only (`trg_stock_movement_append_only`)
--     and every projected balance is derived from it, so the column is part of the fact and is never edited.
--   * the writer that needs it — `StockLedgerWriter.book(...)`, one INSERT whose `(source_type, source_id)` pair
--     is chosen by the caller. The receipt and the manual edit state the legacy row they mirror; the sale states
--     the line, and from here on states the line's write with it.
--
-- WHY TWO COLUMNS AND NOT ONE
--   The counter has to live where the write is chosen — on the document line, under the document's own
--   transaction and row lock — while the key that uses it lives on the movement. `sales_order_items.write_seq`
--   is therefore the authority and `stock_movement.write_seq` is the copy of the number the movement was booked
--   under: the ledger stays a self-contained fact, and no reader has to join a mutable document table to learn
--   which movement came first.
--
-- WHY THE COUNTER IS NOT AN ENTITY FIELD, AND WHY THAT IS NOT STATED HERE
--   `sales_order_items.write_seq` is deliberately unmapped on `SalesOrderItem`: the bump is a single
--   `UPDATE … RETURNING` taken by the service (`SalesOrderService`), and a mapped
--   field would let Hibernate's flush write a stale value back over the number SQL just produced. A migration
--   cannot enforce that, so the javadoc of the record and the writer's own tests are what pin it; the column
--   exists here only as a column.
--
-- WHY `DEFAULT 0` AND `NOT NULL` ON BOTH, and why that keeps today's ledger exactly as unique as it is
--   `NOT NULL` for the reason `V63`'s self-check states about the location: in a B-tree unique index a `NULL` is
--   distinct from every other `NULL`, so a nullable `write_seq` would make the fifth column refuse nothing for
--   exactly the rows that have none. `DEFAULT 0` is what carries the existing rows over without a backfill: every
--   row `V60`/`V63`/`V62` wrote already holds a `source_id` that is unique per row (the mirror's primary key, or
--   the backfill's `product_variant_locations` id), so appending a constant to the key cannot collapse two of them
--   into one. The same default is what a source with no counter — a receipt, a manual edit, a reversal — states
--   explicitly through `StockLedgerWriter.SINGLE_WRITE`, so `0` means "this source books at most one row per
--   direction and location" wherever it appears, and `1, 2, 3…` means "the nth write of a document line".
--
-- WHAT THE WIDER KEY PERMITS, AND WHAT IT STILL REFUSES
--   It permits two movements with the same source, direction and location under two different writes — one line
--   sold, grown and sold again. It still refuses the same source, direction, location **and** write, which is the
--   half of I5 (`docs/adr/0001-inventory-ledger-single-source.md:184`) that makes a replayed write a no-op instead of a
--   second deduction; a reversal row is still outside the index entirely (`WHERE reverses_movement_id IS NULL`
--   is carried over unchanged), so a reversal is never counted against its source and `uq_movement_one_reversal`
--   still bounds the reversals of one movement at one. Reversing a movement still does not free its key: the
--   reversed row stays in the index and the reversal is a new row that names it.
--
-- WHY DROP + CREATE IN ONE FILE, and what it costs, stated rather than hidden
--   An index's column set is not alterable, and a rename would leave the wider index under a name that says the
--   old thing. `DROP INDEX` takes `ACCESS EXCLUSIVE` and `CREATE INDEX` takes `SHARE` on `stock_movement`, and
--   Flyway runs this file as one transaction on the owner's connection — so there is no instant at which the
--   table is writable with the key absent, and no concurrent writer can slip a duplicate into the window. The
--   cost is the same one `V63` paid and stated: the build rewrites a new index over the whole table while it
--   holds the exclusive lock. `CONCURRENTLY` is refused for `V63`'s reason — it cannot run inside a transaction,
--   so a failure would leave the ledger half-keyed, which is the one state this file must not produce.
--
-- WHY NO ROW-LEVEL SECURITY WINDOW, measured rather than assumed
--   `stock_movement` is armed with `ENABLE` + `FORCE` (`V60:308-312`) and Flyway runs as its owner, which
--   `FORCE` does not exempt. Nothing here writes or queries a row: `ALTER TABLE … ADD COLUMN` and an index build
--   read the heap through the DDL and index-build machinery, not through the rewriter, so ADR-0005's policies
--   neither filter them nor approve them — and a policy-filtered index build would be the one thing a unique key
--   must never be. `sales_order_items` carries no `company_id` and therefore has no policy at all.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT DO
--   * it does not touch `uq_movement_one_reversal`: I4 is a different question and the self-check asserts it still
--     has its single column;
--   * it does not change what any existing row says: no `UPDATE`, no backfill, no seed, so a re-run of the schema
--     would produce the same rows and nothing here can double;
--   * it does not move the receipt's or the reversal's identity: those stay on the mirror until
--     `odd/tasks/inventory-ledger.md`'s T5d, which is why `stock_movement.write_seq` has a default at all;
--   * it adds no other index: the access pattern for `write_seq` is the unique key itself, and an index nothing
--     queries is a write cost with no reader.
--
-- Forward-only. Undoing it by hand means dropping the five-column index, re-creating the four-column one with the
-- predicate unchanged, and dropping both columns — which only succeeds while no line has booked two movements
-- under one source, direction and location: once one has, the narrow index cannot be built over the existing rows
-- and the undo fails with a duplicate key rather than silently choosing which movement to keep.
-- ============================================

-- ─── 1. the counter, on the line that owns the write ──────────────────────────────────────────────
ALTER TABLE sales_order_items ADD COLUMN write_seq BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sales_order_items.write_seq IS
    'ADR-0001 I5 as V73 states it: how many times this line has booked stock. Bumped by a single '
    'UPDATE … write_seq = write_seq + 1 … RETURNING in the same transaction as the movement, and deliberately '
    'unmapped on the entity so Hibernate cannot write a stale value back over it. 0 is "never written".';

-- ─── 2. the number the movement was booked under ─────────────────────────────────────────────────
ALTER TABLE stock_movement ADD COLUMN write_seq BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN stock_movement.write_seq IS
    'ADR-0001 I5 as V73 states it: the write of the source document line that produced this movement. Together '
    'with source_type, source_id, movement_type and store_location_id it is uq_movement_source, so a line may '
    'book once per write, direction and location and never twice under one. 0 is a source that has no counter '
    '(a receipt, a manual edit, a reversal) and every row the ledger held before this file.';

-- ─── 3. the key, re-created with the write ────────────────────────────────────────────────────────
DROP INDEX uq_movement_source;

CREATE UNIQUE INDEX uq_movement_source
    ON stock_movement (source_type, source_id, movement_type, store_location_id, write_seq)
    WHERE reverses_movement_id IS NULL;

COMMENT ON INDEX uq_movement_source IS
    'ADR-0001 invariant I5 (V60:170-173, widened by V63, keyed on the write by V73): one movement per source '
    'document line, direction, location AND write. A line sold, grown and sold again is three legal changes of '
    'quantity under one line id, and only the write tells them apart; a replayed write still holds the same key '
    'and is still refused, which is what keeps a retried request a no-op. Reversing a movement does not free its '
    'key: the reversed row keeps reverses_movement_id NULL and the reversal is a new row that names it.';

-- ─── 4. the self-check this branch ends every migration with ─────────────────────────────────────
-- Four refusals, because there are four ways this file can report success and have decided nothing: a key that is
-- missing, that is not unique, that does not belong to `stock_movement`, or whose column list is not exactly the
-- five this file states; a key that lost its predicate, which would count every reversal against its own source; a
-- write_seq that is nullable, which would make the fifth column refuse nothing; and a counter on the line that is
-- nullable or not a number, which would leave the movement's copy of it meaningless. `uq_movement_one_reversal` is
-- checked in the same block because "I did not touch I4" is a claim this file makes and the check is cheap.
--
-- EVERY PART IS COMPARED, NONE IS SEARCHED FOR. The first version of this block asked whether
-- `pg_get_indexdef` *contained* the five column names in order, and that question has the wrong answer for a
-- sixth column: `(…, write_seq, id)` contains them and is a different key — one that refuses nothing, because
-- the row's own id makes every row unique. It also never asked which table the index was on, and it accepted a
-- non-unique index because the word UNIQUE merely had to appear somewhere in the definition. So the column list
-- is now read from the catalog the way the planner reads it — `pg_index.indkey` in key order, each entry resolved
-- to its `pg_attribute.attname` — and compared to the exact list as one string; the table is compared by oid;
-- uniqueness is `pg_index.indisunique`; and the predicate is `pg_get_expr` normalised of whitespace and
-- parentheses (the only freedom PostgreSQL's deparser takes with it) and compared to the exact expression. The
-- eight shapes this refuses were run against a scratch database: a sixth column, a reordered list, a dropped
-- predicate, an inverted predicate, a non-unique index, the same index on another table, a nullable write_seq and
-- a `uq_movement_one_reversal` that gained the fifth column.
DO $$
DECLARE
    v_index              oid;
    v_table              oid;
    v_is_unique          boolean;
    v_predicate          text;
    v_columns            text;
    v_reversal_index     oid;
    v_reversal_table     oid;
    v_reversal_is_unique boolean;
    v_reversal_predicate text;
    v_reversal_columns   text;
    v_nullable           boolean;
    v_type               text;
BEGIN
    SELECT i.indexrelid, i.indrelid, i.indisunique, pg_get_expr(i.indpred, i.indrelid)
      INTO v_index, v_table, v_is_unique, v_predicate
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE c.relname = 'uq_movement_source';

    IF v_index IS NULL THEN
        RAISE EXCEPTION
            'V73 did not leave uq_movement_source in place: stock_movement holds no key at all, so nothing refuses a replayed write and a line that books twice under one location is accepted twice.';
    END IF;

    SELECT string_agg(a.attname, ',' ORDER BY k.ord)
      INTO v_columns
      FROM pg_index i
      CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
     WHERE i.indexrelid = v_index;

    IF v_table IS DISTINCT FROM 'stock_movement'::regclass
       OR v_is_unique IS DISTINCT FROM true
       OR v_columns IS DISTINCT FROM 'source_type,source_id,movement_type,store_location_id,write_seq'
       OR regexp_replace(lower(COALESCE(v_predicate, '')), '[[:space:]()]', '', 'g')
          IS DISTINCT FROM 'reverses_movement_idisnull' THEN
        RAISE EXCEPTION
            'V73 did not leave uq_movement_source as one unique index on stock_movement over exactly (source_type, source_id, movement_type, store_location_id, write_seq) where reverses_movement_id is null. Found: table %, unique %, columns (%), predicate %. Each part is compared to the value this file states and never searched for inside a longer one, because an index that merely contains the five names is a different key: without the fifth column a line that books twice under one location is refused again, a sixth or a reordered column leaves the key refusing something other than the write, an index on another table keys nothing this ledger writes, and a lost predicate counts every reversal against its own source.',
            v_table::regclass, v_is_unique, COALESCE(v_columns, '<none>'), COALESCE(v_predicate, '<none>');
    END IF;

    SELECT i.indexrelid, i.indrelid, i.indisunique, pg_get_expr(i.indpred, i.indrelid)
      INTO v_reversal_index, v_reversal_table, v_reversal_is_unique, v_reversal_predicate
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE c.relname = 'uq_movement_one_reversal';

    IF v_reversal_index IS NULL THEN
        RAISE EXCEPTION
            'V73 was not supposed to touch uq_movement_one_reversal (ADR-0001 invariant I4) and it is gone: without it a movement can be reversed twice and the ledger holds two facts for one correction.';
    END IF;

    SELECT string_agg(a.attname, ',' ORDER BY k.ord)
      INTO v_reversal_columns
      FROM pg_index i
      CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
     WHERE i.indexrelid = v_reversal_index;

    IF v_reversal_table IS DISTINCT FROM 'stock_movement'::regclass
       OR v_reversal_is_unique IS DISTINCT FROM true
       OR v_reversal_columns IS DISTINCT FROM 'reverses_movement_id'
       OR regexp_replace(lower(COALESCE(v_reversal_predicate, '')), '[[:space:]()]', '', 'g')
          IS DISTINCT FROM 'reverses_movement_idisnotnull' THEN
        RAISE EXCEPTION
            'V73 was not supposed to touch uq_movement_one_reversal (ADR-0001 invariant I4), and its definition is not the one V60 declared. Found: table %, unique %, columns (%), predicate %.',
            v_reversal_table::regclass, v_reversal_is_unique, COALESCE(v_reversal_columns, '<none>'),
            COALESCE(v_reversal_predicate, '<none>');
    END IF;

    SELECT NOT a.attnotnull, format_type(a.atttypid, a.atttypmod)
      INTO v_nullable, v_type
      FROM pg_attribute a
     WHERE a.attrelid = 'stock_movement'::regclass
       AND a.attname = 'write_seq'
       AND NOT a.attisdropped;

    IF v_nullable IS DISTINCT FROM false OR v_type IS DISTINCT FROM 'bigint' THEN
        RAISE EXCEPTION
            'V73 widened uq_movement_source with a write_seq that is missing, nullable or not a bigint (found %, %). A NULL is distinct from every other NULL in a unique index, so every row with no write would be unique against every other row of its source and the fifth column would refuse nothing.',
            COALESCE(v_type, '<missing>'),
            v_nullable;
    END IF;

    SELECT NOT a.attnotnull, format_type(a.atttypid, a.atttypmod)
      INTO v_nullable, v_type
      FROM pg_attribute a
     WHERE a.attrelid = 'sales_order_items'::regclass
       AND a.attname = 'write_seq'
       AND NOT a.attisdropped;

    IF v_nullable IS DISTINCT FROM false OR v_type IS DISTINCT FROM 'bigint' THEN
        RAISE EXCEPTION
            'V73 gave sales_order_items a write_seq that is missing, nullable or not a bigint (found %, %). The movement copies this number, so a line whose counter cannot be read or cannot hold a number makes every movement of that line state a write nobody produced.',
            COALESCE(v_type, '<missing>'),
            v_nullable;
    END IF;
END $$;
