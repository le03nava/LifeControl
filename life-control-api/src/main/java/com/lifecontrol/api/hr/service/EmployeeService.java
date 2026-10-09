package com.lifecontrol.api.hr.service;

import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.address.repository.AddressRepository;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.EmployeeEmailSuggestionResponse;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.dto.EmployeeResponse;
import com.lifecontrol.api.hr.exception.DuplicateEmployeeException;
import com.lifecontrol.api.hr.exception.EmployeeEmailFrozenException;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import com.lifecontrol.api.status.exception.StatusNotFoundException;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.service.StatusValidator;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the company-scoped employee registry.
 *
 * <p>Every public method starts by resolving the company through {@link #resolveCompany(UUID)},
 * whose first statement is {@link CurrentUserContext#verifyCompanyAccess(UUID)} — the company-scope
 * contract. Every single-row read and write then goes through a {@code companyId}-scoped lookup
 * ({@code findByIdAndCompanyId}), so an employee of another company is a
 * {@link EmployeeNotFoundException} (404) rather than a 200.</p>
 *
 * <p>Three rules the database cannot express live here. The status must belong to the
 * {@code EMPLOYEE_STATUS} family (decision T5), and the {@code Terminated} status requires a
 * termination date while every other status forbids one. The birth date must be strictly before the
 * hire date and the termination date, when present, on or after it; both are 400s thrown before the
 * write so the operator gets a message instead of a raw CHECK violation. And the address's domain
 * must equal the company's configured {@code email_domain} (gap G5).</p>
 *
 * <p>The email is generated from the names when the request leaves it blank, and it is frozen once
 * {@code keycloakUserId} is set (decision T9): changing the address after provisioning would
 * desynchronize the Keycloak username, which is the full email. A company without a configured
 * domain fails the write path closed with a 400 (decision D8); there is no manual-entry mode.
 * {@code keycloakUserId} is never written by this service (decision T17).</p>
 *
 * <p>The uniqueness checks are per company and, on update, exclude the row being updated. The
 * check-then-write race is left to {@code GlobalExceptionHandler}'s
 * {@code DataIntegrityViolationException} mapping to 409; no lock is added. The email collision is
 * resolved with a pre-check loop over the suffixed candidates rather than by catching a unique
 * violation, because in PostgreSQL the first constraint violation aborts the whole transaction and
 * no later statement of that transaction can run: the {@code UNIQUE (company_id, email)} remains the
 * final authority for a lost race (decisions T7/T8).</p>
 *
 * <p>The reads are deliberately <b>not</b> cached, following the neighbouring {@code
 * DepartmentService}: a company-scoped read must run its scope check on every call, and a
 * {@code @Cacheable} would serve a hit without reaching
 * {@link CurrentUserContext#verifyCompanyAccess(UUID)}.</p>
 */
@Service
public class EmployeeService {

    private static final Logger logger = LoggerFactory.getLogger(EmployeeService.class);

    private static final String EMPLOYEE_STATUS_TYPE = "EMPLOYEE_STATUS";
    private static final String ACTIVE_STATUS_NAME = "Active";

    /** Attempts of the email collision loop: the plain local part plus suffixes 2..20. */
    private static final int MAX_EMAIL_ATTEMPTS = 20;

    private final EmployeeRepository employeeRepository;
    private final CompanyRepository companyRepository;
    private final StatusRepository statusRepository;
    private final AddressRepository addressRepository;
    private final CurrentUserContext currentUserContext;
    private final AccessProvisioningQueryService accessProvisioningQueryService;

    public EmployeeService(
            EmployeeRepository employeeRepository,
            CompanyRepository companyRepository,
            StatusRepository statusRepository,
            AddressRepository addressRepository,
            CurrentUserContext currentUserContext,
            AccessProvisioningQueryService accessProvisioningQueryService) {
        this.employeeRepository = employeeRepository;
        this.companyRepository = companyRepository;
        this.statusRepository = statusRepository;
        this.addressRepository = addressRepository;
        this.currentUserContext = currentUserContext;
        this.accessProvisioningQueryService = accessProvisioningQueryService;
    }

    /**
     * Verifies company access and then loads the company, in that order.
     *
     * <p>The access check is deliberately the first statement: a caller without access to the company
     * is denied before any row is loaded.</p>
     *
     * @throws org.springframework.security.access.AccessDeniedException when the current user cannot
     *     access the company
     * @throws CompanyNotFoundException                                when the company does not exist
     */
    private Company resolveCompany(UUID companyId) {
        currentUserContext.verifyCompanyAccess(companyId);
        return companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> getAllEmployees(
            UUID companyId, String search, UUID statusId, boolean includeDisabled) {
        resolveCompany(companyId);
        return employeeRepository
                .findCompanyEmployees(companyId, normalizeSearch(search), statusId, includeDisabled)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse getEmployeeById(UUID companyId, UUID id) {
        resolveCompany(companyId);
        return employeeRepository
                .findByIdAndCompanyId(id, companyId)
                .map(employee -> toResponse(employee, accessProvisioningQueryService.accessState(employee.getId())))
                .orElseThrow(() -> new EmployeeNotFoundException(id));
    }

    /**
     * Returns the next free candidate address for the given names.
     *
     * <p>The suggestion <b>reserves nothing</b>: it takes no lock and writes no row (decision T8).
     * Two operators can be shown the same candidate; the {@code UNIQUE (company_id, email)} catches
     * the loser at save time and the 409 is the honest outcome. The UI must present it as a
     * suggestion, not a reservation.</p>
     *
     * <p>This is a read: when every candidate is already taken it answers
     * {@code NO_FREE_CANDIDATE} with a null email instead of a 409, so the form is never shown an
     * error toast for a GET.</p>
     */
    @Transactional(readOnly = true)
    public EmployeeEmailSuggestionResponse suggestEmail(UUID companyId, String firstName, String paternalLastName) {
        var company = resolveCompany(companyId);

        var domain = company.getEmailDomain();
        if (domain == null || domain.isBlank()) {
            return new EmployeeEmailSuggestionResponse(null, "NO_EMAIL_DOMAIN");
        }

        var localPart = EmployeeEmailGenerator.localPart(firstName, paternalLastName);
        if (localPart.isEmpty()) {
            return new EmployeeEmailSuggestionResponse(null, "EMPTY_LOCAL_PART");
        }

        var candidate = firstFreeAddressOrNull(localPart, domain, companyId, null);
        if (candidate == null) {
            return new EmployeeEmailSuggestionResponse(null, "NO_FREE_CANDIDATE");
        }
        return new EmployeeEmailSuggestionResponse(candidate, null);
    }

    @Transactional
    public EmployeeResponse createEmployee(UUID companyId, EmployeeRequest request) {
        var company = resolveCompany(companyId);
        var status = resolveStatus(request.statusId());

        validateStatusAndTerminationDate(status, request.terminationDate());
        validateDates(request.birthDate(), request.hireDate(), request.terminationDate());

        if (employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, request.employeeNumber())) {
            throw new DuplicateEmployeeException("employeeNumber", request.employeeNumber());
        }

        var email = resolveEmail(request, company, companyId, null, null);

        var employee = Employee.builder()
                .company(company)
                .employeeNumber(request.employeeNumber())
                .firstName(request.firstName())
                .paternalLastName(request.paternalLastName())
                .maternalLastName(request.maternalLastName())
                .email(email)
                .phoneNumber(request.phoneNumber())
                .birthDate(request.birthDate())
                .hireDate(request.hireDate())
                .terminationDate(request.terminationDate())
                .address(resolveAddress(request.addressId()))
                .status(status)
                .enabled(true)
                .build();

        var saved = employeeRepository.save(employee);
        logger.info(
                "Employee created: id={}, companyId={}, employeeNumber={}",
                saved.getId(),
                companyId,
                saved.getEmployeeNumber());

        return toResponse(saved);
    }

    @Transactional
    public EmployeeResponse updateEmployee(UUID companyId, UUID id, EmployeeRequest request) {
        var company = resolveCompany(companyId);

        var employee = employeeRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(id));

        var status = resolveStatus(request.statusId());
        validateStatusAndTerminationDate(status, request.terminationDate());
        validateDates(request.birthDate(), request.hireDate(), request.terminationDate());

        if (employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, request.employeeNumber(), id)) {
            throw new DuplicateEmployeeException("employeeNumber", request.employeeNumber());
        }

        var email = resolveEmail(request, company, companyId, id, employee);
        if (employee.getKeycloakUserId() != null && !email.equals(employee.getEmail())) {
            throw new EmployeeEmailFrozenException(id);
        }

        employee.setEmployeeNumber(request.employeeNumber());
        employee.setFirstName(request.firstName());
        employee.setPaternalLastName(request.paternalLastName());
        employee.setMaternalLastName(request.maternalLastName());
        employee.setEmail(email);
        employee.setPhoneNumber(request.phoneNumber());
        employee.setBirthDate(request.birthDate());
        employee.setHireDate(request.hireDate());
        employee.setTerminationDate(request.terminationDate());
        employee.setAddress(resolveAddress(request.addressId()));
        employee.setStatus(status);

        var updated = employeeRepository.save(employee);
        logger.info(
                "Employee updated: id={}, companyId={}, employeeNumber={}", id, companyId, updated.getEmployeeNumber());

        return toResponse(updated);
    }

    /**
     * Soft-deletes an employee by flipping {@code enabled} to {@code false}.
     *
     * <p>Idempotent: re-disabling an already-disabled employee is a success and writes nothing, while
     * a missing row is an {@link EmployeeNotFoundException}.</p>
     *
     * <p>The soft-deleted row keeps both natural keys: {@code uq_employees_company_number} and
     * {@code uq_employees_company_email} have no {@code WHERE enabled} predicate and the service's
     * existence probes are equally unfiltered, so the employee number can never be reused and the
     * same address is silently suffixed on the next create. {@code PATCH …/enable} is the recovery
     * path.</p>
     */
    @Transactional
    public void deleteEmployee(UUID companyId, UUID id) {
        resolveCompany(companyId);

        var employee = employeeRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(id));

        if (Boolean.FALSE.equals(employee.getEnabled())) {
            logger.info("Employee already soft-deleted: id={}, companyId={}", id, companyId);
            return;
        }

        employee.setEnabled(false);
        employeeRepository.save(employee);
        logger.info("Employee soft-deleted: id={}, companyId={}", id, companyId);
    }

    /** Sets exactly the value it is given; it is a set, not a toggle. */
    @Transactional
    public EmployeeResponse setEmployeeEnabled(UUID companyId, UUID id, boolean enabled) {
        resolveCompany(companyId);

        var employee = employeeRepository
                .findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new EmployeeNotFoundException(id));

        employee.setEnabled(enabled);
        var saved = employeeRepository.save(employee);
        logger.info("Employee enabled={}: id={}, companyId={}", enabled, id, companyId);

        return toResponse(saved);
    }

    /**
     * Resolves the status of the write: the pinned {@code Active} default when none is given, or the
     * given row validated against the {@code EMPLOYEE_STATUS} family (decision T5).
     */
    private Status resolveStatus(UUID statusId) {
        if (statusId == null) {
            return statusRepository
                    .findByTypeNameAndStatusName(EMPLOYEE_STATUS_TYPE, ACTIVE_STATUS_NAME)
                    .orElseThrow(() -> new StatusNotFoundException("Default status '" + ACTIVE_STATUS_NAME
                            + "' not found for " + EMPLOYEE_STATUS_TYPE + " type"));
        }
        return StatusValidator.requireStatusOfType(statusRepository, statusId, EMPLOYEE_STATUS_TYPE);
    }

    private void validateStatusAndTerminationDate(Status status, LocalDate terminationDate) {
        var isTerminated = EmployeeStatuses.isTerminated(status);
        if (isTerminated && terminationDate == null) {
            throw new IllegalArgumentException("terminationDate is required when the employee status is Terminated");
        }
        if (!isTerminated && terminationDate != null) {
            throw new IllegalArgumentException(
                    "terminationDate must be absent unless the employee status is Terminated");
        }
    }

    private void validateDates(LocalDate birthDate, LocalDate hireDate, LocalDate terminationDate) {
        if (!birthDate.isBefore(hireDate)) {
            throw new IllegalArgumentException("birthDate must be strictly before hireDate");
        }
        if (terminationDate != null && terminationDate.isBefore(hireDate)) {
            throw new IllegalArgumentException("terminationDate must be on or after hireDate");
        }
    }

    /**
     * Resolves the email of the write.
     *
     * <p>A company without a configured domain fails closed with a 400 regardless of the email in the
     * request (decision D8). A present email is normalized, its domain must match the company's
     * case-insensitively, and it is rejected as a duplicate with the same {@link
     * DuplicateEmployeeException} the generated path throws. A blank one is generated from the names,
     * falling back to the employee number when the generated local part is empty (decision T6) and
     * failing with a 400 when that fallback carries no {@code [a-z0-9]} character either, and
     * resolved against the existing candidates (decision T7). When the stored row is already frozen
     * ({@code keycloakUserId} set) and the request leaves the email blank, the stored address is kept
     * unchanged: the record path cannot change a provisioned address (decision T9), so it must not
     * try.</p>
     */
    private String resolveEmail(
            EmployeeRequest request, Company company, UUID companyId, UUID excludeId, Employee existing) {
        var domain = company.getEmailDomain();
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException(
                    "Company " + companyId + " has no configured email domain; configure it before writing employees");
        }

        var requested = EmployeeEmailGenerator.normalized(request.email());
        if (requested != null && !requested.isBlank()) {
            var requestedDomain = EmployeeEmailGenerator.domainOf(requested);
            if (requestedDomain == null || !requestedDomain.equalsIgnoreCase(domain)) {
                throw new IllegalArgumentException(
                        "Email domain must match the company's configured domain (" + domain + ")");
            }
            var taken = excludeId == null
                    ? employeeRepository.existsByCompanyIdAndEmail(companyId, requested)
                    : employeeRepository.existsByCompanyIdAndEmailAndIdNot(companyId, requested, excludeId);
            if (taken) {
                throw new DuplicateEmployeeException("email", requested);
            }
            return requested;
        }

        if (existing != null && existing.getKeycloakUserId() != null) {
            return existing.getEmail();
        }

        var localPart = EmployeeEmailGenerator.localPart(request.firstName(), request.paternalLastName());
        if (localPart.isEmpty()) {
            localPart = EmployeeEmailGenerator.localPartFromToken(request.employeeNumber());
        }
        if (localPart.isEmpty()) {
            throw new IllegalArgumentException(
                    "No email local part can be derived from the names or the employee number; provide an email");
        }
        return firstFreeAddress(localPart, domain, companyId, excludeId);
    }

    /**
     * Picks the first free candidate address, suffixing the local part from the second attempt on.
     *
     * <p>No {@code DataIntegrityViolationException} is caught to retry: the first unique violation
     * aborts the PostgreSQL transaction, so an in-transaction retry is not expressible without
     * savepoints or a per-attempt transaction. The loop pre-checks and the {@code UNIQUE
     * (company_id, email)} stays the final authority for a lost race. Exhausting the attempts is a
     * 409 that names the full first-candidate address.</p>
     */
    private String firstFreeAddress(String localPart, String domain, UUID companyId, UUID excludeId) {
        var candidate = firstFreeAddressOrNull(localPart, domain, companyId, excludeId);
        if (candidate == null) {
            throw new DuplicateEmployeeException("email", EmployeeEmailGenerator.address(localPart, domain));
        }
        return candidate;
    }

    /**
     * Nullable variant of {@link #firstFreeAddress(String, String, UUID, UUID)} for the read-only
     * {@code suggest-email} path: it returns {@code null} instead of throwing when every candidate is
     * taken, so a GET documented as 200 cannot answer 409 (decision T8).
     */
    private String firstFreeAddressOrNull(String localPart, String domain, UUID companyId, UUID excludeId) {
        for (var attempt = 1; attempt <= MAX_EMAIL_ATTEMPTS; attempt++) {
            var candidate = EmployeeEmailGenerator.address(EmployeeEmailGenerator.suffixed(localPart, attempt), domain);
            var taken = excludeId == null
                    ? employeeRepository.existsByCompanyIdAndEmail(companyId, candidate)
                    : employeeRepository.existsByCompanyIdAndEmailAndIdNot(companyId, candidate, excludeId);
            if (!taken) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Resolves the optional address reference.
     *
     * @throws IllegalArgumentException when the referenced address does not exist
     */
    private Address resolveAddress(UUID addressId) {
        if (addressId == null) {
            return null;
        }
        return addressRepository
                .findById(addressId)
                .orElseThrow(() -> new IllegalArgumentException("Address not found with id: " + addressId));
    }

    private static String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private EmployeeResponse toResponse(Employee employee) {
        return toResponse(employee, null);
    }

    /**
     * Builds the read model with the access state the <b>detail</b> path resolves.
     *
     * <p>The state is a parameter instead of a read performed here because
     * {@link #toResponse(Employee)} is the single builder of {@link EmployeeResponse} and is also
     * called by the company-scoped list and the three write paths; resolving the state inside it
     * would run one provisioning query per row on the list (record G3). The list and the write paths
     * therefore keep delegating to {@link #toResponse(Employee)} and emit {@code null}.</p>
     */
    private EmployeeResponse toResponse(Employee employee, String accessState) {
        return new EmployeeResponse(
                employee.getId(),
                employee.getCompany().getId(),
                employee.getEmployeeNumber(),
                employee.getFirstName(),
                employee.getPaternalLastName(),
                employee.getMaternalLastName(),
                employee.getEmail(),
                employee.getPhoneNumber(),
                employee.getBirthDate(),
                employee.getHireDate(),
                employee.getTerminationDate(),
                employee.getAddress() != null ? employee.getAddress().getId() : null,
                employee.getStatus().getId(),
                employee.getStatus().getStatusName(),
                employee.getKeycloakUserId(),
                employee.getEnabled(),
                employee.getVersion(),
                employee.getCreatedAt(),
                employee.getUpdatedAt(),
                accessState);
    }
}
