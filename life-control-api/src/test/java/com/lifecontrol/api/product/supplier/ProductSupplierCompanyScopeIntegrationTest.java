package com.lifecontrol.api.product.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.product.supplier.repository.ProductSupplierRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
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
 * <p>The fixture is the case that makes the rule necessary rather than decorative — one supplier row
 * carrying relations to two companies' products — so a query without the company predicate returns both
 * products and the assertion fails instead of quietly agreeing with itself. A supplier is no longer
 * global: it belongs to exactly one company ({@code V42} makes {@code suppliers.company_id} mandatory and
 * keeps the RFC unique per company), so the row the fixture inserts belongs to the company in the path.
 * The relation to the <b>other</b> company's product is deliberately kept: it is the shape that
 * {@code product_suppliers} still accepts today — the composite key that would refuse it needs a
 * {@code company_id} column on the relation table and is the migration after {@code V42} — and it is
 * exactly why the read must be scoped by the company in the path rather than by the supplier alone.</p>
 */
@SpringBootTest
@DisplayName("Product supplier by-company read — integration")
class ProductSupplierCompanyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PREFIX = "pscs";
    private static final String COMPANY_KEY = "PSCS-COMPANY-KEY";
    private static final String OTHER_COMPANY_KEY = "PSCS-OTHER-COMPANY-KEY";
    private static final String SUPPLIER_RFC = "PSCS010101AB1";

    @Autowired
    private ProductSupplierRepository productSupplierRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID companyId;
    private UUID otherCompanyId;
    private UUID supplierId;
    private UUID myProductId;
    private UUID otherProductId;

    @BeforeEach
    void setUp() {
        cleanUp();

        companyId = insertCompany(COMPANY_KEY, "PSCS company", "PSCS010101AA1");
        otherCompanyId = insertCompany(OTHER_COMPANY_KEY, "PSCS other company", "PSCS010101AA2");

        supplierId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO suppliers (id, supplier_name, rfc, company_id, enabled) VALUES (?, ?, ?, ?, true)",
                supplierId,
                PREFIX + " supplier",
                SUPPLIER_RFC,
                companyId);

        myProductId = insertProduct("Alicate", companyId);
        otherProductId = insertProduct("Martillo", otherCompanyId);

        insertRelation(myProductId);
        insertRelation(otherProductId);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update(
                "DELETE FROM product_suppliers WHERE product_id IN (SELECT id FROM products WHERE sku LIKE ?)",
                PREFIX + "%");
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM suppliers WHERE rfc = ?", SUPPLIER_RFC);
        jdbc.update("DELETE FROM companies WHERE company_key IN (?, ?)", COMPANY_KEY, OTHER_COMPANY_KEY);
    }

    private UUID insertCompany(String key, String name, String rfc) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, rfc, enabled) VALUES (?, ?, ?, ?, true)",
                id,
                key,
                name,
                rfc);
        return id;
    }

    private UUID insertProduct(String name, UUID ownerId) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, sku, name, company_id, enabled) VALUES (?, ?, ?, ?, true)",
                id,
                PREFIX + "-" + name.substring(0, 3).toUpperCase() + "-"
                        + UUID.randomUUID().toString().substring(0, 8),
                name,
                ownerId);
        return id;
    }

    private void insertRelation(UUID productId) {
        jdbc.update(
                "INSERT INTO product_suppliers (id, product_id, supplier_id, main, enabled)"
                        + " VALUES (?, ?, ?, false, true)",
                UUID.randomUUID(),
                productId,
                supplierId);
    }

    @Test
    @DisplayName("should return only the company's product of a supplier both companies share")
    void returnsOnlyTheCompanysProducts() {
        var mine = productSupplierRepository.findByCompanyIdAndSupplierIdWithSearch(companyId, supplierId, null);

        assertThat(mine).extracting(relation -> relation.getProduct().getId()).containsExactly(myProductId);
    }

    @Test
    @DisplayName("should keep the search filter inside the company")
    void keepsTheSearchFilterInsideTheCompany() {
        var hit = productSupplierRepository.findByCompanyIdAndSupplierIdWithSearch(companyId, supplierId, "Alic");
        var miss = productSupplierRepository.findByCompanyIdAndSupplierIdWithSearch(companyId, supplierId, "Mart");

        assertThat(hit).extracting(relation -> relation.getProduct().getId()).containsExactly(myProductId);
        assertThat(miss).isEmpty();
    }

    @Test
    @DisplayName("should answer empty for a company the supplier does not supply")
    void answersEmptyForACompanyWithoutTheSupplier() {
        var other = productSupplierRepository.findByCompanyIdAndSupplierIdWithSearch(otherCompanyId, supplierId, null);

        assertThat(other).extracting(relation -> relation.getProduct().getId()).containsExactly(otherProductId);
    }
}
