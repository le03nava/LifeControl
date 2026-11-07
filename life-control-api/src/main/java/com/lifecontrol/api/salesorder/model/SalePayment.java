package com.lifecontrol.api.salesorder.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One payment against a sales order (ADR-0002 decision 5, migration {@code V83__sale_payment.sql}).
 *
 * <p><b>Why a row and not a column.</b> The document used to carry one {@code payment_method_id}, which
 * cannot express mixed tender, change, a partial refund or a drawer report per method. A sale may have many
 * payments, and each of them is a row with its own method, its own amount and its own moment.</p>
 *
 * <p><b>Why this entity has no setters.</b> The schema makes the table append-only: a
 * {@code BEFORE UPDATE OR DELETE} trigger refuses the write and {@code REVOKE UPDATE, DELETE} makes the
 * application's role unable to attempt it. A void is a {@code sale_payment_void} row that names a payment,
 * never an edit of this one. A mutable mapping would be a promise the database refuses to keep, so the
 * object is built and inserted, and nothing in the tree updates it afterwards.</p>
 *
 * <p><b>Why {@code company_id} is stated and not derived.</b> {@code fk_payment_order_same_company} makes
 * the pair {@code (company_id, sales_order_id)} a foreign key to the order, so a mis-stated tenant is refused
 * by name instead of being hidden by the row-level policy, which filters rather than fails (invariant M9).</p>
 */
@Entity
@Table(name = "sale_payment")
public class SalePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "sales_order_id", nullable = false, updatable = false)
    private UUID salesOrderId;

    @Column(name = "method_id", nullable = false, updatable = false)
    private UUID methodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 8, nullable = false, updatable = false)
    private SalePaymentDirection direction;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    /** What the customer handed over; null unless the method is a cash method and the tender was stated. */
    @Column(name = "tendered_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal tenderedAmount;

    /** What came back; held to {@code tendered_amount - amount} by {@code ck_payment_tender}. */
    @Column(name = "change_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal changeAmount;

    @Column(name = "reference", length = 100, updatable = false)
    private String reference;

    /** The moment the money moved. A backfill reads the order's own date here, never {@code now()}. */
    @Column(name = "paid_at", nullable = false, updatable = false)
    private OffsetDateTime paidAt;

    @Column(name = "actor", length = 255, nullable = false, updatable = false)
    private String actor;

    protected SalePayment() {}

    /**
     * A charge of {@code amount} through {@code methodId}, with the tender the operator counted or none at all.
     *
     * <p><b>Why the direction is not a parameter.</b> D-g: this factory writes {@code CHARGE} and nothing else,
     * because the endpoint that records money coming in is not the endpoint money leaves by. A {@code REFUND} row
     * stays expressible in the schema and waits for the unit that authorizes one.</p>
     *
     * <p><b>Why the tender pair is one pair and not two optional arguments with defaults.</b>
     * {@code ck_payment_tender} admits both or neither, so a call that passed one would be a row the schema refuses
     * at flush. {@code null} means no cash was counted — never zero, which would report a customer who handed over
     * nothing. The arithmetic ({@code tendered >= amount}, {@code change = tendered - amount}) is the caller's to
     * satisfy; the constraint is what makes it true.</p>
     */
    public static SalePayment charge(
            UUID companyId,
            UUID salesOrderId,
            UUID methodId,
            BigDecimal amount,
            BigDecimal tenderedAmount,
            BigDecimal changeAmount,
            String actor) {
        var payment = new SalePayment();
        payment.companyId = companyId;
        payment.salesOrderId = salesOrderId;
        payment.methodId = methodId;
        payment.direction = SalePaymentDirection.CHARGE;
        payment.amount = amount;
        payment.tenderedAmount = tenderedAmount;
        payment.changeAmount = changeAmount;
        payment.paidAt = OffsetDateTime.now();
        payment.actor = actor;
        return payment;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getSalesOrderId() {
        return salesOrderId;
    }

    public UUID getMethodId() {
        return methodId;
    }

    public SalePaymentDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getTenderedAmount() {
        return tenderedAmount;
    }

    public BigDecimal getChangeAmount() {
        return changeAmount;
    }

    public String getReference() {
        return reference;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public String getActor() {
        return actor;
    }
}
