package com.lifecontrol.api.customer.service;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.customer.dto.CustomerRequest;
import com.lifecontrol.api.customer.dto.CustomerResponse;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.exception.InvalidReceiverRegimenFiscalException;
import com.lifecontrol.api.customer.exception.InvalidReceiverUsoCfdiException;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import com.lifecontrol.api.fiscal.model.RegimenFiscal;
import com.lifecontrol.api.fiscal.model.UsoCfdi;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The customer endpoints, scoped to the caller's company (S4).
 *
 * <p><b>Where the company comes from, and why.</b> {@code /api/customers} names no company in its path, so
 * {@code TenantScopeFilter} states the operation's tenant from the caller's single {@code company_id} claim
 * — the same rule every company-less path that reads a tenant table follows
 * ({@code TenantScopeFilter.java:139}), and the reason a caller claiming several companies is refused before
 * a controller runs instead of reading a union. {@link TenantScope#current()} is therefore the operation's
 * tenant, already verified against the claims; reading the claim again here would be a second
 * implementation of the same decision.</p>
 *
 * <p><b>The three answers a tenantless operation gets.</b> A caller whose token claims no company has no
 * tenant to read under, so the list is empty, an id is a 404, and a create is a 403 — because a customer
 * with no company is a row the schema refuses, and turning that into a 500 would report a server fault for
 * a token that simply names no tenant.</p>
 *
 * <p><b>The fiscal profile's two halves.</b> The receiver's profile (S2 of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decision L8 of the same record) is written here: the create path
 * copies all seven facts and the update path copies <b>only the ones the request states</b>, so a PUT that edits
 * a phone number cannot silently make a customer unable to be invoiced. The all-or-nothing rule — the four facts
 * a stamp consumes are stated together or not at all — is refused at the boundary by
 * {@code CustomerRequest}'s {@code @FiscalProfileComplete}, and the issue-time question is
 * {@link Customer#fiscalProfileComplete()}, which the document slice calls. Two vocabularies are resolved here
 * against the enums in {@code com.lifecontrol.api.fiscal.model}, because a {@code @Pattern} over their codes
 * would be a second copy of each catalog, free to drift.</p>
 */
@Service
public class CustomerService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> getAllCustomers(Pageable pageable, String search) {
        var companyId = currentCompanyId();

        if (companyId.isEmpty()) {
            // No tenant stated: the policy would answer no row, and saying so here is the same answer without
            // a query whose result depends on which pooled connection served it.
            return Page.empty(pageable);
        }

        var customers = StringUtils.hasText(search)
                ? customerRepository.findByCompanyIdAndSearchTerm(companyId.get(), search.trim(), pageable)
                : customerRepository.findByCompanyIdAndEnabledTrueOrderByCreatedAtDesc(companyId.get(), pageable);

        return customers.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomerById(UUID id) {
        return toResponse(findScoped(id));
    }

    @Transactional
    public CustomerResponse createCustomer(CustomerRequest request) {
        var companyId = requireCompanyId();

        logger.info("Creating customer: name={}, salesChannel={}", request.name(), request.salesChannel());

        var customer = Customer.builder()
                // Stamped from the operation's scope and never from the request: a body that could name its
                // own company would let one caller write a row into another tenant.
                .companyId(companyId)
                .name(request.name())
                .email(request.email())
                .phone(request.phone())
                .rfc(request.rfc())
                .salesChannel(request.salesChannel())
                .enabled(request.enabled())
                // The profile is copied whole on a create: the boundary already refused every partial shape, so
                // what arrives here is either all four facts or none of them plus at most the optional half.
                .legalName(request.legalName())
                .regimenFiscal(resolveRegimenFiscal(request.regimenFiscal()))
                .fiscalStreet(request.fiscalStreet())
                .fiscalZipCode(request.fiscalZipCode())
                .fiscalState(request.fiscalState())
                .fiscalCountryId(request.fiscalCountryId())
                .defaultUsoCfdi(resolveDefaultUsoCfdi(request.defaultUsoCfdi()))
                .build();

        var saved = customerRepository.save(customer);
        logger.info("Customer created: id={}, name={}", saved.getId(), saved.getName());

        return toResponse(saved);
    }

    @Transactional
    public CustomerResponse updateCustomer(UUID id, CustomerRequest request) {
        logger.info("Updating customer: id={}", id);

        var customer = findScoped(id);

        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setRfc(request.rfc());
        customer.setSalesChannel(request.salesChannel());
        customer.setEnabled(request.enabled());
        // The fiscal profile is copied one stated field at a time, the shape `CompanyService.updateCompany` uses
        // for the issuer's two: an omitted value means "leave what is stored" and never "clear it", so a PUT that
        // edits a phone number must not silently leave a declared profile behind. The all-or-nothing rule is the
        // request's, refused before this method runs, so a request that reaches here states all four or none.
        if (request.legalName() != null) {
            customer.setLegalName(request.legalName());
        }
        if (request.regimenFiscal() != null) {
            customer.setRegimenFiscal(resolveRegimenFiscal(request.regimenFiscal()));
        }
        if (request.fiscalStreet() != null) {
            customer.setFiscalStreet(request.fiscalStreet());
        }
        if (request.fiscalZipCode() != null) {
            customer.setFiscalZipCode(request.fiscalZipCode());
        }
        if (request.fiscalState() != null) {
            customer.setFiscalState(request.fiscalState());
        }
        if (request.fiscalCountryId() != null) {
            customer.setFiscalCountryId(request.fiscalCountryId());
        }
        if (request.defaultUsoCfdi() != null) {
            customer.setDefaultUsoCfdi(resolveDefaultUsoCfdi(request.defaultUsoCfdi()));
        }

        var updated = customerRepository.save(customer);
        logger.info("Customer updated: id={}", id);

        return toResponse(updated);
    }

    @Transactional
    public void deleteCustomer(UUID id) {
        logger.info("Soft-deleting customer: id={}", id);

        var customer = findScoped(id);

        customer.setEnabled(false);
        customerRepository.save(customer);
        logger.info("Customer soft-deleted: id={}", id);
    }

    @Transactional
    public CustomerResponse enableCustomer(UUID id) {
        logger.info("Re-enabling customer: id={}", id);

        var customer = findScoped(id);

        customer.setEnabled(true);
        var saved = customerRepository.save(customer);

        return toResponse(saved);
    }

    // ─── The tenant of this operation ────────────────────────────────────

    /**
     * The operation's company, or empty when the caller's token names none.
     *
     * <p>Empty is not an error on a read: it is a caller with nothing to see. It <b>is</b> an error on a
     * write, and {@link #requireCompanyId()} is where that becomes a refusal.</p>
     */
    private Optional<UUID> currentCompanyId() {
        return TenantScope.current().map(TenantScope.Scope::companyId).filter(companyId -> companyId != null);
    }

    /**
     * The operation's company, refusing when it has none.
     *
     * <p>{@code AccessDeniedException} and not a 404: there is no id here that could be absent, so the 404
     * convention S4 states for another company's customer does not apply. A write with no tenant is a caller
     * asking to write into no company, which is the same refusal {@code TenantScopeFilter} answers with when
     * the tenant cannot be determined.</p>
     */
    private UUID requireCompanyId() {
        return currentCompanyId()
                .orElseThrow(() -> new AccessDeniedException("The customer's company cannot be determined: "
                        + "this path names no company and the caller's token carries no company_id claim, so "
                        + "there is no tenant to write the customer into."));
    }

    /**
     * One customer of the operation's company.
     *
     * <p>A customer of <b>another</b> company is a {@link CustomerNotFoundException} — a 404 — and not a
     * refusal: in this company the row is not there, which is the tree's {@code ResourceNotFoundException}
     * convention (S4). The read names both columns, so the answer is the same with or without the policy.</p>
     */
    private Customer findScoped(UUID id) {
        var companyId = currentCompanyId().orElseThrow(() -> new CustomerNotFoundException(id));
        return customerRepository
                .findByCompanyIdAndId(companyId, id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }

    // ─── Response Mapper ─────────────────────────────────────────────────

    private CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getRfc(),
                customer.getSalesChannel(),
                customer.getEnabled(),
                customer.getLegalName(),
                customer.getRegimenFiscal(),
                customer.getFiscalStreet(),
                customer.getFiscalZipCode(),
                customer.getFiscalState(),
                customer.getFiscalCountryId(),
                customer.getDefaultUsoCfdi(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }

    // ─── The two fiscal vocabularies ─────────────────────────────────────

    /**
     * Resolves the receiver's regime against the closed vocabulary this tree declares, or {@code null} when the
     * request states none.
     *
     * <p><b>Why the service and not bean validation.</b> The vocabulary is {@link RegimenFiscal}, and a
     * {@code @Pattern} over nineteen codes would be a second copy of it in an annotation, free to drift. The
     * refusal is an {@link InvalidReceiverRegimenFiscalException}, whose superclass is the tree's
     * {@code FieldViolationException}: a 400 with {@code errors: {regimenFiscal: …}} beside the same sentence in
     * {@code message}.</p>
     *
     * @param code the code the request states, already normalized from blank by {@code CustomerRequest}
     * @return the canonical code to store, or {@code null}
     */
    private static String resolveRegimenFiscal(String code) {
        if (code == null) {
            return null;
        }
        return RegimenFiscal.fromCode(code)
                .orElseThrow(() -> new InvalidReceiverRegimenFiscalException(code))
                .code();
    }

    /**
     * Resolves the receiver's default use of the voucher against the closed vocabulary this tree declares, or
     * {@code null} when the request states none. The same shape as {@link #resolveRegimenFiscal(String)}, for the
     * same reason: a {@code @Pattern} over twenty-five codes would be a second copy of the catalog.
     *
     * @param code the code the request states, already normalized from blank by {@code CustomerRequest}
     * @return the canonical code to store, or {@code null}
     */
    private static String resolveDefaultUsoCfdi(String code) {
        if (code == null) {
            return null;
        }
        return UsoCfdi.fromCode(code)
                .orElseThrow(() -> new InvalidReceiverUsoCfdiException(code))
                .code();
    }
}
