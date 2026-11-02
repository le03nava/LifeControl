-- ============================================
-- V63 — ADR-0001: a source document line can span two locations, so uq_movement_source gains the location
-- ============================================
-- ADR-0001 is accepted (`docs/adr/0001-inventory-ledger-single-source.md`, 2026-11-02). `V60` created the five
-- tables and the two partial indexes, `V61` installed the engine, `V62` replayed today's balances. This file is
-- **one statement pair and nothing else**: it drops `uq_movement_source` and re-creates it with
-- `store_location_id` appended. No column, no table, no function, no row: the schema, the trigger, the ledger's
-- identity and the legacy write path are exactly what they were.
--
-- It is T5c of `odd/tasks/inventory-ledger.md`, and the scope of T5c is the maintainer's decision rather than the
-- task table's: the drops, the deletion of `InventoryService` and the `InsufficientStockException` move are T6,
-- and that record's last `## Log` entry stops the unit before them because a movement's identity cannot outlive
-- `inventory_movements` without ADR-0004. What survives that decision is what this file does and what T5c's other
-- half does, because neither depends on the identity.
--
-- WHAT IS MEASURED IN THE TREE, opened rather than guessed
--   * the index being replaced — `V60__stock_ledger_schema.sql:171-173`:
--     `CREATE UNIQUE INDEX uq_movement_source ON stock_movement (source_type, source_id, movement_type)
--     WHERE reverses_movement_id IS NULL`, commented "one movement per source document line and direction: a
--     retried request cannot double-book" (`:170`). This file's `DROP`/`CREATE` states the same three columns
--     and the same predicate, with one column appended.
--   * the column appended — `V60:120`: `store_location_id UUID NOT NULL`. `NOT NULL` is what makes appending it
--     a widening and not a hole; see the NULL paragraph below.
--   * the writer that needs it — `InventoryService.java:425-460`: `applySaleDeduction` walks the store's locked
--     balances and writes **one legacy `SALE` row and one ledger `SALE` movement per location it drains**, so a
--     line that draws 10 from the priority location and 4 from the overflow books two movements that share the
--     line's source. Line 453 is the `stockLedgerWriter.bookSale(...)` call inside that loop, and
--     `StockLedgerWriter`'s class javadoc names this collision as the first of the three shapes the narrow index
--     cannot satisfy ("A sale line that spans locations books more than one movement").
--   * the legacy table had no such rule — `V10__inventory.sql:30-38`: `inventory_movements` carries
--     `reference_type`/`reference_id` and no unique key over them at all, which is why the tree could already
--     record one line against two locations and the ledger alone refused it.
--   * the second identity shape, which this file does **not** solve — `SalesOrderService.java:987-993`: a line's
--     quantity is raised by deducting the delta and lowered by reversing and re-deducting the whole new quantity,
--     always under the same `sales_order_items` id. Two `SALE` rows under one line id therefore happen with one
--     location, and no widening of this index can permit them. That is ADR-0004's per-transition key, and T5d
--     waiting on it is the decision this file does not reopen.
--
-- WHAT THIS FILE PERMITS THAT THE NARROW INDEX REFUSED
--   Two movements with the same `source_type`, the same `source_id` and the same `movement_type` (**the same
--   direction**) at **two different locations**, both outside a reversal. That is exactly one document line that
--   spans locations, and nothing else: the number of movements one line may book is now bounded by the number of
--   locations it draws from — a finite, physical set — instead of being pinned to one.
--
-- WHAT IT STILL REFUSES, stated so the widening is not read as "the key was relaxed"
--   * the same source, direction **and location** — a retried request is still refused, which is the half of I5
--     (`:184`) that makes a repeated booking a no-op instead of a double deduction. `V63` adds a column to the
--     key; it removes none.
--   * a reversal is still outside the index entirely: the predicate `reverses_movement_id IS NULL` is carried
--     over byte for byte, so a reversal row is never counted against its source, and `uq_movement_one_reversal`
--     is what bounds the reversals of one movement (at most one, I4 `:183`).
--   * reversing a movement does **not** free its key. The reversed row is still a row with
--     `reverses_movement_id IS NULL` — the reversal is a new row that names it (decision 1, `:48-50`) — so a
--     second booking of the same source, direction and location is refused even after the first was reversed.
--     That is the honest statement of what the index guarantees, and it is why the ledger's re-book identity is a
--     per-transition question (ADR-0004) rather than something a wider key could hand out.
--
-- WHY THE WIDENING IS NOT A WEAKENING OF I5
--   I5 is "a source document line books **one movement per direction**" (`:184`), and decision 1 is "every change
--   of quantity is one `stock_movement` row" (`:48-50`) with a movement naming exactly one location
--   (`V60:120`). A line that changes quantity at two locations is therefore two changes of quantity, and a key
--   that could hold only one of them was not enforcing I5 — it was enforcing a stricter rule the ADR never
--   states, at the cost of a legal fact. Read at the grain it is written on, I5 survives unchanged: one movement
--   per source, direction **and location**. Nothing can double-book: a second row at a location that already
--   booked is refused, and the number of rows one source may produce is bounded by the locations it names.
--
-- WHY APPENDING A COLUMN CANNOT LET DUPLICATES THROUGH, and why that is a property of `NOT NULL`
--   In a B-tree unique index two rows are distinct when any indexed column differs, and `NULL` is distinct from
--   everything including another `NULL`. So appending a **nullable** column would have destroyed the key: every
--   row with a `NULL` location would be unique against every other row of the same source. `store_location_id`
--   is `NOT NULL` since `V60:120` and carries no default, so there is no row this index cannot see, and the
--   self-check at the end of this file asserts the column is still `NOT NULL` — because that is the fact the
--   widening rests on, and a later migration that relaxed it would silently turn this key into decoration.
--
-- WHY DROP + CREATE IN ONE FILE IS SAFE, AND WHAT IT COSTS
--   `DROP INDEX` takes `ACCESS EXCLUSIVE` on `stock_movement` and `CREATE INDEX` takes `SHARE`, and Flyway runs
--   this file as one transaction on the owner's connection — so there is no instant at which the table is
--   writable with the key absent, and no concurrent writer can slip a duplicate into the window. The cost is
--   stated rather than hidden: building the index rewrites a new index over the whole table, so the migration
--   holds `ACCESS EXCLUSIVE` for as long as that scan takes, and waiting writers queue behind it. `CONCURRENTLY`
--   would avoid the lock and is refused here for the reason no migration in this tree uses it (grep: this file is
--   the only one that even names it): it cannot run inside a transaction, so a failure would leave the ledger
--   half-keyed — the one state this file must not produce. At the size this table has today the scan is a formality; at a size where it is not, the right
--   answer is a separate operational migration, not a silent `CONCURRENTLY`.
--
-- WHY NO ROW-LEVEL SECURITY WINDOW, measured rather than assumed
--   `stock_movement` is armed with `ENABLE` + `FORCE` by `V60:308-312`, and Flyway runs as the table's **owner**
--   (`lifecontrol_owner`), which `FORCE` does not exempt. Neither statement here writes or queries a row: an
--   index build reads the heap through the index-build machinery, not through the rewriter, so ADR-0005's
--   policies neither filter it nor approve it. That is the behaviour a unique key needs — a policy-filtered
--   build would let two rows the policy hides from each other both exist — and it is the same thing `V60` relies
--   on when it creates the two partial indexes on this table while it is already armed. No `NO FORCE` window is
--   opened, because there is no statement here that a policy could filter.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT DO
--   * it does not touch `uq_movement_one_reversal`: I4 is a different question, its predicate is the opposite
--     one, and this file's self-check asserts it still has its single column;
--   * it does not change `source_type`/`source_id`, which still carry the mirrored `inventory_movements` row
--     during the dual-write window — a document identity for a movement is ADR-0004's, and T5d consumes the
--     answer;
--   * it does not add an index for the tenant predicate or for the reversal lookup: `V60:97-101` and `V61:129-137`
--     decided those, and this file has no new access pattern to justify another one;
--   * it writes no row, no backfill and no seed, so there is nothing here that a `NO FORCE` window would make
--     visible and nothing that a re-run would double.
--
-- Forward-only. Undoing it by hand means dropping the four-column index and re-creating the three-column one with
-- the predicate unchanged, and it only succeeds while no source line spans two locations: once one does, the
-- narrow index cannot be built over the existing rows and the undo fails with a duplicate key rather than
-- silently choosing which movement to keep. That asymmetry is why the widening is safe to land and the narrowing
-- is not safe to run later, and it is the reason this file's whole content is the two statements below.
-- ============================================

-- ─── 1. the key, re-created with the location ─────────────────────────────────────────────────────
-- `DROP` and `CREATE` rather than `ALTER INDEX … RENAME`: an index's column set is not alterable, and a rename
-- would leave the wider index under a name that says the old thing. The predicate is copied from `V60:173`
-- character for character — `uq_movement_one_reversal` keeps its own, which is the opposite one.
DROP INDEX uq_movement_source;

CREATE UNIQUE INDEX uq_movement_source
    ON stock_movement (source_type, source_id, movement_type, store_location_id)
    WHERE reverses_movement_id IS NULL;

COMMENT ON INDEX uq_movement_source IS
    'ADR-0001 invariant I5 (V60:170-173, widened by V63): one movement per source document line, direction AND '
    'location. The location is in the key because a movement names exactly one location (V60:120) and a line '
    'that draws stock from two of them is two facts — refusing the second would refuse a legal change of '
    'quantity. What it still refuses is a second row under the same source, direction and location, which is '
    'what makes a retried booking a no-op; reversing the first row does not free the key, because the reversed '
    'row keeps reverses_movement_id NULL (a reversal is a new row that names it).';

-- ─── 2. the self-check this branch ends every migration with ─────────────────────────────────────
-- Three refusals, because there are three ways this file can report success and have decided nothing: an index
-- that is missing or not unique; a key that lost its predicate or its fourth column, which would either
-- duplicate rows or refuse the second location again; and a `store_location_id` that is no longer `NOT NULL`,
-- which is the single fact the widening rests on (a `NULL` location is distinct from every other `NULL`, so the
-- key would stop refusing anything at all for such a row). `uq_movement_one_reversal` is checked in the same
-- block because "I did not touch I4" is a claim this file makes and the check is cheap.
DO $$
DECLARE
    v_source   text;
    v_reversal text;
    v_nullable boolean;
BEGIN
    SELECT pg_get_indexdef(i.indexrelid)
      INTO v_source
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE c.relname = 'uq_movement_source';

    IF v_source IS NULL
       OR position('UNIQUE' IN v_source) = 0
       OR position('source_type, source_id, movement_type, store_location_id' IN v_source) = 0
       OR position('WHERE (reverses_movement_id IS NULL)' IN v_source) = 0 THEN
        RAISE EXCEPTION
            'V63 did not leave uq_movement_source as one unique index over (source_type, source_id, movement_type, store_location_id) where reverses_movement_id is null. Found: %. Without the fourth column a line that spans locations is refused again, and without the predicate a reversal would be counted against its own source.',
            COALESCE(v_source, '<missing>');
    END IF;

    SELECT pg_get_indexdef(i.indexrelid)
      INTO v_reversal
      FROM pg_index i
      JOIN pg_class c ON c.oid = i.indexrelid
     WHERE c.relname = 'uq_movement_one_reversal';

    IF v_reversal IS NULL
       OR position('UNIQUE' IN v_reversal) = 0
       OR position('(reverses_movement_id)' IN v_reversal) = 0
       OR position('store_location_id' IN v_reversal) > 0 THEN
        RAISE EXCEPTION
            'V63 was not supposed to touch uq_movement_one_reversal (ADR-0001 invariant I4), and its definition is not the one V60 declared: %.',
            COALESCE(v_reversal, '<missing>');
    END IF;

    SELECT NOT a.attnotnull
      INTO v_nullable
      FROM pg_attribute a
     WHERE a.attrelid = 'stock_movement'::regclass
       AND a.attname = 'store_location_id'
       AND NOT a.attisdropped;

    IF v_nullable IS DISTINCT FROM false THEN
        RAISE EXCEPTION
            'V63 widened uq_movement_source with a store_location_id that is missing or nullable. A NULL is distinct from every other NULL in a unique index, so every row with no location would be unique against every other row of its source and the key would refuse nothing — the widening only holds while the column is NOT NULL, as V60:120 declares it.';
    END IF;
END $$;
