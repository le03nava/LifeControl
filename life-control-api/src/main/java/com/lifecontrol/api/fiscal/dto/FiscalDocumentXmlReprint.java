package com.lifecontrol.api.fiscal.dto;

import java.util.UUID;
import org.springframework.util.StringUtils;

/**
 * What a reprint serves: the document it belongs to, the folio it states, and the bytes that were stamped
 * ({@code S10} of {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L15}(a) of the same record).
 *
 * <p><b>Why the bytes are a {@code byte[]} and not a {@code String}.</b> {@code S10} says the artefact returns
 * the bytes that were stamped, and a {@code String} would invite re-encoding on the way out: the artefact is the
 * authority's copy, so the only correct transformation between the row and the response is none. The bytes are
 * encoded once, from the stored text, with the charset the stamp wrote it in.</p>
 *
 * <p><b>Why the file name is derived here and not by the controller.</b> "The folio when present, else the id"
 * is one rule about what a reprint is called, and two places deriving it would be two places free to disagree
 * about which document a downloaded file belongs to.</p>
 *
 * @param fiscalDocumentId the document the bytes belong to, which is also the artefact's primary key
 * @param folio the optional local convention {@code fiscal_document.folio} states, or {@code null} when it
 *     states none (ADR-0007 decision 2 makes it optional)
 * @param xml the bytes the authority witnessed, encoded and never re-serialised
 */
public record FiscalDocumentXmlReprint(UUID fiscalDocumentId, String folio, byte[] xml) {

    /**
     * The name the download carries: the folio when the document states one, else its id, always with the
     * {@code .xml} extension so the file opens as what it is. {@code L15}(b) keeps the disposition an
     * <b>attachment</b> precisely so a third-party document is not rendered inline, and a name is the other half
     * of a download a human can find again.
     */
    public String fileName() {
        return (StringUtils.hasText(folio) ? folio : fiscalDocumentId.toString()) + ".xml";
    }
}
