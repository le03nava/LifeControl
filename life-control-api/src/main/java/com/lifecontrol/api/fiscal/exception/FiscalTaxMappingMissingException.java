package com.lifecontrol.api.fiscal.exception;

import com.lifecontrol.api.exception.ConflictException;
import java.util.List;
import java.util.UUID;

/**
 * The line's tax code cannot be mapped onto the authority's two coordinates, so the document cannot state what
 * tax it carries ({@code S3} of {@code odd/tasks/cfdi-fiscal-document.md}, {@code V103}, decision {@code L6}(d)
 * of the same record).
 *
 * <p><b>Why a code with no mapping has to be a named refusal.</b> {@code V103} adds {@code sat_impuesto} and
 * {@code sat_tipo_factor} as <b>nullable</b> columns on purpose: decision 11 refuses to pin the authority's
 * catalog in this tree, so a code nobody mapped stays unmapped instead of being guessed. The consequence is that
 * the stamp refuses it by name — the alternative is a document stating a tax nobody decided.</p>
 *
 * <p><b>Why {@code NA} is not this refusal.</b> {@code V103} maps {@code NA} to <b>both nulls</b> deliberately:
 * "not subject to the tax" is the absence of a tax node and not a tax of zero. The writer states the object
 * {@code 01} for it and never asks this question; every other code has to answer it.</p>
 */
public class FiscalTaxMappingMissingException extends ConflictException {

    private FiscalTaxMappingMissingException(String message) {
        super(message);
    }

    /** A code that is in the catalog and states none of the coordinates a document needs. */
    public static FiscalTaxMappingMissingException ofMissingColumns(
            UUID salesOrderItemId, String taxCode, List<String> columns) {
        return new FiscalTaxMappingMissingException("Sale line " + salesOrderItemId + " carries tax code '"
                + taxCode + "' and tax_code." + String.join(", tax_code.", columns)
                + " is not declared, so the document cannot state the tax it carries. A mapping is a decision "
                + "with a document behind it: declare it in a migration (V103's shape) or leave the code unmapped "
                + "so the stamp refuses it here (S3, ADR-0007 decision 11).");
    }

    /** A code the platform catalog does not carry at all: a sale line whose tax nobody can name. */
    public static FiscalTaxMappingMissingException ofUnknownCode(UUID salesOrderItemId, String taxCode) {
        return new FiscalTaxMappingMissingException("Sale line " + salesOrderItemId + " carries tax code '"
                + taxCode + "', which the platform tax catalog does not hold. A code is the one thing a document "
                + "states about a tax, so a line naming one that does not exist cannot be stated at all (S3).");
    }
}
