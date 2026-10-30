package com.lifecontrol.api.company.service;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.dto.AddressResponse;
import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.dto.CompanySelectorResponse;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.country.exception.CountryNotFoundException;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class CompanyService {

    private static final Logger logger = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserContext currentUserContext;
    private final CountryRepository countryRepository;
    private final TransactionTemplate transactionTemplate;

    public CompanyService(
            CompanyRepository companyRepository,
            ApplicationEventPublisher eventPublisher,
            CurrentUserContext currentUserContext,
            CountryRepository countryRepository,
            TransactionTemplate transactionTemplate) {
        this.companyRepository = companyRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserContext = currentUserContext;
        this.countryRepository = countryRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public Page<CompanyResponse> getAllCompanies(Pageable pageable, String search) {
        return companyPage(pageable, search, this::toResponse);
    }

    /**
     * Returns the narrow company list a cascade selector needs, under the <b>same</b> claim filter as
     * {@link #getAllCompanies}.
     *
     * <p>The filter is shared rather than re-implemented on purpose: the selector's whole
     * authorization is the {@code company_id} claim, so a second copy of the branch below would be a
     * second place where "what a non-admin may see" is decided. What changes between the two methods
     * is only the projection — {@link CompanySelectorResponse} carries no fiscal data, no corporate
     * email domain and no address, so the twelve screens that read this do not receive the
     * administration contract to paint a {@code <mat-select>}. The claim filter is the same mechanism
     * too: one scoped read per claimed company, for the reason {@link #companyPage} states.</p>
     *
     * @param pageable page request, resolved by the controller's {@code @PageableDefault}
     * @param search   optional search term; whitespace-only is treated as absent, exactly as in
     *                 {@link #getAllCompanies}
     * @return the companies the caller belongs to (or every company for an admin), projected to the
     *     selector read model
     */
    public Page<CompanySelectorResponse> getCompanySelector(Pageable pageable, String search) {
        return companyPage(pageable, search, this::toSelectorResponse);
    }

    /**
     * Resolves the company page a caller may see: every company for an admin, and only the caller's
     * claims otherwise — <b>one claim at a time</b>.
     *
     * <p><b>Why this method is not {@code @Transactional}, and why the claims are read separately.</b>
     * ADR-0005 isolates {@code companies} by {@code id}, so a read of a company needs that company as the
     * transaction's scope, and the scope is installed at the transaction boundary from a holder the caller
     * states <b>before</b> it (records L13/L14). One transaction cannot state several tenants, and widening
     * the policy is the one thing the decision forbids — so a caller with two claims gets two scoped reads
     * and the rows are collected in the order the claim yields them, which is the claim set's iteration
     * order ({@code CurrentUserContext.getCompanyIds()} returns a {@code HashSet}; a {@code LinkedHashSet}
     * there would make it the token's own order, and that file is outside this slice). A token with no
     * {@code company_id} gets an empty page: that is the honest answer for a caller who belongs to no
     * company, not an error.</p>
     *
     * <p>The admin path stays a single transaction with its database-side search and pagination, exactly
     * as it was: it is not claim-filtered, and under the armed policy it reads the scope the request's own
     * transaction carries, which is T5b's question rather than this slice's.</p>
     */
    private <R> Page<R> companyPage(Pageable pageable, String search, Function<Company, R> projection) {
        if (!currentUserContext.isAdmin()) {
            return claimedCompaniesPage(pageable, search, projection);
        }
        var page = inTransaction(() -> StringUtils.hasText(search)
                ? companyRepository.findBySearchTerm(search.trim(), pageable)
                : companyRepository.findAll(pageable));
        // Mapped inside the transaction: `Company.address` is lazy and the response reads it.
        return page.map(projection);
    }

    private <R> Page<R> claimedCompaniesPage(Pageable pageable, String search, Function<Company, R> projection) {
        Set<UUID> companyIds = currentUserContext.getCompanyIds();
        if (companyIds.isEmpty()) {
            return Page.empty(pageable);
        }
        var text = StringUtils.hasText(search) ? search.trim().toLowerCase(Locale.ROOT) : null;
        var rows = new ArrayList<R>(companyIds.size());
        for (UUID companyId : companyIds) {
            var row = TenantScope.with(
                    companyId,
                    () -> inTransaction(() -> companyRepository
                            .findById(companyId)
                            .filter(company -> text == null || matchesSearch(company, text))
                            .map(projection)
                            .orElse(null)));
            if (row != null) {
                rows.add(row);
            }
        }
        return pageOf(rows, pageable);
    }

    /**
     * Runs {@code work} in a transaction of its own, so the scope stated around this call is what the
     * boundary hook installs.
     */
    private <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    /**
     * The page of an in-memory list, in the order it was built: with one read per claim the order is the
     * order the claim yielded, and the page is the requested slice of it.
     */
    private static <R> Page<R> pageOf(List<R> rows, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(rows);
        }
        var from = (int) Math.min(pageable.getOffset(), rows.size());
        var to = (int) Math.min((long) from + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(from, to), pageable, rows.size());
    }

    /**
     * The search the claim path applies in memory, field for field the one the admin path asks the
     * database for: name, RFC, legal name and email, case-insensitive.
     */
    private static boolean matchesSearch(Company company, String lowerCaseSearch) {
        return contains(company.getCompanyName(), lowerCaseSearch)
                || contains(company.getRfc(), lowerCaseSearch)
                || contains(company.getRazonSocial(), lowerCaseSearch)
                || contains(company.getEmail(), lowerCaseSearch);
    }

    private static boolean contains(String value, String lowerCaseSearch) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerCaseSearch);
    }

    @Transactional(readOnly = true)
    public CompanyResponse getCompanyById(UUID id) {
        currentUserContext.verifyCompanyAccess(id);
        return companyRepository.findById(id).map(this::toResponse).orElseThrow(() -> new CompanyNotFoundException(id));
    }

    /**
     * Creates a company, stating the tenant its row will carry (ADR-0005, record L16).
     *
     * <p><b>Why this method is not {@code @Transactional}.</b> {@code companies} is isolated by its own
     * {@code id}, so the row's tenant is its identity — which means the identity has to exist
     * <b>before</b> the transaction that inserts the row. The boundary hook reads the scope holder in
     * {@code doBegin}, i.e. before the method body of a {@code @Transactional} method ever runs, so a
     * scope stated inside one arrives one boundary too late to be installed. This method therefore
     * generates the id, states it around the call, and drives the transaction itself; the work it wraps
     * is the same work as before. The id is generated here and not by the database: {@code companies.id}
     * has no database default, and the entity's generator would produce its own value at persist time,
     * inside the transaction and after the scope was installed, which the {@code WITH CHECK} of the
     * policy would refuse.</p>
     *
     * <p>Nothing about authorization changes: creating a company is role-guarded at the controller and
     * there is no existing row whose access could be verified. The scope is the company being created, so
     * it can only ever make the new row visible to the caller who is creating it.</p>
     */
    public CompanyResponse createCompany(CompanyRequest request) {
        var id = UUID.randomUUID();
        return TenantScope.with(id, () -> inTransaction(() -> createCompany(id, request)));
    }

    private CompanyResponse createCompany(UUID id, CompanyRequest request) {
        // Validate uniqueness
        if (companyRepository.existsByCompanyKey(request.companyKey())) {
            throw new DuplicateCompanyException(
                    "Company with companyKey '" + request.companyKey() + "' already exists");
        }

        if (companyRepository.existsByRfc(request.rfc())) {
            throw new DuplicateCompanyException("Company with RFC '" + request.rfc() + "' already exists");
        }

        // Build entity
        Company company = Company.builder()
                .id(id)
                .companyKey(request.companyKey())
                .companyName(request.companyName())
                .tipoPersonaId(request.tipoPersonaId())
                .razonSocial(request.razonSocial())
                .rfc(request.rfc())
                .phone(request.phone())
                .email(request.email())
                .emailDomain(normalizeEmailDomain(request.emailDomain()))
                .enabled(request.enabled() != null ? request.enabled() : true)
                .address(buildAddress(request.address()))
                .build();

        Company saved = companyRepository.save(company);

        eventPublisher.publishEvent(
                new CompanyCreatedEvent(this, saved.getId(), saved.getCompanyKey(), saved.getCompanyName()));
        logger.info(
                "Company created and event published: id={}, companyKey={}, name={}",
                saved.getId(),
                saved.getCompanyKey(),
                saved.getCompanyName());

        return toResponse(saved);
    }

    @Transactional
    public CompanyResponse updateCompany(UUID id, CompanyRequest request) {
        currentUserContext.verifyCompanyAccess(id);

        // Fetch existing company
        Company company = companyRepository.findById(id).orElseThrow(() -> new CompanyNotFoundException(id));

        // Validate uniqueness (excluding current company)
        if (companyRepository.existsByRfcAndIdNot(request.rfc(), id)) {
            throw new DuplicateCompanyException("Company with RFC '" + request.rfc() + "' already exists");
        }

        // Update fields (companyKey is immutable)
        company.setCompanyName(request.companyName());
        company.setTipoPersonaId(request.tipoPersonaId());
        company.setRazonSocial(request.razonSocial());
        company.setRfc(request.rfc());
        company.setPhone(request.phone());
        company.setEmail(request.email());
        company.setEmailDomain(normalizeEmailDomain(request.emailDomain()));
        company.setEnabled(request.enabled() != null ? request.enabled() : true);
        // Update address (dual-path: update existing Address entity or create new one)
        if (request.address() != null) {
            if (company.getAddress() != null) {
                updateAddress(company.getAddress(), request.address());
            } else {
                company.setAddress(buildAddress(request.address()));
            }
        }

        Company updated = companyRepository.save(company);

        return toResponse(updated);
    }

    @Transactional
    public void deleteCompany(UUID id) {
        currentUserContext.verifyCompanyAccess(id);

        Company company = companyRepository.findById(id).orElseThrow(() -> new CompanyNotFoundException(id));

        company.setEnabled(false);
        companyRepository.save(company);

        logger.info(
                "Company soft-deleted: id={}, companyKey={}, timestamp={}",
                id,
                company.getCompanyKey(),
                java.time.LocalDateTime.now());
    }

    private CompanyResponse toResponse(Company company) {
        return new CompanyResponse(
                company.getId(),
                company.getCompanyKey(),
                company.getCompanyName(),
                company.getTipoPersonaId(),
                company.getRazonSocial(),
                company.getRfc(),
                company.getPhone(),
                company.getEmail(),
                company.getEmailDomain(),
                company.getEnabled(),
                company.getCreatedAt(),
                company.getUpdatedAt(),
                buildAddressResponse(company));
    }

    /**
     * Projects a company to the cascade-selector read model.
     *
     * <p>Three fields, deliberately: the id the caller sends back, the key some screens search by, and
     * the name they render. The administration contract's fiscal and identity fields stay behind the
     * role that admits the full read.
     */
    private CompanySelectorResponse toSelectorResponse(Company company) {
        return new CompanySelectorResponse(company.getId(), company.getCompanyKey(), company.getCompanyName());
    }

    /**
     * Normalizes the corporate email domain to a single stored form: trimmed, lowercased with
     * {@link Locale#ROOT}, and blank collapsed to {@code null}.
     *
     * <p>One case form is what keeps "one domain, one login identity" true: {@code EmployeeService}
     * compares {@code requestedDomain.equalsIgnoreCase(company.getEmailDomain())}, the generated login
     * identity embeds the domain verbatim, and {@code uq_employees_company_email} plus
     * {@code EmployeeRepository.existsByCompanyIdAndEmail} are case-sensitive. Blank becomes
     * {@code null} because a company without a domain fails the employee write path closed with a 400
     * (feature decision D8), never an empty-string domain.
     */
    private static String normalizeEmailDomain(String emailDomain) {
        if (emailDomain == null || emailDomain.isBlank()) {
            return null;
        }
        return emailDomain.trim().toLowerCase(Locale.ROOT);
    }

    private AddressResponse buildAddressResponse(Company company) {
        if (company.getAddress() != null) {
            // NEW record: Address entity exists → map from entity
            var addr = company.getAddress();
            return new AddressResponse(
                    addr.getId(),
                    addr.getStreet(),
                    addr.getStreetNumber(),
                    addr.getInternalNumber(),
                    addr.getNeighborhood(),
                    addr.getZipCode(),
                    addr.getCity(),
                    addr.getState(),
                    addr.getCountry() != null ? addr.getCountry().getId() : null);
        } else if (company.getStreet() != null) {
            // LEGACY record: inline columns → map to AddressResponse
            return new AddressResponse(
                    null,
                    company.getStreet(),
                    company.getStreetNumber(),
                    company.getInternalNumber(),
                    company.getNeighborhood(),
                    company.getZipCode(),
                    company.getCity(),
                    company.getState(),
                    company.getCountryId());
        }
        return null;
    }

    private Address buildAddress(AddressRequest request) {
        if (request == null) return null;
        Country country = null;
        if (request.countryId() != null) {
            country = countryRepository
                    .findById(request.countryId())
                    .orElseThrow(() -> new CountryNotFoundException(request.countryId()));
        }
        return Address.builder()
                .street(request.street())
                .streetNumber(request.streetNumber())
                .internalNumber(request.internalNumber())
                .neighborhood(request.neighborhood())
                .zipCode(request.zipCode())
                .city(request.city())
                .state(request.state())
                .country(country)
                .enabled(true)
                .build();
    }

    private void updateAddress(Address address, AddressRequest request) {
        address.setStreet(request.street());
        address.setStreetNumber(request.streetNumber());
        address.setInternalNumber(request.internalNumber());
        address.setNeighborhood(request.neighborhood());
        address.setZipCode(request.zipCode());
        address.setCity(request.city());
        address.setState(request.state());
        if (request.countryId() != null) {
            Country country = countryRepository
                    .findById(request.countryId())
                    .orElseThrow(() -> new CountryNotFoundException(request.countryId()));
            address.setCountry(country);
        } else {
            address.setCountry(null);
        }
    }
}
