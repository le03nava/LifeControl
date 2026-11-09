package com.lifecontrol.api.tax.controller;

import com.lifecontrol.api.tax.dto.TaxCodeResponse;
import com.lifecontrol.api.tax.service.TaxCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only surface of the platform tax-code catalog: the codes a product's tax code may name.
 *
 * <p><b>Why one {@code GET} and nothing else.</b> The catalog is reference data seeded by migration
 * ({@code V82:45-49}), and the operation this endpoint exists for is the product form filling its tax-code
 * select — which is a read. There is no {@code POST}/{@code PUT}/{@code DELETE} because there is no screen
 * that edits a code, and an endpoint nobody's workflow reaches is an unowned write path on a table
 * {@code tax_rate.tax_code} references.</p>
 *
 * <p><b>Why {@code isAuthenticated()} and not a role.</b> Reading the catalog grants nothing: it is the same
 * public reference data {@code /api/currencies} and {@code /api/countries} serve, and a caller who cannot
 * read it cannot fill the form that names a product's tax code at all. Authentication is the whole
 * requirement, and a request with no token is a 401.</p>
 *
 * <p><b>Why it is on {@code TenantScopeFilter}'s tenant-agnostic list.</b> {@code tax_code} has no
 * {@code company_id} and no policy ({@code V82:14-17}), so a token carrying several {@code company_id}
 * claims is refused with a 403 on any path that is not exempt — a refusal that would look like a security
 * problem and would be a missing list entry. Which is also why this endpoint and
 * {@code /api/currencies} are the two catalog reads whose exemption
 * {@code TenantScopeFilterIntegrationTest} pins in both directions.</p>
 */
@RestController
@RequestMapping("/api/tax-codes")
@Tag(name = "Tax Code Management", description = "API for reading the platform tax-code catalog")
public class TaxCodeController {

    private final TaxCodeService taxCodeService;

    public TaxCodeController(TaxCodeService taxCodeService) {
        this.taxCodeService = taxCodeService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get all tax codes",
            description = "Returns the platform tax-code catalog. Only enabled codes are returned unless "
                    + "?includeDisabled=true is passed, which also returns the retired ones.")
    public ResponseEntity<List<TaxCodeResponse>> getAllTaxCodes(
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(taxCodeService.getAllTaxCodes(includeDisabled));
    }
}
