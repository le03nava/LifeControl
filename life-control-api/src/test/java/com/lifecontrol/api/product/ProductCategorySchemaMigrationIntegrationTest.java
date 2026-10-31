package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence-level verification of {@code V31__product_categories.sql} against real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves the product category tree
 * landed exactly as the migration declares it.
 *
 * <p>The service layer cannot prove these guarantees: they are enforced by the database itself, and
 * the service only mirrors them. That split is the whole point of the migration header. Tenancy is a
 * composite foreign key ({@code fk_product_categories_parent_same_company}) rather than a service
 * check, so a future service change cannot create a category tree that leaks across tenants. Sibling
 * uniqueness is an <b>expression</b> index ({@code uq_product_categories_sibling_name}), not a plain
 * {@code UNIQUE (company_id, parent_category_id, category_name)}, because {@code NULL <> NULL} would
 * let two identically named ROOTS coexist — the case asserted here explicitly. The trivial cycle is a
 * {@code CHECK} ({@code ck_product_categories_not_self_parent}); the deep cycle and the 3-level
 * ceiling are service rules and are covered by the service unit test suite.</p>
 */
@SpringBootTest
@DisplayName("Product Category Schema Integration Tests")
class ProductCategorySchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String CATEGORY_COMPANY_KEY = "PRODUCT-CATEGORY-SCHEMA-KEY";
    private static final String CATEGORY_COMPANY_RFC = "PCSA010101ABC";
    private static final String OTHER_COMPANY_KEY = "PRODUCT-CATEGORY-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "PCSB010101ABC";

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @BeforeEach
    void resetProductCategoryTable() {
        // The container is shared per JVM, so each method starts from an empty tree to keep the
        // row-count assertions deterministic. `product_categories` references itself through
        // `fk_product_categories_parent_same_company`, so a parent cannot be deleted while a child
        // survives: every non-root row is removed first, which drops all parent references at once
        // (a grandchild and its parent go in the same statement). Only then are the surviving roots
        // detached and the table emptied. The detach is kept because deleting a parent before its
        // children is exactly the ordering trap this reset has to avoid.
        resetCategories(categoryCompanyId());
        resetCategories(otherCompanyId());
    }

    /**
     * The three statements of the reset, run once per tenant this class owns.
     *
     * <p>Per tenant and not table-wide, because an unscoped {@code DELETE} on an armed table matches no
     * row: the reset would clear nothing and every method would count the previous one's fixtures.</p>
     */
    private void resetCategories(UUID companyId) {
        TenantFixtures.inTenant(companyId, () -> {
            jdbcTemplate.update("DELETE FROM product_categories WHERE parent_category_id IS NOT NULL");
            jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL");
            jdbcTemplate.update("DELETE FROM product_categories");
        });
    }

    @Test
    @DisplayName("applies every migration on the classpath and leaves no pending migration")
    void flywayHeadIsTheLatestMigration() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(latestMigrationVersion());
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    @DisplayName("creates the product_categories table")
    void productCategoriesTableExists() {
        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'product_categories'
                """, Integer.class);

        assertThat(count).as("product_categories exists").isEqualTo(1);
    }

    @Test
    @DisplayName("fk_product_categories_parent_same_company rejects a parent from another company")
    void crossCompanyParentIsRejectedByCompositeForeignKey() {
        var ownCompanyId = categoryCompanyId();
        var otherCompanyId = otherCompanyId();
        var foreignParentId = insertCategory(otherCompanyId, "Bebidas");

        assertViolatesConstraint(
                ownCompanyId,
                "fk_product_categories_parent_same_company",
                """
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Refrescos')
                """,
                UUID.randomUUID(),
                ownCompanyId,
                foreignParentId);

        assertThat(count("product_categories", ownCompanyId, otherCompanyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("uq_product_categories_sibling_name rejects two ROOTs whose names differ only by case")
    void rootSiblingNameIsUniqueCaseInsensitively() {
        // The regression a plain UNIQUE (company_id, parent_category_id, category_name) would allow:
        // both rows have a NULL parent, and NULL <> NULL in SQL, so the constraint would be silently
        // satisfied. The COALESCE expression index turns the missing parent into a value.
        var companyId = categoryCompanyId();
        insertCategory(companyId, "Bebidas");

        assertViolatesConstraint(companyId, "uq_product_categories_sibling_name", """
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, 'bebidas')
                """, UUID.randomUUID(), companyId);

        assertThat(count("product_categories", companyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("uq_product_categories_sibling_name allows the same name under different parents and companies")
    void sameNameUnderDifferentParentsAndCompaniesIsAccepted() {
        var companyId = categoryCompanyId();
        var otherCompanyId = otherCompanyId();
        var firstParentId = insertCategory(companyId, "Alimentos");
        var secondParentId = insertCategory(companyId, "Limpieza");

        var underFirstParent = TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Bebidas')
                """, UUID.randomUUID(), companyId, firstParentId));
        var underSecondParent = TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Bebidas')
                """, UUID.randomUUID(), companyId, secondParentId));
        var inOtherCompany = TenantFixtures.inTenant(
                otherCompanyId, () -> jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, 'Bebidas')
                """, UUID.randomUUID(), otherCompanyId));

        assertThat(underFirstParent)
                .as("same name under a different parent is accepted")
                .isEqualTo(1);
        assertThat(underSecondParent)
                .as("same name under a different parent is accepted")
                .isEqualTo(1);
        assertThat(inOtherCompany)
                .as("same name in a different company is accepted")
                .isEqualTo(1);
        assertThat(count("product_categories", companyId, otherCompanyId)).isEqualTo(5);
    }

    @Test
    @DisplayName("ck_product_categories_not_self_parent rejects a category whose parent is itself")
    void selfParentIsRejectedByCheckConstraint() {
        var companyId = categoryCompanyId();
        var categoryId = UUID.randomUUID();

        assertViolatesConstraint(
                companyId, "ck_product_categories_not_self_parent", """
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Bucle')
                """, categoryId, companyId, categoryId);

        assertThat(count("product_categories", companyId)).isZero();
    }

    @Test
    @DisplayName("a same-company parent is accepted: the composite FK is not over-strict")
    void sameCompanyParentIsAccepted() {
        var companyId = categoryCompanyId();
        var parentId = insertCategory(companyId, "Bebidas");

        var child = TenantFixtures.inTenant(
                companyId, () -> jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Refrescos')
                """, UUID.randomUUID(), companyId, parentId));

        assertThat(child).as("the tenancy FK admits the legal half of the rule").isEqualTo(1);
        assertThat(count("product_categories", companyId)).isEqualTo(2);
    }

    @Test
    @DisplayName("adds an optional sat_code column of eight characters")
    void satCodeColumnIsNullableAndEightCharactersLong() {
        var satCode = jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'product_categories'
                  AND column_name = 'sat_code'
                """);

        assertThat(satCode.get("data_type")).as("sat_code type").isEqualTo("character varying");
        assertThat(satCode.get("character_maximum_length"))
                .as("sat_code length")
                .isEqualTo(8);
        assertThat(satCode.get("is_nullable"))
                .as("a category may exist before its SAT key is known")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("ck_product_categories_sat_code_format accepts NULL and an eight-digit key")
    void satCodeFormatAcceptsNullAndEightDigits() {
        var companyId = categoryCompanyId();

        var withKeyId = insertCategoryWithSatCode(companyId, "Con Clave", "27110000");
        var withoutKeyId = insertCategoryWithSatCode(companyId, "Sin Clave", null);

        assertThat(count("product_categories", companyId))
                .as("an absent SAT key is legal next to a present one")
                .isEqualTo(2);
        assertThat(categorySatCode(companyId, withKeyId)).isEqualTo("27110000");
        assertThat(categorySatCode(companyId, withoutKeyId)).isNull();
    }

    @Test
    @DisplayName("ck_product_categories_sat_code_format rejects a key that is not eight digits")
    void satCodeFormatRejectsMalformedKeys() {
        var companyId = categoryCompanyId();

        assertViolatesConstraint(companyId, "ck_product_categories_sat_code_format", """
                INSERT INTO product_categories (id, company_id, category_name, sat_code)
                VALUES (?, ?, 'Corta', '2711000')
                """, UUID.randomUUID(), companyId);

        assertViolatesConstraint(companyId, "ck_product_categories_sat_code_format", """
                INSERT INTO product_categories (id, company_id, category_name, sat_code)
                VALUES (?, ?, 'Con Letras', 'ABCDEFGH')
                """, UUID.randomUUID(), companyId);

        assertThat(count("product_categories", companyId)).isZero();
    }

    @Test
    @DisplayName("two categories of one company may share a SAT key: the code is not unique")
    void satCodeIsNotUnique() {
        var companyId = categoryCompanyId();
        var rootId = insertCategoryWithSatCode(companyId, "Calzado para Dama", "53110000");
        var childId = insertCategoryWithSatCode(companyId, "Botas y Botines", "53110000");
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "UPDATE product_categories SET parent_category_id = ? WHERE id = ?", rootId, childId));

        var rowsWithKey = TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM product_categories WHERE company_id = ? AND sat_code = '53110000'",
                        Integer.class,
                        companyId));

        assertThat(rowsWithKey)
                .as("a segment key legitimately appears on the parent and on its children")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("product_categories carries no version column: a catalog has no 412 precondition")
    void productCategoriesHasNoVersionColumn() {
        var versionColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'product_categories'
                  AND column_name = 'version'
                """, Integer.class);

        assertThat(versionColumns).isZero();
    }

    // ──────────────────────────────────────
    // V36 — products.category_id, as V40 left it
    // ──────────────────────────────────────
    //
    // V36 added a plain FK because a product had no `company_id` then; V40 replaced it with the
    // composite `(company_id, category_id) -> product_categories (company_id, id)`. The nullable
    // column V36 introduced is what survives unchanged — a product nobody classified is still a
    // legal row — and the assertions below name the constraint the database has at head, so this
    // suite keeps measuring the classification link instead of the migration that once carried it.

    @Test
    @DisplayName("products.category_id stays nullable and is guarded by fk_products_category_same_company")
    void productsCarryANullableCategoryForeignKey() {
        var nullable = jdbcTemplate.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'products' AND column_name = 'category_id'
                """, String.class);
        var foreignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'products'
                  AND constraint_name = 'fk_products_category_same_company'
                  AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);

        assertThat(nullable).as("an unclassified product is a legal row").isEqualTo("YES");
        assertThat(foreignKeys).as("fk_products_category_same_company exists").isEqualTo(1);
    }

    @Test
    @DisplayName("fk_products_category_same_company rejects a category id that does not exist")
    void productCategoryForeignKeyRejectsAnUnknownId() {
        assertViolatesConstraint(
                categoryCompanyId(),
                "fk_products_category_same_company",
                """
                INSERT INTO products (id, sku, name, company_id, category_id)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                "PCSL-UNKNOWN",
                "Unknown category product",
                categoryCompanyId(),
                UUID.randomUUID());
    }

    @Test
    @DisplayName("any level is a legal classification: a root, a child and no category are all accepted")
    void anyLevelIsALegalClassification() {
        var companyId = categoryCompanyId();
        var rootId = insertCategory(companyId, "Calzado");
        var childId = insertCategory(companyId, "Botas");
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "UPDATE product_categories SET parent_category_id = ? WHERE id = ?", rootId, childId));

        try {
            var unclassified = insertProduct(companyId, "PCSL-NONE", null);
            var onRoot = insertProduct(companyId, "PCSL-ROOT", rootId);
            var onChild = insertProduct(companyId, "PCSL-CHILD", childId);

            assertThat(unclassified).isEqualTo(1);
            assertThat(onRoot)
                    .as("a root with no children is a complete classification")
                    .isEqualTo(1);
            assertThat(onChild)
                    .as("a deeper node is stored instead of its ancestors")
                    .isEqualTo(1);
        } finally {
            deleteSeedProducts();
        }
    }

    @Test
    @DisplayName("a category with a classified product cannot be hard-deleted: NO ACTION guards the link")
    void referencedCategoryCannotBeHardDeleted() {
        var companyId = categoryCompanyId();
        var rootId = insertCategory(companyId, "Alimentos");

        try {
            insertProduct(companyId, "PCSL-REFERENCED", rootId);

            // The API soft-deletes (`enabled = false`), so this path exists only for a direct SQL
            // delete — which is exactly what the default NO ACTION has to refuse.
            assertViolatesConstraint(
                    companyId,
                    "fk_products_category_same_company",
                    "DELETE FROM product_categories WHERE id = ?",
                    rootId);
        } finally {
            deleteSeedProducts();
        }
    }

    /** Inserts a minimal product row; returns the affected-row count so the caller can assert it. */
    private int insertProduct(UUID companyId, String sku, UUID categoryId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update("""
                INSERT INTO products (id, sku, name, company_id, category_id)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sku, "Seed product " + sku, companyId, categoryId));
    }

    /**
     * Removes only the rows these tests create, by SKU prefix.
     *
     * <p>{@code products} outlives the category reset, and a leftover row holding a {@code category_id}
     * would make the next method's {@code DELETE FROM product_categories} fail on the foreign key. The
     * prefix keeps the sweep from touching fixtures other suites own.</p>
     */
    private void deleteSeedProducts() {
        TenantFixtures.inTenant(
                categoryCompanyId(), () -> jdbcTemplate.update("DELETE FROM products WHERE sku LIKE 'PCSL-%'"));
    }

    /**
     * Find-or-create the company the category rows hang off, mirroring the idempotent fixture style of
     * {@code HrSchemaMigrationIntegrationTest}. Reuses the persisted entity path instead of
     * hand-writing an insert into {@code companies}.
     */
    private UUID categoryCompanyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository,
                        CATEGORY_COMPANY_KEY,
                        "Product Category Schema Test Company",
                        CATEGORY_COMPANY_RFC)
                .getId();
    }

    /** Second tenant, so a name or a parent can be proven unique <em>per company</em>. */
    private UUID otherCompanyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository, OTHER_COMPANY_KEY, "Product Category Other Company", OTHER_COMPANY_RFC)
                .getId();
    }

    private UUID insertCategory(UUID companyId, String name) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, ?)
                """, id, companyId, name));
        return id;
    }

    private UUID insertCategoryWithSatCode(UUID companyId, String name, String satCode) {
        var id = UUID.randomUUID();
        TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name, sat_code)
                VALUES (?, ?, ?, ?)
                """, id, companyId, name, satCode));
        return id;
    }

    /** The row's SAT key, read inside the tenant whose policy guards it. */
    private String categorySatCode(UUID companyId, UUID categoryId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT sat_code FROM product_categories WHERE id = ?", String.class, categoryId));
    }

    /**
     * How many rows {@code table} holds for the given tenants together.
     *
     * <p>One count per tenant named, summed, and not a single {@code COUNT(*)}: the policy filters the
     * table to the scope the connection stated, so an unscoped count answers zero. The statement therefore
     * names the tenant in its own predicate instead of relying on the scope alone — that is the shape that
     * answers the same question on both pools, the superuser one the default run uses (where the scope is
     * inert and a filtered count still counts once) and the application one the gate will use.</p>
     */
    private int count(String table, UUID... companyIds) {
        var total = 0;
        for (var companyId : companyIds) {
            total += TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE company_id = ?", Integer.class, companyId));
        }
        return total;
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint. The most specific cause carries the {@code PSQLException} message.
     *
     * <p>The write runs inside {@code companyId}'s tenant: the scope is what the row names, so the policy
     * admits the write and the constraint under test is the rule that refuses it.</p>
     */
    private void assertViolatesConstraint(UUID companyId, String constraintName, String sql, Object... args) {
        assertThatThrownBy(() -> TenantFixtures.inTenant(companyId, () -> jdbcTemplate.update(sql, args)))
                .as("write rejected by %s", constraintName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(((DataIntegrityViolationException) thrown)
                                .getMostSpecificCause()
                                .getMessage())
                        .as("PostgreSQL reports the violated constraint")
                        .contains(constraintName));
    }
}
