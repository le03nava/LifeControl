package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyCountryRequest;
import com.lifecontrol.api.company.dto.CompanyCountryResponse;
import com.lifecontrol.api.company.event.CompanyCountryCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyCountryException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompanyCountryService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyCountryService.class);

    private final CompanyCountryRepository companyCountryRepository;
    private final CompanyRepository companyRepository;
    private final CountryRepository countryRepository;
    private final CurrentUserContext currentUserContext;
    private final ApplicationEventPublisher eventPublisher;

    public CompanyCountryService(
            CompanyCountryRepository companyCountryRepository,
            CompanyRepository companyRepository,
            CountryRepository countryRepository,
            CurrentUserContext currentUserContext,
            ApplicationEventPublisher eventPublisher) {
        this.companyCountryRepository = companyCountryRepository;
        this.companyRepository = companyRepository;
        this.countryRepository = countryRepository;
        this.currentUserContext = currentUserContext;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Lists the company's countries, bounded by the caller's own scope — specs S1&ndash;S3 of issue
     * #548.
     *
     * <p>The branch is decided by {@link CurrentUserContext#hasCompanyWideScope()}, which reads the
     * hierarchy declared in {@code ScopeLevel} instead of a list of role literals: a caller whose
     * broadest granted scope <em>is</em> company ({@code lc-company}, {@code lc-company-read}) reads
     * the whole company, and every other admitted caller reads only the relations named by their
     * {@code company_country_id} claim. An absent or empty claim answers an empty list and never the
     * unscoped query, which is the half that makes the widening safe: admitting region, zone, store,
     * receiving and employee callers without this filter would hand each of them every country of the
     * company.</p>
     *
     * <p>That includes {@code lc-employee}, a functional role absent from every {@code ScopeLevel}.
     * Its own shape — no role in the hierarchy — is not company-wide, so it is bounded by its claim
     * like any narrower caller (decision D3, reversed by the owner on 2026-10-23 against the first
     * draft, which kept it unscoped for its assignment dialogs). The consequence is accepted and
     * declared: an HR operator whose token carries no {@code company_country_id} reads an empty
     * country selector instead of the company's whole catalogue.</p>
     *
     * <p>An administrator keeps the whole company on purpose. It is the only caller no scope check
     * denies, so {@link CurrentUserContext#isAdmin()} stays an explicit bypass: a token that also
     * carries a narrower role must not narrow that grant.</p>
     *
     * <p>A caller holding {@code lc-company-read} next to a country role reads every country: the
     * broader grant is not narrowed by the narrower role it accompanies, the same union rule
     * {@code verifyCompanyStoreAccess} applies to a region role accompanied by a store one. That is
     * one behavior this unit changed, measured and declared in the record: it hands nobody a country
     * their own token could not already read.</p>
     */
    @Transactional(readOnly = true)
    public List<CompanyCountryResponse> getCountriesByCompanyId(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);

        // Verify company exists
        if (!companyRepository.existsById(companyId)) {
            throw new CompanyNotFoundException(companyId);
        }

        if (!currentUserContext.isAdmin() && !currentUserContext.hasCompanyWideScope()) {
            var countryIds = currentUserContext.getCompanyCountryIds();
            if (countryIds.isEmpty()) {
                return List.of();
            }
            return companyCountryRepository.findByIdInAndCompanyId(countryIds, companyId).stream()
                    .map(this::toResponse)
                    .toList();
        }

        return companyCountryRepository.findByCompanyId(companyId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public CompanyCountryResponse addCountryToCompany(UUID companyId, CompanyCountryRequest request) {
        currentUserContext.verifyCompanyAccess(companyId);
        logger.info("Adding country {} to company {}", request.countryCode(), companyId);

        Company company =
                companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));

        Country country = countryRepository
                .findByCountryCode(request.countryCode())
                .orElseThrow(() -> new CountryNotFoundException(request.countryCode()));

        if (companyCountryRepository.existsByCompanyIdAndCountryId(companyId, country.getId())) {
            throw new DuplicateCompanyCountryException(request.countryCode());
        }

        CompanyCountry companyCountry = CompanyCountry.builder()
                .company(company)
                .country(country)
                .localAlias(request.localAlias())
                .build();

        CompanyCountry saved = companyCountryRepository.save(companyCountry);

        eventPublisher.publishEvent(new CompanyCountryCreatedEvent(
                this, saved.getId(), companyId, country.getCountryName(), company.getCompanyName()));
        logger.info("Country {} added to company {} successfully, event published", request.countryCode(), companyId);

        return toResponse(saved);
    }

    @Transactional
    public void removeCountryFromCompany(UUID companyId, UUID companyCountryId) {
        currentUserContext.verifyCompanyCountryAccess(companyId, companyCountryId);
        logger.info("Removing country relation {} from company {}", companyCountryId, companyId);

        CompanyCountry companyCountry = companyCountryRepository
                .findById(companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));

        if (!companyCountry.getCompany().getId().equals(companyId)) {
            throw new CompanyCountryNotFoundException(companyCountryId);
        }

        companyCountryRepository.delete(companyCountry);
        logger.info("Country relation {} removed from company {}", companyCountryId, companyId);
    }

    @Transactional
    public CompanyCountryResponse updateCountry(UUID companyId, UUID companyCountryId, CompanyCountryRequest request) {
        currentUserContext.verifyCompanyCountryAccess(companyId, companyCountryId);
        logger.info(
                "Updating country relation {} for company {} with request: {}", companyCountryId, companyId, request);

        CompanyCountry companyCountry = companyCountryRepository
                .findById(companyCountryId)
                .orElseThrow(() -> new CompanyCountryNotFoundException(companyCountryId));

        if (!companyCountry.getCompany().getId().equals(companyId)) {
            throw new CompanyCountryNotFoundException(companyCountryId);
        }

        Country country = countryRepository
                .findByCountryCode(request.countryCode())
                .orElseThrow(() -> new CountryNotFoundException(request.countryCode()));

        // Check if another company-country relation already exists with this country code for the same company
        if (companyCountryRepository.existsByCompanyIdAndCountryId(companyId, country.getId())
                && !companyCountry.getCountry().getId().equals(country.getId())) {
            throw new DuplicateCompanyCountryException(request.countryCode());
        }

        companyCountry.setCountry(country);
        companyCountry.setLocalAlias(request.localAlias());

        CompanyCountry saved = companyCountryRepository.save(companyCountry);
        logger.info("Country relation {} updated for company {} successfully", companyCountryId, companyId);

        return toResponse(saved);
    }

    private CompanyCountryResponse toResponse(CompanyCountry cc) {
        return new CompanyCountryResponse(
                cc.getId(),
                cc.getCompany().getId(),
                cc.getCountry().getId(),
                cc.getCountry().getCountryCode(),
                cc.getCountry().getCountryName(),
                cc.getLocalAlias(),
                cc.getCreatedAt(),
                cc.getUpdatedAt());
    }
}
