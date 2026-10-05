package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.exception.ConflictException;
import com.lifecontrol.api.hr.dto.CloseStoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.exception.EmployeeNotFoundException;
import com.lifecontrol.api.hr.exception.StoreAssignmentNotFoundException;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.EmployeeStoreAssignment;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.EmployeeStoreAssignmentRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.store.exception.CompanyStoreNotFoundException;
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
 * Unit spec of {@link EmployeeStoreAssignmentService}, the Mockito shape
 * {@code ContractServiceTest} establishes for the neighbouring HR service.
 *
 * <p>Every rule the record puts on the service is pinned here: the company-scope-first order, the
 * same-company store refusal (T8, a 404), the close-the-predecessor day before (T4) leaving no gap
 * under the exclusive column (D5), the close path's single conversion (D5), and the 409 of an
 * already-closed row. The database's partial exclusion constraint is deliberately not mocked: the
 * service does not pre-check it, and {@code StoreAssignmentCrudIntegrationTest} is where the real
 * PostgreSQL semantics are proven.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeStoreAssignmentService Tests")
class EmployeeStoreAssignmentServiceTest {

    @Mock
    private EmployeeStoreAssignmentRepository employeeStoreAssignmentRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanyStoreRepository companyStoreRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private EmployeeStoreAssignmentService service;

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate PREDECESSOR_START = LocalDate.of(2026, 1, 1);

    private UUID companyId;
    private UUID employeeId;
    private Company testCompany;
    private CompanyCountry testCompanyCountry;
    private CompanyRegion testRegion;
    private CompanyZone testZone;
    private CompanyStore testStore;
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
        testStore = CompanyStore.builder()
                .id(UUID.randomUUID())
                .companyZone(testZone)
                .storeName("Main Store")
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

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void employeeExists() {
        companyExists();
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(testEmployee));
    }

    private void storeExists() {
        when(companyStoreRepository.findById(testStore.getId())).thenReturn(Optional.of(testStore));
    }

    private void noPredecessor() {
        when(employeeStoreAssignmentRepository.findEnabledAssignmentForStoreCoveringDate(
                        eq(employeeId), eq(testStore.getId()), any(LocalDate.class)))
                .thenReturn(List.of());
    }

    private void saveReturnsArgument() {
        when(employeeStoreAssignmentRepository.save(any(EmployeeStoreAssignment.class)))
                .thenAnswer(inv -> {
                    EmployeeStoreAssignment a = inv.getArgument(0);
                    if (a.getId() == null) {
                        a.setId(UUID.randomUUID());
                    }
                    return a;
                });
    }

    private EmployeeStoreAssignment assignment(
            UUID id, CompanyStore store, LocalDate validFrom, LocalDate validTo, boolean enabled) {
        return EmployeeStoreAssignment.builder()
                .id(id)
                .employee(testEmployee)
                .companyStore(store)
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(enabled)
                .build();
    }

    /** The entity handed to {@code save}, as stored: the D5 tests assert the raw column bound. */
    private EmployeeStoreAssignment savedAssignment() {
        var captor = ArgumentCaptor.forClass(EmployeeStoreAssignment.class);
        verify(employeeStoreAssignmentRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("company scope")
    class CompanyScopeTests {

        @Test
        @DisplayName("getAssignments verifies company access before loading, then scopes the employee")
        void getAssignments_VerifiesCompanyAccessFirst() {
            employeeExists();
            when(employeeStoreAssignmentRepository.findEmployeeAssignments(employeeId, true))
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
            storeExists();
            noPredecessor();
            saveReturnsArgument();

            service.createAssignment(companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), START));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("closeAssignment verifies company access before loading the company")
        void closeAssignment_VerifiesCompanyAccessFirst() {
            employeeExists();
            var open = assignment(UUID.randomUUID(), testStore, PREDECESSOR_START, null, true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();

            service.closeAssignment(
                    companyId, employeeId, open.getId(), new CloseStoreAssignmentRequest(LocalDate.of(2026, 2, 1)));

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
            verify(employeeStoreAssignmentRepository, never()).findEmployeeAssignments(any(), anyBoolean());
        }

        @Test
        @DisplayName("a foreign employee is a 404 before the store is resolved")
        void createAssignment_ForeignEmployee_ThrowsException() {
            companyExists();
            when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createAssignment(
                            companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), START)))
                    .isInstanceOf(EmployeeNotFoundException.class);
            verify(companyStoreRepository, never()).findById(any());
            verify(employeeStoreAssignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the same-company store rule (T8)")
    class SameCompanyStoreTests {

        @Test
        @DisplayName("a store of another company is a 404 and writes nothing")
        void createAssignment_ForeignStore_IsRejected() {
            employeeExists();
            var foreignCompany = Company.builder()
                    .id(UUID.randomUUID())
                    .companyKey("2")
                    .companyName("Other Company")
                    .rfc("XAXX010101001")
                    .enabled(true)
                    .build();
            var foreignStore = storeUnder(foreignCompany);
            when(companyStoreRepository.findById(foreignStore.getId())).thenReturn(Optional.of(foreignStore));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId, employeeId, new StoreAssignmentRequest(foreignStore.getId(), START)))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
            verify(employeeStoreAssignmentRepository, never()).save(any());
            verify(employeeStoreAssignmentRepository, never())
                    .findEnabledAssignmentForStoreCoveringDate(any(), any(), any());
        }

        @Test
        @DisplayName("an unknown store is a 404")
        void createAssignment_UnknownStore_IsRejected() {
            employeeExists();
            var unknownStoreId = UUID.randomUUID();
            when(companyStoreRepository.findById(unknownStoreId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createAssignment(
                            companyId, employeeId, new StoreAssignmentRequest(unknownStoreId, START)))
                    .isInstanceOf(CompanyStoreNotFoundException.class);
            verify(employeeStoreAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a disabled store is not refused on write (T12: the write mirrors resolveStore)")
        void createAssignment_DisabledStore_IsAccepted() {
            employeeExists();
            var disabledStore = CompanyStore.builder()
                    .id(UUID.randomUUID())
                    .companyZone(testZone)
                    .storeName("Disabled Store")
                    .enabled(false)
                    .build();
            when(companyStoreRepository.findById(disabledStore.getId())).thenReturn(Optional.of(disabledStore));
            when(employeeStoreAssignmentRepository.findEnabledAssignmentForStoreCoveringDate(
                            eq(employeeId), eq(disabledStore.getId()), any(LocalDate.class)))
                    .thenReturn(List.of());
            saveReturnsArgument();

            var result = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(disabledStore.getId(), START));

            assertThat(result.companyStoreId()).isEqualTo(disabledStore.getId());
            verify(employeeStoreAssignmentRepository).save(any(EmployeeStoreAssignment.class));
        }
    }

    @Nested
    @DisplayName("create — the close-the-predecessor rule (T4/D5)")
    class CloseThePredecessorTests {

        @Test
        @DisplayName("no predecessor writes the new row only")
        void createAssignment_NoPredecessor_InsertsTheNewRowOnly() {
            employeeExists();
            storeExists();
            noPredecessor();
            saveReturnsArgument();

            service.createAssignment(companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), START));

            verify(employeeStoreAssignmentRepository)
                    .findEnabledAssignmentForStoreCoveringDate(employeeId, testStore.getId(), START);
            verify(employeeStoreAssignmentRepository, times(1)).save(any(EmployeeStoreAssignment.class));
            verify(employeeStoreAssignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a predecessor covering the new start stores the successor's validFrom verbatim, flushed first")
        void createAssignment_PredecessorCoveringStart_IsClosedTheDayBefore() {
            var predecessor = assignment(UUID.randomUUID(), testStore, PREDECESSOR_START, null, true);
            employeeExists();
            storeExists();
            when(employeeStoreAssignmentRepository.findEnabledAssignmentForStoreCoveringDate(
                            employeeId, testStore.getId(), START))
                    .thenReturn(List.of(predecessor));
            saveReturnsArgument();

            service.createAssignment(companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), START));

            // The column is exclusive (D5), so storing the successor's validFrom as the predecessor's
            // bound makes the predecessor's last covered day START - 1: no gap and no overlap.
            assertThat(predecessor.getValidTo()).isEqualTo(START);
            var captor = ArgumentCaptor.forClass(EmployeeStoreAssignment.class);
            verify(employeeStoreAssignmentRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue()).isSameAs(predecessor);
            verify(employeeStoreAssignmentRepository, times(1)).save(any(EmployeeStoreAssignment.class));
        }

        @Test
        @DisplayName("opening for a different store closes nothing (D1)")
        void createAssignment_DifferentStore_ClosesNothing() {
            employeeExists();
            var otherStore = storeUnder(testCompany);
            var otherStorePredecessor = assignment(UUID.randomUUID(), otherStore, PREDECESSOR_START, null, true);
            when(companyStoreRepository.findById(otherStore.getId())).thenReturn(Optional.of(otherStore));
            when(employeeStoreAssignmentRepository.findEnabledAssignmentForStoreCoveringDate(
                            employeeId, otherStore.getId(), START))
                    .thenReturn(List.of());
            saveReturnsArgument();

            service.createAssignment(companyId, employeeId, new StoreAssignmentRequest(otherStore.getId(), START));

            // The finder is scoped to the requested store, so the other store's live row is never
            // touched and nothing is flushed as a close.
            assertThat(otherStorePredecessor.getValidTo()).isNull();
            verify(employeeStoreAssignmentRepository)
                    .findEnabledAssignmentForStoreCoveringDate(employeeId, otherStore.getId(), START);
            verify(employeeStoreAssignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a predecessor starting on the new start is a 400 and nothing is written")
        void createAssignment_PredecessorStartsSameDay_IsRejected() {
            var predecessor = assignment(UUID.randomUUID(), testStore, START, null, true);
            employeeExists();
            storeExists();
            when(employeeStoreAssignmentRepository.findEnabledAssignmentForStoreCoveringDate(
                            employeeId, testStore.getId(), START))
                    .thenReturn(List.of(predecessor));

            assertThatThrownBy(() -> service.createAssignment(
                            companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), START)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("previous assignment");

            assertThat(predecessor.getValidTo()).isNull();
            verify(employeeStoreAssignmentRepository, never()).save(any());
            verify(employeeStoreAssignmentRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a future validFrom is legal and derives nothing until it covers today")
        void createAssignment_FutureValidFrom_IsAccepted() {
            employeeExists();
            storeExists();
            noPredecessor();
            saveReturnsArgument();
            var future = LocalDate.now().plusDays(30);

            var result = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), future));

            assertThat(result.validFrom()).isEqualTo(future);
            assertThat(result.validTo()).isNull();
            assertThat(savedAssignment().getValidFrom()).isEqualTo(future);
        }
    }

    @Nested
    @DisplayName("the self-scoped current-assignments read (T25/D10)")
    class CurrentAssignmentsTests {

        @Test
        @DisplayName("delegates to the covering-date finder and fills T14's derived chain, with no scope check")
        void getCurrentAssignmentsForEmployee_MapsTheCoveringRows() {
            var id = UUID.randomUUID();
            var row = assignment(id, testStore, LocalDate.now().minusDays(5), null, true);
            when(employeeStoreAssignmentRepository.findEnabledAssignmentsCoveringDate(
                            eq(employeeId), any(LocalDate.class)))
                    .thenReturn(List.of(row));

            var result = service.getCurrentAssignmentsForEmployee(employeeId);

            assertThat(result).hasSize(1);
            var response = result.get(0);
            assertThat(response.id()).isEqualTo(id);
            assertThat(response.companyStoreId()).isEqualTo(testStore.getId());
            assertThat(response.companyStoreName()).isEqualTo("Main Store");
            assertThat(response.validFrom()).isEqualTo(row.getValidFrom());
            assertThat(response.validTo()).isNull();
            assertThat(response.enabled()).isTrue();
            assertThat(response.derived().companyId()).isEqualTo(companyId);
            assertThat(response.derived().companyName()).isEqualTo("Test Company");
            assertThat(response.derived().companyCountryId()).isEqualTo(testCompanyCountry.getId());
            assertThat(response.derived().companyCountryName()).isEqualTo("México");
            assertThat(response.derived().companyRegionId()).isEqualTo(testRegion.getId());
            assertThat(response.derived().companyZoneId()).isEqualTo(testZone.getId());

            // The read resolves "today" through the same covering predicate the derivation uses
            // (T27); it never checks the caller's company scope, because the only caller resolves the
            // employee from the authenticated principal's own keycloak id.
            var dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(employeeStoreAssignmentRepository)
                    .findEnabledAssignmentsCoveringDate(eq(employeeId), dateCaptor.capture());
            assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
            verify(currentUserContext, never()).verifyCompanyAccess(any());
        }

        @Test
        @DisplayName("a covering row whose store is disabled is still in the set (T27, no store-enabled filter)")
        void getCurrentAssignmentsForEmployee_DisabledStoreRowIsStillReturned() {
            var disabledStore = CompanyStore.builder()
                    .id(UUID.randomUUID())
                    .companyZone(testZone)
                    .storeName("Disabled Store")
                    .enabled(false)
                    .build();
            var row =
                    assignment(UUID.randomUUID(), disabledStore, LocalDate.now().minusDays(1), null, true);
            when(employeeStoreAssignmentRepository.findEnabledAssignmentsCoveringDate(
                            eq(employeeId), any(LocalDate.class)))
                    .thenReturn(List.of(row));

            var result = service.getCurrentAssignmentsForEmployee(employeeId);

            // T27: the assigned set is the covering assignment row, not the store's enabled flag; T12
            // leaves whether a live assignment to a disabled store grants scope to the derivation.
            assertThat(result).hasSize(1);
            assertThat(result.get(0).companyStoreId()).isEqualTo(disabledStore.getId());
        }

        @Test
        @DisplayName("an employee with no covering rows reads an empty list, not null")
        void getCurrentAssignmentsForEmployee_NoRows_ReturnsEmptyList() {
            when(employeeStoreAssignmentRepository.findEnabledAssignmentsCoveringDate(
                            eq(employeeId), any(LocalDate.class)))
                    .thenReturn(List.of());

            var result = service.getCurrentAssignmentsForEmployee(employeeId);

            assertThat(result).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("create — the response")
    class CreateResponseTests {

        @Test
        @DisplayName("maps the store, the open-ended range and the derived display chain")
        void createAssignment_MapsTheDerivedChain() {
            employeeExists();
            storeExists();
            noPredecessor();
            saveReturnsArgument();

            var result = service.createAssignment(
                    companyId, employeeId, new StoreAssignmentRequest(testStore.getId(), START));

            assertThat(result.companyStoreId()).isEqualTo(testStore.getId());
            assertThat(result.companyStoreName()).isEqualTo("Main Store");
            assertThat(result.validFrom()).isEqualTo(START);
            assertThat(result.validTo()).isNull();
            assertThat(result.enabled()).isTrue();
            assertThat(result.id()).isNotNull();
            assertThat(result.derived().companyId()).isEqualTo(companyId);
            assertThat(result.derived().companyName()).isEqualTo("Test Company");
            assertThat(result.derived().companyCountryId()).isEqualTo(testCompanyCountry.getId());
            assertThat(result.derived().companyCountryName()).isEqualTo("México");
            assertThat(result.derived().companyRegionId()).isEqualTo(testRegion.getId());
            assertThat(result.derived().companyRegionName()).isEqualTo("Region");
            assertThat(result.derived().companyZoneId()).isEqualTo(testZone.getId());
            assertThat(result.derived().companyZoneName()).isEqualTo("Zone");
        }
    }

    @Nested
    @DisplayName("close (T5/D5)")
    class CloseAssignmentTests {

        @Test
        @DisplayName("stores endDate + 1 as the exclusive bound and reports endDate back inclusively")
        void closeAssignment_StoresTheExclusiveBound() {
            employeeExists();
            var open = assignment(UUID.randomUUID(), testStore, PREDECESSOR_START, null, true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();
            var inclusiveEnd = LocalDate.of(2026, 6, 30);

            var result = service.closeAssignment(
                    companyId, employeeId, open.getId(), new CloseStoreAssignmentRequest(inclusiveEnd));

            assertThat(result.validTo()).isEqualTo(inclusiveEnd);
            assertThat(open.getValidTo()).isEqualTo(inclusiveEnd.plusDays(1));
        }

        @Test
        @DisplayName("an omitted body defaults the end date to today")
        void closeAssignment_OmittedBody_DefaultsToToday() {
            employeeExists();
            var open = assignment(UUID.randomUUID(), testStore, PREDECESSOR_START, null, true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();

            var result = service.closeAssignment(companyId, employeeId, open.getId(), null);

            var today = LocalDate.now();
            assertThat(result.validTo()).isEqualTo(today);
            assertThat(open.getValidTo()).isEqualTo(today.plusDays(1));
        }

        @Test
        @DisplayName("an explicit null end date also defaults to today")
        void closeAssignment_ExplicitNullEndDate_DefaultsToToday() {
            employeeExists();
            var open = assignment(UUID.randomUUID(), testStore, PREDECESSOR_START, null, true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));
            saveReturnsArgument();

            var result =
                    service.closeAssignment(companyId, employeeId, open.getId(), new CloseStoreAssignmentRequest(null));

            var today = LocalDate.now();
            assertThat(result.validTo()).isEqualTo(today);
            assertThat(open.getValidTo()).isEqualTo(today.plusDays(1));
        }

        @Test
        @DisplayName("an end date before validFrom is a 400")
        void closeAssignment_EndBeforeStart_IsRejected() {
            employeeExists();
            var open = assignment(UUID.randomUUID(), testStore, START, null, true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, open.getId()))
                    .thenReturn(Optional.of(open));

            assertThatThrownBy(() -> service.closeAssignment(
                            companyId, employeeId, open.getId(), new CloseStoreAssignmentRequest(START.minusDays(1))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
            verify(employeeStoreAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a future assignment closed without a body is a 400 (today precedes validFrom)")
        void closeAssignment_FutureWithoutEndDate_IsRejected() {
            employeeExists();
            var future =
                    assignment(UUID.randomUUID(), testStore, LocalDate.now().plusDays(10), null, true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, future.getId()))
                    .thenReturn(Optional.of(future));

            assertThatThrownBy(() -> service.closeAssignment(companyId, employeeId, future.getId(), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endDate");
        }

        @Test
        @DisplayName("an already-closed assignment is a 409 and is not written again")
        void closeAssignment_AlreadyClosed_IsRejected() {
            employeeExists();
            var closed = assignment(UUID.randomUUID(), testStore, PREDECESSOR_START, LocalDate.of(2026, 3, 1), true);
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, closed.getId()))
                    .thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.closeAssignment(
                            companyId, employeeId, closed.getId(), new CloseStoreAssignmentRequest(START)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already closed");
            verify(employeeStoreAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a missing assignment of the employee is a 404")
        void closeAssignment_Missing_IsRejected() {
            employeeExists();
            var id = UUID.randomUUID();
            when(employeeStoreAssignmentRepository.findByEmployeeIdAndId(employeeId, id))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.closeAssignment(companyId, employeeId, id, null))
                    .isInstanceOf(StoreAssignmentNotFoundException.class);
            verify(employeeStoreAssignmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("list")
    class ListAssignmentTests {

        @Test
        @DisplayName("maps the stored exclusive bound back to the inclusive API value (D5)")
        void getAssignments_MapsTheExclusiveBoundBackToInclusive() {
            employeeExists();
            var stored = assignment(UUID.randomUUID(), testStore, START, LocalDate.of(2026, 7, 1), true);
            when(employeeStoreAssignmentRepository.findEmployeeAssignments(employeeId, true))
                    .thenReturn(List.of(stored));

            var result = service.getAssignments(companyId, employeeId, true);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).validTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        @DisplayName("passes includeDisabled through and maps disabled rows too")
        void getAssignments_PassesIncludeDisabledThrough() {
            employeeExists();
            when(employeeStoreAssignmentRepository.findEmployeeAssignments(employeeId, false))
                    .thenReturn(List.of(assignment(UUID.randomUUID(), testStore, START, null, false)));

            var result = service.getAssignments(companyId, employeeId, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).enabled()).isFalse();
            verify(employeeStoreAssignmentRepository).findEmployeeAssignments(employeeId, false);
        }
    }

    /** A fresh whole store chain under the given company, so the store belongs to it (T8). */
    private CompanyStore storeUnder(Company company) {
        var country = CompanyCountry.builder()
                .id(UUID.randomUUID())
                .company(company)
                .country(Country.builder()
                        .id(UUID.randomUUID())
                        .countryCode("MX")
                        .countryName("México")
                        .enabled(true)
                        .build())
                .build();
        var region = CompanyRegion.builder()
                .id(UUID.randomUUID())
                .companyCountry(country)
                .regionCode("R")
                .regionName("Region")
                .enabled(true)
                .build();
        var zone = CompanyZone.builder()
                .id(UUID.randomUUID())
                .companyRegion(region)
                .zoneCode("Z")
                .zoneName("Zone")
                .enabled(true)
                .build();
        return CompanyStore.builder()
                .id(UUID.randomUUID())
                .companyZone(zone)
                .storeName("Other Store")
                .enabled(true)
                .build();
    }
}
