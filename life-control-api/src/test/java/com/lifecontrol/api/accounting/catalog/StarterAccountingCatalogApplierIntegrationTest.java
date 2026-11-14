package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verification of {@link StarterAccountingCatalogApplier} against real PostgreSQL, so the assertions are the
 * rows a company actually receives and not what the applier believes it inserted.
 *
 * <p><b>Which clause of S9 this half observes.</b> The migration reaches the tenants that exist when it
 * runs; a company created afterwards has to receive the same book from the same seed, or "a tenant added
 * tomorrow does not arrive without a book" is a claim about a listener nobody can see. These cases prove the
 * applier writes the eight accounts, the three rules and their seven lines, that a second run is a no-op (so
 * the listener's retry, an operator's re-run and a replay all cost nothing), and that what one tenant receives
 * is invisible to another — the three properties a seed per tenant has to hold.</p>
 *
 * <p><b>Every case runs the applier with no tenant stated on the thread</b>, which is how its weakest
 * production caller runs it: the {@code @TransactionalEventListener(fallbackExecution = true)} path has no
 * request scope to hand over, and an operator script has none either. The applier is what states the company
 * id it was given, inside the transaction it owns — delete that statement and every case here fails with
 * {@link CompanyNotFoundException}, because the read of {@code companies} and the writes to the armed
 * {@code account} and {@code posting_rule} answer zero rows with no scope installed.</p>
 *
 * <p><b>{@code account} and {@code posting_rule} are read inside their own tenant.</b> Both are armed by
 * {@code V97} with {@code ENABLE} + {@code FORCE}, so an unscoped count answers zero and a test that trusted
 * it would pass while nothing had been seeded.</p>
 */
@SpringBootTest
@DisplayName("Starter Accounting Catalog Applier Integration Tests")
class StarterAccountingCatalogApplierIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "STARTER-ACCOUNTING-CATALOG";
    private static final String COMPANY_RFC = "SAC010101AB1";

    private static final String OTHER_COMPANY_KEY = "STARTER-ACCOUNTING-OTHER";
    private static final String OTHER_COMPANY_RFC = "SAC020202CD2";

    /** The eight codes of the chart, in the order the catalog declares them. */
    private static final List<String> ACCOUNT_CODES =
            List.of("1101", "1102", "1201", "1202", "1301", "2101", "4101", "5101");

    /** The three rules, read as {@code source_type:posting}, in the order the catalog declares them. */
    private static final List<String> RULE_ANCHORS =
            List.of("SALE_PAYMENT:COLLECTION", "STATUS_TRANSITION:REVENUE", "STOCK_ALLOCATION:COST_OF_SALES");

    /**
     * The seven lines of those rules, read as {@code source_type:posting:side:amount_selector:code}.
     *
     * <p>Seven and not three: {@code V99} says a rule is N lines, and the revenue rule is the one that needed
     * the third — the document posts revenue, {@code IVA trasladado} and the receivable separately (S11), and
     * the tax part has no account of its own under the pair the ADR first stated (L13).</p>
     */
    private static final List<String> LINE_KEYS = List.of(
            "SALE_PAYMENT:COLLECTION:CREDIT:SOURCE_AMOUNT:1201",
            "SALE_PAYMENT:COLLECTION:DEBIT:SOURCE_AMOUNT:1101",
            "STATUS_TRANSITION:REVENUE:CREDIT:REVENUE:4101",
            "STATUS_TRANSITION:REVENUE:CREDIT:TAX:2101",
            "STATUS_TRANSITION:REVENUE:DEBIT:TOTAL:1201",
            "STOCK_ALLOCATION:COST_OF_SALES:CREDIT:ALLOCATED_COST:1301",
            "STOCK_ALLOCATION:COST_OF_SALES:DEBIT:ALLOCATED_COST:5101");

    /** The number of rows one whole seed writes: eight accounts, three rules and their seven lines. */
    private static final int WHOLE_CATALOG_ROWS = 18;

    @Autowired
    private StarterAccountingCatalogApplier applier;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Every case starts from an empty catalog for the two companies it uses, because the container is shared
     * per JVM and another method's rows would make the counts non-deterministic. Leaf first: a line points at a
     * rule, a rule points at nothing now that the two account columns are gone, and both point down to the
     * company — while an account is still named by the lines, so it goes last among the three.
     */
    @BeforeEach
    void emptyAllCatalogs() {
        for (var companyId : List.of(companyId(), otherCompanyId())) {
            TenantFixtures.inTenant(companyId, () -> {
                jdbcTemplate.update("DELETE FROM posting_rule_line WHERE company_id = ?", companyId);
                jdbcTemplate.update("DELETE FROM posting_rule WHERE company_id = ?", companyId);
                jdbcTemplate.update("DELETE FROM account WHERE company_id = ?", companyId);
            });
        }
    }

    @Test
    @DisplayName("seeds the whole catalog: eight accounts, three rules and the seven lines that state each side")
    void seedsTheWholeCatalog() {
        var companyId = companyId();

        var inserted = applyTo(companyId);

        assertThat(inserted)
                .as("eighteen rows: the eight accounts, then the three rules, then the seven lines that say what "
                        + "each rule moves and from which amount")
                .isEqualTo(WHOLE_CATALOG_ROWS);
        assertThat(accountCodes(companyId))
                .as("the chart of accounts, one code per row, all enabled")
                .isEqualTo(ACCOUNT_CODES);
        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM account WHERE company_id = ? AND enabled",
                                Integer.class,
                                companyId)))
                .as("every seeded account is enabled: a disabled one is unreachable for a posting")
                .isEqualTo(8);
        assertThat(kindOf(companyId, "1201")).as("IVA acreditable is an asset").isEqualTo("ASSET");
        assertThat(kindOf(companyId, "2101"))
                .as("IVA trasladado is a liability: collected and owed, not an asset")
                .isEqualTo("LIABILITY");
        assertThat(ruleAnchors(companyId))
                .as("the three anchors, one rule each")
                .isEqualTo(RULE_ANCHORS);
        assertThat(lineKeys(companyId))
                .as("and the lines: 3/2/2, each resolving its account by code inside its own tenant")
                .isEqualTo(LINE_KEYS);
        assertThat(linesOfPosting(companyId, "REVENUE"))
                .as("the revenue rule is the one that needed the third line: revenue, IVA trasladado and the "
                        + "receivable are three parts of the document, and the tax part has no account of its "
                        + "own under the pair the ADR first stated")
                .isEqualTo(3);
        assertThat(linesOfPosting(companyId, "COLLECTION")).isEqualTo(2);
        assertThat(linesOfPosting(companyId, "COST_OF_SALES")).isEqualTo(2);
    }

    @Test
    @DisplayName("a second run inserts nothing and leaves every count where the first run put them")
    void aSecondRunIsANoOp() {
        var companyId = companyId();
        assertThat(applyTo(companyId)).isEqualTo(WHOLE_CATALOG_ROWS);

        var insertedAgain = applyTo(companyId);

        assertThat(insertedAgain)
                .as("idempotent by the tenant's own keys — code for an account, (source_type, posting) for a "
                        + "rule, (rule_id, side, amount_selector) for a line — so the listener's retry, an "
                        + "operator's re-run and a replay all cost nothing")
                .isZero();
        assertThat(accountCodes(companyId)).hasSize(8);
        assertThat(ruleAnchors(companyId)).hasSize(3);
        assertThat(lineKeys(companyId)).hasSize(7);
    }

    @Test
    @DisplayName("a company keeps an account it already had and receives only the rest")
    void aPreExistingAccountIsKept() {
        var companyId = companyId();
        var preExistingId = UUID.randomUUID();
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO account (id, company_id, code, name, kind, enabled) VALUES (?, ?, '1101', ?, 'ASSET', true)",
                        preExistingId,
                        companyId,
                        "Caja chica"));

        var inserted = applyTo(companyId);

        assertThat(inserted)
                .as("only the skipped account is missing from the count")
                .isEqualTo(WHOLE_CATALOG_ROWS - 1);
        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT name FROM account WHERE company_id = ? AND code = '1101'",
                                String.class,
                                companyId)))
                .as("the tenant's own row survives: the seed fills a chart, it does not overwrite one")
                .isEqualTo("Caja chica");
        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT a.name FROM posting_rule_line l "
                                        + "JOIN posting_rule r ON r.id = l.rule_id "
                                        + "JOIN account a ON a.id = l.account_id "
                                        + "WHERE l.company_id = ? AND l.side = 'DEBIT' "
                                        + "AND r.posting = 'COLLECTION'",
                                String.class,
                                companyId)))
                .as("and the collection rule's debit line points at the account the company already had, "
                        + "because the id is read back from the tenant's rows and not from the catalog")
                .isEqualTo("Caja chica");
        assertThat(lineKeys(companyId))
                .as("the seven lines are still written, over the accounts that exist")
                .isEqualTo(LINE_KEYS);
    }

    @Test
    @DisplayName("one tenant's seed is not visible from another tenant's scope")
    void anotherTenantDoesNotSeeTheSeed() {
        var seeded = companyId();
        var other = otherCompanyId();
        applyTo(seeded);

        assertThat(TenantFixtures.inTenant(
                        other, () -> jdbcTemplate.queryForObject("SELECT count(*) FROM account", Integer.class)))
                .as("the other tenant has no accounts of its own, and the forced policy is what proves it "
                        + "rather than the seed being absent")
                .isZero();
        assertThat(TenantFixtures.inTenant(
                        other,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM account WHERE company_id = ?", Integer.class, seeded)))
                .as("naming the first tenant's id from the second scope resolves to no row")
                .isZero();

        assertThat(TenantFixtures.inTenant(
                        other,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM posting_rule_line WHERE company_id = ?", Integer.class, seeded)))
                .as("the other tenant names the first one's lines and resolves to no row: the column and the "
                        + "forced policy are one fact, which is why S1 makes the line state its own company")
                .isZero();
        assertThat(TenantFixtures.inTenant(
                        seeded,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM posting_rule_line WHERE company_id = ?", Integer.class, seeded)))
                .as("while the seeded tenant reads its own seven")
                .isEqualTo(7);

        applyTo(other);

        assertThat(accountCodes(other)).as("its own eight rows").isEqualTo(ACCOUNT_CODES);
        assertThat(lineKeys(other))
                .as("and its own seven lines, resolved against its own accounts")
                .isEqualTo(LINE_KEYS);
        assertThat(accountCodes(seeded))
                .as("and the first tenant's book is untouched")
                .isEqualTo(ACCOUNT_CODES);
        assertThat(TenantFixtures.inTenant(
                        seeded,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM account a JOIN account b ON b.company_id = ? "
                                        + "WHERE a.company_id = ?",
                                Integer.class,
                                other,
                                seeded)))
                .as("the two charts share no row: a policy that only filtered one direction would show here")
                .isZero();
    }

    @Test
    @DisplayName("refuses a company id that is not a company")
    void unknownCompanyIsRejected() {
        var unknown = UUID.randomUUID();

        assertThat(catchThrowable(() -> applyTo(unknown)))
                .as("a system hook with a stale id has to fail loudly, not silently seed nothing")
                .isInstanceOf(CompanyNotFoundException.class);
    }

    // --- fixtures ---

    private UUID companyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository, COMPANY_KEY, "Starter Accounting Catalog Company", COMPANY_RFC)
                .getId();
    }

    private UUID otherCompanyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository, OTHER_COMPANY_KEY, "Starter Accounting Other Company", OTHER_COMPANY_RFC)
                .getId();
    }

    // --- reads, each inside its own tenant ---

    private List<String> accountCodes(UUID companyId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT code FROM account WHERE company_id = ? ORDER BY code", String.class, companyId));
    }

    private String kindOf(UUID companyId, String code) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT kind FROM account WHERE company_id = ? AND code = ?", String.class, companyId, code));
    }

    private List<String> ruleAnchors(UUID companyId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT source_type || ':' || posting FROM posting_rule WHERE company_id = ? "
                                + "ORDER BY source_type",
                        String.class,
                        companyId));
    }

    private Integer linesOfPosting(UUID companyId, String posting) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM posting_rule_line l JOIN posting_rule r "
                                + "ON r.company_id = l.company_id AND r.id = l.rule_id "
                                + "WHERE l.company_id = ? AND r.posting = ?",
                        Integer.class,
                        companyId,
                        posting));
    }

    private List<String> lineKeys(UUID companyId) {
        return TenantFixtures.inTenant(companyId, () -> jdbcTemplate.queryForList("""
                        SELECT r.source_type || ':' || r.posting || ':' || l.side || ':' || l.amount_selector
                               || ':' || a.code
                          FROM posting_rule_line l
                          JOIN posting_rule r ON r.company_id = l.company_id AND r.id = l.rule_id
                          JOIN account a ON a.company_id = l.company_id AND a.id = l.account_id
                         WHERE l.company_id = ?
                         ORDER BY r.source_type, l.side, l.amount_selector
                        """, String.class, companyId));
    }

    /**
     * The applier called the way its weakest production caller calls it: nothing stated on the thread, so
     * the applier's own {@link TenantScope} statement is the only reason the write reaches a row.
     */
    private int applyTo(UUID companyId) {
        assertThat(TenantScope.current())
                .as("this suite runs the applier the way a caller with no scope runs it")
                .isEmpty();
        return applier.applyTo(companyId);
    }
}
