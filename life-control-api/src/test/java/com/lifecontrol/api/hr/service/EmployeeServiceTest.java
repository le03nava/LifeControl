package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.address.repository.AddressRepository;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.EmployeeEmailSuggestionResponse;
import com.lifecontrol.api.hr.dto.EmployeeRequest;
import com.lifecontrol.api.hr.exception.DuplicateEmployeeException;
import com.lifecontrol.api.hr.exception.EmployeeEmailFrozenException;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningQueryService;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeService Tests")
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private StatusRepository statusRepository;

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private AccessProvisioningQueryService accessProvisioningQueryService;

    @InjectMocks
    private EmployeeService employeeService;

    private UUID companyId;
    private UUID employeeId;
    private Company testCompany;
    private Status activeStatus;
    private Status terminatedStatus;
    private Employee testEmployee;

    private static final LocalDate BIRTH_DATE = LocalDate.of(1990, 1, 1);
    private static final LocalDate HIRE_DATE = LocalDate.of(2020, 1, 1);

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .emailDomain("example.com")
                .enabled(true)
                .build();

        activeStatus = status(UUID.randomUUID(), "Active");
        terminatedStatus = status(UUID.randomUUID(), "Terminated");
        testEmployee = employee(employeeId, "EMP-001", "juan.perez@example.com", true);
    }

    private Status status(UUID id, String name) {
        var type = StatusType.builder()
                .id(UUID.randomUUID())
                .statusTypeName("EMPLOYEE_STATUS")
                .enabled(true)
                .build();
        return Status.builder()
                .id(id)
                .statusName(name)
                .statusType(type)
                .enabled(true)
                .build();
    }

    private Employee employee(UUID id, String number, String email, boolean enabled) {
        return Employee.builder()
                .id(id)
                .company(testCompany)
                .employeeNumber(number)
                .firstName("Juan")
                .paternalLastName("Pérez")
                .email(email)
                .birthDate(BIRTH_DATE)
                .hireDate(HIRE_DATE)
                .status(activeStatus)
                .enabled(enabled)
                .build();
    }

    private EmployeeRequest request(String number, String email) {
        return new EmployeeRequest(number, "Juan", "Pérez", null, email, null, BIRTH_DATE, HIRE_DATE, null, null, null);
    }

    private EmployeeRequest requestWithStatus(String number, String email, UUID statusId, LocalDate terminationDate) {
        return new EmployeeRequest(
                number, "Juan", "Pérez", null, email, null, BIRTH_DATE, HIRE_DATE, terminationDate, null, statusId);
    }

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void activeStatusExists() {
        when(statusRepository.findByTypeNameAndStatusName("EMPLOYEE_STATUS", "Active"))
                .thenReturn(Optional.of(activeStatus));
    }

    private void saveReturnsArgument() {
        when(employeeRepository.save(any(Employee.class))).thenAnswer(inv -> {
            Employee e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(employeeId);
            }
            return e;
        });
    }

    @Nested
    @DisplayName("company scope contract")
    class CompanyScopeContractTests {

        @Test
        @DisplayName("getAllEmployees verifies company access before loading the company")
        void getAllEmployees_VerifiesCompanyAccessFirst() {
            companyExists();
            when(employeeRepository.findCompanyEmployees(companyId, null, null, false))
                    .thenReturn(List.of());

            employeeService.getAllEmployees(companyId, null, null, false);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("createEmployee verifies company access before loading the company")
        void createEmployee_VerifiesCompanyAccessFirst() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();

            employeeService.createEmployee(companyId, request("EMP-001", null));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException when the company does not exist")
        void getAllEmployees_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> employeeService.getAllEmployees(companyId, null, null, false))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }
    }

    @Nested
    @DisplayName("status resolution")
    class StatusResolutionTests {

        @Test
        @DisplayName("create resolves the pinned Active default when no status is given")
        void create_NoStatus_UsesActiveDefault() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();

            var result = employeeService.createEmployee(companyId, request("EMP-001", null));

            assertThat(result.statusName()).isEqualTo("Active");
            verify(statusRepository).findByTypeNameAndStatusName("EMPLOYEE_STATUS", "Active");
        }

        @Test
        @DisplayName("create accepts a status of the EMPLOYEE_STATUS family")
        void create_ExplicitStatus_IsValidated() {
            companyExists();
            when(statusRepository.findById(terminatedStatus.getId())).thenReturn(Optional.of(terminatedStatus));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();

            var result = employeeService.createEmployee(
                    companyId, requestWithStatus("EMP-001", null, terminatedStatus.getId(), LocalDate.of(2021, 1, 1)));

            assertThat(result.statusName()).isEqualTo("Terminated");
            assertThat(result.terminationDate()).isEqualTo(LocalDate.of(2021, 1, 1));
        }

        @Test
        @DisplayName("Terminated requires a termination date")
        void create_TerminatedWithoutTerminationDate_IsRejected() {
            companyExists();
            when(statusRepository.findById(terminatedStatus.getId())).thenReturn(Optional.of(terminatedStatus));

            assertThatThrownBy(() -> employeeService.createEmployee(
                            companyId, requestWithStatus("EMP-001", null, terminatedStatus.getId(), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("terminationDate");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("any other status forbids a termination date")
        void create_NonTerminatedWithTerminationDate_IsRejected() {
            companyExists();
            activeStatusExists();

            assertThatThrownBy(() -> employeeService.createEmployee(
                            companyId, requestWithStatus("EMP-001", null, null, LocalDate.of(2021, 1, 1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("terminationDate");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("update rejects Terminated without a termination date")
        void update_TerminatedWithoutTerminationDate_IsRejected() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
            when(statusRepository.findById(terminatedStatus.getId())).thenReturn(Optional.of(terminatedStatus));

            assertThatThrownBy(() -> employeeService.updateEmployee(
                            companyId, employeeId, requestWithStatus("EMP-001", null, terminatedStatus.getId(), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("terminationDate");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("update forbids a termination date for a non-Terminated status")
        void update_NonTerminatedWithTerminationDate_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));

            assertThatThrownBy(() -> employeeService.updateEmployee(
                            companyId, employeeId, requestWithStatus("EMP-001", null, null, LocalDate.of(2021, 1, 1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("terminationDate");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("update defaulting a Terminated employee to Active clears the termination date")
        void update_TerminatedOmittedStatus_ReactivatesWithActive() {
            var terminated = Employee.builder()
                    .id(employeeId)
                    .company(testCompany)
                    .employeeNumber("EMP-001")
                    .firstName("Juan")
                    .paternalLastName("Pérez")
                    .email("juan.perez@example.com")
                    .birthDate(BIRTH_DATE)
                    .hireDate(HIRE_DATE)
                    .terminationDate(LocalDate.of(2021, 1, 1))
                    .status(terminatedStatus)
                    .enabled(true)
                    .build();
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(terminated));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, "EMP-001", employeeId))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmailAndIdNot(companyId, "juan.perez@example.com", employeeId))
                    .thenReturn(false);
            saveReturnsArgument();

            var result =
                    employeeService.updateEmployee(companyId, employeeId, request("EMP-001", "juan.perez@example.com"));

            assertThat(result.statusName()).isEqualTo("Active");
            assertThat(result.terminationDate()).isNull();
        }
    }

    @Nested
    @DisplayName("date rules")
    class DateRulesTests {

        @Test
        @DisplayName("birthDate equal to hireDate is rejected")
        void create_BirthEqualToHire_IsRejected() {
            companyExists();
            activeStatusExists();
            var request = new EmployeeRequest(
                    "EMP-001", "Juan", "Pérez", null, null, null, HIRE_DATE, HIRE_DATE, null, null, null);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("birthDate");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("terminationDate before hireDate is rejected")
        void create_TerminationBeforeHire_IsRejected() {
            companyExists();
            when(statusRepository.findById(terminatedStatus.getId())).thenReturn(Optional.of(terminatedStatus));
            var request = requestWithStatus("EMP-001", null, terminatedStatus.getId(), LocalDate.of(2019, 12, 31));

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("terminationDate");
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("email resolution")
    class EmailResolutionTests {

        @Test
        @DisplayName("a company without an email domain fails closed with 400 and no write")
        void create_NoCompanyDomain_IsRejectedWithoutWrite() {
            testCompany.setEmailDomain(null);
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request("EMP-001", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email domain");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a request email whose domain does not match the company domain is rejected")
        void create_ForeignEmailDomain_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request("EMP-001", "juan@other.com")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email domain");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a blank request email generates the address from the names")
        void create_BlankEmail_GeneratesAddress() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();

            var result = employeeService.createEmployee(companyId, request("EMP-001", "  "));

            assertThat(result.email()).isEqualTo("juan.perez@example.com");
        }

        @Test
        @DisplayName("a visible collision produces the suffixed address")
        void create_VisibleCollision_SuffixesAddress() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(true);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez2@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();

            var result = employeeService.createEmployee(companyId, request("EMP-001", null));

            assertThat(result.email()).isEqualTo("juan.perez2@example.com");
        }

        @Test
        @DisplayName("probes the plain candidate then the numeric suffixes in candidate order")
        void create_VisibleCollisions_ProbeTheCandidateSequence() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(true);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez2@example.com"))
                    .thenReturn(true);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez3@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();

            var result = employeeService.createEmployee(companyId, request("EMP-001", null));

            assertThat(result.email()).isEqualTo("juan.perez3@example.com");
            var inOrder = inOrder(employeeRepository);
            inOrder.verify(employeeRepository).existsByCompanyIdAndEmail(companyId, "juan.perez@example.com");
            inOrder.verify(employeeRepository).existsByCompanyIdAndEmail(companyId, "juan.perez2@example.com");
            inOrder.verify(employeeRepository).existsByCompanyIdAndEmail(companyId, "juan.perez3@example.com");
        }

        @Test
        @DisplayName("twenty taken candidates exhaust the loop after exactly twenty probes")
        void create_AllCandidatesTaken_ThrowsAfterTwentyProbes() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(eq(companyId), any(String.class)))
                    .thenReturn(true);
            var candidates = ArgumentCaptor.forClass(String.class);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request("EMP-001", null)))
                    .isInstanceOf(DuplicateEmployeeException.class)
                    .hasMessageContaining("juan.perez@example.com");
            verify(employeeRepository, times(20)).existsByCompanyIdAndEmail(eq(companyId), candidates.capture());
            assertThat(candidates.getAllValues())
                    .containsExactly(
                            "juan.perez@example.com",
                            "juan.perez2@example.com",
                            "juan.perez3@example.com",
                            "juan.perez4@example.com",
                            "juan.perez5@example.com",
                            "juan.perez6@example.com",
                            "juan.perez7@example.com",
                            "juan.perez8@example.com",
                            "juan.perez9@example.com",
                            "juan.perez10@example.com",
                            "juan.perez11@example.com",
                            "juan.perez12@example.com",
                            "juan.perez13@example.com",
                            "juan.perez14@example.com",
                            "juan.perez15@example.com",
                            "juan.perez16@example.com",
                            "juan.perez17@example.com",
                            "juan.perez18@example.com",
                            "juan.perez19@example.com",
                            "juan.perez20@example.com");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a frozen employee cannot change the email")
        void update_FrozenEmployeeChangedEmail_IsRejected() {
            var frozen = Employee.builder()
                    .id(employeeId)
                    .company(testCompany)
                    .employeeNumber("EMP-001")
                    .firstName("Juan")
                    .paternalLastName("Pérez")
                    .email("juan.perez@example.com")
                    .birthDate(BIRTH_DATE)
                    .hireDate(HIRE_DATE)
                    .status(activeStatus)
                    .keycloakUserId("kc-1")
                    .enabled(true)
                    .build();
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(frozen));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, "EMP-001", employeeId))
                    .thenReturn(false);

            assertThatThrownBy(() -> employeeService.updateEmployee(
                            companyId, employeeId, request("EMP-001", "juan.perez9@example.com")))
                    .isInstanceOf(EmployeeEmailFrozenException.class)
                    .hasMessageContaining("frozen");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a frozen employee may keep the same email")
        void update_FrozenEmployeeSameEmail_IsAllowed() {
            var frozen = Employee.builder()
                    .id(employeeId)
                    .company(testCompany)
                    .employeeNumber("EMP-001")
                    .firstName("Juan")
                    .paternalLastName("Pérez")
                    .email("juan.perez@example.com")
                    .birthDate(BIRTH_DATE)
                    .hireDate(HIRE_DATE)
                    .status(activeStatus)
                    .keycloakUserId("kc-1")
                    .enabled(true)
                    .build();
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(frozen));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, "EMP-001", employeeId))
                    .thenReturn(false);
            saveReturnsArgument();

            var result =
                    employeeService.updateEmployee(companyId, employeeId, request("EMP-001", "juan.perez@example.com"));

            assertThat(result.email()).isEqualTo("juan.perez@example.com");
            assertThat(result.keycloakUserId()).isEqualTo("kc-1");
        }

        @Test
        @DisplayName("the empty-name fallback sanitizes the employee number instead of using it raw")
        void create_EmptyNames_PunctuatedNumber_StoresSanitizedFallback() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP 001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "emp001@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();
            var request = new EmployeeRequest(
                    "EMP 001", "!!!", "###", null, null, null, BIRTH_DATE, HIRE_DATE, null, null, null);

            var result = employeeService.createEmployee(companyId, request);

            assertThat(result.email()).isEqualTo("emp001@example.com");
        }

        @Test
        @DisplayName("an employee number with no derivable character falls back to a 400, not a constraint violation")
        void create_NoDerivableLocalPart_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "!!!"))
                    .thenReturn(false);
            var request =
                    new EmployeeRequest("!!!", "!!!", "###", null, null, null, BIRTH_DATE, HIRE_DATE, null, null, null);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("local part");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("an explicit duplicate email names the full address like the generated path")
        void create_ExplicitDuplicateEmail_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(true);

            assertThatThrownBy(() ->
                            employeeService.createEmployee(companyId, request("EMP-001", "juan.perez@example.com")))
                    .isInstanceOf(DuplicateEmployeeException.class)
                    .hasMessageContaining("juan.perez@example.com");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("update rejects a duplicate explicit email excluding the row itself")
        void update_ExplicitDuplicateEmail_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, "EMP-001", employeeId))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmailAndIdNot(
                            companyId, "grace.hopper@example.com", employeeId))
                    .thenReturn(true);

            assertThatThrownBy(() -> employeeService.updateEmployee(
                            companyId, employeeId, request("EMP-001", "grace.hopper@example.com")))
                    .isInstanceOf(DuplicateEmployeeException.class)
                    .hasMessageContaining("grace.hopper@example.com");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a company without an email domain rejects even a fully-formed request email")
        void create_NoCompanyDomain_WithExplicitEmail_IsRejected() {
            testCompany.setEmailDomain(null);
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);

            assertThatThrownBy(() ->
                            employeeService.createEmployee(companyId, request("EMP-001", "juan.perez@example.com")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("email domain");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("a blank-email no-op update of a frozen employee keeps the stored address")
        void update_FrozenEmployeeBlankEmail_KeepsStoredAddress() {
            var frozen = Employee.builder()
                    .id(employeeId)
                    .company(testCompany)
                    .employeeNumber("EMP-001")
                    .firstName("Juan")
                    .paternalLastName("Pérez")
                    .email("juan.perez2@example.com")
                    .birthDate(BIRTH_DATE)
                    .hireDate(HIRE_DATE)
                    .status(activeStatus)
                    .keycloakUserId("kc-1")
                    .enabled(true)
                    .build();
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(frozen));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, "EMP-001", employeeId))
                    .thenReturn(false);
            saveReturnsArgument();

            // The plain candidate juan.perez@example.com is free again (the other row moved away),
            // so the generation path would resolve a different address and 409 against the frozen
            // one. Because the request leaves the email blank, the record path keeps the stored
            // address and never attempts the change (decision T9).
            var result = employeeService.updateEmployee(companyId, employeeId, request("EMP-001", null));

            assertThat(result.email()).isEqualTo("juan.perez2@example.com");
            assertThat(result.keycloakUserId()).isEqualTo("kc-1");
        }
    }

    @Nested
    @DisplayName("address resolution")
    class AddressResolutionTests {

        @Test
        @DisplayName("create resolves the optional address reference")
        void create_AddressId_ResolvesTheAddress() {
            var addressId = UUID.randomUUID();
            var address = Address.builder().id(addressId).enabled(true).build();
            companyExists();
            activeStatusExists();
            when(addressRepository.findById(addressId)).thenReturn(Optional.of(address));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);
            saveReturnsArgument();
            var request = new EmployeeRequest(
                    "EMP-001", "Juan", "Pérez", null, null, null, BIRTH_DATE, HIRE_DATE, null, addressId, null);

            var result = employeeService.createEmployee(companyId, request);

            assertThat(result.addressId()).isEqualTo(addressId);
        }

        @Test
        @DisplayName("create rejects a missing address reference without writing")
        void create_MissingAddress_IsRejected() {
            var addressId = UUID.randomUUID();
            companyExists();
            activeStatusExists();
            when(addressRepository.findById(addressId)).thenReturn(Optional.empty());
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(false);
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);
            var request = new EmployeeRequest(
                    "EMP-001", "Juan", "Pérez", null, null, null, BIRTH_DATE, HIRE_DATE, null, addressId, null);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Address not found");
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("employee number uniqueness")
    class EmployeeNumberUniquenessTests {

        @Test
        @DisplayName("create rejects a duplicate employee number")
        void create_DuplicateNumber_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.existsByCompanyIdAndEmployeeNumber(companyId, "EMP-001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> employeeService.createEmployee(companyId, request("EMP-001", null)))
                    .isInstanceOf(DuplicateEmployeeException.class)
                    .hasMessageContaining("employeeNumber");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("update rejects a duplicate employee number excluding the row itself")
        void update_DuplicateNumber_IsRejected() {
            companyExists();
            activeStatusExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
            when(employeeRepository.existsByCompanyIdAndEmployeeNumberAndIdNot(companyId, "EMP-002", employeeId))
                    .thenReturn(true);

            assertThatThrownBy(() -> employeeService.updateEmployee(
                            companyId, employeeId, request("EMP-002", "juan.perez@example.com")))
                    .isInstanceOf(DuplicateEmployeeException.class);
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("read and delete")
    class ReadAndDeleteTests {

        @Test
        @DisplayName("getEmployeeById returns the scoped employee")
        void getEmployeeById_Success() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));

            var result = employeeService.getEmployeeById(companyId, employeeId);

            assertThat(result.employeeNumber()).isEqualTo("EMP-001");
            assertThat(result.companyId()).isEqualTo(companyId);
        }

        @Test
        @DisplayName("getEmployeeById carries the access state the detail path resolves")
        void getEmployeeById_CarriesAccessState() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
            when(accessProvisioningQueryService.accessState(employeeId)).thenReturn("PENDING");

            var result = employeeService.getEmployeeById(companyId, employeeId);

            assertThat(result.accessState()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("getAllEmployees never resolves the access state and emits null")
        void getAllEmployees_DoesNotResolveAccessState() {
            companyExists();
            when(employeeRepository.findCompanyEmployees(companyId, null, null, false))
                    .thenReturn(List.of(testEmployee));

            var result = employeeService.getAllEmployees(companyId, null, null, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).accessState()).isNull();
            verifyNoInteractions(accessProvisioningQueryService);
        }

        @Test
        @DisplayName("getEmployeeById throws EmployeeNotFoundException for a foreign employee")
        void getEmployeeById_NotFound_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> employeeService.getEmployeeById(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(employeeRepository, never()).findById(any());
        }

        @Test
        @DisplayName("delete sets enabled=false and is idempotent")
        void delete_SoftDeletesAndIsIdempotent() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
            saveReturnsArgument();

            employeeService.deleteEmployee(companyId, employeeId);
            employeeService.deleteEmployee(companyId, employeeId);

            assertThat(testEmployee.getEnabled()).isFalse();
            verify(employeeRepository, times(1)).save(any(Employee.class));
        }

        @Test
        @DisplayName("delete throws EmployeeNotFoundException when the row is missing")
        void delete_Missing_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> employeeService.deleteEmployee(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);
        }

        @Test
        @DisplayName("setEmployeeEnabled sets exactly the value it is given")
        void setEmployeeEnabled_SetsExactValue() {
            var disabled = employee(employeeId, "EMP-001", "juan.perez@example.com", false);
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(disabled));
            saveReturnsArgument();

            var enabledResult = employeeService.setEmployeeEnabled(companyId, employeeId, true);
            assertThat(enabledResult.enabled()).isTrue();

            var disabledResult = employeeService.setEmployeeEnabled(companyId, employeeId, false);
            assertThat(disabledResult.enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("suggestEmail")
    class SuggestEmailTests {

        @Test
        @DisplayName("returns NO_EMAIL_DOMAIN when the company has no domain")
        void suggestEmail_NoDomain_ReturnsReason() {
            testCompany.setEmailDomain(null);
            companyExists();

            var result = employeeService.suggestEmail(companyId, "Juan", "Pérez");

            assertThat(result).isEqualTo(new EmployeeEmailSuggestionResponse(null, "NO_EMAIL_DOMAIN"));
        }

        @Test
        @DisplayName("returns EMPTY_LOCAL_PART when the names normalize to nothing")
        void suggestEmail_EmptyLocalPart_ReturnsReason() {
            companyExists();

            var result = employeeService.suggestEmail(companyId, "!!!", "###");

            assertThat(result).isEqualTo(new EmployeeEmailSuggestionResponse(null, "EMPTY_LOCAL_PART"));
        }

        @Test
        @DisplayName("returns the first free candidate without writing anything")
        void suggestEmail_FreeCandidate_ReturnsItWithoutWrite() {
            companyExists();
            when(employeeRepository.existsByCompanyIdAndEmail(companyId, "juan.perez@example.com"))
                    .thenReturn(false);

            var result = employeeService.suggestEmail(companyId, "Juan", "Pérez");

            assertThat(result).isEqualTo(new EmployeeEmailSuggestionResponse("juan.perez@example.com", null));
            verify(employeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("returns NO_FREE_CANDIDATE when every candidate is taken")
        void suggestEmail_AllCandidatesTaken_ReturnsReason() {
            companyExists();
            when(employeeRepository.existsByCompanyIdAndEmail(eq(companyId), any(String.class)))
                    .thenReturn(true);

            var result = employeeService.suggestEmail(companyId, "Juan", "Pérez");

            assertThat(result).isEqualTo(new EmployeeEmailSuggestionResponse(null, "NO_FREE_CANDIDATE"));
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getAllEmployees")
    class GetAllEmployeesTests {

        @Test
        @DisplayName("normalizes the search term before querying")
        void getAllEmployees_NormalizesSearch() {
            companyExists();
            when(employeeRepository.findCompanyEmployees(companyId, "%juan%", null, false))
                    .thenReturn(List.of(testEmployee));

            var result = employeeService.getAllEmployees(companyId, "  JuAn  ", null, false);

            assertThat(result).hasSize(1);
            verify(employeeRepository).findCompanyEmployees(companyId, "%juan%", null, false);
        }

        @Test
        @DisplayName("passes a null search through when the term is blank")
        void getAllEmployees_BlankSearch_IsNull() {
            companyExists();
            when(employeeRepository.findCompanyEmployees(companyId, null, null, true))
                    .thenReturn(List.of());

            employeeService.getAllEmployees(companyId, "   ", null, true);

            verify(employeeRepository).findCompanyEmployees(companyId, null, null, true);
        }
    }
}
