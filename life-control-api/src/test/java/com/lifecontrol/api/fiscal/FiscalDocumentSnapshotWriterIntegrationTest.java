package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentDraft;
import com.lifecontrol.api.fiscal.exception.FiscalIssuerProfileIncompleteException;
import com.lifecontrol.api.fiscal.exception.FiscalLineKeyMissingException;
import com.lifecontrol.api.fiscal.exception.FiscalReceiverProfileIncompleteException;
import com.lifecontrol.api.fiscal.exception.FiscalSaleNotCompletedException;
import com.lifecontrol.api.fiscal.exception.FiscalSaleNotFoundException;
import com.lifecontrol.api.fiscal.exception.FiscalTaxMappingMissingException;
import com.lifecontrol.api.fiscal.service.FiscalDocumentSnapshotWriter;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.SaleFixtures;
import com.lifecontrol.api.support.TenantFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/**
 * The snapshot writer — the first pull request of {@code T6}: a completed sale becomes a {@code DRAFT}
 * {@code fiscal_document} with one line per enabled sale line, and every fact it states is frozen from the
 * catalogs it read <b>once</b> ({@code S9}, {@code S11} and {@code F6}/{@code F9} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L12}(e), {@code L12}(f) and {@code L12}(h) of
 * the same record).
 *
 * <p><b>Why this is a {@code @SpringBootTest} against real PostgreSQL.</b> The subject is a transaction: the
 * document and its lines are written in one unit of work, the money columns are compared by
 * {@code trg_fiscal_document_snapshot_matches_sale} at commit, and {@code fiscal_document} is
 * {@code ENABLE}+{@code FORCE}d so a write only lands under the tenant scope the request installs. None of
 * those three facts exists in H2 or behind a mock, so the suite runs as the pool logs in
 * ({@code lifecontrol_app}) inside one test transaction whose tenant is stated the way production states it.</p>
 *
 * <p><b>What each case is about, and why it asserts the database rather than the returned record.</b> A
 * returned {@link FiscalDocumentDraft} is a claim about what the writer <i>meant</i> to write. The document is
 * the frozen fiscal evidence — the one artifact a stamped CFDI can never be recomputed from — so every case
 * below reads {@code fiscal_document} and {@code fiscal_document_line} back on the superuser-free pool, with
 * the stated scope, and compares them with the sale and the catalogs the fixture wrote.</p>
 *
 * <p><b>The chain is {@link SaleFixtures}'.</b> The sale and its two lines carry the amounts the schema derives
 * ({@code V81}, {@code V84}, {@code V90}), the creation rows {@code V68} requires and the sequence identity
 * ADR-0003 states. Writing a second copy of that here would be a second set of numbers a comparison test could
 * pass against, so the salestatements are the shared ones; only what this slice's company needs and
 * {@code SaleFixtures} predates — the issuer's fiscal regime and place of issue ({@code V102}), the receiver's
 * profile ({@code V107}) and the book a completion posts to ({@code V100}) — is stated here.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Fiscal document snapshot writer (S11, S9)")
class FiscalDocumentSnapshotWriterIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The attempt key every positive case states: what makes a replayed attempt a no-op. */
    private static final String ATTEMPT = "attempt-2026-11-15-01";

    /** A second attempt key, so two drafts of one sale are told apart. */
    private static final String SECOND_ATTEMPT = "attempt-2026-11-15-02";

    /** The actor the request carries, which the document freezes in {@code issued_by}. */
    private static final String ACTOR = "suite:fiscal-actor";

    /** The issuer's regime and place of issue ({@code V102}), required to issue and never to sell (S1). */
    private static final String ISSUER_REGIME = "601";

    private static final String ISSUER_ZIP = "06700";

    /** The issuer's legal name, frozen into {@code emisor_name} (L9(f)). */
    private static final String ISSUER_NAME = "V110 Issuer SA de CV";

    /** The receiver's profile ({@code V107}), stated whole because the writer refuses a partial one. */
    private static final String RECEIVER_REGIME = "612";

    private static final String RECEIVER_ZIP = "44100";

    private static final String RECEIVER_USE = "G03";

    private static final String RECEIVER_RFC = "RECE010101AB1";

    private static final String RECEIVER_NAME = "V110 Receiver SA de CV";

    /** The product's and the unit's authority keys, copied onto every line (S9). */
    private static final String PRODUCT_KEY = "01010101";

    private static final String UNIT_KEY = "H87";

    @Autowired
    private FiscalDocumentSnapshotWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The book every company here needs: {@code V100} refuses a completion that has no posting rule, so a sale
     * this suite marks {@code COMPLETED} would fail with a message about the chart instead of about the writer.
     */
    @Autowired
    private StarterAccountingCatalogApplier accountingCatalog;

    /**
     * The persistence context of this test's transaction.
     *
     * <p>The fixture seeds the book through {@link StarterAccountingCatalogApplier}, which loads the company
     * through its repository, so the {@code Company} entity is in the context before a case's raw
     * {@code UPDATE} runs. A case about a missing fiscal fact therefore clears the context first: without it
     * the writer reads the instance the seed loaded and the case would pass against a value the database no
     * longer holds — the classic stale-mapping trap, and the opposite of what the case is about.</p>
     */
    @Autowired
    private EntityManager entityManager;

    private SaleFixtures fixtures;

    private UUID company;

    private UUID store;

    private UUID variant;

    private UUID product;

    private UUID customer;

    private UUID sale;

    private UUID firstLine;

    private UUID secondLine;

    /** A third line, disabled, so "one line per <b>enabled</b> sale item" is observable. */
    private UUID disabledLine;

    @BeforeEach
    void seedACompletedSaleWithAFiscalProfile() {
        fixtures = new SaleFixtures(
                statements -> {
                    for (var statement : statements) {
                        jdbc.update(statement);
                    }
                },
                sql -> jdbc.queryForObject(sql, String.class));

        company = newCompany("PRIMARY");
        seedTree();
        customer = newCustomer();
        sale = newSale(customer, true);
        authenticate();
    }

    @AfterEach
    void clearTheIdentity() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────
    // S9 — the snapshot, frozen
    // ──────────────────────────────────────

    @Test
    @DisplayName("S9/S11: a completed sale becomes a DRAFT document whose every stated fact is the sale's, the "
            + "catalog's and both fiscal faces' as they stood when it was written")
    void theDraftFreezesTheSaleTheCatalogsAndBothFiscalFaces() {
        var draft = writer.createDraft(sale, ATTEMPT);

        assertThat(draft.written())
                .as("the first attempt of a key writes the row set")
                .isTrue();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.lineCount()).as("one line per enabled sale line").isEqualTo(2);

        var document = row(draft.id());
        assertThat(document.get("company_id")).isEqualTo(company);
        assertThat(document.get("source_type")).isEqualTo("SALES_ORDER");
        assertThat(document.get("source_id")).isEqualTo(sale);
        assertThat(document.get("status")).isEqualTo("DRAFT");
        assertThat(document.get("attempt_key")).isEqualTo(ATTEMPT);
        assertThat(document.get("issued_by")).as("the actor is the request's").isEqualTo(ACTOR);
        assertThat(document.get("serie")).isNull();
        assertThat(document.get("folio")).isNull();
        assertThat(document.get("uuid_fiscal"))
                .as("a draft carries no authority UUID (ck_fiscal_document_uuid_matches_status)")
                .isNull();
        assertThat(document.get("stamped_at")).isNull();
        assertThat(document.get("cancels_fiscal_document_id")).isNull();
        assertThat(document.get("cancellation_motive")).isNull();
        assertThat(document.get("replaces_fiscal_document_id")).isNull();

        // The two faces, frozen from companies and customers (S9, L9(f)). The issuer's name is the legal one
        // and not the trade name, and both values are re-read from the row the writer loaded, never from a
        // constant this suite happens to share with it.
        assertThat(document.get("emisor_rfc")).isEqualTo(issuerRfc());
        assertThat(document.get("emisor_name")).isEqualTo(ISSUER_NAME);
        assertThat(document.get("emisor_regimen")).isEqualTo(ISSUER_REGIME);
        assertThat(document.get("lugar_expedicion"))
                .as("the place of issue is the issuer's own postal code (L6(b))")
                .isEqualTo(ISSUER_ZIP);
        assertThat(document.get("receptor_rfc")).isEqualTo(RECEIVER_RFC);
        assertThat(document.get("receptor_name"))
                .as("the receiver's legal name, never the trade name the catalog also carries")
                .isEqualTo(RECEIVER_NAME);
        assertThat(document.get("receptor_regimen")).isEqualTo(RECEIVER_REGIME);
        assertThat(document.get("receptor_zip_code")).isEqualTo(RECEIVER_ZIP);
        assertThat(document.get("receptor_uso_cfdi"))
                .as("the customer's declared default, which a later stamp may override for one document")
                .isEqualTo(RECEIVER_USE);

        // The document's own vocabulary: the schema's defaults for the three facts this slice does not state,
        // and the frozen type. `metodo_pago` is PUE because T9 is what refuses the deferred one.
        assertThat(document.get("tipo_comprobante")).isEqualTo("I");
        assertThat(document.get("metodo_pago")).isEqualTo("PUE");
        assertThat(document.get("forma_pago")).isNull();
        assertThat(document.get("exportacion")).isEqualTo("01");

        // The money is the sale's four parts, and the total is the document's own arithmetic: the authority's
        // document has no rounding term (L9(g)), so the total is the sum of the parts and not the sale's total.
        assertThat(document.get("currency"))
                .as("the sale's own currency, frozen and never re-derived from the company (V109)")
                .isEqualTo("MXN");
        assertMoney(document, "subtotal", SaleFixtures.SALE_SUBTOTAL);
        assertMoney(document, "discount_total", SaleFixtures.SALE_DISCOUNT);
        assertMoney(document, "tax_transferred", SaleFixtures.SALE_TAX_TOTAL);
        assertMoney(document, "tax_withheld", "0");
        assertMoney(document, "total_amount", SaleFixtures.SALE_TOTAL);

        var lines = lines(draft.id());
        assertThat(lines).as("the disabled line is not a concept").hasSize(2);
        assertLine(lines, firstLine, line -> {
            assertThat(line.get("clave_prod_serv")).isEqualTo(PRODUCT_KEY);
            assertThat(line.get("clave_unidad")).isEqualTo(UNIT_KEY);
            assertThat(line.get("description"))
                    .as("the line's own name snapshots, not the catalog's current name")
                    .isEqualTo("V110 Product as sold talla 8");
            assertMoney(line, "quantity", "2");
            assertMoney(line, "unit_value", "100.0000");
            assertMoney(line, "discount", "10.0000");
            assertMoney(line, "base", "200.0000");
            assertThat(line.get("tax_code")).isEqualTo("IVA");
            assertThat(line.get("tipo_factor"))
                    .as("the authority's factor, frozen from the tax vocabulary (V103)")
                    .isEqualTo("Tasa");
            assertMoney(line, "tasa_o_cuota", "0.160000");
            assertMoney(line, "tax_amount", SaleFixtures.FIRST_LINE_TAX);
            assertThat(line.get("tax_object"))
                    .as("a taxable concept is subject and breaks its tax out (L12(e))")
                    .isEqualTo("02");
        });
        assertLine(lines, secondLine, line -> {
            assertThat(line.get("description"))
                    .as("a line with no name snapshot states the catalogs' names, which is the only names a sale "
                            + "written since V81 carries")
                    .isEqualTo("V110 Product unica");
            assertMoney(line, "quantity", "3");
            assertMoney(line, "base", "151.5000");
            assertMoney(line, "tax_amount", SaleFixtures.SECOND_LINE_TAX);
        });

        assertThat(totalDocuments())
                .as("one attempt of one sale is one document")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("S9/L12(f): an exempt line states a factor and no rate, and a not-subject one states the "
            + "object 01 with no factor and no rate either")
    void anExemptLineCarriesNoRateAndANotSubjectLineStatesTheObject() {
        jdbc.update("UPDATE sales_order_items SET tax_code = 'EXENTO', tax_rate = 0 WHERE id = ?", firstLine);
        jdbc.update("UPDATE sales_order_items SET tax_code = 'NA', tax_rate = 0 WHERE id = ?", secondLine);
        jdbc.update(
                "UPDATE sales_orders SET subtotal = 351.5000, discount_total = 11.5000, tax_total = 0,"
                        + " total_amount = 340.0000 WHERE id = ?",
                sale);

        var draft = writer.createDraft(sale, ATTEMPT);

        var lines = lines(draft.id());
        assertLine(lines, firstLine, line -> {
            assertThat(line.get("tax_code")).isEqualTo("EXENTO");
            assertThat(line.get("tipo_factor")).isEqualTo("Exento");
            assertMoney(line, "tasa_o_cuota", null);
            assertThat(line.get("tax_object")).isEqualTo("02");
        });
        assertLine(lines, secondLine, line -> {
            assertThat(line.get("tax_code")).isEqualTo("NA");
            assertThat(line.get("tax_object"))
                    .as("`NA` is not subject to the tax: object 01 and no tax node (L12(e))")
                    .isEqualTo("01");
            assertThat(line.get("tipo_factor"))
                    .as("and therefore no factor either: ObjetoImp 01 states no TipoFactor, so the row states "
                            + "none instead of borrowing `Exento`, which would mean subject and exempt (V110)")
                    .isNull();
            assertMoney(line, "tasa_o_cuota", null);
        });
    }

    // ──────────────────────────────────────
    // S11 — the idempotent attempt (L12(h))
    // ──────────────────────────────────────

    @Test
    @DisplayName("S11/L12(h): the same attempt key for the same sale writes nothing and returns the document "
            + "the first attempt wrote")
    void theSameAttemptTwiceWritesNothingAndReturnsTheExistingDocument() {
        var first = writer.createDraft(sale, ATTEMPT);
        var documents = totalDocuments();
        var lines = totalLines();

        var second = writer.createDraft(sale, ATTEMPT);

        assertThat(second.id())
                .as("a replay finds its own key and answers with it")
                .isEqualTo(first.id());
        assertThat(second.written()).as("and it writes nothing").isFalse();
        assertThat(second.lineCount()).isEqualTo(2);
        assertThat(totalDocuments()).as("the row count did not move").isEqualTo(documents);
        assertThat(totalLines()).as("nor did the lines'").isEqualTo(lines);
    }

    @Test
    @DisplayName("S6/F1: a second attempt key for the same sale writes a second DRAFT, and only one of the two "
            + "can be stamped")
    void aSecondAttemptKeyWritesASecondDraftAndOnlyOneCanBeStamped() {
        var first = writer.createDraft(sale, ATTEMPT);
        var second = writer.createDraft(sale, SECOND_ATTEMPT);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.written()).isTrue();
        assertThat(totalDocuments()).as("two attempts are two documents").isEqualTo(2);
        assertThat(totalLines()).as("each with its own two lines").isEqualTo(4);

        stamp(first.id());
        settle();
        assertThat(row(first.id()).get("status")).isEqualTo("STAMPED");

        // F1 is a partial unique index over (company_id, source_type, source_id) where status = 'STAMPED': two
        // drafts of one sale are legal and only one of them can become the live stamped document. The refusal
        // is the schema's, so it is observed as the constraint's own name and not as a service guard.
        jdbc.execute("SAVEPOINT one_stamped");
        assertThatThrownBy(() -> stamp(second.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_fiscal_document_one_stamped");
        jdbc.execute("ROLLBACK TO SAVEPOINT one_stamped");

        assertThat(row(second.id()).get("status"))
                .as("the refused transition left the second draft exactly as it was")
                .isEqualTo("DRAFT");
    }

    // ──────────────────────────────────────
    // S11/F6/F9 — the refusals, each by name
    // ──────────────────────────────────────

    @Test
    @DisplayName("S11: a sale that does not exist is refused by name and writes nothing")
    void aSaleThatDoesNotExistIsRefusedByName() {
        var missing = UUID.randomUUID();

        assertThatThrownBy(() -> writer.createDraft(missing, ATTEMPT))
                .isInstanceOf(FiscalSaleNotFoundException.class)
                .hasMessageContaining(missing.toString());

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S11: a sale of another company is refused exactly like a sale that does not exist, because "
            + "the policy hides it instead of admitting it")
    void aSaleOfAnotherCompanyIsRefusedByNameAndWritesNothing() {
        var foreign = newCompany("FOREIGN");
        var foreignSale = newSaleUnder(foreign, null);

        TenantFixtures.stateTenantWithinTheOpenTransaction(company, jdbc);

        assertThatThrownBy(() -> writer.createDraft(foreignSale, ATTEMPT))
                .isInstanceOf(FiscalSaleNotFoundException.class)
                .hasMessageContaining(foreignSale.toString());

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S11/F6: a sale that has not reached COMPLETED is refused by name and writes nothing")
    void aSaleThatIsNotCompletedIsRefusedByName() {
        jdbc.update("UPDATE sales_orders SET status = 'PENDING' WHERE id = ?", sale);

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalSaleNotCompletedException.class)
                .hasMessageContaining("PENDING");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("F6: a sale with no customer is refused by name, because a receiver is not an omission a "
            + "document may carry")
    void aSaleWithNoCustomerIsRefusedByName() {
        jdbc.update("UPDATE sales_orders SET customer_id = NULL WHERE id = ?", sale);

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalReceiverProfileIncompleteException.class)
                .hasMessageContaining("customer");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S2/F6: a customer whose fiscal profile is incomplete is refused by name and the refusal names "
            + "the facts that are missing")
    void aCustomerWhoseFiscalProfileIsIncompleteIsRefusedByName() {
        jdbc.update("UPDATE customers SET default_uso_cfdi = NULL, regimen_fiscal = NULL WHERE id = ?", customer);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalReceiverProfileIncompleteException.class)
                .hasMessageContaining("regimen_fiscal")
                .hasMessageContaining("default_uso_cfdi");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S2/F6: a complete profile with no RFC is still refused, because a document states the "
            + "receiver's RFC and not only their four profile facts")
    void aCompleteProfileWithoutAnRfcIsRefusedByName() {
        jdbc.update("UPDATE customers SET rfc = NULL WHERE id = ?", customer);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalReceiverProfileIncompleteException.class)
                .hasMessageContaining("rfc");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S1/F6: an issuer that has not declared its regime is refused by name and writes nothing")
    void anIssuerWithoutARegimeIsRefusedByName() {
        jdbc.update("UPDATE companies SET regimen_fiscal = NULL WHERE id = ?", company);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalIssuerProfileIncompleteException.class)
                .hasMessageContaining("regimen_fiscal");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S1/F6: an issuer that has not declared its place of issue is refused by name and writes nothing")
    void anIssuerWithoutAPlaceOfIssueIsRefusedByName() {
        jdbc.update("UPDATE companies SET fiscal_zip_code = NULL WHERE id = ?", company);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalIssuerProfileIncompleteException.class)
                .hasMessageContaining("fiscal_zip_code");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S9/F6: an issuer with no legal name is refused by name, because a document states the "
            + "issuer's name and a trade name is not it")
    void anIssuerWithoutALegalNameIsRefusedByName() {
        jdbc.update("UPDATE companies SET razon_social = NULL WHERE id = ?", company);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalIssuerProfileIncompleteException.class)
                .hasMessageContaining("razon_social");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S4/S9: a product with no fiscal key is refused by name, because the concept is the one "
            + "element a stamp cannot omit")
    void aProductWithoutItsFiscalKeyIsRefusedByName() {
        jdbc.update("UPDATE products SET sat_code = NULL WHERE id = ?", product);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalLineKeyMissingException.class)
                .hasMessageContaining("products.sat_code");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S9: a measure unit with no key is refused by name, because a blank key is not a key")
    void aMeasureUnitWithoutItsFiscalKeyIsRefusedByName() {
        jdbc.update("UPDATE measure_units SET sat_code = '' WHERE sat_code = ?", UNIT_KEY);
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalLineKeyMissingException.class)
                .hasMessageContaining("measure_units.sat_code");

        assertThat(totalDocuments()).isZero();
    }

    @Test
    @DisplayName("S3/L12(e): a tax code with no authority mapping is refused by name, because a document that "
            + "invented one would state a tax nobody decided")
    void aTaxCodeWithoutItsAuthorityMappingIsRefusedByName() {
        jdbc.update("UPDATE tax_code SET sat_impuesto = NULL, sat_tipo_factor = NULL WHERE code = 'IVA'");
        forgetCatalogReads();

        assertThatThrownBy(() -> writer.createDraft(sale, ATTEMPT))
                .isInstanceOf(FiscalTaxMappingMissingException.class)
                .hasMessageContaining("IVA");

        assertThat(totalDocuments()).isZero();
    }

    // ──────────────────────────────────────
    // the fixtures
    // ──────────────────────────────────────

    private UUID newCompany(String label) {
        var id = UUID.randomUUID();
        var suffix = id.toString().replace("-", "").substring(0, 8).toUpperCase();
        TenantFixtures.stateTenantWithinTheOpenTransaction(id, jdbc);
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, razon_social, rfc, currency, regimen_fiscal,"
                        + " fiscal_zip_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                "V110-" + label + "-" + suffix,
                "V110 " + label + " Company",
                ISSUER_NAME,
                "RFC" + suffix,
                "MXN",
                ISSUER_REGIME,
                ISSUER_ZIP);
        // A company arrives with its book in production (CompanyService publishes the event the applier
        // listens to), and a completion posts revenue through V100, so a fixture that completes a sale needs
        // one. applyTo is idempotent and joins this transaction.
        accountingCatalog.applyTo(id);
        return id;
    }

    private void seedTree() {
        var suffix = company.toString().replace("-", "").substring(0, 8);
        var country = jdbc.queryForObject("SELECT id FROM countries WHERE country_code = 'MX'", UUID.class);
        var companyCountry = UUID.randomUUID();
        var region = UUID.randomUUID();
        var zone = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO company_countries (id, company_id, country_id) VALUES (?, ?, ?)",
                companyCountry,
                company,
                country);
        jdbc.update(
                "INSERT INTO company_regions (id, company_id, company_country_id, region_code, region_name)"
                        + " VALUES (?, ?, ?, ?, ?)",
                region,
                company,
                companyCountry,
                "R" + suffix.substring(0, 4),
                "V110 Region");
        jdbc.update(
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)"
                        + " VALUES (?, ?, ?, ?, ?)",
                zone,
                company,
                region,
                "Z" + suffix.substring(0, 4),
                "V110 Zone");
        jdbc.update(
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled)"
                        + " VALUES (?, ?, ?, ?, true)",
                store,
                company,
                zone,
                "V110 Store");

        product = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, sku, name, company_id, sat_code) VALUES (?, ?, ?, ?, ?)",
                product,
                "V110-" + suffix,
                "V110 Product",
                company,
                PRODUCT_KEY);

        variant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                variant,
                product,
                "V110-BC-" + suffix,
                "unica",
                jdbc.queryForObject("SELECT id FROM measure_units WHERE sat_code = ?", UUID.class, UNIT_KEY),
                company);
    }

    private UUID newCustomer() {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO customers (id, company_id, name, sales_channel, rfc, legal_name, regimen_fiscal,"
                        + " fiscal_zip_code, default_uso_cfdi) VALUES (?, ?, ?, 'STORE', ?, ?, ?, ?, ?)",
                id,
                company,
                "V110 Receiver trade name",
                RECEIVER_RFC,
                RECEIVER_NAME,
                RECEIVER_REGIME,
                RECEIVER_ZIP,
                RECEIVER_USE);
        return id;
    }

    /** A completed sale of the primary company, with its two lines, its disabled line and its two snapshots. */
    private UUID newSale(UUID receiver, boolean completed) {
        var id = newSaleUnder(company, receiver);
        if (completed) {
            jdbc.update("UPDATE sales_orders SET status = 'COMPLETED' WHERE id = ?", id);
            jdbc.update(
                    "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                            + " actor, occurred_at) VALUES (?, 'SALES_ORDER', ?, 'PENDING', 'COMPLETED', ?, now())",
                    company,
                    id,
                    ACTOR);
        }
        return id;
    }

    /**
     * One sale of {@code owner} through {@link SaleFixtures}' own statements, plus the third line its fixture
     * does not write.
     *
     * <p>The line's two name snapshots are stated on the first line and left null on the second, because both
     * halves of the description rule are real: a line written before {@code V81} carries them and one written
     * after does not, and neither is a line with no description.</p>
     */
    private UUID newSaleUnder(UUID owner, UUID receiver) {
        var saleId = UUID.randomUUID();
        var lineOne = UUID.randomUUID();
        var lineTwo = UUID.randomUUID();
        var disabled = UUID.randomUUID();
        var tree = new SaleFixtures.Tree(owner, store, variant);
        for (var statement : fixtures.saleStatements(tree, saleId, lineOne, lineTwo, 1L)) {
            jdbc.update(statement);
        }
        jdbc.update(fixtures.saleLine(tree, saleId, disabled, "1.0000", "10.0000", "0"));
        jdbc.update(fixtures.creationRow(owner, "SALES_ORDER_ITEM", disabled, "PENDING"));
        jdbc.update("UPDATE sales_order_items SET enabled = false WHERE id = ?", disabled);
        jdbc.update(
                "UPDATE sales_order_items SET product_name_snapshot = 'V110 Product as sold',"
                        + " variant_name_snapshot = 'talla 8' WHERE id = ?",
                lineOne);
        jdbc.update("UPDATE sales_orders SET customer_id = ? WHERE id = ?", receiver, saleId);
        if (owner.equals(company)) {
            firstLine = lineOne;
            secondLine = lineTwo;
            disabledLine = disabled;
        }
        return saleId;
    }

    /** The issuer's RFC, read from the row instead of restated, so the assertion is about the copy. */
    private String issuerRfc() {
        return jdbc.queryForObject("SELECT rfc FROM companies WHERE id = ?", String.class, company);
    }

    /** Drops what the seed loaded through JPA, so a case's raw {@code UPDATE} is what the writer reads. */
    private void forgetCatalogReads() {
        entityManager.clear();
    }

    private void authenticate() {
        var jwt = Jwt.withTokenValue("v110")
                .header("alg", "RS256")
                .subject(ACTOR)
                .claim("company_id", company.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private Map<String, Object> row(UUID documentId) {
        return jdbc.queryForMap("SELECT * FROM fiscal_document WHERE id = ?", documentId);
    }

    private List<Map<String, Object>> lines(UUID documentId) {
        return jdbc.queryForList("SELECT * FROM fiscal_document_line WHERE fiscal_document_id = ?", documentId);
    }

    private void assertLine(
            List<Map<String, Object>> lines, UUID saleLine, java.util.function.Consumer<Map<String, Object>> body) {
        var line = lines.stream()
                .filter(candidate -> saleLine.equals(candidate.get("sales_order_item_id")))
                .findFirst();
        assertThat(line).as("the document states sale line " + saleLine).isPresent();
        body.accept(line.orElseThrow());
    }

    private static void assertMoney(Map<String, Object> row, String column, String expected) {
        var actual = (BigDecimal) row.get(column);
        if (expected == null) {
            assertThat(actual).as(column).isNull();
            return;
        }
        assertThat(actual).as(column).isNotNull();
        assertThat(actual).as(column).isEqualByComparingTo(expected);
    }

    private long totalDocuments() {
        return jdbc.queryForObject("SELECT count(*) FROM fiscal_document WHERE company_id = ?", Long.class, company);
    }

    private long totalLines() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document_line WHERE company_id = ?", Long.class, company);
    }

    /**
     * The transition out of {@code DRAFT}, which is the one legitimate update this table grants
     * ({@code V108}, {@code L9(b)}). The three columns move together because the two `CHECK`s of {@code V108}
     * make the status, the UUID and the instant one fact.
     */
    private void stamp(UUID documentId) {
        jdbc.update(
                "UPDATE fiscal_document SET status = 'STAMPED', uuid_fiscal = ?, stamped_at = now() WHERE id = ?",
                UUID.randomUUID(),
                documentId);
    }

    /**
     * Runs the deferred judgement a commit would run, so a stamp written inside a test transaction is compared
     * with its sale at the moment it is written rather than never ({@code V109}, {@code L9(h)}).
     */
    private void settle() {
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }
}
