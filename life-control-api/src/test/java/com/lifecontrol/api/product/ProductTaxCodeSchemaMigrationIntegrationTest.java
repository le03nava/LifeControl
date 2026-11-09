package com.lifecontrol.api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.product.dto.ProductRequest;
import com.lifecontrol.api.product.dto.ProductVariantRequest;
import com.lifecontrol.api.product.service.ProductService;
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
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Verification of {@code V89__products_tax_code.sql}: the product declares its fiscal code, and the code it
 * declares is one the platform catalog has (ADR-0002 decision 6, S2 and S4 of
 * {@code odd/tasks/product-tax-code.md}).
 *
 * <p><b>What is asserted, and why it is four facts and not one.</b> The column's {@code NOT NULL}, its
 * {@code 'NA'} default and its reference to {@code tax_code} are three separate promises of the same
 * migration, and a reader of the schema can lose any one of them without the other two noticing: a nullable
 * column that still defaults to {@code 'NA'} accepts a product with no fiscal code at all, and a
 * {@code VARCHAR(20)} with no reference accepts {@code 'IVA '} or a code the catalog retired ten years ago.
 * The reference is read back from {@code pg_constraint} rather than from a violation message alone, because
 * the point of the reference is that it exists on every write, not that one hand-written statement was
 * refused.</p>
 *
 * <p><b>Why the default is a case and not a detail.</b> {@code 'NA'} is what every product in every
 * environment means today — no tax applies — so the migration states it as a {@code DEFAULT} instead of
 * writing a backfill. PostgreSQL applies a constant default to the rows that already exist when the column
 * is added, so the case below inserts a product that states <b>no</b> code and reads {@code 'NA'} back: that
 * is the promise the absence of a backfill rests on.</p>
 *
 * <p><b>What a suite can observe and a migration cannot.</b> {@code products} is armed {@code ENABLE} +
 * {@code FORCE} by {@code V53:122}, so a row-count check <i>inside</i> the migration reads zero rows and
 * passes vacuously ({@code V64:36}) — which is why the self-checks there are structural. Here the pool states
 * the tenant, the policy admits the rows, and the counts are real.</p>
 */
@SpringBootTest
@WithMockUser(roles = "lc-admin")
@DisplayName("products.tax_code schema (V89)")
class ProductTaxCodeSchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** Unique to this suite: the container is shared, so the prefix is how the cleanup finds its own rows. */
    private static final String SKU_PREFIX = "PTAX-";

    private static final String COMPANY_KEY = "PRODUCT-TAX-CODE-KEY";
    private static final String COMPANY_RFC = "PTAX010101AB1";

    /** The seeded code that names a taxed product: {@code V82:45-49} is the only source of codes. */
    private static final String TAXED_CODE = "IVA";

    /** The seeded code a second write moves to, so a copy is told apart from a literal. */
    private static final String ZERO_RATE_CODE = "TASA_0";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ProductService productService;

    @BeforeEach
    void deleteOwnRows() {
        cleanUp();
    }

    @AfterEach
    void leaveNoRowsBehind() {
        // The same sweep on the way out, for the same reason the sibling catalog suites do it: a suite that
        // leaves products behind makes the next class' count measure this one.
        cleanUp();
    }

    private void cleanUp() {
        var companyId = companyId();
        TenantFixtures.inTenant(companyId, () -> {
            // The variants go first: `fk_product_variants_product_same_company` holds the product in place, and
            // the cases that write through the service create one per product (ADR-0006 decision 2).
            jdbcTemplate.update(
                    "DELETE FROM product_variants WHERE product_id IN (SELECT id FROM products WHERE sku LIKE ?)",
                    SKU_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM products WHERE sku LIKE ?", SKU_PREFIX + "%");
        });
    }

    // --- the column's own shape ---

    @Test
    @DisplayName("the column is VARCHAR(20) NOT NULL and defaults to 'NA'")
    void columnIsNotNullAndDefaultsToNoTax() {
        var column = jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'products' AND column_name = 'tax_code'
                """);

        assertThat(column)
                .as("V89 adds products.tax_code; a missing column is the whole migration missing")
                .isNotEmpty();
        assertThat(column.get("data_type"))
                .as("the code is text, like tax_code.code (V82:27)")
                .isEqualTo("character varying");
        assertThat(column.get("character_maximum_length"))
                .as("VARCHAR(20) is sales_order_items.tax_code's width (V81:87): two different types would "
                        + "make the same code compare differently in the catalog and on the document")
                .isEqualTo(20);
        assertThat(column.get("is_nullable"))
                .as("a product always declares a fiscal code, even if that code is `no aplica`")
                .isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default")))
                .as("`NA` is the default, so every product that existed before V89 means 'no tax applies'")
                .startsWith("'NA'");
    }

    @Test
    @DisplayName("the reference points at the platform catalog's code column, not at a tenant table")
    void referenceTargetsTheTaxCodeCatalog() {
        var definitions = jdbcTemplate.queryForList("""
                SELECT pg_get_constraintdef(c.oid)
                  FROM pg_constraint c
                 WHERE c.conrelid = 'products'::regclass AND c.contype = 'f'
                   AND c.conname = 'fk_products_tax_code'
                """, String.class);

        assertThat(definitions)
                .as("one foreign key from products.tax_code, named, so a violation can be read")
                .containsExactly("FOREIGN KEY (tax_code) REFERENCES tax_code(code)");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_schema = 'public' AND table_name = 'tax_code' AND column_name = 'code'
                        """, Integer.class))
                .as("the target column exists, and it is the catalog V82 created")
                .isEqualTo(1);
    }

    // --- the promises, observed on rows the policy admits ---

    @Test
    @DisplayName("a product that states no code is stored as 'NA', and a stated code round-trips")
    void defaultAppliesAndAStatedCodeRoundTrips() {
        var companyId = companyId();

        insertProduct(companyId, sku("DEFAULT"), null);
        insertProduct(companyId, sku("TAXED"), TAXED_CODE);

        assertThat(taxCodeOf(companyId, sku("DEFAULT")))
                .as("the default is what lets V89 add a NOT NULL column with no backfill over a FORCEd table")
                .isEqualTo("NA");
        assertThat(taxCodeOf(companyId, sku("TAXED")))
                .as("a code the catalog has is stored as stated")
                .isEqualTo(TAXED_CODE);

        // The other end of the same promise: the column is not write-once. A code change is a product edit,
        // and it is what the product form will do once T5 renders the catalog (S2: no per-line override).
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "UPDATE products SET tax_code = ? WHERE sku = ?", ZERO_RATE_CODE, sku("TAXED")));

        assertThat(taxCodeOf(companyId, sku("TAXED")))
                .as("the second code is the one stored, so the read above is not a literal in a fixture")
                .isEqualTo(ZERO_RATE_CODE);
    }

    @Test
    @DisplayName("an explicit NULL is refused: 'no tax' is a code, never an absence")
    void explicitNullIsRefused() {
        var companyId = companyId();

        var refused = catchThrowable(() -> TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO products (id, sku, name, company_id, tax_code) VALUES (?, ?, ?, ?, NULL)",
                        UUID.randomUUID(),
                        sku("NULL"),
                        "Explicit null",
                        companyId)));

        assertThat(refused)
                .as("NOT NULL, and the default only fills a column the INSERT does not mention")
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown ->
                        assertThat(deepestMessage(thrown)).contains("tax_code").contains("not-null"));

        assertThat(countProducts(companyId))
                .as("nothing was stored by the refused write")
                .isZero();
    }

    @Test
    @DisplayName("a code the catalog does not have is refused by fk_products_tax_code")
    void unknownCodeIsRefused() {
        var companyId = companyId();

        assertThatThrownBy(() -> insertProduct(companyId, sku("UNKNOWN"), "NO_EXISTE"))
                .as("the catalog is the vocabulary: a free-text code is a typo nobody would notice")
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(deepestMessage(thrown)).contains("fk_products_tax_code"));

        assertThat(countProducts(companyId))
                .as("the refused product is not stored")
                .isZero();
    }

    // --- the API surface the column exists for, over real PostgreSQL ---

    /**
     * The code travels the whole way a client would: request → entity → row → response, on <b>create</b>.
     *
     * <p>Asserted on the row and not only on the response, because the response is built from the entity the
     * service just saved: a mapping bug that reports the request back without writing it would pass a
     * response-only assertion.</p>
     */
    @Test
    @DisplayName("a product created through the service stores the fiscal code it declares")
    void createRoundTripsTheFiscalCode() {
        var companyId = companyId();

        var created = TenantFixtures.inTenant(
                companyId, () -> productService.createProduct(companyId, creationRequest(sku("CREATED"), TAXED_CODE)));

        assertThat(created.taxCode())
                .as("the response reports the code the request declared")
                .isEqualTo(TAXED_CODE);
        assertThat(taxCodeOf(companyId, sku("CREATED")))
                .as("and the row is where a later sale reads it from")
                .isEqualTo(TAXED_CODE);
    }

    /**
     * The same on <b>update</b>, and the second half of it: a request that states no code is not a request to
     * clear one. {@code tax_code} is {@code NOT NULL} and "no fiscal code" is not a state a product can be in —
     * {@code NA} is a code with a meaning — so an absent field leaves the stored value alone, the rule
     * {@code attributes} already follows in the same method.
     */
    @Test
    @DisplayName("an update moves the fiscal code, and an update that states none leaves it alone")
    void updateRoundTripsTheFiscalCode() {
        var companyId = companyId();
        var created = TenantFixtures.inTenant(
                companyId, () -> productService.createProduct(companyId, creationRequest(sku("UPDATED"), TAXED_CODE)));

        var moved = TenantFixtures.inTenant(
                companyId,
                () -> productService.updateProduct(
                        companyId, created.id(), updateRequest(sku("UPDATED"), ZERO_RATE_CODE)));

        assertThat(moved.taxCode()).as("the edit's code is the product's").isEqualTo(ZERO_RATE_CODE);
        assertThat(taxCodeOf(companyId, sku("UPDATED")))
                .as("and it is the one stored")
                .isEqualTo(ZERO_RATE_CODE);

        var untouched = TenantFixtures.inTenant(
                companyId,
                () -> productService.updateProduct(companyId, created.id(), updateRequest(sku("UPDATED"), null)));

        assertThat(untouched.taxCode())
                .as("an absent field is not a request to clear it")
                .isEqualTo(ZERO_RATE_CODE);
        assertThat(taxCodeOf(companyId, sku("UPDATED")))
                .as("so the row still states the code the operator chose")
                .isEqualTo(ZERO_RATE_CODE);
    }

    /** A creation as a client sends it: one variant (ADR-0006 decision 2), the category left open, one code. */
    private ProductRequest creationRequest(String sku, String taxCode) {
        return new ProductRequest(
                sku,
                "Tax code probe " + sku,
                null,
                null,
                taxCode,
                "GOODS",
                null,
                null,
                null,
                List.of(new ProductVariantRequest(null, "Unica", pieceUnitId(), null)));
    }

    /** An edit as the form sends it: the scalar fields it renders, and the code it holds. */
    private ProductRequest updateRequest(String sku, String taxCode) {
        return new ProductRequest(sku, "Tax code probe " + sku, null, null, taxCode, "GOODS", null, null, null, null);
    }

    /** The {@code measure_units} row {@code V3} seeds as "Pieza": read, never invented. */
    private UUID pieceUnitId() {
        return jdbcTemplate.queryForObject("SELECT id FROM measure_units WHERE sat_code = 'H87'", UUID.class);
    }

    // --- fixtures, every one of them inside the tenant the policies require ---

    private String sku(String suffix) {
        return SKU_PREFIX + suffix;
    }

    /** A product with its fiscal code stated, or {@code null} to let the column's default speak. */
    private void insertProduct(UUID companyId, String sku, String taxCode) {
        if (taxCode == null) {
            TenantFixtures.inTenant(
                    companyId,
                    () -> jdbcTemplate.update(
                            "INSERT INTO products (id, sku, name, company_id) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(),
                            sku,
                            "Seed product " + sku,
                            companyId));
            return;
        }
        TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.update(
                        "INSERT INTO products (id, sku, name, company_id, tax_code) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        sku,
                        "Seed product " + sku,
                        companyId,
                        taxCode));
    }

    private String taxCodeOf(UUID companyId, String sku) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject("SELECT tax_code FROM products WHERE sku = ?", String.class, sku));
    }

    private int countProducts(UUID companyId) {
        return TenantFixtures.inTenant(
                companyId,
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM products WHERE company_id = ? AND sku LIKE ?",
                        Integer.class,
                        companyId,
                        SKU_PREFIX + "%"));
    }

    /** The reason PostgreSQL gave, whichever Spring exception carried it. */
    private String deepestMessage(Throwable thrown) {
        var cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /** Find-or-create the company the product rows hang off, the same idempotent shape the siblings use. */
    private UUID companyId() {
        return TenantFixtures.findOrCreate(
                        companyRepository, COMPANY_KEY, "Product Tax Code Schema Test Company", COMPANY_RFC)
                .getId();
    }
}
