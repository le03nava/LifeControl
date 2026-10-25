package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
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
        jdbcTemplate.update("DELETE FROM product_categories WHERE parent_category_id IS NOT NULL");
        jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL");
        jdbcTemplate.update("DELETE FROM product_categories");
    }

    @Test
    @DisplayName("applies every migration through V33 and leaves no pending migration")
    void flywayHeadIsV33() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("33");
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
                "fk_product_categories_parent_same_company", """
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Refrescos')
                """, UUID.randomUUID(), ownCompanyId, foreignParentId);

        assertThat(count("product_categories")).isEqualTo(1);
    }

    @Test
    @DisplayName("uq_product_categories_sibling_name rejects two ROOTs whose names differ only by case")
    void rootSiblingNameIsUniqueCaseInsensitively() {
        // The regression a plain UNIQUE (company_id, parent_category_id, category_name) would allow:
        // both rows have a NULL parent, and NULL <> NULL in SQL, so the constraint would be silently
        // satisfied. The COALESCE expression index turns the missing parent into a value.
        var companyId = categoryCompanyId();
        insertCategory(companyId, "Bebidas");

        assertViolatesConstraint("uq_product_categories_sibling_name", """
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, 'bebidas')
                """, UUID.randomUUID(), companyId);

        assertThat(count("product_categories")).isEqualTo(1);
    }

    @Test
    @DisplayName("uq_product_categories_sibling_name allows the same name under different parents and companies")
    void sameNameUnderDifferentParentsAndCompaniesIsAccepted() {
        var companyId = categoryCompanyId();
        var otherCompanyId = otherCompanyId();
        var firstParentId = insertCategory(companyId, "Alimentos");
        var secondParentId = insertCategory(companyId, "Limpieza");

        var underFirstParent = jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Bebidas')
                """, UUID.randomUUID(), companyId, firstParentId);
        var underSecondParent = jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Bebidas')
                """, UUID.randomUUID(), companyId, secondParentId);
        var inOtherCompany = jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, 'Bebidas')
                """, UUID.randomUUID(), otherCompanyId);

        assertThat(underFirstParent)
                .as("same name under a different parent is accepted")
                .isEqualTo(1);
        assertThat(underSecondParent)
                .as("same name under a different parent is accepted")
                .isEqualTo(1);
        assertThat(inOtherCompany)
                .as("same name in a different company is accepted")
                .isEqualTo(1);
        assertThat(count("product_categories")).isEqualTo(5);
    }

    @Test
    @DisplayName("ck_product_categories_not_self_parent rejects a category whose parent is itself")
    void selfParentIsRejectedByCheckConstraint() {
        var companyId = categoryCompanyId();
        var categoryId = UUID.randomUUID();

        assertViolatesConstraint("ck_product_categories_not_self_parent", """
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Bucle')
                """, categoryId, companyId, categoryId);

        assertThat(count("product_categories")).isZero();
    }

    @Test
    @DisplayName("a same-company parent is accepted: the composite FK is not over-strict")
    void sameCompanyParentIsAccepted() {
        var companyId = categoryCompanyId();
        var parentId = insertCategory(companyId, "Bebidas");

        var child = jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, parent_category_id, category_name)
                VALUES (?, ?, ?, 'Refrescos')
                """, UUID.randomUUID(), companyId, parentId);

        assertThat(child).as("the tenancy FK admits the legal half of the rule").isEqualTo(1);
        assertThat(count("product_categories")).isEqualTo(2);
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

    /**
     * Find-or-create the company the category rows hang off, mirroring the idempotent fixture style of
     * {@code HrSchemaMigrationIntegrationTest}. Reuses the persisted entity path instead of
     * hand-writing an insert into {@code companies}.
     */
    private UUID categoryCompanyId() {
        return companyRepository
                .findByCompanyKey(CATEGORY_COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(CATEGORY_COMPANY_KEY)
                        .companyName("Product Category Schema Test Company")
                        .rfc(CATEGORY_COMPANY_RFC)
                        .enabled(true)
                        .build()))
                .getId();
    }

    /** Second tenant, so a name or a parent can be proven unique <em>per company</em>. */
    private UUID otherCompanyId() {
        return companyRepository
                .findByCompanyKey(OTHER_COMPANY_KEY)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(OTHER_COMPANY_KEY)
                        .companyName("Product Category Other Company")
                        .rfc(OTHER_COMPANY_RFC)
                        .enabled(true)
                        .build()))
                .getId();
    }

    private UUID insertCategory(UUID companyId, String name) {
        var id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, ?)
                """, id, companyId, name);
        return id;
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * Asserts the raw write is rejected by a real PostgreSQL constraint and that the reported error
     * names the exact constraint. The most specific cause carries the {@code PSQLException} message.
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
