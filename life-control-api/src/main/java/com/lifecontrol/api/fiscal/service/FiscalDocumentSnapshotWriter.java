package com.lifecontrol.api.fiscal.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.fiscal.FiscalPaymentMethod;
import com.lifecontrol.api.fiscal.dto.FiscalDocumentDraft;
import com.lifecontrol.api.fiscal.exception.FiscalAttemptConflictException;
import com.lifecontrol.api.fiscal.exception.FiscalAttemptKeyRequiredException;
import com.lifecontrol.api.fiscal.exception.FiscalIssuerProfileIncompleteException;
import com.lifecontrol.api.fiscal.exception.FiscalLineKeyMissingException;
import com.lifecontrol.api.fiscal.exception.FiscalReceiverProfileIncompleteException;
import com.lifecontrol.api.fiscal.exception.FiscalSaleNotCompletedException;
import com.lifecontrol.api.fiscal.exception.FiscalSaleNotFoundException;
import com.lifecontrol.api.fiscal.exception.FiscalTaxFactorUnsupportedException;
import com.lifecontrol.api.fiscal.exception.FiscalTaxMappingMissingException;
import com.lifecontrol.api.fiscal.model.FiscalDocument;
import com.lifecontrol.api.fiscal.model.FiscalDocumentLine;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentLineRepository;
import com.lifecontrol.api.fiscal.repository.FiscalDocumentRepository;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import com.lifecontrol.api.product.model.Product;
import com.lifecontrol.api.product.model.ProductVariant;
import com.lifecontrol.api.product.repository.ProductRepository;
import com.lifecontrol.api.product.repository.ProductVariantRepository;
import com.lifecontrol.api.salesorder.model.SalesOrder;
import com.lifecontrol.api.salesorder.model.SalesOrderItem;
import com.lifecontrol.api.salesorder.model.SalesOrderState;
import com.lifecontrol.api.salesorder.repository.SalesOrderItemRepository;
import com.lifecontrol.api.salesorder.repository.SalesOrderRepository;
import com.lifecontrol.api.tax.repository.TaxCodeRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Turns a completed sale into a {@code DRAFT} fiscal document with its frozen concepts — the first pull request
 * of {@code T6}, and the only place in this tree that reads a live catalog to state a fiscal fact
 * ({@code S9} and {@code S11} of {@code odd/tasks/cfdi-fiscal-document.md}, decisions {@code L12}(e),
 * {@code L12}(f) and {@code L12}(h) of the same record).
 *
 * <p><b>This is the seam, and it is the whole reason the class is a separate slice.</b> Everything a stamped
 * CFDI states is copied <b>here</b>: the issuer's and the receiver's fiscal identities, the two authority keys
 * of every concept, the description, the quantities and values, the tax object, the base, the tax, its factor
 * and its rate, and the money of the document. <b>After this method returns, no catalog is ever read again</b>
 * — a re-issue, a reprint and a cancellation read the document's own columns (S9) — so this is the one place
 * where {@code products}, {@code measure_units}, {@code tax_code}, {@code customers} and {@code companies} are
 * allowed to be the answer, and the reason its javadoc is longer than its body:</p>
 *
 * <ul>
 *   <li>the <b>receiver</b> comes from the sale's customer — {@code receptor_rfc}, the customer's
 *       <b>legal</b> name, regime, fiscal postal code and default use of the voucher;</li>
 *   <li>the <b>issuer</b> comes from the company — {@code emisor_rfc}, its legal name, its regime, and
 *       {@code lugar_expedicion} from {@code companies.fiscal_zip_code} (S1 + S9, {@code L6}(b));</li>
 *   <li>the <b>money</b> is the sale's four parts and its currency, with {@code tax_withheld} at zero because
 *       withholding is {@code T9}'s decision and not this slice's;</li>
 *   <li>each <b>line</b> is one enabled sale line: its own sale-line id, the product's fiscal key and the
 *       unit's key, the description, the quantity and values, and the tax the sale line already froze;</li>
 *   <li>{@code issued_by} is the caller, from {@link CurrentUserContext}.</li>
 * </ul>
 *
 * <p><b>Why the document and the lines are written in one transaction and why the document is a
 * {@code DRAFT}.</b> A stamp attempt that wrote a document without its lines would be a document that states
 * less than the sale, and {@code L9}(b) makes a draft the only mutable state precisely so a failed attempt
 * leaves something a later attempt can find rather than garbage nobody can delete — {@code V108} revokes
 * {@code DELETE} on all three tables. Nothing here stamps: the transition out of {@code DRAFT} is the second
 * pull request's.</p>
 *
 * <p><b>The total is the document's own arithmetic, not the sale's total.</b> The authority's document states
 * subtotal, discount and tax and <b>its total is their sum</b>; it has no rounding term ({@code L9}(g)). So
 * {@code total_amount} is computed from the parts, and the difference from the sale's total — bounded to one
 * minor unit by {@code ck_order_rounding_bounded} — is exactly the tolerance
 * {@code trg_fiscal_document_snapshot_matches_sale} grants at commit. Copying the sale's total would make
 * {@code ck_fiscal_document_total} refuse every sale that carries a rounding adjustment.</p>
 *
 * <p><b>The refusals, each by name and none of them a database error.</b> A sale that is not there (or is
 * another company's, which the policy makes the same answer), a sale that is not {@code COMPLETED}, a sale whose
 * document would be issued with the deferred payment method ({@code S13}), a receiver
 * that states no customer or an incomplete profile, an issuer that has not declared its regime, its place of
 * issue or its legal name, a product or unit with no authority key, a tax code with no mapping, a tax code
 * mapped to a quota, and a blank attempt key. Every one
 * of them is raised <b>before the first write</b>, so a refused attempt leaves nothing behind — {@code S11}:
 * «a failure leaves no stamped row», and a draft it did not mean to write is the same defect one state
 * earlier.</p>
 *
 * <p><b>The attempt is idempotent, and the schema is why.</b> An attempt that already wrote its document is
 * found by {@code (company, source, attempt)} — {@code uq_fiscal_document_attempt}, {@code V110} — and answered
 * with the document it wrote, writing nothing ({@code L12}(h)). The read is the fast path and the constraint is
 * the guarantee: two concurrent writers both miss the read, both insert, and the schema refuses the second,
 * which is translated into {@link FiscalAttemptConflictException} rather than leaking a
 * {@code DataIntegrityViolationException} whose message names a rule and no fact.</p>
 */
@Service
public class FiscalDocumentSnapshotWriter {

    private static final Logger logger = LoggerFactory.getLogger(FiscalDocumentSnapshotWriter.class);

    /** The only source kind this tree issues against: {@code ck_fiscal_document_source_type} closes the set. */
    public static final String SOURCE_TYPE_SALES_ORDER = "SALES_ORDER";

    /**
     * The tax code that means "not subject to the tax".
     *
     * <p>{@code V103} maps it to <b>no</b> authority coordinates on purpose: "not subject" is the absence of a
     * tax node and not a tax of zero ({@code L6}(d)), so a line carrying it states the object {@code 01} and asks
     * the catalog nothing.</p>
     */
    private static final String NOT_SUBJECT_TAX_CODE = "NA";

    /** {@code c_ObjetoImp} {@code 01}: not subject to the tax, so the builder emits no tax node (L12(e)). */
    private static final String TAX_OBJECT_NOT_SUBJECT = "01";

    /** {@code c_ObjetoImp} {@code 02}: subject, and the tax is broken out. Codes {@code 03}/{@code 04} are not written. */
    private static final String TAX_OBJECT_SUBJECT = "02";

    /** The authority's factor for a rate — which is the only one this tree can state with a number. */
    private static final String FACTOR_RATE = "Tasa";

    /** The authority's factor for a quota, which this tree holds no column for. Refused, never written. */
    private static final String FACTOR_QUOTA = "Cuota";

    /** The authority's factor for an exempt concept: a factor and no rate (L12(f)). */
    private static final String FACTOR_EXEMPT = "Exento";

    /** The two authority keys a concept cannot omit, named so a refusal names a column and not a rule. */
    private static final String PRODUCT_KEY_COLUMN = "products.sat_code";

    private static final String UNIT_KEY_COLUMN = "measure_units.sat_code";

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final CustomerRepository customerRepository;
    private final CompanyRepository companyRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductRepository productRepository;
    private final MeasureUnitRepository measureUnitRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final FiscalDocumentRepository fiscalDocumentRepository;
    private final FiscalDocumentLineRepository fiscalDocumentLineRepository;
    private final CurrentUserContext currentUserContext;

    public FiscalDocumentSnapshotWriter(
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository salesOrderItemRepository,
            CustomerRepository customerRepository,
            CompanyRepository companyRepository,
            ProductVariantRepository productVariantRepository,
            ProductRepository productRepository,
            MeasureUnitRepository measureUnitRepository,
            TaxCodeRepository taxCodeRepository,
            FiscalDocumentRepository fiscalDocumentRepository,
            FiscalDocumentLineRepository fiscalDocumentLineRepository,
            CurrentUserContext currentUserContext) {
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.customerRepository = customerRepository;
        this.companyRepository = companyRepository;
        this.productVariantRepository = productVariantRepository;
        this.productRepository = productRepository;
        this.measureUnitRepository = measureUnitRepository;
        this.taxCodeRepository = taxCodeRepository;
        this.fiscalDocumentRepository = fiscalDocumentRepository;
        this.fiscalDocumentLineRepository = fiscalDocumentLineRepository;
        this.currentUserContext = currentUserContext;
    }

    /**
     * Writes the {@code DRAFT} document and its concepts for one attempt of one sale, or answers with the
     * document that attempt already wrote.
     *
     * @param salesOrderId the completed sale the document derives from; the sale of another company is not
     *     visible under the caller's tenant and is therefore refused exactly like a sale that does not exist
     * @param attemptKey which stamp attempt this is; the same key for the same sale is the same attempt, and a
     *     replayed one writes nothing
     * @return the document the attempt names and how many concepts it states
     * @throws FiscalAttemptKeyRequiredException when the key is blank, because the key is what makes a replay a
     *     no-op
     * @throws FiscalSaleNotFoundException when the sale does not exist or belongs to another company
     * @throws FiscalSaleNotCompletedException when the sale has not reached {@code COMPLETED}
     * @throws FiscalReceiverProfileIncompleteException when the sale names no customer, the customer's profile
     *     is incomplete, or it has no RFC
     * @throws FiscalIssuerProfileIncompleteException when the company has not declared its regime, its place of
     *     issue or its legal name
     * @throws FiscalLineKeyMissingException when a product or a measure unit has no authority key
     * @throws FiscalTaxMappingMissingException when a line's tax code has no mapping onto the authority's
     *     coordinates
     * @throws FiscalTaxFactorUnsupportedException when that mapping states a quota, which this tree cannot
     *     express
     * @throws FiscalAttemptConflictException when another writer committed the same attempt first
     */
    @Transactional
    public FiscalDocumentDraft createDraft(UUID salesOrderId, String attemptKey) {
        var key = requireAttemptKey(salesOrderId, attemptKey);
        var sale = salesOrderRepository
                .findById(salesOrderId)
                .orElseThrow(() -> new FiscalSaleNotFoundException(salesOrderId));

        requireCompleted(sale);
        requirePaidWhenItHappens(sale);

        // The replay is answered after the two facts the sale owns and before every catalog is read: a document
        // that exists already states the catalogs as they stood, and re-reading them to answer a replay would be
        // the one thing S9 forbids.
        var existing = fiscalDocumentRepository.findByCompanyIdAndSourceTypeAndSourceIdAndAttemptKey(
                sale.getCompanyId(), SOURCE_TYPE_SALES_ORDER, salesOrderId, key);
        if (existing.isPresent()) {
            logger.info(
                    "Fiscal snapshot attempt replay: sale={}, attempt={}, document={}",
                    salesOrderId,
                    key,
                    existing.get().getId());
            return describe(existing.get(), key, false);
        }

        var company = requireCompany(sale.getCompanyId());
        var receiver = requireReceiver(sale);
        requireIssuer(company);
        var lines = snapshotLines(sale);

        var document = writeDocument(sale, company, receiver, key);
        writeLines(document, lines);

        logger.info(
                "Fiscal draft written: sale={}, attempt={}, document={}, lines={}",
                salesOrderId,
                key,
                document.getId(),
                lines.size());

        return describe(document, key, true);
    }

    // ─── the refusals ─────────────────────────────────────────────────────────────────────────────────

    private static String requireAttemptKey(UUID salesOrderId, String attemptKey) {
        if (!StringUtils.hasText(attemptKey)) {
            throw new FiscalAttemptKeyRequiredException(salesOrderId);
        }
        return attemptKey.trim();
    }

    private static void requireCompleted(SalesOrder sale) {
        if (sale.getStatus() != SalesOrderState.COMPLETED) {
            throw new FiscalSaleNotCompletedException(sale.getId(), String.valueOf(sale.getStatus()));
        }
    }

    /**
     * Refuses a sale whose document would be issued with the deferred payment method ({@code S13}).
     *
     * <p><b>Why here, and why before the replay.</b> This is the one place a sale becomes a document, so a rule
     * stated here cannot be skipped by a caller of the stamp, of a replay or of a future endpoint — the reason
     * {@code L15}(d) rejected the stamper (its refusal would run after the draft existed, in a table that
     * refuses {@code DELETE}) and the endpoint (which would leave the rule to whoever remembers to call it). It
     * is stated beside {@link #requireCompleted} because both are facts about the <b>sale</b> and not about the
     * attempt: a replay answers a document that already exists, and a sale that cannot be invoiced at all
     * cannot be invoiced twice either.</p>
     *
     * <p><b>What the method is read from, and why not from the sale.</b> {@code V109} declares
     * {@code metodo_pago VARCHAR(3) NOT NULL DEFAULT 'PUE'} and no code sets it, so what the document would
     * state is the schema's own default — there is no column of a sale to read it from, because {@code V87}
     * dropped {@code sales_orders.payment_method_id} and the charge refuses anything but an exact payment
     * ({@code SalesOrderService}). {@link #metodoPagoOf} is the one place that fact is stated, so the day a sale
     * can declare a deferred method the resolver changes and the refusal is already in the path — instead of a
     * rule that has to be added at the same time as the capability.</p>
     */
    private static void requirePaidWhenItHappens(SalesOrder sale) {
        FiscalPaymentMethod.requirePaidWhenItHappens(sale.getId(), metodoPagoOf(sale));
    }

    /**
     * The method the document of this sale would be issued with.
     *
     * <p>It is {@code PUE} for every sale that can reach this method, and that is a measured fact rather than a
     * default chosen here: the column carries the schema's default ({@code V109}), nothing in this tree writes
     * it, and a charge that deferred the money is refused before a sale can reach {@code COMPLETED}. The sale is
     * a parameter and not a constant's replacement because it is the <b>subject</b> of the rule — the day a sale
     * states its own method, this body reads it and no caller changes.</p>
     */
    private static String metodoPagoOf(SalesOrder sale) {
        return FiscalPaymentMethod.PUE;
    }

    private Company requireCompany(UUID companyId) {
        // Guaranteed by the sale's own key, and still never guessed: a document with no issuer is not a document.
        return companyRepository
                .findById(companyId)
                .orElseThrow(() -> new IllegalStateException("Sale of company " + companyId
                        + " is visible and the company is not: the document cannot state an issuer."));
    }

    /**
     * The receiver as the document states it, or the refusal that names what is missing.
     *
     * <p>{@link Customer#fiscalProfileComplete()} is the rule ({@code L8}(f) left it for exactly this call), and
     * the RFC is asked <b>in addition</b>: S2 keeps it optional in the profile and {@code V109} makes
     * {@code receptor_rfc} required on the document, so a complete profile without one is a customer a sale
     * cannot be invoiced for. The list of missing facts is only built when the predicate said no — the rule is
     * still the predicate's answer, and this method does not restate it.</p>
     */
    private Customer requireReceiver(SalesOrder sale) {
        var customerId = sale.getCustomerId();
        if (customerId == null) {
            throw new FiscalReceiverProfileIncompleteException(
                    sale.getId(), "the sale names no customer (sales_orders.customer_id is null)");
        }

        var customer = customerRepository
                .findByCompanyIdAndId(sale.getCompanyId(), customerId)
                .orElseThrow(() -> new FiscalReceiverProfileIncompleteException(
                        sale.getId(),
                        "customer " + customerId + " is not a customer of company " + sale.getCompanyId()));

        if (!customer.fiscalProfileComplete()) {
            throw FiscalReceiverProfileIncompleteException.ofMissingFacts(sale.getId(), missingProfileFacts(customer));
        }
        if (!StringUtils.hasText(customer.getRfc())) {
            throw FiscalReceiverProfileIncompleteException.ofMissingFacts(sale.getId(), List.of("rfc"));
        }
        return customer;
    }

    /**
     * Which of the profile's four facts is absent, for the message of a refusal the predicate already decided.
     *
     * <p>It names facts and never re-decides: the rule — all four together or none — is
     * {@code Customer#fiscalProfileComplete()}'s, and this list is only ever built once that answer is "no", so
     * the two cannot disagree about whether a customer is complete. What it adds is the one thing the predicate
     * cannot say: <b>which</b> fact an operator has to fill in.</p>
     */
    private static List<String> missingProfileFacts(Customer customer) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(customer.getLegalName())) {
            missing.add("legal_name");
        }
        if (!StringUtils.hasText(customer.getRegimenFiscal())) {
            missing.add("regimen_fiscal");
        }
        if (!StringUtils.hasText(customer.getFiscalZipCode())) {
            missing.add("fiscal_zip_code");
        }
        if (!StringUtils.hasText(customer.getDefaultUsoCfdi())) {
            missing.add("default_uso_cfdi");
        }
        return missing;
    }

    /**
     * The issuer's three required facts, or the refusal that names the ones missing.
     *
     * <p>Regime and place of issue are S1's, nullable on the company because they are "required to issue and
     * neither required to sell" ({@code L6}(a)). The legal name joins them because a document states
     * {@code emisor_name} and {@code companies.razon_social} is a nullable column: a company without a legal name
     * is a company that cannot issue, and falling back to the trade name would put a guess on a document the
     * authority witnesses.</p>
     */
    private static void requireIssuer(Company company) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(company.getRegimenFiscal())) {
            missing.add("regimen_fiscal");
        }
        if (!StringUtils.hasText(company.getFiscalZipCode())) {
            missing.add("fiscal_zip_code");
        }
        if (!StringUtils.hasText(company.getRazonSocial())) {
            missing.add("razon_social");
        }
        if (!missing.isEmpty()) {
            throw new FiscalIssuerProfileIncompleteException(company.getId(), missing);
        }
    }

    // ─── the snapshot ─────────────────────────────────────────────────────────────────────────────────

    /**
     * One concept per <b>enabled</b> sale line, in the order the sale wrote them.
     *
     * <p>The order is {@code created_at} and then {@code id}, because the sale stores no ordinal and a document
     * whose line order depended on the planner's would be a document that reprints differently. The whole list
     * is built — and therefore every refusal is raised — before the first write.</p>
     */
    private List<FrozenLine> snapshotLines(SalesOrder sale) {
        return salesOrderItemRepository.findBySalesOrderIdAndEnabledTrue(sale.getId()).stream()
                .sorted(Comparator.comparing(
                                SalesOrderItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(SalesOrderItem::getId))
                .map(this::freeze)
                .toList();
    }

    private FrozenLine freeze(SalesOrderItem item) {
        var variant = productVariantRepository
                .findById(item.getProductVariantId())
                .orElseThrow(() -> new IllegalStateException("Sale line " + item.getId() + " names variant "
                        + item.getProductVariantId() + ", which no longer exists: the concept's keys cannot be "
                        + "stated."));
        var product = productRepository
                .findByIdAndCompanyId(variant.getProductId(), variant.getCompanyId())
                .orElseThrow(() -> new IllegalStateException("Variant " + variant.getId() + " names product "
                        + variant.getProductId() + " of company " + variant.getCompanyId()
                        + ", which does not exist: the concept's key cannot be stated."));
        var unit = measureUnitRepository
                .findById(variant.getMeasureUnitId())
                .orElseThrow(() -> new IllegalStateException("Variant " + variant.getId() + " names measure unit "
                        + variant.getMeasureUnitId() + ", which no longer exists: the concept's key cannot be "
                        + "stated."));

        var productKey = requireKey(item.getId(), PRODUCT_KEY_COLUMN, product.getId(), product.getSatCode());
        var unitKey = requireKey(item.getId(), UNIT_KEY_COLUMN, unit.getId(), unit.getSatCode());
        var tax = freezeTax(item);

        return new FrozenLine(
                item.getId(),
                productKey,
                unitKey,
                describe(item, product, variant),
                item.getQuantity(),
                item.getListPrice(),
                item.getDiscountApplied(),
                item.getSubtotal(),
                item.getTaxCode(),
                tax,
                item.getTaxAmount());
    }

    private static String requireKey(UUID saleLine, String column, UUID holder, String key) {
        if (!StringUtils.hasText(key)) {
            throw new FiscalLineKeyMissingException(saleLine, column, holder);
        }
        return key;
    }

    /**
     * What the concept states of the tax: its object, its factor and its rate.
     *
     * <p>Three rules, all of them {@code L12}'s:</p>
     *
     * <ul>
     *   <li>{@code tax_object} is {@code 01} when the code is {@code NA} and {@code 02} otherwise (L12(e)); no
     *       code writes {@code 04}, which exists only in a secondary mirror.</li>
     *   <li>{@code tipo_factor} is the tax vocabulary's own mapping ({@code V103}); an {@code Exento} line states
     *       a factor and <b>no rate</b>, because a zero would be a rate and that is a different statement
     *       (L12(f)), and a not-subject line states <b>no factor either</b> ({@code V110}): the authority gives
     *       a concept with {@code ObjetoImp = 01} no {@code TipoFactor}, and borrowing {@code Exento} would
     *       say «subject and exempt» about an item that is neither.</li>
     *   <li>a code with no mapping is refused by name instead of guessed — that is the whole point of
     *       {@code V103}'s two nullable columns (S3, decision 11). {@code NA} is not such a code: it maps to no
     *       coordinates because "not subject" is the absence of a tax node.</li>
     * </ul>
     */
    private FrozenTax freezeTax(SalesOrderItem item) {
        var code = item.getTaxCode();
        var taxCode = taxCodeRepository
                .findById(code)
                .orElseThrow(() -> FiscalTaxMappingMissingException.ofUnknownCode(item.getId(), code));

        if (NOT_SUBJECT_TAX_CODE.equals(code)) {
            // Not subject to the tax is the absence of a tax node, and therefore the absence of a factor
            // (V110): `ObjetoImp = 01` states no `TipoFactor` in the authority's schema, and a frozen row that
            // borrowed `Exento` would say *subject and exempt* about an item that is neither.
            return new FrozenTax(TAX_OBJECT_NOT_SUBJECT, null, null);
        }

        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(taxCode.getSatImpuesto())) {
            missing.add("sat_impuesto");
        }
        if (!StringUtils.hasText(taxCode.getSatTipoFactor())) {
            missing.add("sat_tipo_factor");
        }
        if (!missing.isEmpty()) {
            throw FiscalTaxMappingMissingException.ofMissingColumns(item.getId(), code, missing);
        }

        var factor = taxCode.getSatTipoFactor();
        if (FACTOR_QUOTA.equals(factor) || (!FACTOR_RATE.equals(factor) && !FACTOR_EXEMPT.equals(factor))) {
            throw new FiscalTaxFactorUnsupportedException(item.getId(), code, factor);
        }

        var rate = FACTOR_RATE.equals(factor) ? item.getTaxRate() : null;
        return new FrozenTax(TAX_OBJECT_SUBJECT, factor, rate);
    }

    /**
     * What the concept states the customer bought.
     *
     * <p>The sale line's two name snapshots are the answer when they exist, and they are the answer this document
     * prefers: a rename in the catalog must not restate an issued document. They are <b>null on almost every
     * line</b> — {@code V81} backfilled the old ones and no application path has written one since — so a
     * description composed only from them would be empty for every sale made after that migration. The catalogs
     * are therefore the fallback, read here, once, by the only code allowed to read them.</p>
     */
    private static String describe(SalesOrderItem item, Product product, ProductVariant variant) {
        var productName =
                StringUtils.hasText(item.getProductNameSnapshot()) ? item.getProductNameSnapshot() : product.getName();
        var variantName = StringUtils.hasText(item.getVariantNameSnapshot())
                ? item.getVariantNameSnapshot()
                : variant.getVariantName();
        return StringUtils.hasText(variantName) ? productName + " " + variantName : productName;
    }

    // ─── the write ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The document header, with the money derived from the sale's parts.
     *
     * <p>{@code tax_withheld} is zero and not a decision: withholding is {@code T9}'s and the column exists so a
     * later slice can state it. The total is the parts' sum because the document has no rounding term
     * ({@code L9}(g)) — see the class javadoc.</p>
     */
    private FiscalDocument writeDocument(SalesOrder sale, Company company, Customer customer, String attemptKey) {
        var subtotal = sale.getSubtotal();
        var discount = sale.getDiscountTotal();
        var taxTransferred = sale.getTaxTotal();
        var taxWithheld = BigDecimal.ZERO;
        var total = subtotal.subtract(discount).add(taxTransferred).subtract(taxWithheld);

        var document = new FiscalDocument();
        document.setCompanyId(sale.getCompanyId());
        document.setSourceType(SOURCE_TYPE_SALES_ORDER);
        document.setSourceId(sale.getId());
        document.setStatus(FiscalDocument.STATUS_DRAFT);
        document.setAttemptKey(attemptKey);
        document.setIssuedBy(requireActor(currentUserContext));
        document.setReceptorRfc(customer.getRfc());
        document.setReceptorName(customer.getLegalName());
        document.setReceptorRegimen(customer.getRegimenFiscal());
        document.setReceptorZipCode(customer.getFiscalZipCode());
        document.setReceptorUsoCfdi(customer.getDefaultUsoCfdi());
        document.setEmisorRfc(company.getRfc());
        document.setEmisorName(company.getRazonSocial());
        document.setEmisorRegimen(company.getRegimenFiscal());
        document.setLugarExpedicion(company.getFiscalZipCode());
        document.setCurrency(currencyOf(sale));
        document.setSubtotal(subtotal);
        document.setDiscountTotal(discount);
        document.setTaxTransferred(taxTransferred);
        document.setTaxWithheld(taxWithheld);
        document.setTotalAmount(total);

        try {
            // The flush is what makes the id available to the lines below and what makes a lost race visible
            // here, where it can be named, instead of at the end of the transaction.
            return fiscalDocumentRepository.saveAndFlush(document);
        } catch (DataIntegrityViolationException refused) {
            if (mentionsAttemptKey(refused)) {
                throw new FiscalAttemptConflictException(sale.getId(), attemptKey);
            }
            throw refused;
        }
    }

    private void writeLines(FiscalDocument document, List<FrozenLine> frozen) {
        List<FiscalDocumentLine> lines = new ArrayList<>(frozen.size());
        for (var concept : frozen) {
            var line = new FiscalDocumentLine();
            line.setCompanyId(document.getCompanyId());
            line.setFiscalDocumentId(document.getId());
            line.setSalesOrderItemId(concept.salesOrderItemId());
            line.setClaveProdServ(concept.claveProdServ());
            line.setClaveUnidad(concept.claveUnidad());
            line.setDescription(concept.description());
            line.setQuantity(concept.quantity());
            line.setUnitValue(concept.unitValue());
            line.setDiscount(concept.discount());
            line.setBase(concept.base());
            line.setTaxCode(concept.taxCode());
            line.setTaxObject(concept.tax().taxObject());
            line.setTipoFactor(concept.tax().tipoFactor());
            line.setTasaOCuota(concept.tax().tasaOCuota());
            line.setTaxAmount(concept.taxAmount());
            lines.add(line);
        }
        fiscalDocumentLineRepository.saveAll(lines);
    }

    private FiscalDocumentDraft describe(FiscalDocument document, String attemptKey, boolean written) {
        var lineCount = fiscalDocumentLineRepository.countByFiscalDocumentId(document.getId());
        return new FiscalDocumentDraft(
                document.getId(),
                document.getCompanyId(),
                document.getSourceId(),
                attemptKey,
                document.getStatus(),
                Math.toIntExact(lineCount),
                written);
    }

    /**
     * The currency the document states: the sale's own.
     *
     * <p><b>Why a read and not a getter.</b> No entity maps {@code sales_orders.currency} — its one writer is
     * {@code V80}'s trigger, and mapping it would make it a field something could set — so the fiscal module
     * reads it through the query the sale's own repository already states for the same column. The document
     * freezes the sale's code and never re-derives it from the company: a document must not restate its own
     * money the day the company changes currency ({@code V109}).</p>
     */
    private String currencyOf(SalesOrder sale) {
        return salesOrderRepository
                .findCurrencyCode(sale.getId())
                .orElseThrow(() -> new IllegalStateException("Sale " + sale.getId()
                        + " was loaded and its currency cannot be read, so the document has no currency to state."));
    }

    /**
     * The actor the document attributes its issue to.
     *
     * <p>{@code issued_by} is {@code NOT NULL} and is not a fiscal fact, which is why it is the request's and not
     * the company's. A caller with no identity is refused here instead of writing a placeholder — the same
     * answer {@code StatusTransitionRecorder} gives for the same column shape, and for the same reason: an
     * actor nobody can stand behind is not an actor.</p>
     *
     * <p><b>Why it is shared with {@code FiscalDocumentCancellationService}.</b> A cancellation row states
     * {@code issued_by} for the same column and the same reason — cancelling is an act by a person and not a fact
     * about the company — so the rule lives in one place. Two copies would be two answers to "who asked for this"
     * the day one of them starts reading a different claim.</p>
     */
    static String requireActor(CurrentUserContext currentUserContext) {
        if (StringUtils.hasText(currentUserContext.getUserId())) {
            return currentUserContext.getUserId();
        }
        if (StringUtils.hasText(currentUserContext.getUsername())) {
            return currentUserContext.getUsername();
        }
        throw new IllegalStateException("A fiscal document states who issued it, and this request carries neither "
                + "a `sub` nor a `preferred_username` claim.");
    }

    /** Whether a refusal is the attempt key's, read from the driver's message and never assumed. */
    private static boolean mentionsAttemptKey(DataIntegrityViolationException refused) {
        return refused.getMessage() != null && refused.getMessage().contains("uq_fiscal_document_attempt");
    }

    /** One concept as it was frozen, before the row exists: the shape that keeps the write one pass. */
    private record FrozenLine(
            UUID salesOrderItemId,
            String claveProdServ,
            String claveUnidad,
            String description,
            BigDecimal quantity,
            BigDecimal unitValue,
            BigDecimal discount,
            BigDecimal base,
            String taxCode,
            FrozenTax tax,
            BigDecimal taxAmount) {

        private FrozenLine {
            Objects.requireNonNull(salesOrderItemId, "a concept states the sale line it comes from");
        }
    }

    /** The three facts of a concept's tax, as the authority's vocabulary states them. */
    private record FrozenTax(String taxObject, String tipoFactor, BigDecimal tasaOCuota) {}
}
