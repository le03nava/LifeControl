package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.company.exception.CompanyCountryNotFoundException;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.CompanyRegionNotFoundException;
import com.lifecontrol.api.company.exception.CompanyZoneNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.exception.ScopeAssignmentNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import com.lifecontrol.api.provisioning.service.AccessProvisioningProducer;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
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

/**
 * Unit spec of {@link EmployeeScopeAssignmentService}, the Mockito shape
 * {@code ContractServiceTest} establishes for the neighbouring HR service.
 *
 * <p>Every rule the record puts on the service is pinned here: the company-scope-first order, the
 * exactly-one-node refusal, the same-company node refusal (a 404 naming the level), the
 * close-the-predecessor day before (which is what keeps two rows of one node from overlapping under
 * {@code ex_employee_scope_assignments_<level>_no_overlap}), the close-once mutation with its
 * inclusive-to-exclusive conversion, and the provisioning hook. The database's three partial
 * exclusion constraints are deliberately not mocked: the service does not pre-check them, and the
 * migration suite is where the real PostgreSQL semantics belong.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeScopeAssignmentService Tests")
class EmployeeScopeAssignmentServiceTest {

    @Mock
    private EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanyCountryRepository companyCountryRepository;

    @Mock
    private CompanyRegionRepository companyRegionRepository;

    @Mock
    private CompanyZoneRepository companyZoneRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private AccessProvisioningProducer accessProvisioningProducer;

    @InjectMocks
    private EmployeeScopeAssignmentService service;

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate PREDECESSOR_START = LocalDate.of(2026, 1, 1);

    private UUID companyId;
    private UUID employeeId;
    private Company testCompany;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private Employee testEmployee;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();
        testCompanyCountry = CompanyCountry.builder()
                .id(UUID.randomUUID())
                .company(testCompany)
                .country(Country.builder()
                        .id(UUID.randomUUID())
                        .countryCode("MX")
                        .countryName("México")
                        .enabled(true)
                        .build())
                .build();
        testRegion = CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(testCompanyCountry)
                .regionCode("R")
                .regionName("Region")
                .enabled(true)
                .build();
        testZone = CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(testRegion)
                .zoneCode("Z")
                .zoneName("Zone")
                .enabled(true)
                .build();

        var activeStatus = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Active")
                .statusType(StatusType.builder()
                        .id(UUID.randomUUID())
                        .statusTypeName("EMPLOYEE_STATUS")
                        .enabled(true)
                        .build())
                .enabled(true)
                .build();
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
    }

    // --- stubbing helpers ---

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void employeeExists() {
        companyExists();
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
    }

    private void countryExists() {
        when(companyCountryRepository.findById(testCompanyCountry.getId())).thenReturn(Optional.of(testCompanyCountry));
    }

    private void regionExists() {
        when(companyRegionRepository.findById(testRegion.getId())).thenReturn(Optional.of(testRegion));
    }

    private void zoneExists() {
        when(companyZoneRepository.findById(testZone.getId())).thenReturn(Optional.of(testZone));
    }

    private void noPredecessor(UUID nodeId) {
        when(employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                        eq(employeeId), eq(nodeId), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    private void saveReturnsArgument() {
        when(employeeScopeAssignmentRepository.save(any(EmployeeScopeAssignment.class)))
                .thenAnswer(inv -> {
                    EmployeeScopeAssignment a = inv.getArgument(0);
                    if (a.getId() == null) {
                        a.setId(UUID.randomUUID());
                    }
                    return a;
                });
    }

    private EmployeeScopeAssignment assignment(
            UUID id,
            CompanyCountry companyCountry,
            CompanyRegion companyRegion,
            CompanyZone companyZone,
            LocalDate validFrom,
            LocalDate validTo,
            boolean enabled) {
        return assignment(id, companyCountry, companyRegion, companyZone, null, validFrom, validTo, enabled);
    }

    private EmployeeScopeAssignment assignment(
            UUID id,
            CompanyCountry companyCountry,
            CompanyRegion companyRegion,
            CompanyZone companyZone,
            CompanyStore companyStore,
            LocalDate validFrom,
            LocalDate validTo,
            boolean enabled) {
        return EmployeeScopeAssignment.builder()
                .id(id)
                .employee(testEmployee)
                .companyCountry(companyCountry)
                .companyRegion(companyRegion)
                .companyZone(companyZone)
                .companyStore(companyStore)
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(enabled)
                .build();
    }

    private EmployeeScopeAssignment regionRow(LocalDate validFrom, LocalDate validTo) {
        return assignment(UUID.randomUUID(), null, testRegion, null, validFrom, validTo, true);
    }

    private EmployeeScopeAssignment savedAssignment() {
        var captor = ArgumentCaptor.forClass(EmployeeScopeAssignment.class);
        verify(employeeScopeAssignmentRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("company scope")
    class CompanyScopeTests {

        @Test
        @DisplayName("getAssignments verifies company access before loading, then scopes the employee")
        void getAssignments_VerifiesCompanyAccessFirst() {
            employeeExists();
            when(employeeScopeAssignmentRepository.findEmployeeAssignments(employeeId, true))
                    .thenReturn(List.of());

            service.getAssignments(companyId, employeeId, true);

            InOrder inOrder = inOrder(currentUserContext, companyRepository, employeeRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
            inOrder.verify(employeeRepository).findByIdAndCompanyId(employeeId, companyId);
        }

        @Test
        @DisplayName("createAssignment verifies company access before loading the company")
        void createAssignment_VerifiesCompanyAccessFirst() {
            employeeExists();
            regionExists();
            noPredecessor(testRegion.getId());
            saveReturnsArgument();

            service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("closeAssignment verifies company access before loading the company")
        void closeAssignment_VerifiesCompanyAccessFirst() {
            employeeExists();
            var open = regionRow(PREDECESSOR_START, null);
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();

            service.closeAssignment(
                    companyId, employeeId, open.getId(), new CloseScopeAssignmentRequest(LocalDate.of(2026, 2, 1)));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException when the company does not exist")
        void getAssignments_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAssignments(companyId, employeeId, true))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }

        @Test
        @DisplayName("a foreign employee is a 404 before any assignment is read")
        void getAssignments_ForeignEmployee_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAssignments(companyId, employeeId, true))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(employeeScopeAssignmentRepository, never()).findEmployeeAssignments(any(), anyBoolean());
        }

        @Test
        @DisplayName("a foreign employee is a 404 before the node is resolved")
        void createAssignment_ForeignEmployee_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START)))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(companyRegionRepository, never()).findById(any());
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the exactly-one-node rule")
    class ExactlyOneNodeTests {

        @Test
        @DisplayName("no node is a 400 and writes nothing")
        void createAssignment_NoNode_IsRejected() {
            employeeExists();

            assertThatThrownBy(() -> service.createAssignment(
                            companyId, employeeId, new ScopeAssignmentRequest(null, null, null, null, START)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one");
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("two nodes are a 400 and write nothing")
        void createAssignment_TwoNodes_IsRejected() {
            employeeExists();

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(
                                    testCompanyCountry.getId(), testRegion.getId(), null, null, START)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly one");
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the same-company node rule")
    class SameCompanyNodeTests {

        @Test
        @DisplayName("a country of another company is a 404 and writes nothing")
        void createAssignment_ForeignCountry_IsRejected() {
            employeeExists();
            var foreignCountry = companyCountryOfAnotherCompany();
            when(companyCountryRepository.findById(foreignCountry.getId())).thenReturn(Optional.of(foreignCountry));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(foreignCountry.getId(), null, null, null, START)))
                    .isInstanceOf(CompanyCountryNotFoundException.class);
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a region of another company is a 404 and writes nothing")
        void createAssignment_ForeignRegion_IsRejected() {
            employeeExists();
            var foreignCountry = companyCountryOfAnotherCompany();
            var foreignRegion = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(foreignCountry)
                    .regionCode("R2")
                    .regionName("Foreign Region")
                    .enabled(true)
                    .build();
            when(companyRegionRepository.findById(foreignRegion.getId())).thenReturn(Optional.of(foreignRegion));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(null, foreignRegion.getId(), null, null, START)))
                    .isInstanceOf(CompanyRegionNotFoundException.class);
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a zone of another company is a 404 and writes nothing")
        void createAssignment_ForeignZone_IsRejected() {
            employeeExists();
            var foreignZone = CompanyZone.builder()
                    .id(UUID.randomUUID())
                    .companyRegion(CompanyRegion.builder()
                            .id(UUID.randomUUID())
                            .companyCountry(companyCountryOfAnotherCompany())
                            .regionCode("R2")
                            .regionName("Foreign Region")
                            .enabled(true)
                            .build())
                    .zoneCode("Z2")
                    .zoneName("Foreign Zone")
                    .enabled(true)
                    .build();
            when(companyZoneRepository.findById(foreignZone.getId())).thenReturn(Optional.of(foreignZone));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(null, null, foreignZone.getId(), null, START)))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
            verify(employeeScopeAssignmentRepository, never()).save(any());
            verify(employeeScopeAssignmentRepository, never())
                    .findEnabledAssignmentForNodeCoveringDate(any(), any(), any());
        }

        @Test
        @DisplayName("an unknown node is a 404 naming its level")
        void createAssignment_UnknownZone_IsRejected() {
            employeeExists();
            var unknownZoneId = UUID.randomUUID();
            when(companyZoneRepository.findById(unknownZoneId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createAssignment(
                            companyId, employeeId, new ScopeAssignmentRequest(null, null, unknownZoneId, null, START)))
                    .isInstanceOf(CompanyZoneNotFoundException.class);
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("create — the close-the-predecessor rule")
    class CloseThePredecessorTests {

        @Test
        @DisplayName("no predecessor writes the new row only")
        void createAssignment_NoPredecessor_InsertsTheNewRowOnly() {
            employeeExists();
            regionExists();
            noPredecessor(testRegion.getId());
            saveReturnsArgument();

            service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START));

            verify(employeeScopeAssignmentRepository)
                    .findEnabledAssignmentForNodeCoveringDate(employeeId, testRegion.getId(), START);
            verify(employeeScopeAssignmentRepository, times(1)).save(any(EmployeeScopeAssignment.class));
            verify(employeeScopeAssignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a predecessor covering the new start is closed the day before, flushed first (no overlap)")
        void createAssignment_PredecessorCoveringStart_IsClosedTheDayBefore() {
            var predecessor = regionRow(PREDECESSOR_START, null);
            employeeExists();
            regionExists();
            when(employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                            employeeId, testRegion.getId(), START))
                    .thenReturn(List.of(predecessor));
            saveReturnsArgument();

            service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START));

            // The column is exclusive, so storing the successor's validFrom as the predecessor's bound
            // makes the predecessor's last covered day START - 1: no gap and no overlap.
            assertThat(predecessor.getValidTo()).isEqualTo(START);
            var captor = ArgumentCaptor.forClass(EmployeeScopeAssignment.class);
            verify(employeeScopeAssignmentRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue()).isSameAs(predecessor);
            verify(employeeScopeAssignmentRepository, times(1)).save(any(EmployeeScopeAssignment.class));
        }

        @Test
        @DisplayName("a predecessor starting on the new start is a 400 and nothing is written")
        void createAssignment_PredecessorStartsSameDay_IsRejected() {
            var predecessor = regionRow(START, null);
            employeeExists();
            regionExists();
            when(employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                            employeeId, testRegion.getId(), START))
                    .thenReturn(List.of(predecessor));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("previous assignment");

            assertThat(predecessor.getValidTo()).isNull();
            verify(employeeScopeAssignmentRepository, never()).save(any());
            verify(employeeScopeAssignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("opening for a different node closes nothing")
        void createAssignment_DifferentNode_ClosesNothing() {
            var otherRegion = CompanyRegion.builder()
                    .id(UUID.randomUUID())
                    .companyCountry(testCompanyCountry)
                    .regionCode("R3")
                    .regionName("Other Region")
                    .enabled(true)
                    .build();
            var otherPredecessor =
                    assignment(UUID.randomUUID(), null, otherRegion, null, PREDECESSOR_START, null, true);
            employeeExists();
            when(companyRegionRepository.findById(otherRegion.getId())).thenReturn(Optional.of(otherRegion));
            when(employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                            employeeId, otherRegion.getId(), START))
                    .thenReturn(List.of());
            saveReturnsArgument();

            service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, otherRegion.getId(), null, null, START));

            // The finder is scoped to the requested node, so the other node's live row is never touched.
            assertThat(otherPredecessor.getValidTo()).isNull();
            verify(employeeScopeAssignmentRepository)
                    .findEnabledAssignmentForNodeCoveringDate(employeeId, otherRegion.getId(), START);
            verify(employeeScopeAssignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a future validFrom is legal and derives nothing until it covers today")
        void createAssignment_FutureValidFrom_IsAccepted() {
            employeeExists();
            regionExists();
            noPredecessor(testRegion.getId());
            saveReturnsArgument();
            var future = LocalDate.now().plusDays(30);

            var result = service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, testRegion.getId(), null, null, future));

            assertThat(result.validFrom()).isEqualTo(future);
            assertThat(result.validTo()).isNull();
            assertThat(savedAssignment().getValidFrom()).isEqualTo(future);
        }
    }

    @Nested
    @DisplayName("create — the response")
    class CreateResponseTests {

        @Test
        @DisplayName("a region row reports level REGION, its name and its derived ancestor chain")
        void createAssignment_MapsTheRegionRow() {
            employeeExists();
            regionExists();
            noPredecessor(testRegion.getId());
            saveReturnsArgument();

            var result = service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START));

            assertThat(result.id()).isNotNull();
            assertThat(result.level()).isEqualTo(ScopeLevel.REGION);
            assertThat(result.companyCountryId()).isNull();
            assertThat(result.companyRegionId()).isEqualTo(testRegion.getId());
            assertThat(result.companyZoneId()).isNull();
            assertThat(result.nodeName()).isEqualTo("Region");
            assertThat(result.validFrom()).isEqualTo(START);
            assertThat(result.validTo()).isNull();
            assertThat(result.enabled()).isTrue();
            assertThat(result.derived().companyId()).isEqualTo(companyId);
            assertThat(result.derived().companyName()).isEqualTo("Test Company");
            assertThat(result.derived().companyCountryId()).isEqualTo(testCompanyCountry.getId());
            assertThat(result.derived().companyCountryName()).isEqualTo("México");
            assertThat(result.derived().companyRegionId()).isEqualTo(testRegion.getId());
            assertThat(result.derived().companyRegionName()).isEqualTo("Region");
            assertThat(result.derived().companyZoneId()).isNull();
            assertThat(result.derived().companyZoneName()).isNull();
        }

        @Test
        @DisplayName("a country row reports level COUNTRY and leaves the deeper chain null")
        void createAssignment_MapsTheCountryRow() {
            employeeExists();
            countryExists();
            noPredecessor(testCompanyCountry.getId());
            saveReturnsArgument();

            var result = service.createAssignment(
                    companyId,
                    employeeId,
                    new ScopeAssignmentRequest(testCompanyCountry.getId(), null, null, null, START));

            assertThat(result.level()).isEqualTo(ScopeLevel.COUNTRY);
            assertThat(result.companyCountryId()).isEqualTo(testCompanyCountry.getId());
            assertThat(result.nodeName()).isEqualTo("México");
            assertThat(result.derived().companyCountryId()).isEqualTo(testCompanyCountry.getId());
            assertThat(result.derived().companyRegionId()).isNull();
            assertThat(result.derived().companyZoneId()).isNull();
        }

        @Test
        @DisplayName("a zone row reports level ZONE and the whole chain")
        void createAssignment_MapsTheZoneRow() {
            employeeExists();
            zoneExists();
            noPredecessor(testZone.getId());
            saveReturnsArgument();

            var result = service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, null, testZone.getId(), null, START));

            assertThat(result.level()).isEqualTo(ScopeLevel.ZONE);
            assertThat(result.companyZoneId()).isEqualTo(testZone.getId());
            assertThat(result.nodeName()).isEqualTo("Zone");
            assertThat(result.derived().companyRegionId()).isEqualTo(testRegion.getId());
            assertThat(result.derived().companyZoneId()).isEqualTo(testZone.getId());
        }
    }

    @Nested
    @DisplayName("close — once, and only once")
    class CloseAssignmentTests {

        @Test
        @DisplayName("stores endDate + 1 as the exclusive bound and reports endDate back inclusively")
        void closeAssignment_StoresTheExclusiveBound() {
            employeeExists();
            var open = regionRow(PREDECESSOR_START, null);
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();
            var inclusiveEnd = LocalDate.of(2026, 6, 30);

            var result = service.closeAssignment(
                    companyId, employeeId, open.getId(), new CloseScopeAssignmentRequest(inclusiveEnd));

            assertThat(result.validTo()).isEqualTo(inclusiveEnd);
            assertThat(open.getValidTo()).isEqualTo(inclusiveEnd.plusDays(1));
        }

        @Test
        @DisplayName("an omitted body defaults the end date to today")
        void closeAssignment_OmittedBody_DefaultsToToday() {
            employeeExists();
            var open = regionRow(PREDECESSOR_START, null);
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();

            var result = service.closeAssignment(companyId, employeeId, open.getId(), null);

            var today = LocalDate.now();
            assertThat(result.validTo()).isEqualTo(today);
            assertThat(open.getValidTo()).isEqualTo(today.plusDays(1));
        }

        @Test
        @DisplayName("an end date before validFrom is a 400")
        void closeAssignment_EndBeforeStart_IsRejected() {
            employeeExists();
            var open = regionRow(START, null);
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));

            assertThatThrownBy(() -> service.closeAssignment(
                            companyId, employeeId, open.getId(), new CloseScopeAssignmentRequest(START.minusDays(1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a future assignment closed without a body is a 400 (today precedes validFrom)")
        void closeAssignment_FutureWithoutEndDate_IsRejected() {
            employeeExists();
            var future = regionRow(LocalDate.now().plusDays(10), null);
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, future.getId()))
                    .thenReturn(Optional.of(future));

            assertThatThrownBy(() -> service.closeAssignment(companyId, employeeId, future.getId(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
        }

        @Test
        @DisplayName("an already-closed assignment is a 409 and is not written again")
        void closeAssignment_AlreadyClosed_IsRejected() {
            employeeExists();
            var closed = regionRow(PREDECESSOR_START, LocalDate.of(2026, 3, 1));
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, closed.getId()))
                    .thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.closeAssignment(
                            companyId, employeeId, closed.getId(), new CloseScopeAssignmentRequest(START)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already closed");
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a missing assignment of the employee is a 404")
        void closeAssignment_Missing_IsRejected() {
            employeeExists();
            var id = UUID.randomUUID();
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, id))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.closeAssignment(companyId, employeeId, id, null))
                    .isInstanceOf(ScopeAssignmentNotFoundException.class);
            verify(employeeScopeAssignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the provisioning hook (T6)")
    class ProvisioningHookTests {

        @Test
        @DisplayName("creating a scope assignment asks the producer to reconcile the membership")
        void createAssignment_NotifiesTheProducer() {
            employeeExists();
            regionExists();
            noPredecessor(testRegion.getId());
            saveReturnsArgument();

            service.createAssignment(
                    companyId, employeeId, new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START));

            verify(accessProvisioningProducer).onMembershipChanged(companyId, testEmployee);
        }

        @Test
        @DisplayName("closing a scope assignment asks the producer to reconcile the membership")
        void closeAssignment_NotifiesTheProducer() {
            employeeExists();
            var open = regionRow(PREDECESSOR_START, null);
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();

            service.closeAssignment(companyId, employeeId, open.getId(), new CloseScopeAssignmentRequest(START));

            verify(accessProvisioningProducer).onMembershipChanged(companyId, testEmployee);
        }

        @Test
        @DisplayName("a refused write never notifies the producer, so no orphan intent is enqueued")
        void refusedCreate_DoesNotNotifyTheProducer() {
            employeeExists();
            regionExists();
            when(employeeScopeAssignmentRepository.findEnabledAssignmentForNodeCoveringDate(
                            employeeId, testRegion.getId(), START))
                    .thenReturn(List.of(regionRow(START, null)));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId,
                            employeeId,
                            new ScopeAssignmentRequest(null, testRegion.getId(), null, null, START)))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(accessProvisioningProducer, never()).onMembershipChanged(any(), any());
        }
    }

    @Nested
    @DisplayName("list")
    class ListAssignmentTests {

        @Test
        @DisplayName("maps the stored exclusive bound back to the inclusive API value")
        void getAssignments_MapsTheExclusiveBoundBackToInclusive() {
            employeeExists();
            var stored = assignment(UUID.randomUUID(), null, null, testZone, START, LocalDate.of(2026, 7, 1), true);
            when(employeeScopeAssignmentRepository.findEmployeeAssignments(employeeId, true))
                    .thenReturn(List.of(stored));

            var result = service.getAssignments(companyId, employeeId, true);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).validTo()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(result.get(0).level()).isEqualTo(ScopeLevel.ZONE);
        }

        @Test
        @DisplayName("passes includeDisabled through and maps disabled rows too")
        void getAssignments_PassesIncludeDisabledThrough() {
            employeeExists();
            when(employeeScopeAssignmentRepository.findEmployeeAssignments(employeeId, false))
                    .thenReturn(
                            List.of(assignment(UUID.randomUUID(), testCompanyCountry, null, null, START, null, false)));

            var result = service.getAssignments(companyId, employeeId, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).enabled()).isFalse();
            assertThat(result.get(0).level()).isEqualTo(ScopeLevel.COUNTRY);
            verify(employeeScopeAssignmentRepository).findEmployeeAssignments(employeeId, false);
        }

        @Test
        @DisplayName("an empty history is an empty list, not null")
        void getAssignments_NoRows_ReturnsEmptyList() {
            employeeExists();
            when(employeeScopeAssignmentRepository.findEmployeeAssignments(employeeId, true))
                    .thenReturn(List.of());

            List<ScopeAssignmentResponse> result = service.getAssignments(companyId, employeeId, true);

            assertThat(result).isNotNull().isEmpty();
        }
    }

    /** A whole fresh chain under a different company, so its nodes belong to someone else. */
    private CompanyCountry companyCountryOfAnotherCompany() {
        var foreignCompany = Company.builder()
                .id(UUID.randomUUID())
                .companyKey("2")
                .companyName("Other Company")
                .rfc("XAXX010101001")
                .enabled(true)
                .build();
        return CompanyCountry.builder()
                .id(UUID.randomUUID())
                .company(foreignCompany)
                .country(Country.builder()
                        .id(UUID.randomUUID())
                        .countryCode("MX")
                        .countryName("México")
                        .enabled(true)
                        .build())
                .build();
    }
}
