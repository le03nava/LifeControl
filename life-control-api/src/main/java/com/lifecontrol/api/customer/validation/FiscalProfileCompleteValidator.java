package com.lifecontrol.api.customer.validation;

import com.lifecontrol.api.customer.dto.CustomerRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The all-or-nothing rule of the receiver's fiscal profile, stated once: which four facts a stamp consumes, and
 * that a request states either all of them or none of them ({@link FiscalProfileComplete}, decision L8(e) of
 * {@code odd/tasks/cfdi-fiscal-document.md}).
 *
 * <p><b>Why one violation per missing field.</b> The client marks controls, not prose: a request that states the
 * postal code and the use and forgets the regime has to learn <b>which</b> fact is missing, and the tree's
 * validation envelope already carries that map ({@code errors: {regimenFiscal: …}}). The sentence of each
 * violation names the field too, so a log reader does not need the map either.</p>
 *
 * <p><b>Why the optional half is not consulted.</b> {@code fiscalStreet}, {@code fiscalState} and
 * {@code fiscalCountryId} are carried and optional, and a domicile without a regime is a domicile and not half a
 * profile (L8(e)): what a stamp cannot omit of the receiver's address is the postal code. Consulting them here
 * would make a street typed on a cash customer a 400 for four facts nobody asked for.</p>
 */
public class FiscalProfileCompleteValidator implements ConstraintValidator<FiscalProfileComplete, CustomerRequest> {

    /**
     * The facts a stamp consumes, in the order the response states them. This map is the one place the rule is
     * written: {@link CustomerRequest} declares the components, the enum vocabularies resolve their values and
     * {@code V107} constrains their shape, but <b>which four are required together</b> is decided here.
     */
    private static final Map<String, String> REQUIRED_FACTS = requiredFacts();

    private static Map<String, String> requiredFacts() {
        var facts = new LinkedHashMap<String, String>();
        facts.put("legalName", "the receiver's legal name as the fiscal document states it");
        facts.put("regimenFiscal", "the receiver's SAT regime (c_RegimenFiscal)");
        facts.put("fiscalZipCode", "the receiver's fiscal postal code, five digits");
        facts.put("defaultUsoCfdi", "the receiver's default SAT use of the voucher (c_UsoCFDI)");
        // `Map.copyOf` would drop the insertion order, and the order is what makes a refusal's sentence read the
        // same on every run.
        return Collections.unmodifiableMap(facts);
    }

    @Override
    public boolean isValid(CustomerRequest request, ConstraintValidatorContext context) {
        // A null body is not this constraint's business; bean validation answers it with its own violation.
        if (request == null) {
            return true;
        }

        var missing = new LinkedHashMap<String, String>();
        REQUIRED_FACTS.forEach((field, description) -> {
            if (!isStated(request, field)) {
                missing.put(field, description);
            }
        });

        // None stated is a cash customer and all four stated is a profile; only a partial one is refused.
        if (missing.isEmpty() || missing.size() == REQUIRED_FACTS.size()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        missing.forEach(
                (field, description) -> context.buildConstraintViolationWithTemplate(field + " is required when any of "
                                + String.join(", ", REQUIRED_FACTS.keySet())
                                + " is stated: the receiver's fiscal profile is all-or-nothing, and " + description
                                + " is part of it. A customer with no fiscal profile leaves all four absent instead.")
                        .addPropertyNode(field)
                        .addConstraintViolation());
        return false;
    }

    /**
     * Whether the request states one of the four facts.
     *
     * <p>A blank value is not a declaration: {@link CustomerRequest}'s canonical constructor already collapses
     * blank to {@code null}, so the only question left here is null or not. The reflection-free lookup keeps the
     * rule readable next to the record it is about.</p>
     */
    private static boolean isStated(CustomerRequest request, String field) {
        return switch (field) {
            case "legalName" -> request.legalName() != null;
            case "regimenFiscal" -> request.regimenFiscal() != null;
            case "fiscalZipCode" -> request.fiscalZipCode() != null;
            case "defaultUsoCfdi" -> request.defaultUsoCfdi() != null;
            default ->
                throw new IllegalStateException(
                        "FiscalProfileCompleteValidator names a fact CustomerRequest does not declare: " + field);
        };
    }
}
