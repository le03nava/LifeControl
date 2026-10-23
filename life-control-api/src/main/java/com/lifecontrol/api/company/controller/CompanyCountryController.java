package com.lifecontrol.api.company.controller;

import static com.lifecontrol.api.common.security.Roles.ADMIN;
import static com.lifecontrol.api.common.security.Roles.COMPANY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY;
import static com.lifecontrol.api.common.security.Roles.COMPANY_COUNTRY_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION;
import static com.lifecontrol.api.common.security.Roles.COMPANY_REGION_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_STORE_READ;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE;
import static com.lifecontrol.api.common.security.Roles.COMPANY_ZONE_READ;
import static com.lifecontrol.api.common.security.Roles.EMPLOYEE;
import static com.lifecontrol.api.common.security.Roles.RECEIVING;

import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.service.CompanyCountryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/countries")
@Tag(name = "Company Country Management", description = "API for managing country associations for a company")
public class CompanyCountryController {

    private final CompanyCountryService companyCountryService;

    public CompanyCountryController(CompanyCountryService companyCountryService) {
        this.companyCountryService = companyCountryService;
    }

    /**
     * Lists the company's countries, bounded by the caller's own scope.
     *
     * <p>The allow-list is the set of <b>measured consumers</b> of a country selector — the roles
     * whose screens or dialogs read this endpoint, taken from {@code companies.routes.ts} and
     * {@code purchases.routes.ts} — and not the company&rarr;store hierarchy. {@code lc-sales}, and
     * the scheduling pair, sit at the store level but no screen of theirs reads this list, so they
     * stay out: the repo treats these lists as flat consumer sets, and a new consumer adds itself
     * here, where the pin in {@code CompanyCountryControllerSecurityTest} fails until it does.</p>
     *
     * <p>Widening the allow-list without the filter would be a horizontal over-grant, which is why
     * the two halves ship together: {@link
     * com.lifecontrol.api.company.service.CompanyCountryService#getCountriesByCompanyId} narrows every
     * caller whose broadest granted scope is <b>not</b> company — a functional role with no scope in
     * the hierarchy included — to the countries named by its {@code company_country_id} claim.</p>
     *
     * <p>This is a read. The three write annotations below are unchanged on purpose: no new role
     * enters them.</p>
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_READ + "','" + COMPANY_COUNTRY + "','"
            + COMPANY_COUNTRY_READ + "','" + COMPANY_REGION + "','" + COMPANY_REGION_READ + "','" + COMPANY_ZONE
            + "','" + COMPANY_ZONE_READ + "','" + COMPANY_STORE + "','" + COMPANY_STORE_READ + "','" + RECEIVING
            + "','" + EMPLOYEE + "')")
    @Operation(
            summary = "Get countries by company",
            description =
                    "Returns countries associated with a company. A caller whose broadest granted scope is not company (a functional role with no scope in the hierarchy included) reads only the countries named by its company_country_id claim; an absent or empty claim returns an empty list. lc-company, lc-company-read and lc-admin read every country of the company.")
    public ResponseEntity<List<CompanyCountryResponse>> getCompanyCountries(@PathVariable UUID companyId) {
        return ResponseEntity.ok(companyCountryService.getCountriesByCompanyId(companyId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "')")
    @Operation(summary = "Add country to company", description = "Associates a country with a company")
    public ResponseEntity<CompanyCountryResponse> addCompanyCountry(
            @PathVariable UUID companyId, @Valid @RequestBody CompanyCountryRequest request) {
        CompanyCountryResponse response = companyCountryService.addCountryToCompany(companyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "')")
    @Operation(
            summary = "Update country assignment",
            description = "Updates an existing country assignment for a company")
    public ResponseEntity<CompanyCountryResponse> updateCompanyCountry(
            @PathVariable UUID companyId, @PathVariable UUID id, @Valid @RequestBody CompanyCountryRequest request) {
        CompanyCountryResponse response = companyCountryService.updateCountry(companyId, id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('" + ADMIN + "','" + COMPANY + "','" + COMPANY_COUNTRY + "')")
    @Operation(summary = "Remove country from company", description = "Removes a country association from a company")
    public ResponseEntity<Void> removeCompanyCountry(@PathVariable UUID companyId, @PathVariable UUID id) {
        companyCountryService.removeCountryFromCompany(companyId, id);
        return ResponseEntity.noContent().build();
    }
}
