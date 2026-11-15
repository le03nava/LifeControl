package com.lifecontrol.api.fiscal;

import com.lifecontrol.api.accounting.catalog.StarterAccountingCatalogApplier;
import com.lifecontrol.api.support.SaleFixtures;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * One completed sale with the fiscal profile a document needs, for the two suites of {@code T6}'s second pull
 * request: the CFDI builder and the stamp attempt.
 *
 * <p><b>Why it is the chain {@code SaleFixtures} describes and not a second copy of it.</b> The sale, its two
 * lines, the creation rows {@code V68} requires and the derived amounts ({@code V81}, {@code V84}, {@code V90})
 * come from the shared fixture, because a document whose numbers were fabricated beside the sale it names would
 * be comparing itself with constants. What this class adds is what {@code SaleFixtures} predates and a fiscal
 * document needs: the issuer's regime and place of issue ({@code V102}), the legal name the document freezes,
 * the receiver's whole fiscal profile ({@code V107}) and the book a completion posts to ({@code V100}).</p>
 *
 * <p><b>Why the tenant is stated through {@link TenantFixtures}.</b> The pool logs in as {@code lifecontrol_app}
 * and every table of this document is {@code FORCE}d, so a raw write with no scope is refused by the policy. The
 * fixtures state the company on the open transaction once, before the first insert, exactly as the sibling
 * writer suite does — and the scope then stays for everything the case writes.</p>
 *
 * <p><b>Why the actor is part of the seed.</b> {@code issued_by} is {@code NOT NULL} and is the request's, not a
 * fiscal fact ({@code FiscalDocumentSnapshotWriter#requireActor}), so a case that reached the writer without an
 * identity would fail for a reason that has nothing to do with what it asserts.</p>
 */
final class FiscalDocumentSeed {

    /** The actor the request carries, frozen in {@code issued_by}. */
    static final String ACTOR = "suite:fiscal-stamp";

    /** The issuer's regime and place of issue ({@code V102}), required to issue and never to sell (S1). */
    static final String ISSUER_REGIME = "601";

    static final String ISSUER_ZIP = "06700";

    static final String ISSUER_NAME = "Fiscal Stamp Issuer SA de CV";

    /** The receiver's profile ({@code V107}), stated whole because the writer refuses a partial one. */
    static final String RECEIVER_REGIME = "612";

    static final String RECEIVER_ZIP = "44100";

    static final String RECEIVER_USE = "G03";

    static final String RECEIVER_RFC = "RECE010101AB1";

    static final String RECEIVER_NAME = "Fiscal Stamp Receiver SA de CV";

    /** The product's and the unit's authority keys, copied onto every line (S9). */
    static final String PRODUCT_KEY = "01010101";

    static final String UNIT_KEY = "H87";

    private final JdbcTemplate jdbc;
    private final StarterAccountingCatalogApplier accountingCatalog;
    private final SaleFixtures fixtures;

    private UUID company;
    private UUID store;
    private UUID variant;
    private UUID product;
    private UUID customer;
    private UUID sale;
    private UUID firstLine;
    private UUID secondLine;

    FiscalDocumentSeed(JdbcTemplate jdbc, StarterAccountingCatalogApplier accountingCatalog) {
        this.jdbc = jdbc;
        this.accountingCatalog = accountingCatalog;
        this.fixtures = new SaleFixtures(
                statements -> {
                    for (var statement : statements) {
                        jdbc.update(statement);
                    }
                },
                sql -> jdbc.queryForObject(sql, String.class));
    }

    /** Seeds a company with its fiscal face, a store hierarchy, a product, a customer and a completed sale. */
    FiscalDocumentSeed aCompletedSaleWithAFiscalProfile() {
        company = newCompany();
        seedTree();
        customer = newCustomer();
        sale = newSale(1L);
        authenticate();
        return this;
    }

    /**
     * A second completed sale of the <b>same</b> company, for the cases that need two documents of one tenant —
     * the substitution, whose {@code replaces_fiscal_document_id} points at another document of the company it
     * cancels.
     *
     * <p><b>Why a second sale and not a second company.</b> A folio of another company is a refusal, so the only
     * way to prove that a resolved folio is written is for the two documents to share a tenant. The sequence
     * argument is what keeps the two {@code order_number}s distinct: the shared fixture derives the number from
     * it plus the sale's own id prefix, and two sales with the same sequence would collide one time in sixty-five
     * thousand runs.</p>
     *
     * <p>It reuses the company's store, product, variant and customer, and it does not touch {@link #sale()}:
     * the caller keeps the first sale and asks this one for the id it needs.</p>
     *
     * @return the id of the second sale, already {@code COMPLETED} and with its own two lines
     */
    UUID anotherCompletedSale() {
        return newSale(2L);
    }

    UUID company() {
        return company;
    }

    UUID store() {
        return store;
    }

    UUID product() {
        return product;
    }

    UUID variant() {
        return variant;
    }

    UUID customer() {
        return customer;
    }

    UUID sale() {
        return sale;
    }

    UUID firstLine() {
        return firstLine;
    }

    UUID secondLine() {
        return secondLine;
    }

    /**
     * A company whose fiscal face is complete: regime, place of issue, RFC and legal name, which are the three
     * facts the writer refuses a document for when they are absent.
     */
    private UUID newCompany() {
        var id = UUID.randomUUID();
        var suffix = id.toString().replace("-", "").substring(0, 8).toUpperCase();
        TenantFixtures.stateTenantWithinTheOpenTransaction(id, jdbc);
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, razon_social, rfc, currency, regimen_fiscal,"
                        + " fiscal_zip_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                "FS-" + suffix,
                "Fiscal Stamp Company",
                ISSUER_NAME,
                "RFC" + suffix,
                SaleFixtures.CURRENCY,
                ISSUER_REGIME,
                ISSUER_ZIP);
        // A company arrives with its book in production, and V100 refuses a completion that has no posting rule,
        // so a fixture that marks a sale COMPLETED needs one. applyTo is idempotent and joins this transaction.
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
                "Fiscal Stamp Region");
        jdbc.update(
                "INSERT INTO company_zones (id, company_id, company_region_id, zone_code, zone_name)"
                        + " VALUES (?, ?, ?, ?, ?)",
                zone,
                company,
                region,
                "Z" + suffix.substring(0, 4),
                "Fiscal Stamp Zone");
        jdbc.update(
                "INSERT INTO company_stores (id, company_id, company_zone_id, store_name, enabled)"
                        + " VALUES (?, ?, ?, ?, true)",
                store,
                company,
                zone,
                "Fiscal Stamp Store");

        product = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, sku, name, company_id, sat_code) VALUES (?, ?, ?, ?, ?)",
                product,
                "FS-" + suffix,
                "Fiscal Stamp Product",
                company,
                PRODUCT_KEY);

        variant = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO product_variants (id, product_id, bar_code, variant_name, measure_unit_id, company_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                variant,
                product,
                "FS-BC-" + suffix,
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
                "Fiscal Stamp Receiver trade name",
                RECEIVER_RFC,
                RECEIVER_NAME,
                RECEIVER_REGIME,
                RECEIVER_ZIP,
                RECEIVER_USE);
        return id;
    }

    private UUID newSale(long sequence) {
        var id = UUID.randomUUID();
        var lineOne = UUID.randomUUID();
        var lineTwo = UUID.randomUUID();
        var tree = new SaleFixtures.Tree(company, store, variant);
        for (var statement : fixtures.saleStatements(tree, id, lineOne, lineTwo, sequence)) {
            jdbc.update(statement);
        }
        firstLine = lineOne;
        secondLine = lineTwo;
        jdbc.update("UPDATE sales_orders SET customer_id = ? WHERE id = ?", customer, id);
        jdbc.update("UPDATE sales_orders SET status = 'COMPLETED' WHERE id = ?", id);
        jdbc.update(
                "INSERT INTO status_transition (company_id, document_type, document_id, from_status, to_status,"
                        + " actor, occurred_at) VALUES (?, 'SALES_ORDER', ?, 'PENDING', 'COMPLETED', ?, now())",
                company,
                id,
                ACTOR);
        return id;
    }

    private void authenticate() {
        var jwt = Jwt.withTokenValue("fiscal-stamp")
                .header("alg", "RS256")
                .subject(ACTOR)
                .claim("company_id", company.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
