package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseContractRequest;
import com.lifecontrol.api.hr.dto.ContractRequest;
import com.lifecontrol.api.hr.exception.ContractNotFoundException;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.Contract;
import com.lifecontrol.api.hr.model.ContractType;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.ContractRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractService Tests")
class ContractServiceTest {

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private SeniorityLevelRepository seniorityLevelRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private ContractService contractService;

    private UUID companyId;
    private UUID employeeId;
    private UUID positionId;
    private UUID seniorityLevelId;
    private Company testCompany;
    private Status activeStatus;
    private Status terminatedStatus;
    private Employee testEmployee;
    private Position testPosition;
    private SeniorityLevel testSeniorityLevel;

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PREDECESSOR_START = LocalDate.of(2025, 1, 1);

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        seniorityLevelId = UUID.randomUUID();

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

        testEmployee = Employee.builder()
                .id(employeeId)
                .company(testCompany)
                .employeeNumber("EMP-001")
                .firstName("Juan")
                .paternalLastName("Pérez")
                .email("juan.perez@example.com")
                .birthDate(LocalDate.of(1990, 1, 1))
                .hireDate(LocalDate.of(2020, 1, 1))
                .status(activeStatus)
                .enabled(true)
                .build();

        var department = Department.builder()
                .id(UUID.randomUUID())
                .company(testCompany)
                .departmentCode("OPS")
                .departmentName("Operations")
                .enabled(true)
                .build();
        testPosition = Position.builder()
                .id(positionId)
                .department(department)
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build();
        testSeniorityLevel = SeniorityLevel.builder()
                .id(seniorityLevelId)
                .levelCode("J")
                .levelName("Junior")
                .rank(1)
                .enabled(true)
                .build();
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

    private Contract contract(UUID id, LocalDate start, LocalDate end, boolean enabled) {
        return Contract.builder()
                .id(id)
                .employee(testEmployee)
                .position(testPosition)
                .seniorityLevel(testSeniorityLevel)
                .contractType(ContractType.PERMANENT)
                .monthlySalary(new BigDecimal("1000.00"))
                .startDate(start)
                .endDate(end)
                .enabled(enabled)
                .build();
    }

    private ContractRequest request(LocalDate start, LocalDate end) {
        return new ContractRequest(positionId, seniorityLevelId, "PERMANENT", new BigDecimal("1500.00"), start, end);
    }

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void employeeExists() {
        companyExists();
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
    }

    private void positionExists() {
        when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                .thenReturn(Optional.of(testPosition));
    }

    private void seniorityLevelExists() {
        when(seniorityLevelRepository.findById(seniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
    }

    private void noPredecessor() {
        when(contractRepository.findEnabledContractCoveringDate(eq(employeeId), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    private void contractSaveReturnsArgument() {
        when(contractRepository.save(any(Contract.class))).thenAnswer(inv -> {
            Contract c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
    }

    @Nested
    @DisplayName("company scope contract")
    class CompanyScopeContractTests {

        @Test
        @DisplayName("getContracts verifies company access before loading the company, then scopes the employee")
        void getContracts_VerifiesCompanyAccessFirst() {
            employeeExists();
            when(contractRepository.findByEmployeeIdOrderByStartDateDesc(employeeId))
                    .thenReturn(List.of());

            contractService.getContracts(companyId, employeeId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository, employeeRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
            inOrder.verify(employeeRepository).findByIdAndCompanyId(employeeId, companyId);
        }

        @Test
        @DisplayName("createContract verifies company access before loading the company")
        void createContract_VerifiesCompanyAccessFirst() {
            employeeExists();
            positionExists();
            seniorityLevelExists();
            noPredecessor();
            contractSaveReturnsArgument();

            contractService.createContract(companyId, employeeId, request(START, null));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("closeContract verifies company access before loading the company")
        void closeContract_VerifiesCompanyAccessFirst() {
            employeeExists();
            when(contractRepository.findByEmployeeIdAndId(employeeId, positionId))
                    .thenReturn(Optional.of(contract(positionId, PREDECESSOR_START, null, true)));
            contractSaveReturnsArgument();

            contractService.closeContract(companyId, employeeId, positionId, new CloseContractRequest(START));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException when the company does not exist")
        void getContracts_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.getContracts(companyId, employeeId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("getContracts throws EmployeeNotFoundException for a foreign employee")
        void getContracts_ForeignEmployee_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.getContracts(companyId, employeeId))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(contractRepository, never()).findByEmployeeIdOrderByStartDateDesc(any());
        }

        @Test
        @DisplayName("createContract throws EmployeeNotFoundException for a foreign employee")
        void createContract_ForeignEmployee_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request(START, null)))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(contractRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("create — position and level resolution")
    class CreateResolutionTests {

        @Test
        @DisplayName("resolves the position through the company-scoped lookup")
        void create_ResolvesPositionScopedToCompany() {
            employeeExists();
            positionExists();
            seniorityLevelExists();
            noPredecessor();
            contractSaveReturnsArgument();

            var result = contractService.createContract(companyId, employeeId, request(START, null));

            assertThat(result.positionId()).isEqualTo(positionId);
            assertThat(result.positionName()).isEqualTo("Operator");
            assertThat(result.seniorityLevelId()).isEqualTo(seniorityLevelId);
            assertThat(result.seniorityLevelName()).isEqualTo("Junior");
            assertThat(result.contractType()).isEqualTo(ContractType.PERMANENT);
            assertThat(result.monthlySalary()).isEqualByComparingTo("1500.00");
            assertThat(result.startDate()).isEqualTo(START);
            assertThat(result.endDate()).isNull();
            assertThat(result.enabled()).isTrue();
            assertThat(result.id()).isNotNull();
            verify(positionRepository).findByDepartmentCompanyIdAndId(companyId, positionId);
        }

        @Test
        @DisplayName("a position outside the company is a 404")
        void create_ForeignPosition_IsRejected() {
            employeeExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request(START, null)))
                    .isInstanceOf(PositionNotFoundException.class);
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown seniority level is a 404")
        void create_UnknownSeniorityLevel_IsRejected() {
            employeeExists();
            positionExists();
            when(seniorityLevelRepository.findById(seniorityLevelId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request(START, null)))
                    .isInstanceOf(SeniorityLevelNotFoundException.class);
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown contractType is an IllegalArgumentException (400)")
        void create_UnknownContractType_IsRejected() {
            employeeExists();
            positionExists();
            seniorityLevelExists();
            var request = new ContractRequest(
                    positionId, seniorityLevelId, "PART_TIME", new BigDecimal("1500.00"), START, null);

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(contractRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("create — dates")
    class CreateDateTests {

        @Test
        @DisplayName("an endDate before the startDate is a 400")
        void create_EndBeforeStart_IsRejected() {
            employeeExists();
            positionExists();
            seniorityLevelExists();

            assertThatThrownBy(() ->
                            contractService.createContract(companyId, employeeId, request(START, START.minusDays(1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("endDate equal to startDate is legal (the CHECK is >=)")
        void create_EndEqualToStart_IsAccepted() {
            employeeExists();
            positionExists();
            seniorityLevelExists();
            noPredecessor();
            contractSaveReturnsArgument();

            var result = contractService.createContract(companyId, employeeId, request(START, START));

            assertThat(result.endDate()).isEqualTo(START);
        }
    }

    @Nested
    @DisplayName("create — the close-the-previous rule (T13)")
    class CloseThePreviousTests {

        @Test
        @DisplayName("no predecessor inserts the new contract only")
        void create_NoPredecessor_InsertsNewContractOnly() {
            employeeExists();
            positionExists();
            seniorityLevelExists();
            noPredecessor();
            contractSaveReturnsArgument();

            contractService.createContract(companyId, employeeId, request(START, null));

            verify(contractRepository).findEnabledContractCoveringDate(employeeId, START);
            verify(contractRepository, times(1)).save(any(Contract.class));
        }

        @Test
        @DisplayName("a predecessor covering the new start date is closed the day before it")
        void create_PredecessorCoveringStart_IsClosedTheDayBefore() {
            var predecessor = contract(UUID.randomUUID(), PREDECESSOR_START, null, true);
            employeeExists();
            positionExists();
            seniorityLevelExists();
            when(contractRepository.findEnabledContractCoveringDate(employeeId, START))
                    .thenReturn(List.of(predecessor));
            contractSaveReturnsArgument();

            contractService.createContract(companyId, employeeId, request(START, null));

            assertThat(predecessor.getEndDate()).isEqualTo(START.minusDays(1));
            // The close is flushed before the insert: Hibernate flushes INSERTs before UPDATEs, so a
            // deferred close would let the new row reach the constraint while the predecessor still
            // covers the range.
            verify(contractRepository).saveAndFlush(predecessor);
            verify(contractRepository, times(1)).save(any(Contract.class));
        }

        @Test
        @DisplayName("a predecessor starting on the new start date is a 400, and nothing is written")
        void create_PredecessorStartsSameDay_IsRejected() {
            var predecessor = contract(UUID.randomUUID(), START, null, true);
            employeeExists();
            positionExists();
            seniorityLevelExists();
            when(contractRepository.findEnabledContractCoveringDate(employeeId, START))
                    .thenReturn(List.of(predecessor));

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request(START, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("previous contract");

            assertThat(predecessor.getEndDate()).isNull();
            verify(contractRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("create — the Terminated employee rule (G5)")
    class TerminatedEmployeeTests {

        @Test
        @DisplayName("a Terminated employee cannot open a contract (409)")
        void create_TerminatedEmployee_IsRejected() {
            var terminated = employeeWithStatus(terminatedStatus);
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(terminated));

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request(START, null)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Terminated");
            verify(contractRepository, never()).save(any());
            verify(positionRepository, never()).findByDepartmentCompanyIdAndId(any(), any());
        }

        @Test
        @DisplayName("the Terminated comparison is case-insensitive, like EmployeeService's")
        void create_TerminatedLowerCaseEmployee_IsRejected() {
            var terminated = employeeWithStatus(status(UUID.randomUUID(), "terminated"));
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(terminated));

            assertThatThrownBy(() -> contractService.createContract(companyId, employeeId, request(START, null)))
                    .isInstanceOf(ConflictException.class);
        }

        private Employee employeeWithStatus(Status status) {
            return Employee.builder()
                    .id(employeeId)
                    .company(testCompany)
                    .employeeNumber("EMP-001")
                    .firstName("Juan")
                    .paternalLastName("Pérez")
                    .email("juan.perez@example.com")
                    .birthDate(LocalDate.of(1990, 1, 1))
                    .hireDate(LocalDate.of(2020, 1, 1))
                    .status(status)
                    .enabled(true)
                    .build();
        }
    }

    @Nested
    @DisplayName("close")
    class CloseContractTests {

        @Test
        @DisplayName("sets exactly the requested end date")
        void close_SetsRequestedEndDate() {
            employeeExists();
            var open = contract(UUID.randomUUID(), PREDECESSOR_START, null, true);
            when(contractRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            contractSaveReturnsArgument();

            var result = contractService.closeContract(
                    companyId, employeeId, open.getId(), new CloseContractRequest(LocalDate.of(2025, 6, 30)));

            assertThat(result.endDate()).isEqualTo(LocalDate.of(2025, 6, 30));
            assertThat(result.startDate()).isEqualTo(PREDECESSOR_START);
            assertThat(open.getEndDate()).isEqualTo(LocalDate.of(2025, 6, 30));
        }

        @Test
        @DisplayName("an omitted body defaults the end date to today")
        void close_OmittedBody_DefaultsToToday() {
            employeeExists();
            var open = contract(UUID.randomUUID(), PREDECESSOR_START, null, true);
            when(contractRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            contractSaveReturnsArgument();

            var result = contractService.closeContract(companyId, employeeId, open.getId(), null);

            assertThat(result.endDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("an explicit null end date also defaults to today")
        void close_ExplicitNullEndDate_DefaultsToToday() {
            employeeExists();
            var open = contract(UUID.randomUUID(), PREDECESSOR_START, null, true);
            when(contractRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            contractSaveReturnsArgument();

            var result =
                    contractService.closeContract(companyId, employeeId, open.getId(), new CloseContractRequest(null));

            assertThat(result.endDate()).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("an end date before the contract's start date is a 400")
        void close_EndBeforeStart_IsRejected() {
            employeeExists();
            var open = contract(UUID.randomUUID(), START, null, true);
            when(contractRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));

            assertThatThrownBy(() -> contractService.closeContract(
                            companyId, employeeId, open.getId(), new CloseContractRequest(START.minusDays(1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("a missing end date on a future contract is a 400 (today is before its start)")
        void close_FutureContractWithoutEndDate_IsRejected() {
            employeeExists();
            var future = contract(UUID.randomUUID(), LocalDate.now().plusDays(10), null, true);
            when(contractRepository.findByEmployeeIdAndId(employeeId, future.getId()))
                    .thenReturn(Optional.of(future));

            assertThatThrownBy(() -> contractService.closeContract(companyId, employeeId, future.getId(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
        }

        @Test
        @DisplayName("an already-closed contract is a 409 and is not written again")
        void close_AlreadyClosed_IsRejected() {
            employeeExists();
            var closed = contract(UUID.randomUUID(), PREDECESSOR_START, LocalDate.of(2025, 12, 31), true);
            when(contractRepository.findByEmployeeIdAndId(employeeId, closed.getId()))
                    .thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> contractService.closeContract(
                            companyId, employeeId, closed.getId(), new CloseContractRequest(START)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already closed");
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("a missing contract of the employee is a 404")
        void close_Missing_IsRejected() {
            employeeExists();
            var id = UUID.randomUUID();
            when(contractRepository.findByEmployeeIdAndId(employeeId, id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.closeContract(companyId, employeeId, id, null))
                    .isInstanceOf(ContractNotFoundException.class);
            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("a foreign employee is a 404 before the contract is looked up")
        void close_ForeignEmployee_IsRejected() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.closeContract(companyId, employeeId, UUID.randomUUID(), null))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(contractRepository, never()).findByEmployeeIdAndId(any(), any());
        }
    }

    @Nested
    @DisplayName("list")
    class ListContractTests {

        @Test
        @DisplayName("maps every stored contract, disabled ones included")
        void getContracts_MapsEveryRow() {
            employeeExists();
            var enabled = contract(UUID.randomUUID(), START, null, true);
            var disabled = contract(UUID.randomUUID(), PREDECESSOR_START, LocalDate.of(2025, 12, 31), false);
            when(contractRepository.findByEmployeeIdOrderByStartDateDesc(employeeId))
                    .thenReturn(List.of(enabled, disabled));

            var result = contractService.getContracts(companyId, employeeId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.enabled()).containsExactly(true, false);
        }
    }
}
