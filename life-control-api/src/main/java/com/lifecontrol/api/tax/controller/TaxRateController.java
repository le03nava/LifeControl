package com.lifecontrol.api.tax.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.TAX;

import com.lifecontrol.api.tax.dto.TaxRateRequest;
import com.lifecontrol.api.tax.dto.TaxRateResponse;
import com.lifecontrol.api.tax.dto.TaxRateSupersedeRequest;
import com.lifecontrol.api.tax.service.TaxRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Company-scoped tax-rate endpoints — issue #858, the write half of ADR-0002 decision 6.
 *
 * <p>Every path nests under {@code /api/companies/{companyId}} for the reason the whole tree does: the company
 * of an operation is the path variable, and {@code TenantScopeFilter} states it (verifying it against the
 * caller's claims) before any controller runs. That is also what makes these endpoints work at all — {@code
 * tax_rate} is armed with {@code FORCE} row-level security, so a write without a stated tenant is refused by
 * the policy rather than landed in the wrong company's history.</p>
 *
 * <p><b>Reads require only authentication; writes require {@code lc-admin} or {@code lc-tax}.</b> Rates are
 * configuration a company files, not a tenancy level, so {@code lc-tax} is a functional role
 * ({@code Roles.TAX}) and the company boundary is the path plus the policy.</p>
 *
 * <p><b>A rate can name a region, and naming none is the company-wide rate</b> (issue #855). The resource gained
 * no route for it: {@code regionId} travels in the create body and comes back on every read, because a rate's
 * scope is one of its fields and not a sub-resource — two rates for the same code and day differ exactly by it
 * ({@code V95}'s two partial unique indexes). A region that is not this company's is a 404 naming the id, so a
 * foreign region is never stored ({@code S7}).</p>
 *
 * <p><b>There is no DELETE and no way to edit a rate in place.</b> A rate is corrected by appending the next
 * {@code effective_from} ({@code PUT} does exactly that, naming the row it supersedes) or retired by
 * {@code PATCH …/enable}. Deleting a row would delete what a document dated before it must keep resolving.</p>
 */
@RestController
@RequestMapping("/api/companies/{companyId}/tax-rates")
@Tag(name = "Tax Rate Management", description = "API for stating the tax rates a company files")
public class TaxRateController {

    private final TaxRateService taxRateService;

    public TaxRateController(TaxRateService taxRateService) {
        this.taxRateService = taxRateService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "List a company's tax rates",
            description = "Returns the company's rates ordered by tax code then newest effectiveFrom first; "
                    + "retired rates are excluded unless includeDisabled is true")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "List of tax rates")})
    public ResponseEntity<List<TaxRateResponse>> getTaxRates(
            @PathVariable UUID companyId, @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(taxRateService.getTaxRates(companyId, includeDisabled));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get a tax rate by ID",
            description = "Returns a single rate of the company; a rate of another company is not found")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tax rate found"),
        @ApiResponse(responseCode = "404", description = "Tax rate or company not found")
    })
    public ResponseEntity<TaxRateResponse> getTaxRateById(@PathVariable UUID companyId, @PathVariable UUID id) {
        return ResponseEntity.ok(taxRateService.getTaxRate(companyId, id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + TAX + "')")
    @Operation(
            summary = "Declare a tax rate",
            description = "Appends a rate for the company, effective from the stated day; regionId chooses the "
                    + "scope (absent means the company-wide rate), and the (company, tax code, day) key is per scope")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tax rate declared"),
        @ApiResponse(responseCode = "400", description = "Validation error, e.g. a rate outside [0, 1]"),
        @ApiResponse(
                responseCode = "404",
                description = "Company not found, the tax code is not in the catalog, or the region is not this "
                        + "company's"),
        @ApiResponse(responseCode = "409", description = "The scope already states a rate for that code on that day")
    })
    public ResponseEntity<TaxRateResponse> declareTaxRate(
            @PathVariable UUID companyId, @Valid @RequestBody TaxRateRequest request) {
        var response = taxRateService.declareTaxRate(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + TAX + "')")
    @Operation(
            summary = "Supersede a tax rate",
            description = "Appends the successor of the rate named by id, effective from a later day and in the "
                    + "same scope (a company-wide rate's successor is company-wide, a region's is that region's); "
                    + "the named row is left untouched so documents dated before the successor keep resolving it")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Successor declared; the body is the rate now applying"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation error, or a successor not after the superseded row"),
        @ApiResponse(responseCode = "404", description = "Tax rate or company not found"),
        @ApiResponse(responseCode = "409", description = "That scope already states a rate for that code on that day")
    })
    public ResponseEntity<TaxRateResponse> supersedeTaxRate(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody TaxRateSupersedeRequest request) {
        return ResponseEntity.ok(taxRateService.supersedeTaxRate(companyId, id, request));
    }

    @PatchMapping("/{id}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + TAX + "')")
    @Operation(
            summary = "Retire or reinstate a tax rate",
            description = "Sets the enabled flag of a rate of the company; a retired rate keeps its row and "
                    + "stops being answered by the resolution")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tax rate status updated"),
        @ApiResponse(responseCode = "400", description = "The enabled flag states no value"),
        @ApiResponse(responseCode = "404", description = "Tax rate or company not found")
    })
    public ResponseEntity<TaxRateResponse> setTaxRateEnabled(
            @PathVariable UUID companyId, @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        return ResponseEntity.ok(taxRateService.setTaxRateEnabled(companyId, id, enabledFlag(body)));
    }

    /**
     * Reads the flag out of the request body.
     *
     * <p><b>Why a present key with a {@code null} value is not the default.</b> {@code {"enabled": null}} and
     * {@code {}} are different requests: the first states the field and gives it no value, and the second does
     * not mention it at all. Answering the first with the default would mean this endpoint silently turned
     * "no value" into "enable it" — the one direction an operator never means when they send the field, and
     * for a rate it means a retired rate starts applying to documents again. It also leaked a 500: {@code
     * getOrDefault} returns {@code null} for a present null, and unboxing it into the {@code boolean}
     * parameter raised an NPE that reached the catch-all handler. A request this endpoint can judge is
     * therefore judged: 400, naming the field.
     */
    private static boolean enabledFlag(Map<String, Boolean> body) {
        if (!body.containsKey("enabled")) {
            return true;
        }
        var flag = body.get("enabled");
        if (flag == null) {
            throw new IllegalArgumentException("enabled must be true or false when stated: it cannot be null");
        }
        return flag;
    }
}
