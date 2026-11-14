package com.lifecontrol.api.accounting.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.SALES;

import com.lifecontrol.api.accounting.dto.GrossMarginDocumentResponse;
import com.lifecontrol.api.accounting.dto.GrossMarginStoresResponse;
import com.lifecontrol.api.accounting.dto.SalesJournalEntryResponse;
import com.lifecontrol.api.accounting.dto.TrialBalanceResponse;
import com.lifecontrol.api.accounting.service.AccountingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read side of the derived book: the sales journal, the trial balance and the gross margin by document and
 * by store ({@code S18}, T9 and T10 of {@code odd/tasks/derived-accounting.md}).
 *
 * <p><b>Every path nests under {@code /api/companies/{companyId}}</b> for the reason the whole tree does: the
 * company of an operation is the path variable, and {@code TenantScopeFilter} states it — verifying it against
 * the caller's claims — before any controller runs. That is also what makes these endpoints work at all:
 * {@code account_entry} and {@code account_entry_line} are armed with {@code FORCE} row-level security, so a
 * report read without a stated tenant returns zero rows and looks clean, which is exactly
 * {@code ADR-0008} decision 9's failure. There is no report on this controller that can be read unscoped.</p>
 *
 * <p><b>The gate is {@code lc-admin} / {@code lc-sales}, and there is no {@code lc-accounting} role in this
 * slice</b> ({@code L15(b)}). A role is a realm contract change with its own provisioning, screens and
 * {@code keycloak-setup.sh} wiring, and the subject of these three reports is facts those two roles already
 * see. Inventing one in a read-only slice would ship an endpoint nobody can be granted.</p>
 *
 * <p><b>{@code from} and {@code to} are inclusive business dates</b> compared against the entry's own date, an
 * absent bound is an open end, and an inverted range is a 400 that names both values. An empty result is a 200
 * with no rows and never a 404: the company exists, its book simply has nothing between those dates.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/accounting")
@Tag(name = "Accounting Reports", description = "Read models over the derived book: journal, trial balance and margin")
public class AccountingReportController {

    private final AccountingReportService accountingReportService;

    public AccountingReportController(AccountingReportService accountingReportService) {
        this.accountingReportService = accountingReportService;
    }

    @GetMapping("/sales-journal")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "The sales journal of a date range",
            description = "Returns the posted entries with their lines, ordered by entry date and id. Both bounds "
                    + "are inclusive business dates compared against the entry's own date; an absent bound is an "
                    + "open end. The account is by code and name, and exactly one side of every line is zero.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page of entries, each with its lines"),
        @ApiResponse(responseCode = "400", description = "The range ends before it starts"),
        @ApiResponse(responseCode = "403", description = "The company is not the caller's")
    })
    public ResponseEntity<Page<SalesJournalEntryResponse>> getSalesJournal(
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(accountingReportService.salesJournal(companyId, from, to, pageable));
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "The trial balance of a date range",
            description = "One row per (currency, account) with activity in the range, plus the two sides of each "
                    + "currency's total. Currencies are never added together: the tenant's currency is editable "
                    + "and there is no exchange rate. The range serves the period today; period close is a later "
                    + "slice of the same unit.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The rows and the totals per currency"),
        @ApiResponse(responseCode = "400", description = "The range ends before it starts"),
        @ApiResponse(responseCode = "403", description = "The company is not the caller's")
    })
    public ResponseEntity<TrialBalanceResponse> getTrialBalance(
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(accountingReportService.trialBalance(companyId, from, to));
    }

    @GetMapping("/gross-margin/documents")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Gross margin by document over a date range",
            description = "Returns a page of the documents of the range, each with what it earned against the "
                    + "cost the ledger froze for it, ordered by order date, order number and document id. A "
                    + "document whose cost never posted reports a null cost and a null margin; "
                    + "unknownCostDocuments counts those documents over the whole range, never over the page, so "
                    + "it is a property of the report and not of the twenty rows in hand.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The page of document rows and the range's unknown-cost count"),
        @ApiResponse(responseCode = "400", description = "The range ends before it starts"),
        @ApiResponse(responseCode = "403", description = "The company is not the caller's")
    })
    public ResponseEntity<Page<GrossMarginDocumentResponse>> getGrossMarginByDocument(
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(accountingReportService.grossMarginByDocument(companyId, from, to, pageable));
    }

    @GetMapping("/gross-margin/stores")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + SALES + "')")
    @Operation(
            summary = "Gross margin by store over a date range",
            description = "One bucket per (currency, store) over the whole range, computed by its own group by "
                    + "and never folded out of the paged document report. A bucket holding a document with an "
                    + "unknown cost reports no cost and no margin, with the count of documents that caused it, "
                    + "instead of a total that treats the missing cost as zero.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "One bucket per store and currency"),
        @ApiResponse(responseCode = "400", description = "The range ends before it starts"),
        @ApiResponse(responseCode = "403", description = "The company is not the caller's")
    })
    public ResponseEntity<GrossMarginStoresResponse> getGrossMarginByStore(
            @PathVariable UUID companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(accountingReportService.grossMarginByStore(companyId, from, to));
    }
}
