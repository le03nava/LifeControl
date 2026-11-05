package com.lifecontrol.api.currency.controller;

import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.service.CurrencyService;
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
 * The read-only surface of the platform currency catalog: the codes a company's operating currency may be
 * picked from.
 *
 * <p><b>Why one {@code GET} and nothing else.</b> The catalog is reference data seeded by migration, and
 * the operation this endpoint exists for is the company form filling its currency select — which is a
 * read. There is no {@code POST}/{@code PUT}/{@code DELETE} because there is no screen that edits a
 * currency, and an endpoint nobody's workflow reaches is an unowned write path on a table
 * {@code companies.currency} references.</p>
 *
 * <p><b>Why {@code isAuthenticated()} and not a role.</b> Reading the catalog grants nothing: it is the
 * same public reference data {@code /api/countries} and {@code /api/measure-units} serve, and a caller who
 * cannot read it cannot fill the form that names a company's currency at all. Authentication is the whole
 * requirement, and a request with no token is a 401.</p>
 *
 * <p><b>Why it is on {@code TenantScopeFilter}'s tenant-agnostic list.</b> {@code currency} has no
 * {@code company_id} and no policy, so a token carrying several {@code company_id} claims is refused with
 * a 403 on any path that is not exempt — a refusal that would look like a security problem and would be a
 * missing list entry (S3).</p>
 */
@RestController
@RequestMapping("/api/currencies")
@Tag(name = "Currency Management", description = "API for reading the platform currency catalog")
public class CurrencyController {

    private final CurrencyService currencyService;

    public CurrencyController(CurrencyService currencyService) {
        this.currencyService = currencyService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Get all currencies",
            description = "Returns the platform currency catalog. Only enabled codes are returned unless "
                    + "?includeDisabled=true is passed, which also returns the retired ones.")
    public ResponseEntity<List<CurrencyResponse>> getAllCurrencies(
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled) {
        return ResponseEntity.ok(currencyService.getAllCurrencies(includeDisabled));
    }
}
