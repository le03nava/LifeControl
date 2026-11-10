package com.lifecontrol.api.currency.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.CURRENCY;

import com.lifecontrol.api.currency.dto.CurrencyRequest;
import com.lifecontrol.api.currency.dto.CurrencyResponse;
import com.lifecontrol.api.currency.service.CurrencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform currency catalog: the codes a company's operating currency may be picked from, and the
 * endpoints that maintain them.
 *
 * <p><b>Why four write verbs and not one.</b> They are the four {@code PaymentMethodController} carries
 * ({@code :54,:69,:96,:84}), because an ISO code, a retired flag and a row that five foreign keys
 * reference make every one of them a distinct operation: {@code POST} adds a code, {@code PUT} replaces
 * one, {@code PATCH /{code}/enable} retires or restores one, and {@code DELETE} removes one that nothing
 * references. Collapsing the last two into one verb would make "retire" and "delete" indistinguishable to
 * a caller, and the answer differs: a retire is a 200 with the row, a delete on a referenced code is a
 * 409.</p>
 *
 * <p><b>Why the path variable is {@code code} and not {@code id}.</b> The catalog has no generated
 * identity: an ISO 4217 code <b>is</b> the row, {@code companies.currency} references that column, and a
 * caller names the currency it wants by {@code MXN}. See {@code Currency}'s javadoc for why the column is
 * {@code CHAR(3)}.</p>
 *
 * <p><b>Why writes take a role and reads do not.</b> Reading the catalog grants nothing: it is the same
 * reference data {@code /api/countries} and {@code /api/measure-units} serve, and a caller who cannot read
 * it cannot fill the form that names a company's currency at all, so {@code isAuthenticated()} is the whole
 * requirement. Writing one is a privilege — a retired code stops being assignable to every company — so it
 * carries the same {@code hasAnyRole('lc-admin','lc-currency')} pair {@code MeasureUnitController} and
 * {@code SeniorityLevelController} state at method level: {@code lc-admin} for the administrator, and one
 * role per catalog for the operator who owns that catalog.</p>
 *
 * <p><b>Why it is on {@code TenantScopeFilter}'s tenant-agnostic list.</b> {@code currency} has no
 * {@code company_id} and no policy, so a token carrying several {@code company_id} claims is refused with
 * a 403 on any path that is not exempt — a refusal that would look like a security problem and would be a
 * missing list entry. {@code lc-currency} is deliberately absent from every {@code ScopeLevel} for the same
 * reason: a global catalog has no tenant in which to scope it.</p>
 */
@RestController
@RequestMapping("/api/currencies")
@Tag(name = "Currency Management", description = "API for the platform currency catalog")
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

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + CURRENCY + "')")
    @Operation(
            summary = "Add a currency to the catalog",
            description = "Adds one platform currency code. The code is the key, so an existing code is "
                    + "refused with a 409 instead of being overwritten.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Currency created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "409", description = "Currency code already exists")
    })
    public ResponseEntity<CurrencyResponse> createCurrency(@Valid @RequestBody CurrencyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(currencyService.createCurrency(request));
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + CURRENCY + "')")
    @Operation(
            summary = "Update a currency",
            description = "Replaces the minor units, symbol and enabled flag of one currency code. The code "
                    + "itself is the row's identity and cannot be renamed.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Currency updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Currency not found")
    })
    public ResponseEntity<CurrencyResponse> updateCurrency(
            @PathVariable String code, @Valid @RequestBody CurrencyRequest request) {
        return ResponseEntity.ok(currencyService.updateCurrency(code, request));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + CURRENCY + "')")
    @Operation(
            summary = "Delete a currency",
            description = "Hard-deletes one currency code. A code any row still references is refused with "
                    + "a 409; retiring it is PATCH /{code}/enable.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Currency deleted"),
        @ApiResponse(responseCode = "404", description = "Currency not found"),
        @ApiResponse(responseCode = "409", description = "Currency is referenced and cannot be deleted")
    })
    public ResponseEntity<Void> deleteCurrency(@PathVariable String code) {
        currencyService.deleteCurrency(code);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{code}/enable")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + CURRENCY + "')")
    @Operation(
            summary = "Enable or retire a currency",
            description = "Sets the enabled flag of one currency code. A retired code keeps resolving for "
                    + "history and can no longer be assigned to a company.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Currency status updated"),
        @ApiResponse(responseCode = "400", description = "The enabled flag states no value"),
        @ApiResponse(responseCode = "404", description = "Currency not found")
    })
    public ResponseEntity<CurrencyResponse> setCurrencyEnabled(
            @PathVariable String code, @RequestBody Map<String, Boolean> body) {
        return ResponseEntity.ok(currencyService.setCurrencyEnabled(code, enabledFlag(body)));
    }

    /**
     * Reads the flag out of the request body.
     *
     * <p><b>Why a present key with a {@code null} value is not the default.</b> {@code {"enabled": null}}
     * and {@code {}} are different requests: the first states the field and gives it no value, and the
     * second does not mention it at all. Answering the first with the default would mean this endpoint
     * silently turned "no value" into "enable it", which is the one direction an operator never means when
     * they send the field. It also leaked a 500: {@code getOrDefault} returns {@code null} for a present
     * null, and unboxing it into the {@code boolean} parameter raised an NPE that reached the catch-all
     * handler. A request this endpoint can judge is therefore judged: 400, naming the field.
     *
     * <p>{@code PaymentMethodController#setPaymentMethodEnabled} has the same shape and the same unboxing
     * defect. It is outside this unit's edit surface and is left as a separate fix rather than fixed here.
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
