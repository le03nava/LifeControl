package com.lifecontrol.api.customer.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The receiver's fiscal profile is <b>all-or-nothing at write time</b>: a request that states any of the four
 * facts a stamp consumes — {@code legalName}, {@code regimenFiscal}, {@code fiscalZipCode} and
 * {@code defaultUsoCfdi} — must state all four, and one that states none of them is a complete cash customer
 * (S2 of {@code odd/tasks/cfdi-fiscal-document.md}, decision L8(e) of the same record).
 *
 * <p><b>Why the rule exists at all.</b> The alternative L8(e) refused is a half profile "waiting for the
 * document to refuse it": it looks like a profile in every listing and cannot be used in one, because a stamp
 * needs all four facts and the customer's operator has no way to tell which of them is missing.</p>
 *
 * <p><b>Why a class-level constraint and not the tree's {@code FieldViolationException} family.</b> That family
 * exists to serve <b>one</b> field — {@code FieldViolationException#getField()} is a single key — and the
 * refusal this rule owes the client names <b>each</b> missing field, so one entry per missing fact is the
 * answer. A class-level constraint can add one violation per property (which the tree's
 * {@code GlobalExceptionHandler#handleValidationErrors} already turns into
 * {@code errors: {legalName: …, fiscalZipCode: …}}), and it runs at the boundary the rule is about: the request.
 * The single-value refusals beside it — an unknown regime code, an unknown use — keep the family's shape
 * ({@code InvalidReceiverRegimenFiscalException}, {@code InvalidReceiverUsoCfdiException}), because each of those
 * <b>is</b> about one field.</p>
 *
 * <p><b>Why not a {@code CHECK}.</b> "If a request states any of the four it must state all four" is a rule
 * about a request, not about a row: a row with none of them and a row with all four are both legal, so the
 * schema admits both and the write path is where a partial one is refused. See
 * {@code V107__customer_fiscal_profile.sql} for the same argument stated to the schema.</p>
 */
@Documented
@Constraint(validatedBy = FiscalProfileCompleteValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FiscalProfileComplete {

    String message() default
            "The receiver's fiscal profile is all-or-nothing: legalName, regimenFiscal, fiscalZipCode and "
                    + "defaultUsoCfdi are stated together, or none of them is.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
