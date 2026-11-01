package com.lifecontrol.api.product.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.product.supplier.model.ProductSupplier;
import com.lifecontrol.api.product.supplier.repository.ProductSupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code GET /api/companies/{companyId}/products/by-supplier/{supplierId}} is company-scoped, and the
 * only place that scope can be proved is SQL: a mock can confirm the company reached the call, but not
 * that the predicate kept another company's rows out.
 *
 * <p>A supplier belongs to exactly one company ({@code V42}) and, since {@code V43}, a relation cannot
 * even pair a supplier with a product of another company:
 * {@code fk_product_suppliers_product_same_company} and
 * {@code fk_product_suppliers_supplier_same_company} both key on the relation's own
 * {@code company_id}, so the cross-company relation this fixture used to build is no longer
 * representable. The fixture is rebuilt around that fact instead of around the hole: each company has
 * its <b>own</b> supplier and its own row, and the cases ask for one company's supplier from the other
 * company. That is the question the predicate answers, and a query without it would return the first
 * company's product for the second company's path — which is what makes the empty answer evidence
 * rather than a tautology.</p>
 */
@SpringBootTest
@DisplayName("Product supplier by-company read — integration")
class ProductSupplierCompanyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "pscs";
    private static final String COMPANY_KEY = "PSCS-COMPANY-KEY";
    private static final String OTHER_COMPANY_KEY = "PSCS-OTHER-COMPANY-KEY";
    private static final String SUPPLIER_RFC = "PSCS010101AB1";
    private static final String OTHER_SUPPLIER_RFC = "PSCS010101AB2";

    @Autowired
    private ProductSupplierRepository productSupplierRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID companyId;
    private UUID otherCompanyId;
    private UUID supplierId;
    private UUID otherSupplierId;
    private UUID myProductId;
    private UUID otherProductId;

    @BeforeEach
    void setUp() {
        cleanUp();

        companyId = insertCompany(COMPANY_KEY, "PSCS company", "PSCS010101AA1");
        otherCompanyId = insertCompany(OTHER_COMPANY_KEY, "PSCS other company", "PSCS010101AA2");

        supplierId = insertSupplier(companyId, SUPPLIER_RFC);
        otherSupplierId = insertSupplier(otherCompanyId, OTHER_SUPPLIER_RFC);

        myProductId = insertProduct("Alicate", companyId);
        otherProductId = insertProduct("Martillo", otherCompanyId);

        insertRelation(companyId, myProductId, supplierId);
        insertRelation(otherCompanyId, otherProductId, otherSupplierId);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        // Once per tenant this suite writes: an unscoped DELETE on an armed table matches no row, so the
        // cleanup would leave every fixture behind for the next method to collide with on
        // `companies_company_key_key`.
        cleanUpTenant(TenantFixtures.derivedId(COMPANY_KEY));
        cleanUpTenant(TenantFixtures.derivedId(OTHER_COMPANY_KEY));
    }

    private void cleanUpTenant(UUID tenantId) {
        TenantFixtures.inTenant(tenantId, () -> {
            jdbc.update(
                    "DELETE FROM product_suppliers WHERE product_id IN (SELECT id FROM products WHERE sku LIKE ?)",
                    PREFIX + "%");
            jdbc.update("DELETE FROM products WHERE sku LIKE ?", PREFIX + "%");
            jdbc.update("DELETE FROM suppliers WHERE rfc IN (?, ?)", SUPPLIER_RFC, OTHER_SUPPLIER_RFC);
            jdbc.update("DELETE FROM companies WHERE company_key IN (?, ?)", COMPANY_KEY, OTHER_COMPANY_KEY);
        });
    }

    private UUID insertCompany(String key, String name, String rfc) {
        return TenantFixtures.insertCompany(jdbc, key, name, rfc, TenantFixtures.SEED_CURRENCY);
    }

    private UUID insertProduct(String name, UUID ownerId) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(
                ownerId,
                () -> jdbc.update(
                        "INSERT INTO products (id, sku, name, company_id, enabled) VALUES (?, ?, ?, ?, true)",
                        id,
                        PREFIX + "-" + name.substring(0, 3).toUpperCase() + "-"
                                + UUID.randomUUID().toString().substring(0, 8),
                        name,
                        ownerId));
        return id;
    }

    private UUID insertSupplier(UUID ownerId, String rfc) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(
                ownerId,
                () -> jdbc.update(
                        "INSERT INTO suppliers (id, supplier_name, rfc, company_id, enabled) VALUES (?, ?, ?, ?, true)",
                        id,
                        PREFIX + " supplier " + rfc,
                        rfc,
                        ownerId));
        return id;
    }

    private void insertRelation(UUID ownerId, UUID productId, UUID supplierIdOfRow) {
        TenantFixtures.inTenant(
                ownerId,
                () -> jdbc.update(
                        "INSERT INTO product_suppliers (id, product_id, supplier_id, company_id, main, enabled)"
                                + " VALUES (?, ?, ?, ?, false, true)",
                        UUID.randomUUID(),
                        productId,
                        supplierIdOfRow,
                        ownerId));
    }

    /**
     * The by-supplier read, run the way production runs it: the <b>caller</b> states the tenant, which here
     * is the company the path names.
     *
     * <p>The repository's own transaction is a boundary but states nothing by itself, so with the pool on
     * the application role the query would meet the policy with no scope installed and answer empty — the
     * same shape as an unscoped {@code SELECT}. The assertion is about the query's predicate, and it stays
     * that way: the caller's scope is the fact production supplies and the test supplies the same one.</p>
     */
    private List<ProductSupplier> relationsOf(UUID companyId, UUID supplierIdOfSupplier, String search) {
        return TenantFixtures.inTenant(
                companyId,
                () -> productSupplierRepository.findByCompanyIdAndSupplierIdWithSearch(
                        companyId, supplierIdOfSupplier, search));
    }

    @Test
    @DisplayName("should return only the company's product of a supplier both companies share")
    void returnsOnlyTheCompanysProducts() {
        var mine = relationsOf(companyId, supplierId, null);

        assertThat(mine).extracting(relation -> relation.getProduct().getId()).containsExactly(myProductId);
    }

    @Test
    @DisplayName("should keep the search filter inside the company")
    void keepsTheSearchFilterInsideTheCompany() {
        var hit = relationsOf(companyId, supplierId, "Alic");
        var miss = relationsOf(companyId, supplierId, "Mart");

        assertThat(hit).extracting(relation -> relation.getProduct().getId()).containsExactly(myProductId);
        assertThat(miss).isEmpty();
    }

    @Test
    @DisplayName("should answer empty for a company the supplier does not supply")
    void answersEmptyForACompanyWithoutTheSupplier() {
        // The discriminating half: if the query did not carry the company predicate, this call —
        // company B's path, company A's supplier — would return company A's product.
        var other = relationsOf(otherCompanyId, supplierId, null);

        assertThat(other).isEmpty();

        var owns = relationsOf(otherCompanyId, otherSupplierId, null);

        assertThat(owns).extracting(relation -> relation.getProduct().getId()).containsExactly(otherProductId);
    }
}
