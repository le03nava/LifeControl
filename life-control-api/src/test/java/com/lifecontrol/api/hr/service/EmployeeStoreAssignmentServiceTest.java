package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.security.ScopeLevel;
import com.lifecontrol.api.hr.dto.CloseStoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentRequest;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse;
import com.lifecontrol.api.hr.dto.ScopeAssignmentResponse.DerivedScopeChain;
import com.lifecontrol.api.hr.dto.StoreAssignmentRequest;
import com.lifecontrol.api.hr.dto.StoreAssignmentResponse;
import com.lifecontrol.api.hr.exception.StoreAssignmentNotFoundException;
import com.lifecontrol.api.hr.model.EmployeeScopeAssignment;
import com.lifecontrol.api.hr.repository.EmployeeScopeAssignmentRepository;
import com.lifecontrol.api.store.model.CompanyStore;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The unit spec of {@link EmployeeStoreAssignmentService} <b>as a compatibility façade</b> (S8).
 *
 * <p>Since {@code V32__employee_scope_assignments_store_level.sql} the store is the fourth node column
 * of {@code employee_scope_assignments} and only that table feeds the five {@code company_*} claims, so
 * the pre-merge {@code /store-assignments} surface no longer owns a write: it delegates to
 * {@link EmployeeScopeAssignmentService} and only maps the answer back to the payload its callers
 * already consume. Everything the old suite pinned about create, close, the close-the-predecessor rule,
 * the overlap and the provisioning hook now lives where the code lives —
 * {@code EmployeeScopeAssignmentServiceTest} for the shared path and
 * {@code EmployeeStoreAssignmentHttpIntegrationTest} for the wire — so what is pinned here is exactly
 * what the façade still owns:</p>
 *
 * <ul>
 *   <li>a {@code STORE} scope row maps to a {@link StoreAssignmentResponse} with the same eight
 *       derived components, and a row of another level is <b>filtered out</b> rather than reported as a
 *       store;</li>
 *   <li>closing a non-store row <b>through the store route</b> is the route's own 404
 *       ({@link StoreAssignmentNotFoundException}), and the scope service is never asked to close it —
 *       a route that could close a country row by its id would let a store screen mutate a level it
 *       does not render.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeStoreAssignmentService (compatibility façade) Tests")
class EmployeeStoreAssignmentServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate INCLUSIVE_END = LocalDate.of(2026, 6, 30);

    @Mock
    private EmployeeScopeAssignmentService employeeScopeAssignmentService;

    @Mock
    private EmployeeScopeAssignmentRepository employeeScopeAssignmentRepository;

    @InjectMocks
    private EmployeeStoreAssignmentService service;

    private UUID companyId;
    private UUID employeeId;
    private UUID companyCountryId;
    private UUID regionId;
    private UUID zoneId;
    private UUID storeId;
    private UUID assignmentId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        employeeId = UUID.randomUUID();
        companyCountryId = UUID.randomUUID();
        regionId = UUID.randomUUID();
        zoneId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
    }

    private DerivedScopeChain chain() {
        return new DerivedScopeChain(
                companyId, "Acme", companyCountryId, "México", regionId, "Region", zoneId, "Zone");
    }

    private ScopeAssignmentResponse storeRow() {
        return new ScopeAssignmentResponse(
                assignmentId,
                ScopeLevel.STORE,
                null,
                null,
                null,
                storeId,
                "Store",
                START,
                INCLUSIVE_END,
                true,
                chain());
    }

    private ScopeAssignmentResponse countryRow() {
        return new ScopeAssignmentResponse(
                UUID.randomUUID(),
                ScopeLevel.COUNTRY,
                companyCountryId,
                null,
                null,
                null,
                "México",
                START,
                null,
                true,
                chain());
    }

    @Nested
    @DisplayName("read — the store payload")
    class ReadTests {

        @Test
        @DisplayName("maps a STORE row to StoreAssignmentResponse with all eight derived components")
        void mapsAStoreRowToTheStorePayload() {
            when(employeeScopeAssignmentService.getAssignments(companyId, employeeId, true))
                    .thenReturn(List.of(storeRow()));

            var rows = service.getAssignments(companyId, employeeId, true);

            assertThat(rows).hasSize(1);
            var row = rows.get(0);
            assertThat(row.id()).isEqualTo(assignmentId);
            assertThat(row.companyStoreId()).isEqualTo(storeId);
            assertThat(row.companyStoreName()).isEqualTo("Store");
            assertThat(row.validFrom()).isEqualTo(START);
            assertThat(row.validTo()).isEqualTo(INCLUSIVE_END);
            assertThat(row.enabled()).isTrue();
            assertThat(row.derived().companyId()).isEqualTo(companyId);
            assertThat(row.derived().companyName()).isEqualTo("Acme");
            assertThat(row.derived().companyCountryId()).isEqualTo(companyCountryId);
            assertThat(row.derived().companyCountryName()).isEqualTo("México");
            assertThat(row.derived().companyRegionId()).isEqualTo(regionId);
            assertThat(row.derived().companyRegionName()).isEqualTo("Region");
            assertThat(row.derived().companyZoneId()).isEqualTo(zoneId);
            assertThat(row.derived().companyZoneName()).isEqualTo("Zone");
        }

        @Test
        @DisplayName("a non-store row is filtered out of the store route's list")
        void filtersOutEveryNonStoreLevel() {
            when(employeeScopeAssignmentService.getAssignments(companyId, employeeId, false))
                    .thenReturn(List.of(countryRow(), storeRow()));

            var rows = service.getAssignments(companyId, employeeId, false);

            assertThat(rows).extracting(StoreAssignmentResponse::companyStoreId).containsExactly(storeId);
            verify(employeeScopeAssignmentService).getAssignments(companyId, employeeId, false);
        }

        @Test
        @DisplayName("the scope-free profile read maps the STORE rows and drops the other levels")
        void mapsTheCurrentStoreAssignments() {
            when(employeeScopeAssignmentService.getCurrentStoreAssignments(employeeId))
                    .thenReturn(List.of(storeRow(), countryRow()));

            var rows = service.getCurrentAssignmentsForEmployee(employeeId);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).companyStoreId()).isEqualTo(storeId);
        }
    }

    @Nested
    @DisplayName("create — delegation")
    class CreateTests {

        @Test
        @DisplayName("delegates a request that names only the store and maps the answer back")
        void delegatesTheStoreAsTheOnlyNode() {
            when(employeeScopeAssignmentService.createAssignment(eq(companyId), eq(employeeId), any()))
                    .thenReturn(storeRow());

            var result = service.createAssignment(companyId, employeeId, new StoreAssignmentRequest(storeId, START));

            var captor = ArgumentCaptor.forClass(ScopeAssignmentRequest.class);
            verify(employeeScopeAssignmentService).createAssignment(eq(companyId), eq(employeeId), captor.capture());
            var delegated = captor.getValue();
            assertThat(delegated.companyStoreId()).isEqualTo(storeId);
            assertThat(delegated.validFrom()).isEqualTo(START);
            assertThat(delegated.companyCountryId()).isNull();
            assertThat(delegated.companyRegionId()).isNull();
            assertThat(delegated.companyZoneId()).isNull();

            assertThat(result.companyStoreId()).isEqualTo(storeId);
            assertThat(result.companyStoreName()).isEqualTo("Store");
        }
    }

    @Nested
    @DisplayName("close — a store route closes store rows only")
    class CloseTests {

        @Test
        @DisplayName("a country row is the store route's own 404 and is never handed to the scope service")
        void closingANonStoreRowIsANotFound() {
            var countryAssignment = EmployeeScopeAssignment.builder()
                    .id(assignmentId)
                    .validFrom(START)
                    .enabled(true)
                    .build();
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, assignmentId))
                    .thenReturn(Optional.of(countryAssignment));

            assertThatThrownBy(() -> service.closeAssignment(
                            companyId, employeeId, assignmentId, new CloseStoreAssignmentRequest(INCLUSIVE_END)))
                    .isInstanceOf(StoreAssignmentNotFoundException.class);

            verify(employeeScopeAssignmentService, never()).closeAssignment(any(), any(), any(), any());
            assertThat(countryAssignment.getValidTo()).as("the refused close writes nothing").isNull();
        }

        @Test
        @DisplayName("an unknown id is a 404 and nothing is delegated")
        void closingAnUnknownIdIsANotFound() {
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, assignmentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.closeAssignment(companyId, employeeId, assignmentId, null))
                    .isInstanceOf(StoreAssignmentNotFoundException.class);

            verifyNoInteractions(employeeScopeAssignmentService);
        }

        @Test
        @DisplayName("a store row is delegated to the scope service and mapped back")
        void closingAStoreRowDelegatesAndMaps() {
            var zone = com.lifecontrol.api.company.model.CompanyZone.builder()
                    .id(zoneId)
                    .zoneName("Zone")
                    .enabled(true)
                    .build();
            var storeRow = EmployeeScopeAssignment.builder()
                    .id(assignmentId)
                    .companyStore(CompanyStore.builder()
                            .id(storeId)
                            .companyZone(zone)
                            .storeName("Store")
                            .enabled(true)
                            .build())
                    .validFrom(START)
                    .enabled(true)
                    .build();
            when(employeeScopeAssignmentRepository.findByEmployeeIdAndId(employeeId, assignmentId))
                    .thenReturn(Optional.of(storeRow));
            when(employeeScopeAssignmentService.closeAssignment(eq(companyId), eq(employeeId), eq(assignmentId), any()))
                    .thenReturn(storeRow());

            var result = service.closeAssignment(
                    companyId, employeeId, assignmentId, new CloseStoreAssignmentRequest(INCLUSIVE_END));

            var captor = ArgumentCaptor.forClass(com.lifecontrol.api.hr.dto.CloseScopeAssignmentRequest.class);
            verify(employeeScopeAssignmentService)
                    .closeAssignment(eq(companyId), eq(employeeId), eq(assignmentId), captor.capture());
            assertThat(captor.getValue().endDate()).isEqualTo(INCLUSIVE_END);
            assertThat(result.id()).isEqualTo(assignmentId);
            assertThat(result.companyStoreId()).isEqualTo(storeId);
        }
    }
}
