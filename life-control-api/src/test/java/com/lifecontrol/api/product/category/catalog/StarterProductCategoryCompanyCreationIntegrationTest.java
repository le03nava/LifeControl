package com.lifecontrol.api.product.category.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The end-to-end half of S4: a company created through the application receives the starter catalog.
 *
 * <p>Why this exists next to the applier's own integration test: that one calls
 * {@link StarterProductCategoryApplier#applyTo(java.util.UUID)} directly, so it proves the catalog is
 * correct but not that anyone ever calls it. If {@code CompanyService} stopped publishing
 * {@link CompanyCreatedEvent}, or the listener stopped firing, or the phase regressed from
 * {@code AFTER_COMMIT} to something the commit never reaches, every other test in the suite would stay
 * green while every new tenant silently started with an empty category tree — the failure mode this
 * whole class exists to convert into a red build.</p>
 *
 * <p>The second case publishes the event with <b>no transaction active</b>, which is what pins the
 * listener's {@code fallbackExecution = true}: without it the seed would be skipped in exactly the
 * situation where nobody would notice until an operator opened the Categories screen. Both cases go
 * through the real Spring context — no mocked repository, no mocked listener.</p>
 *
 * <p><b>Both cases run as the application role, which is the pool's login since T6b-4</b>, and
 * {@code product_categories} is armed by V53. So every statement that touches that table — the count this
 * suite measures, and the three cleanup deletes — runs inside the tenant whose rows it names, through
 * {@link TenantFixtures#inTenant}. That is not a test convenience: it is the same rule the application
 * follows, and without it the count would answer zero and the cleanup would remove nothing while reporting
 * success (record L27).</p>
 */
@SpringBootTest
@DisplayName("Starter Product Category Company Creation Integration Tests")
class StarterProductCategoryCompanyCreationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CREATED_COMPANY_KEY = "STARTER-CATALOG-E2E-CREATE";
    private static final String PUBLISHED_COMPANY_KEY = "STARTER-CATALOG-E2E-PUBLISH";

    /** The tenants this suite committed, so the shared container does not accumulate them. */
    private final List<UUID> createdCompanyIds = new ArrayList<>();

    @Autowired
    private CompanyService companyService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeTheCompaniesThisSuiteCreated() {
        // Leaf-first, for the reason ProductCategorySchemaMigrationIntegrationTest records: a parent cannot
        // be deleted while a child survives. And inside each company's own tenant, because
        // `product_categories` and `companies` are armed: an unscoped DELETE removes zero rows and reports
        // success, which would leave a tenant behind for every other suite to count around.
        for (var createdCompanyId : createdCompanyIds) {
            TenantFixtures.inTenant(createdCompanyId, () -> {
                deleteCatalogOf(createdCompanyId);
                jdbcTemplate.update("DELETE FROM companies WHERE id = ?", createdCompanyId);
            });
        }
        createdCompanyIds.clear();
    }

    @Test
    @DisplayName("creating a company through the service seeds the whole catalog")
    void creatingACompanySeedsTheCatalog() {
        var created = companyService.createCompany(new CompanyRequest(
                CREATED_COMPANY_KEY,
                "Starter Catalog E2E Company",
                2,
                "Starter Catalog E2E SA",
                "SCEE010101ABC",
                null,
                null,
                null,
                true,
                null,
                // Required since V57 and stated here, not defaulted: this case is about the starter
                // catalog, so it picks the one currency the seed declares.
                TenantFixtures.SEED_CURRENCY));
        createdCompanyIds.add(created.id());

        assertThat(countCategories(created.id()))
                .as("CompanyCreatedEvent -> AFTER_COMMIT -> applier, all through the real context")
                .isEqualTo(57);
    }

    @Test
    @DisplayName("the event published with no transaction still seeds: the listener falls back")
    void publishingTheEventOutsideATransactionStillSeeds() {
        var company = TenantFixtures.persistAndFlush(
                companyRepository,
                Company.builder()
                        .companyKey(PUBLISHED_COMPANY_KEY)
                        .companyName("Starter Catalog Published Company")
                        .rfc("SCEP010101ABC")
                        .enabled(true)
                        .currency(TenantFixtures.SEED_CURRENCY)
                        .build());
        createdCompanyIds.add(company.getId());

        // The tenant the event is about, stated around the publish: the applier opens its own
        // REQUIRES_NEW transaction and its boundary reads the holder, while `product_categories` is armed
        // by V53 — so without a scope the seed cannot see the company it was told to seed. That is the
        // state a publisher inside `CompanyService.createCompany` already gives it, because the service
        // states the new company's own id around its transaction and the AFTER_COMMIT listener runs inside
        // that block. What this case keeps pinning is the listener's `fallbackExecution`: no transaction is
        // active here, so nothing else would reach it. A publisher with no scope at all — an operator
        // script, a bulk import — still gets a logged ERROR instead of a seed, and that gap is the
        // applier's to close (reported, not patched here: it is a `src/main` change outside this slice).
        TenantScope.with(company.getId(), () -> {
            eventPublisher.publishEvent(
                    new CompanyCreatedEvent(this, company.getId(), company.getCompanyKey(), company.getCompanyName()));
            return null;
        });

        assertThat(countCategories(company.getId()))
                .as("no transaction is active here, so only fallbackExecution can reach the listener")
                .isEqualTo(57);
    }

    private void deleteCatalogOf(UUID companyId) {
        jdbcTemplate.update(
                "DELETE FROM product_categories WHERE parent_category_id IS NOT NULL AND company_id = ?", companyId);
        jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL WHERE company_id = ?", companyId);
        jdbcTemplate.update("DELETE FROM product_categories WHERE company_id = ?", companyId);
    }

    /**
     * The catalog the creation flow seeded, counted under its own tenant: {@code product_categories} is
     * armed, so a read with no tenant stated answers zero for the starter catalog of any company.
     */
    private int countCategories(UUID companyId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM product_categories WHERE company_id = ?", Integer.class, companyId));
    }
}
