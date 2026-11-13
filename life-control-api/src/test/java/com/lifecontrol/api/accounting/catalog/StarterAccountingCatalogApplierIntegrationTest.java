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
 * applier writes the eight accounts and the three rules, that a second run is a no-op (so the listener's
 * retry, an operator's re-run and a replay all cost nothing), and that what one tenant receives is invisible
 * to another — the three properties a seed per tenant has to hold.</p>
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

    /** The three rules, read as {@code source_type:posting:debit_code:credit_code}. */
    private static final List<String> RULE_KEYS = List.of(
            "SALE_PAYMENT:COLLECTION:1101:1201",
            "STATUS_TRANSITION:REVENUE:1201:4101",
            "STOCK_ALLOCATION:COST_OF_SALES:5101:1301");

    @Autowired
    private StarterAccountingCatalogApplier applier;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Every case starts from an empty catalog for the two companies it uses, because the container is shared
     * per JVM and another method's rows would make the counts non-deterministic. Rules first: they point at
     * accounts, and the foreign key is what refuses the reverse order.
     */
    @BeforeEach
    void emptyBothCatalogs() {
        for (var companyId : List.of(companyId(), otherCompanyId())) {
            TenantFixtures.inTenant(companyId, () -> {
                jdbcTemplate.update("DELETE FROM posting_rule WHERE company_id = ?", companyId);
                jdbcTemplate.update("DELETE FROM account WHERE company_id = ?", companyId);
            });
        }
    }

    @Test
    @DisplayName("seeds the whole catalog: eight accounts and three rules, with their kinds and their pairs")
    void seedsTheWholeCatalog() {
        var companyId = companyId();

        var inserted = applyTo(companyId);

        assertThat(inserted)
                .as("eleven rows: the eight accounts first, then the three rules that name them")
                .isEqualTo(11);
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
        assertThat(ruleKeys(companyId))
                .as("each rule resolves its two accounts by code inside its own tenant")
                .isEqualTo(RULE_KEYS);
    }

    @Test
    @DisplayName("a second run inserts nothing and leaves both counts where the first run put them")
    void aSecondRunIsANoOp() {
        var companyId = companyId();
        assertThat(applyTo(companyId)).isEqualTo(11);

        var insertedAgain = applyTo(companyId);

        assertThat(insertedAgain)
                .as("idempotent by code: the guard is the contract the listener's retry relies on")
                .isZero();
        assertThat(accountCodes(companyId)).hasSize(8);
        assertThat(ruleKeys(companyId)).hasSize(3);
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
                .isEqualTo(10);
        assertThat(TenantFixtures.inTenant(
                        companyId,
                        () -> jdbcTemplate.queryForObject(
                                "SELECT name FROM account WHERE company_id = ? AND code = '1101'",
                                String.class,
                                companyId)))
                .as("the tenant's own row survives: the seed fills a chart, it does not overwrite one")
                .isEqualTo("Caja chica");
        assertThat(ruleKeys(companyId))
                .as("and the rules still resolve, against the account the company already had")
                .contains("SALE_PAYMENT:COLLECTION:1101:1201");
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

        applyTo(other);

        assertThat(accountCodes(other)).as("its own eight rows").isEqualTo(ACCOUNT_CODES);
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

    private List<String> ruleKeys(UUID companyId) {
        return TenantFixtures.inTenant(companyId, () -> jdbcTemplate.queryForList("""
                        SELECT r.source_type || ':' || r.posting || ':' || d.code || ':' || k.code
                          FROM posting_rule r
                          JOIN account d ON d.id = r.debit_account_id
                          JOIN account k ON k.id = r.credit_account_id
                         WHERE r.company_id = ?
                         ORDER BY r.source_type
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
