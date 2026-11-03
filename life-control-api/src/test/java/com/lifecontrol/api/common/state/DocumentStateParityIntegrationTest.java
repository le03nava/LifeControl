package com.lifecontrol.api.common.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.goodsreceipt.model.GoodsReceiptState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderDetailState;
import com.lifecontrol.api.purchaseorder.model.PurchaseOrderState;
import com.lifecontrol.api.salesorder.model.SalesOrderItemState;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three copies of one state set, coupled in both directions: the family enum (the authority), the
 * {@code CHECK} on the document table (ADR-0004 invariant T6) and the {@code code} its catalog row carries
 * (invariant T5, inside the scope the ADR claims — five families in, two out, by decision).
 *
 * <p><b>The expected side comes from the enum; the observed side comes from the database.</b>
 * {@link StatusSubject#values()} picks the families and {@code Enum.values()} states their constants, and
 * the other side is read back: {@code pg_constraint} with {@code pg_get_constraintdef} for the {@code CHECK},
 * {@code pg_attribute.attnotnull} for the nullability of the column, {@code information_schema.columns} for
 * the shape of {@code statuses.code}, and {@code statuses} joined to {@code status_types} for the catalog.
 * Nothing compares the database with itself: a suite that read the {@code CHECK} and then asked the
 * {@code CHECK} what it admits would stay green while the enum drifted away from both.</p>
 *
 * <p><b>Why the shared container at head, and not a scratch database.</b> The shape asserted here is the
 * <b>destination</b> of the migration, not a rewrite of existing rows: at head every document already carries
 * its typed state, so the constraint, the column and the catalog are all observable as they will be in
 * delivery. The rewrite — the backfill, the window, the bridge — is
 * {@link com.lifecontrol.api.document.DocumentStateSchemaMigrationIntegrationTest}'s subject, held at
 * {@code V65} for exactly that reason. Two halves, two databases, and neither one re-asks the other's
 * question.</p>
 *
 * <p><b>Why every message names the direction.</b> The three copies disagree in a direction, and the reader
 * of a failure is usually adding one state and does not yet know which copy was forgotten: a value in the
 * {@code CHECK} with no constant ({@code CHECK→enum}), a constant with no value in the {@code CHECK}
 * ({@code enum→CHECK}), a catalog row with no constant to point at ({@code catalog→enum}), a constant with no
 * catalog row ({@code enum→catalog}). Each case states which one broke, plus the table or family and the
 * offending value, because that sentence is the whole diagnostic.</p>
 *
 * <p><b>Two shape facts are pinned here because the ADR argues for them and a later change could take them
 * silently:</b> {@code statuses.code} is <b>nullable</b> — {@code NOT NULL} there and "out of scope" for
 * {@code APPOINTMENT} and {@code EMPLOYEE_STATUS} cannot both be true — and {@code uq_statuses_type_code} is
 * a <b>UNIQUE constraint</b> over {@code (status_type_id, code)}, so one code names one label per family.</p>
 */
@SpringBootTest
@DisplayName("Document state parity: the enum, the CHECK and the catalog (ADR-0004 T5 and T6)")
class DocumentStateParityIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * The pool's own handle, and the role is enough: {@code pg_catalog} and {@code information_schema} answer
     * any role, and {@code statuses}/{@code status_types} are a catalog rather than a tenant table — no policy
     * filters them, which is why no tenant scope is set anywhere in this suite.
     */
    @Autowired
    private JdbcTemplate jdbc;

    /** The one shape a {@code CHECK} renders its values in: quoted literals, and nothing else quoted. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([^']*)'");

    /**
     * The five families ADR-0004 types, as the three names that have to hold the same set: the table, the
     * constraint on it, and the enum. The catalog name is not a fourth copy — it is
     * {@link StatusSubject#name()}, the code's own word for the family, which is what makes the join below
     * possible without a mapping table.
     */
    private static final List<Family> FAMILIES = List.of(
            new Family(StatusSubject.SALES_ORDER, "sales_orders", "ck_sales_orders_status", SalesOrderState.class),
            new Family(
                    StatusSubject.SALES_ORDER_ITEM,
                    "sales_order_items",
                    "ck_sales_order_items_status",
                    SalesOrderItemState.class),
            new Family(
                    StatusSubject.PURCHASE_ORDER,
                    "purchase_orders",
                    "ck_purchase_orders_status",
                    PurchaseOrderState.class),
            new Family(
                    StatusSubject.PURCHASE_ORDER_DETAIL,
                    "purchase_order_details",
                    "ck_purchase_order_details_status",
                    PurchaseOrderDetailState.class),
            new Family(
                    StatusSubject.GOODS_RECEIPT,
                    "goods_receipts",
                    "ck_goods_receipts_status",
                    GoodsReceiptState.class));

    /**
     * The two families the same ADR puts out of scope <b>by decision</b> (appointments are a calendar fact,
     * employment status is an HR attribute). They keep their rows and carry no code, and that asymmetry is
     * the reason {@code statuses.code} cannot be {@code NOT NULL}.
     */
    private static final List<String> OUT_OF_SCOPE_FAMILIES = List.of("APPOINTMENT", "EMPLOYEE_STATUS");

    // ─── T6: the CHECK and the enum ─────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{displayName} → {0}")
    @MethodSource("inScopeFamilies")
    @DisplayName("T6: the CHECK admits exactly the enum's constants, and status is NOT NULL")
    void theCheckAndTheEnumAreOneSet(Family family) {
        var declared = quotedLiterals(constraintDefinition(family));
        var constants = family.constants();

        assertThat(constants)
                .as(
                        "%s: the CHECK %s admits a value that %s does not declare, so the database would accept "
                                + "a state the code cannot produce. The values in the CHECK with no constant are "
                                + "listed below (CHECK→enum direction).",
                        family.table(), family.constraint(), family.enumName())
                .containsAll(declared);

        assertThat(declared)
                .as(
                        "%s: %s declares a constant that the CHECK %s does not admit, so a row the code can store "
                                + "would be refused by the database itself. The constants with no value in the "
                                + "CHECK are listed below (enum→CHECK direction).",
                        family.table(), family.enumName(), family.constraint())
                .containsAll(constants);

        assertThat(statusColumnIsNotNull(family.table()))
                .as(
                        "%s.status is nullable, which makes its CHECK decorative: NULL passes every CHECK, so a "
                                + "row could store no state at all and no enum would be involved (ADR-0004 T6).",
                        family.table())
                .isTrue();
    }

    // ─── T5: the catalog's code and the enum, one direction per case ────────────────────────────────

    @ParameterizedTest(name = "{displayName} → {0}")
    @MethodSource("inScopeFamilies")
    @DisplayName("T5: every catalog row of an in-scope family carries a constant of its enum")
    void everyCatalogRowCarriesAnEnumConstant(Family family) {
        var rows = catalogRows(family.typeName());
        assertThat(rows)
                .as(
                        "%s: the family ADR-0004 types has no row in statuses, so the catalog→enum direction of "
                                + "T5 would be vacuous for it — a green run over zero rows says nothing.",
                        family.typeName())
                .isNotEmpty();

        var constants = family.constants();
        for (var row : rows) {
            assertThat(constants)
                    .as(
                            "%s: the catalog row '%s' carries code %s, which %s does not declare. statuses is "
                                    + "the label and the enum is the authority; a code with no constant behind it "
                                    + "is the lie T5 exists to catch (catalog→enum direction).",
                            family.typeName(), row.get("status_name"), row.get("code"), family.enumName())
                    .contains((String) row.get("code"));
        }
    }

    @ParameterizedTest(name = "{displayName} → {0}")
    @MethodSource("inScopeFamilies")
    @DisplayName("T5: every constant of an in-scope family has exactly one catalog row carrying it")
    void everyConstantHasExactlyOneCatalogRow(Family family) {
        Map<String, Long> rowsPerCode = new LinkedHashMap<>();
        for (var row : catalogRows(family.typeName())) {
            rowsPerCode.merge((String) row.get("code"), 1L, Long::sum);
        }

        for (var constant : family.constants()) {
            assertThat(rowsPerCode)
                    .as(
                            "%s: %s declares the constant %s and no statuses row of that family carries the code, "
                                    + "so the label for a state the code can store does not exist "
                                    + "(enum→catalog direction).",
                            family.typeName(), family.enumName(), constant)
                    .containsKey(constant);

            assertThat(rowsPerCode.get(constant))
                    .as(
                            "%s: the constant %s has %s catalog row(s) and a code must identify exactly one label "
                                    + "— %s is the set the enum declares (enum→catalog direction).",
                            family.typeName(), constant, rowsPerCode.get(constant), family.enumName())
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("T5: the two out-of-scope families keep their rows and carry no code")
    void theOutOfScopeFamiliesKeepTheirRowsWithoutACode() {
        for (var family : OUT_OF_SCOPE_FAMILIES) {
            var rows = catalogRows(family);
            assertThat(rows)
                    .as(
                            "%s: ADR-0004 leaves this family out of scope and keeps its rows, which is what makes "
                                    + "the assertion below a statement about uncoded rows instead of about a "
                                    + "table with no rows at all.",
                            family)
                    .isNotEmpty();

            for (var row : rows) {
                assertThat(row.get("code"))
                        .as(
                                "%s: the catalog row '%s' of a family ADR-0004 puts out of scope carries code %s. "
                                        + "These rows have no domain enum, so a code here names a constant that "
                                        + "does not exist and the catalog→enum direction of T5 would fail on "
                                        + "it: the scope decision is that they stay uncoded.",
                                family, row.get("status_name"), row.get("code"))
                        .isNull();
            }
        }
    }

    // ─── the two shape facts the ADR argues for ────────────────────────────────────────────────────

    @Test
    @DisplayName("statuses.code is nullable, and that is what lets the two out-of-scope families exist")
    void theCodeColumnStaysNullable() {
        var nullable = nullableOf("statuses", "code");
        assertThat(nullable)
                .as(
                        "statuses.code must stay nullable (information_schema says %s). The catalog serves seven "
                                + "families and two of them have no domain enum, so NOT NULL here and «out of "
                                + "scope» there cannot both be true — what replaces it is the join scope of T5 "
                                + "plus V66's own assertion.",
                        nullable)
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("uq_statuses_type_code is UNIQUE over (status_type_id, code): one code names one label")
    void theCatalogKeysOneCodePerFamily() {
        var definitions = jdbc.query("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                 WHERE t.relnamespace = 'public'::regnamespace
                   AND t.relname = 'statuses'
                   AND c.conname = 'uq_statuses_type_code'
                   AND c.contype = 'u'
                """, (result, index) -> result.getString(1));
        assertThat(definitions)
                .as("statuses has no UNIQUE constraint (pg_constraint.contype = 'u') named "
                        + "uq_statuses_type_code. A plain index would not stop a second row of one family "
                        + "carrying a code its enum already has elsewhere, and one state would then have two "
                        + "labels — which is the ambiguity this ADR removes by making the enum the authority.")
                .hasSize(1);

        assertThat(definitions.get(0))
                .as("uq_statuses_type_code must be unique over exactly (status_type_id, code): the family is "
                        + "part of the key because statuses is one catalog for seven families and four names "
                        + "(Pending, Received, Rejected, Cancelled…) are shared between them.")
                .contains("UNIQUE (status_type_id, code)");
    }

    // ─── the expected side: the families and their constants, read from the enum ───────────────────

    /**
     * The parameter source, and the coverage guard: it walks {@link StatusSubject#values()} rather than
     * {@link #FAMILIES}, so a subject the code types without a table, a {@code CHECK} and an enum here fails
     * loudly instead of quietly leaving one family's parity unread.
     */
    static List<Arguments> inScopeFamilies() {
        return Arrays.stream(StatusSubject.values())
                .map(subject -> Arguments.of(familyOf(subject)))
                .toList();
    }

    private static Family familyOf(StatusSubject subject) {
        return FAMILIES.stream()
                .filter(family -> family.subject() == subject)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("StatusSubject." + subject
                        + " is a family the code types and this suite has no table, CHECK constraint and enum"
                        + " for it: its parity would go unread"));
    }

    /**
     * One document family as the three names that must carry the same set.
     *
     * @param subject the family, whose {@code name()} is {@code status_types.status_type_name}
     * @param table the document table holding the typed {@code status}
     * @param constraint the {@code CHECK} the migration put on it
     * @param enumType the Java enum, which is the authority
     */
    private record Family(StatusSubject subject, String table, String constraint, Class<? extends Enum<?>> enumType) {

        /** The catalog's name for the family — the code's own word, not a fourth copy of it. */
        String typeName() {
            return subject.name();
        }

        String enumName() {
            return enumType.getSimpleName();
        }

        /** The expected side of every comparison in this suite: {@code Enum.values()}, never a literal list. */
        List<String> constants() {
            Enum<?>[] constants = enumType.getEnumConstants();
            return Arrays.stream(constants).map(constant -> constant.name()).toList();
        }

        /** So a failing invocation reads {@code … → SALES_ORDER} instead of the record's field dump. */
        @Override
        public String toString() {
            return typeName();
        }
    }

    // ─── the observed side: the database's own answer ─────────────────────────────────────────────

    /** The {@code CHECK} as PostgreSQL renders it, or a failure that says the coupling is missing entirely. */
    private String constraintDefinition(Family family) {
        var definitions = jdbc.query("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                 WHERE t.relnamespace = 'public'::regnamespace
                   AND t.relname = ?
                   AND c.conname = ?
                   AND c.contype = 'c'
                """, (result, index) -> result.getString(1), family.table(), family.constraint());
        assertThat(definitions)
                .as(
                        "%s: no CHECK constraint named %s exists on the table, so nothing in the database couples "
                                + "the stored state to %s — the set would have three copies and only one of them "
                                + "enforced (ADR-0004 T6).",
                        family.table(), family.constraint(), family.enumName())
                .hasSize(1);
        return definitions.get(0);
    }

    /**
     * Whether the {@code status} column is {@code NOT NULL}.
     *
     * <p>Asked separately from the {@code CHECK} on purpose: a nullable column turns the constraint into
     * decoration, because {@code NULL} is not a value the {@code CHECK} refuses and "no state" would then be
     * a storable state that no enum declares.</p>
     */
    private boolean statusColumnIsNotNull(String table) {
        var flags = jdbc.query("""
                SELECT a.attnotnull
                  FROM pg_attribute a
                  JOIN pg_class t ON t.oid = a.attrelid
                 WHERE t.relnamespace = 'public'::regnamespace
                   AND t.relname = ?
                   AND a.attname = 'status'
                   AND a.attnum > 0
                   AND NOT a.attisdropped
                """, (result, index) -> result.getBoolean(1), table);
        assertThat(flags)
                .as("%s: the table has no status column, so there is no typed state to constrain", table)
                .hasSize(1);
        return flags.get(0);
    }

    /** The catalog rows of one family: the label the screen shows and the code the enum declares. */
    private List<Map<String, Object>> catalogRows(String typeName) {
        return jdbc.queryForList("""
                SELECT s.status_name, s.code
                  FROM statuses s
                  JOIN status_types st ON st.id = s.status_type_id
                 WHERE st.status_type_name = ?
                 ORDER BY s.status_name
                """, typeName);
    }

    /** {@code information_schema}'s {@code YES}/{@code NO} for one column, and a failure if it is absent. */
    private String nullableOf(String table, String column) {
        var values = jdbc.query("""
                SELECT is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, (result, index) -> result.getString(1), table, column);
        assertThat(values).as("%s.%s is not a column", table, column).hasSize(1);
        return values.get(0);
    }

    /** Every quoted literal a constraint definition lists, as a set: {@code 'DRAFT', 'ACTIVE', …}. */
    private Set<String> quotedLiterals(String definition) {
        var literals = new TreeSet<String>();
        var matcher = QUOTED_LITERAL.matcher(definition);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }
}
