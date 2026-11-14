package com.lifecontrol.api.accounting.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.service.CompanyService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The end-to-end half of S9: <b>a company created through the application arrives with its book</b>.
 *
 * <p>Why this exists next to the applier's own integration test: that one calls
 * {@link StarterAccountingCatalogApplier#applyTo(java.util.UUID)} directly, so it proves the seed is correct
 * but not that anything calls it. If {@code CompanyService} stopped publishing
 * {@code CompanyCreatedEvent}, or the listener stopped firing, or its phase regressed from {@code AFTER_COMMIT}
 * to something the commit never reaches, every other test in the suite would stay green while every new
 * tenant silently started with no chart of accounts — the failure mode S9's "a tenant added tomorrow does not
 * arrive without a book" exists to refuse, and the one nobody notices until a posting has no account to name.
 * Only a company created through the real service and the real Spring context can observe it.</p>
 *
 * <p><b>Every read and every cleanup delete runs inside the created company's own tenant</b>, because
 * {@code account} and {@code posting_rule} are armed with {@code ENABLE} + {@code FORCE} by {@code V97}. An
 * unscoped count would answer zero and turn this suite into a test of nothing, and an unscoped delete would
 * remove nothing and report success.</p>
 */
@SpringBootTest
@DisplayName("Starter Accounting Catalog Company Creation Integration Tests")
class StarterAccountingCatalogCompanyCreationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "STARTER-ACCOUNTING-E2E";
    private static final String COMPANY_RFC = "SAE010101AB1";

    private static final List<String> ACCOUNT_CODES =
            List.of("1101", "1102", "1201", "1202", "1301", "2101", "4101", "5101");

    /** The seven lines of the three rules, read as {@code source_type:posting:side:amount_selector:code}. */
    private static final List<String> LINE_KEYS = List.of(
            "SALE_PAYMENT:COLLECTION:CREDIT:SOURCE_AMOUNT:1201",
            "SALE_PAYMENT:COLLECTION:DEBIT:SOURCE_AMOUNT:1101",
            "STATUS_TRANSITION:REVENUE:CREDIT:REVENUE:4101",
            "STATUS_TRANSITION:REVENUE:CREDIT:TAX:2101",
            "STATUS_TRANSITION:REVENUE:DEBIT:TOTAL:1201",
            "STOCK_ALLOCATION:COST_OF_SALES:CREDIT:ALLOCATED_COST:1301",
            "STOCK_ALLOCATION:COST_OF_SALES:DEBIT:ALLOCATED_COST:5101");

    /** The tenants this suite committed, so the shared container does not accumulate them. */
    private final List<UUID> createdCompanyIds = new ArrayList<>();

    @Autowired
    private CompanyService companyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeTheCompaniesThisSuiteCreated() {
        // Lines first, then the rules they belong to, then the accounts those lines name. Then the company, all
        // inside its own tenant, because an unscoped DELETE on an armed table removes nothing and would leave a
        // tenant behind for every other suite to count around. The product category tree goes first of all:
        // creating a company through the service also seeds it (the sibling listener), and
        // `product_categories_company_id_fkey` is what refuses the company delete otherwise.
        for (var createdCompanyId : createdCompanyIds) {
            TenantFixtures.inTenant(createdCompanyId, () -> {
                jdbcTemplate.update(
                        "DELETE FROM product_categories WHERE parent_category_id IS NOT NULL AND company_id = ?",
                        createdCompanyId);
                jdbcTemplate.update(
                        "UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?",
                        createdCompanyId);
                jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", createdCompanyId);
                jdbcTemplate.update("DELETE FROM posting_rule_line WHERE company_id = ?", createdCompanyId);
                jdbcTemplate.update("DELETE FROM posting_rule WHERE company_id = ?", createdCompanyId);
                jdbcTemplate.update("DELETE FROM account WHERE company_id = ?", createdCompanyId);
                jdbcTemplate.update("DELETE FROM companies WHERE id = ?", createdCompanyId);
            });
        }
        createdCompanyIds.clear();
    }

    @Test
    @DisplayName("creating a company through the service seeds the chart of accounts and the three rules")
    void creatingACompanySeedsTheBook() {
        var created = companyService.createCompany(new CompanyRequest(
                COMPANY_KEY,
                "Starter Accounting E2E Company",
                2,
                "Starter Accounting E2E SA",
                COMPANY_RFC,
                null,
                null,
                null,
                true,
                null,
                // Required since V57 and stated here, not defaulted: this case is about the book, so it
                // picks the one currency the seed declares.
                TenantFixtures.SEED_CURRENCY));
        createdCompanyIds.add(created.id());

        assertThat(accountCodes(created.id()))
                .as("CompanyCreatedEvent -> BEFORE_COMMIT -> applier, all through the real context")
                .isEqualTo(ACCOUNT_CODES);
        assertThat(lineKeys(created.id()))
                .as("and the three anchor rules with their 3/2/2 lines, each line naming its side, its "
                        + "selector and its account by code")
                .isEqualTo(LINE_KEYS);
        assertThat(TenantFixtures.inTenant(
                        created.id(),
                        () -> jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM account WHERE company_id = ? AND enabled",
                                Integer.class,
                                created.id())))
                .as("a tenant added tomorrow arrives with an enabled book, not an empty one")
                .isEqualTo(8);
    }

    private List<String> accountCodes(UUID companyId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForList(
                        "SELECT code FROM account WHERE company_id = ? ORDER BY code", String.class, companyId));
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
}
