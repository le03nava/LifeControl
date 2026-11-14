package com.lifecontrol.api.accounting.dto;

import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * One page of the gross margin by document, plus the one value that is <b>not</b> about the page: how many
 * documents of the <b>whole range</b> have an unknown cost.
 *
 * <p><b>Why this is a {@code Page} and not a record with a list.</b> A month of a busy tenant is thousands of
 * documents, and every growing list in this tree is a {@code Page} — decision 8 of the brief that said otherwise
 * was wrong. And paging is incompatible with counting: a count taken over a page would state how many documents
 * <em>of that page</em> have an unknown cost, which reads as a property of the report and is a property of twenty
 * rows.</p>
 *
 * <p><b>Why the count rides on the page instead of a header.</b> It is a value of the response body — a client
 * that generates its types from the document should be able to read it without a header lookup — and a header
 * would make it invisible to the JSON contract this endpoint publishes. It comes from its own statement over the
 * whole range ({@code AccountingReportService}), and the javadoc there says which one.</p>
 *
 * <p>Extending {@code PageImpl} rather than wrapping it keeps the response a {@code Page} on the wire: the extra
 * getter is serialized beside {@code content}, {@code totalElements} and the rest, so a caller that already reads
 * this tree's paged endpoints sees the same shape with one more field.</p>
 */
public class GrossMarginDocumentsPage extends PageImpl<GrossMarginDocumentResponse> {

    private final long unknownCostDocuments;

    public GrossMarginDocumentsPage(
            List<GrossMarginDocumentResponse> content, Pageable pageable, long total, long unknownCostDocuments) {
        super(content, pageable, total);
        this.unknownCostDocuments = unknownCostDocuments;
    }

    /**
     * How many documents of the whole range report a null cost — the {@code S15} limitation, counted over the
     * range and never over the page.
     */
    public long getUnknownCostDocuments() {
        return unknownCostDocuments;
    }

    /**
     * A page and its range-scoped count are equal only when both halves are: two responses that print the same
     * twenty rows but state different unknown-cost counts answer a different question, and {@code PageImpl}'
     * equals would call them the same.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GrossMarginDocumentsPage that)) {
            return false;
        }
        return super.equals(other) && unknownCostDocuments == that.unknownCostDocuments;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), unknownCostDocuments);
    }
}
