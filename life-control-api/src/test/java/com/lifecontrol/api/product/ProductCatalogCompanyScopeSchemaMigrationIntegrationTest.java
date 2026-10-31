package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.repository.CompanyRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of {@code V40__product_catalog_company_contract.sql} against real
 * PostgreSQL with Flyway enabled and {@code ddl-auto=validate}, so a green run proves the catalog's
 * tenancy landed exactly as the migration declares it.
 *
 * <p>That the database enforces these rules and not only the service is the whole point: the unit
 * moved {@code /api/products} to {@code /api/companies/{companyId}/products} and taught
 * {@code ProductService} and {@code ProductVariantService} to resolve the owner, and a service check
 * is one refactor away from being forgotten. What is asserted here is the shape underneath it —
 * {@code company_id} is {@code NOT NULL} on both tables, {@code (company_id, id)} is unique on both
 * (the composite-FK targets), SKU and barcode are unique <em>per company</em> instead of globally, a
 * product can only be classified under a category of its own company
 * ({@code fk_products_category_same_company}) and a variant can only belong to a product of its own
 * company ({@code fk_product_variants_product_same_company}). Each of those was a cross-tenant hole
 * before V40 and none of them is reachable through the API any more — but the API is not the only
 * writer, and migrating a database by hand is not the only way to reach the tables.</p>
 *
 * <p>What the schema still cannot express is stated there and not here: a variant of company A
 * stocked in a store of company B stays a service rule, because {@code company_stores} carries no
 * {@code company_id} (V1:127-136 derives it through zone → region → country).</p>
 */
@SpringBootTest
@DisplayName("Product Catalog Company Scope Schema Integration Tests")
class ProductCatalogCompanyScopeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CATALOG_COMPANY_KEY = "PRODUCT-CATALOG-SCHEMA-KEY";
    private static final String CATALOG_COMPANY_RFC = "PCSC010101ABC";
    private static final String OTHER_COMPANY_KEY = "PRODUCT-CATALOG-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "PCSD010101ABC";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @BeforeEach
    void resetCatalogTables() {
        deleteOwnRows();
    }

    @AfterEach
    void leaveNoRowsBehind() {
        // The same sweep on the way out: a suite that leaves products behind makes the next class'
        // unscoped count measure this one, and the shared container is exactly where that happens.
        deleteOwnRows();
    }

    /** Deletes the rows this suite owns, leaf-first over the constraints it can trip. */
    private void deleteOwnRows() {
        // The container is shared per JVM, so each method starts and ends on the rows this suite owns
        // and never leaves one for the next class to count. The `PCS-` prefix keeps the sweep away from
        // fixtures other suites build (`PCSL-` is the category suite's, and it does not match), and the
        // variants go first because `fk_product_variants_product_same_company` holds the product in
        // place. Every row count below is therefore scoped to the prefix as well: `SELECT COUNT(*)
        // FROM products` is an assertion about whatever ran before this class.
        jdbcTemplate.update("""
                DELETE FROM product_variants WHERE product_id IN (SELECT id FROM products WHERE sku LIKE 'PCS-%')
                """);
        jdbcTemplate.update("DELETE FROM products WHERE sku LIKE 'PCS-%'");
        jdbcTemplate.update("DELETE FROM product_categories WHERE category_name LIKE 'PCS %'");
    }

    @Test
    @DisplayName("products.company_id is NOT NULL: an unattributable product cannot be stored")
    void productWithoutCompanyIsRejected() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO products (id, sku, name) VALUES (?, 'PCS-NO-COMPANY', 'Unattributable')
                        """, UUID.randomUUID()))
                .as("a product nobody can attribute has no company and no owner")
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .contains("company_id")
                        .contains("not-null"));

        assertThat(countProducts()).isZero();
    }

    @Test
    @DisplayName("product_variants.company_id is NOT NULL: a variant cannot lose the company of its product")
    void variantWithoutCompanyIsRejected() {
        var companyId = catalogCompanyId();
        var productId = insertProduct(companyId, "PCS-VARIANT-NO-COMPANY", null);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO product_variants (id, product_id, bar_code, variant_name)
                        VALUES (?, ?, 'PCS-BC-NO-COMPANY', '26')
                        """, UUID.randomUUID(), productId))
                .as("the barcode key is per company, so the row has to name its company")
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .contains("company_id")
                        .contains("not-null"));

        assertThat(countVariants()).isZero();
    }

    @Test
    @DisplayName("uq_products_company_sku keys the SKU per company, not globally")
    void skuIsUniquePerCompany() {
        var companyId = catalogCompanyId();
        var otherCompanyId = otherCompanyId();

        insertProduct(companyId, "PCS-SHARED-SKU", null);
        var inOtherCompany = jdbcTemplate.update("""
                INSERT INTO products (id, sku, name, company_id) VALUES (?, 'PCS-SHARED-SKU', 'Same SKU elsewhere', ?)
                """, UUID.randomUUID(), otherCompanyId);

        assertThat(inOtherCompany)
                .as("two businesses legitimately sell the same SKU")
                .isEqualTo(1);

        assertViolatesConstraint("uq_products_company_sku", """
                INSERT INTO products (id, sku, name, company_id) VALUES (?, 'PCS-SHARED-SKU', 'Twice', ?)
                """, UUID.randomUUID(), companyId);

        assertThat(countProducts()).isEqualTo(2);
    }

    @Test
    @DisplayName("uq_product_variants_company_bar_code keys the barcode per company, not globally")
    void barCodeIsUniquePerCompany() {
        var companyId = catalogCompanyId();
        var otherCompanyId = otherCompanyId();
        var productId = insertProduct(companyId, "PCS-BC-OWN", null);
        var otherProductId = insertProduct(otherCompanyId, "PCS-BC-OTHER", null);

        insertVariant(companyId, productId, "PCS-BC-SHARED", "26");
        var inOtherCompany = jdbcTemplate.update("""
                INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name)
                VALUES (?, ?, ?, 'PCS-BC-SHARED', '26')
                """, UUID.randomUUID(), otherCompanyId, otherProductId);

        assertThat(inOtherCompany)
                .as("the same physical product in two businesses is two catalogs")
                .isEqualTo(1);

        assertViolatesConstraint("uq_product_variants_company_bar_code", """
                INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name)
                VALUES (?, ?, ?, 'PCS-BC-SHARED', '27')
                """, UUID.randomUUID(), companyId, productId);

        assertThat(countVariants()).isEqualTo(2);
    }

    @Test
    @DisplayName("fk_products_category_same_company rejects a category that belongs to another company")
    void crossCompanyCategoryIsRejected() {
        var companyId = catalogCompanyId();
        var otherCompanyId = otherCompanyId();
        var foreignCategoryId = insertCategory(otherCompanyId, "PCS Bebidas");

        assertViolatesConstraint(
                "fk_products_category_same_company", """
                INSERT INTO products (id, sku, name, company_id, category_id) VALUES (?, 'PCS-CROSS-CAT', 'Cross', ?, ?)
                """, UUID.randomUUID(), companyId, foreignCategoryId);

        assertThat(countProducts()).isZero();
    }

    @Test
    @DisplayName("an unclassified product still skips the category check: MATCH SIMPLE keeps the row legal")
    void unclassifiedProductIsAccepted() {
        var companyId = catalogCompanyId();
        var ownCategoryId = insertCategory(companyId, "PCS Ferretería");

        var unclassified = insertProduct(companyId, "PCS-UNCLASSIFIED", null);
        var classified = insertProduct(companyId, "PCS-CLASSIFIED", ownCategoryId);

        assertThat(unclassified).as("classification is optional").isNotNull();
        assertThat(classified).as("a same-company category is accepted").isNotNull();
        assertThat(countProducts()).isEqualTo(2);
    }

    @Test
    @DisplayName("fk_product_variants_product_same_company rejects a variant whose company is not its product's")
    void crossCompanyVariantIsRejected() {
        var companyId = catalogCompanyId();
        var otherCompanyId = otherCompanyId();
        var productId = insertProduct(companyId, "PCS-VARIANT-OWNER", null);

        assertViolatesConstraint(
                "fk_product_variants_product_same_company", """
                INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name)
                VALUES (?, ?, ?, 'PCS-BC-CROSS', '26')
                """, UUID.randomUUID(), otherCompanyId, productId);

        assertThat(countVariants()).isZero();
    }

    @Test
    @DisplayName("the global unique keys and the plain category FK are gone from the contract")
    void globalKeysAreReplaced() {
        var constraints = jdbcTemplate.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name IN ('products', 'product_variants')
                """, String.class);

        assertThat(constraints)
                .as("the composite-FK targets and the per-company keys")
                .contains(
                        "uq_products_company_id",
                        "uq_product_variants_company_id",
                        "uq_products_company_sku",
                        "uq_product_variants_company_bar_code",
                        "fk_products_category_same_company",
                        "fk_product_variants_product_same_company");
        assertThat(constraints)
                .as("the global keys and the unscoped FKs they replaced")
                .doesNotContain(
                        "products_sku_key",
                        "uq_product_variants_bar_code",
                        "fk_products_category",
                        "product_variants_product_id_fkey");
        assertThat(constraints)
                .as("the per-product name key is untouched by this slice")
                .contains("uq_product_variants_product_variant_name");
    }

    @Test
    @DisplayName("no plain index duplicates the leading column of a company unique key")
    void companyColumnCarriesNoRedundantIndex() {
        List<String> redundant = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN ('idx_products_company_id', 'idx_product_variants_company_id')
                """, String.class);

        assertThat(redundant)
                .as("a unique key whose leading column is company_id already serves the lookup")
                .isEmpty();
    }

    private UUID insertProduct(UUID companyId, String sku, UUID categoryId) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO products (id, sku, name, company_id, category_id) VALUES (?, ?, ?, ?, ?)
                """, id, sku, "Seed product " + sku, companyId, categoryId);
        return id;
    }

    private UUID insertVariant(UUID companyId, UUID productId, String barCode, String variantName) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_variants (id, company_id, product_id, bar_code, variant_name)
                VALUES (?, ?, ?, ?, ?)
                """, id, companyId, productId, barCode, variantName);
        return id;
    }

    private UUID insertCategory(UUID companyId, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name) VALUES (?, ?, ?)
                """, id, companyId, name);
        return id;
    }

    /** Rows this suite owns: an unscoped count would measure whatever ran before this class. */
    private int countProducts() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products WHERE sku LIKE 'PCS-%'", Integer.class);
    }

    /** Rows this suite owns, reached through the product that carries its prefix. */
    private int countVariants() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM product_variants v
                JOIN products p ON p.id = v.product_id
                WHERE p.sku LIKE 'PCS-%'
                """, Integer.class);
    }

    /**
     * Find-or-create the company the catalog rows hang off. Same idempotent fixture style as
     * {@code ProductCategorySchemaMigrationIntegrationTest}: the persisted entity path instead of a
     * hand-written insert into {@code companies}.
     */
    private UUID catalogCompanyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository,
                        CATALOG_COMPANY_KEY,
                        "Product Catalog Schema Test Company",
                        CATALOG_COMPANY_RFC)
                .getId();
    }

    /** Second tenant, so SKU, barcode and the two composite keys can be proven per company. */
    private UUID otherCompanyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository, OTHER_COMPANY_KEY, "Product Catalog Other Company", OTHER_COMPANY_RFC)
                .getId();
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint, so a violation still tells the operator which tenancy rule broke.
     */
    private void assertViolatesConstraint(String constraintName, String sql, Object... args) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql, args))
                .as("write rejected by %s", constraintName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .as("PostgreSQL reports the violated constraint")
                        .contains(constraintName));
    }
}
