package com.lifecontrol.api.product.category.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verification of {@link StarterProductCategoryApplier} against real PostgreSQL, so the assertions are
 * the rows a company actually receives and not what the applier believes it inserted.
 *
 * <p>The applier is the half of the starter catalog that V35 cannot reach — a company created after the
 * migration — so what matters here is that it writes the same 57 rows the migration writes, that its
 * per-sibling guard makes a second run a no-op instead of a violation of
 * {@code uq_product_categories_sibling_name}, and that a root the company already had is reused as the
 * parent of the children it is missing instead of duplicated.</p>
 *
 * <p>The company is find-or-create under a key unique to this suite and every test starts from an empty
 * {@code product_categories}, because the PostgreSQL container is shared per JVM and another suite's
 * rows would make the counts non-deterministic.</p>
 */
@SpringBootTest
@DisplayName("Starter Product Category Applier Integration Tests")
class StarterProductCategoryApplierIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "STARTER-CATALOG-KEY";
    private static final String COMPANY_RFC = "SCAT010101ABC";

    @Autowired
    private StarterProductCategoryApplier applier;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetProductCategoryTable() {
        // Leaf-first, for the reason ProductCategorySchemaMigrationIntegrationTest records: a parent
        // cannot be deleted while a child survives, so every non-root row goes first, which drops all
        // parent references at once, and only then the surviving roots are detached and removed.
        jdbcTemplate.update("DELETE FROM product_categories WHERE parent_category_id IS NOT NULL");
        jdbcTemplate.update("UPDATE product_categories SET parent_category_id = NULL");
        jdbcTemplate.update("DELETE FROM product_categories");
    }

    @Test
    @DisplayName("inserts the whole catalog: 57 rows, two levels, SAT keys and enabled flags included")
    void appliesTheWholeCatalog() {
        var companyId = companyId();

        var inserted = applier.applyTo(companyId);

        assertThat(inserted).as("every catalog row is new for this company").isEqualTo(57);
        assertThat(countCategories(companyId)).isEqualTo(57);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id IS NULL
                        """, Integer.class, companyId))
                .as("the applier builds exactly the two levels the catalog declares")
                .isEqualTo(18);

        var ferreteria = category(companyId, null, "Ferretería");
        assertThat(ferreteria.get("sat_code")).as("Ferretería's SAT key").isEqualTo("27110000");
        assertThat(ferreteria.get("parent_category_id"))
                .as("Ferretería is a root")
                .isNull();
        assertThat(ferreteria.get("enabled"))
                .as("the user's branches ship enabled")
                .isEqualTo(true);

        var herramientas = category(companyId, (UUID) ferreteria.get("id"), "Herramientas Manuales");
        assertThat(herramientas.get("sat_code")).as("its child's SAT key").isEqualTo("27111600");
        assertThat(herramientas.get("enabled")).isEqualTo(true);

        var automotriz = category(companyId, null, "Automotriz");
        assertThat(automotriz.get("enabled")).as("the proposals ship disabled").isEqualTo(false);
        assertThat(automotriz.get("sat_code"))
                .as("a proposal carries no invented fiscal key")
                .isNull();
    }

    @Test
    @DisplayName("a second run inserts nothing and leaves the row count untouched")
    void secondRunIsANoOp() {
        var companyId = companyId();
        assertThat(applier.applyTo(companyId)).isEqualTo(57);

        var insertedAgain = applier.applyTo(companyId);

        assertThat(insertedAgain).as("the guard is the idempotency contract").isZero();
        assertThat(countCategories(companyId)).isEqualTo(57);
    }

    @Test
    @DisplayName("a pre-existing root with the same name is reused instead of duplicated, whatever its case")
    void preExistingRootIsSkippedAndReusedAsParent() {
        var companyId = companyId();
        // Upper case on purpose: the unique index is over lower(category_name), so a case-sensitive
        // guard would attempt an insert the index rejects — a no-op turned into an error.
        jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name)
                VALUES (?, ?, 'FERRETERÍA')
                """, UUID.randomUUID(), companyId);
        var preExistingId = jdbcTemplate.queryForObject(
                "SELECT id FROM product_categories WHERE company_id = ?", UUID.class, companyId);

        var inserted = applier.applyTo(companyId);

        assertThat(inserted)
                .as("only the skipped root is missing from the count")
                .isEqualTo(56);
        assertThat(countCategories(companyId))
                .as("the skipped root is replaced by the pre-existing row, so the total does not grow")
                .isEqualTo(57);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM product_categories
                WHERE company_id = ? AND parent_category_id IS NULL AND lower(category_name) = 'ferretería'
                """, Integer.class, companyId))
                .as("the company keeps the root it had")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM product_categories
                WHERE company_id = ? AND parent_category_id = ?
                """, Integer.class, companyId, preExistingId))
                .as("its two children hang from the pre-existing row, not from a copy")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a disabled pre-existing root is reused and its children arrive disabled too")
    void disabledPreExistingRootKeepsItsChildrenDisabled() {
        var companyId = companyId();
        var disabledRootId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_categories (id, company_id, category_name, enabled)
                VALUES (?, ?, 'Ferretería', false)
                """, disabledRootId, companyId);

        applier.applyTo(companyId);

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id = ? AND enabled
                        """, Integer.class, companyId, disabledRootId))
                .as("an enabled child under a disabled root is the state the cascade exists to prevent")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM product_categories
                        WHERE company_id = ? AND parent_category_id = ?
                        """, Integer.class, companyId, disabledRootId))
                .as("the branches are still created, only disabled")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("refuses a company id that is not a company")
    void unknownCompanyIsRejected() {
        var unknown = UUID.randomUUID();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> applier.applyTo(unknown)))
                .as("a system hook with a stale id has to fail loudly, not silently seed nothing")
                .isInstanceOf(CompanyNotFoundException.class);
    }

    private UUID companyId() {
        return TenantFixtures.findOrCreate(companyRepository, COMPANY_KEY, "Starter Catalog Test Company", COMPANY_RFC)
                .getId();
    }

    private int countCategories(UUID companyId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_categories WHERE company_id = ?", Integer.class, companyId);
    }

    /** One row as the database holds it, so an assertion cannot pass on a stale entity. */
    private Map<String, Object> category(UUID companyId, UUID parentId, String name) {
        var sql = parentId == null
                ? "SELECT * FROM product_categories WHERE company_id = ? AND parent_category_id IS NULL AND category_name = ?"
                : "SELECT * FROM product_categories WHERE company_id = ? AND parent_category_id = ? AND category_name = ?";
        return parentId == null
                ? jdbcTemplate.queryForMap(sql, companyId, name)
                : jdbcTemplate.queryForMap(sql, companyId, parentId, name);
    }
}
