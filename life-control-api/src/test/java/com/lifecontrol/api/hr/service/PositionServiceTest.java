package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.PositionRequest;
import com.lifecontrol.api.hr.exception.DepartmentNotFoundException;
import com.lifecontrol.api.hr.exception.DuplicatePositionException;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
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
@DisplayName("PositionService Tests")
class PositionServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private PositionService positionService;

    private UUID companyId;
    private UUID departmentId;
    private UUID otherDepartmentId;
    private UUID positionId;
    private Company testCompany;
    private Department testDepartment;
    private Department otherDepartment;
    private Position testPosition;
    private PositionRequest testRequest;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        otherDepartmentId = UUID.randomUUID();
        positionId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        testDepartment = department(departmentId, "OPS", "Operations");
        otherDepartment = department(otherDepartmentId, "FIN", "Finance");

        testPosition = position(positionId, "OP1", "Operator", null);
        testRequest = new PositionRequest(departmentId, "OP1", "Operator", "Operator position", null, 1, true);
    }

    private Department department(UUID id, String code, String name) {
        return Department.builder()
                .id(id)
                .company(testCompany)
                .departmentCode(code)
                .departmentName(name)
                .enabled(true)
                .build();
    }

    private Position position(UUID id, String code, String name, Position reportsTo) {
        return Position.builder()
                .id(id)
                .department(testDepartment)
                .positionCode(code)
                .positionName(name)
                .reportsToPosition(reportsTo)
                .displayOrder(1)
                .enabled(true)
                .build();
    }

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void departmentExists() {
        when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                .thenReturn(Optional.of(testDepartment));
    }

    @Nested
    @DisplayName("company scope contract")
    class CompanyScopeContractTests {

        @Test
        @DisplayName("getAllPositions verifies company access before loading the company")
        void getAllPositions_VerifiesCompanyAccessFirst() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(
                            companyId))
                    .thenReturn(List.of());

            positionService.getAllPositions(companyId, null, false);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("getPositionById verifies company access before loading the company")
        void getPositionById_VerifiesCompanyAccessFirst() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));

            positionService.getPositionById(companyId, positionId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("createPosition verifies company access before loading the company")
        void createPosition_VerifiesCompanyAccessFirst() {
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(departmentId, "Operator"))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            positionService.createPosition(companyId, testRequest);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("updatePosition verifies company access before loading the company")
        void updatePosition_VerifiesCompanyAccessFirst() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            positionService.updatePosition(companyId, positionId, testRequest);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("deletePosition verifies company access before loading the company")
        void deletePosition_VerifiesCompanyAccessFirst() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            positionService.deletePosition(companyId, positionId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("setPositionEnabled verifies company access before loading the company")
        void setPositionEnabled_VerifiesCompanyAccessFirst() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            positionService.setPositionEnabled(companyId, positionId, true);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException when the company does not exist")
        void getAllPositions_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.getAllPositions(companyId, null, false))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }
    }

    @Nested
    @DisplayName("getAllPositions")
    class GetAllPositionsTests {

        @Test
        @DisplayName("should return every company position in repository order when includeDisabled is true")
        void getAllPositions_CompanyWideIncludeDisabled_ReturnsAllOrdered() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdOrderByDisplayOrderAscPositionCodeAsc(companyId))
                    .thenReturn(List.of(
                            position(UUID.randomUUID(), "OP1", "Operator", null),
                            position(UUID.randomUUID(), "FIN1", "Analyst", null)));

            var result = positionService.getAllPositions(companyId, null, true);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.positionCode()).containsExactly("OP1", "FIN1");
            verify(positionRepository).findByDepartmentCompanyIdOrderByDisplayOrderAscPositionCodeAsc(companyId);
            verify(positionRepository, never())
                    .findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(any());
        }

        @Test
        @DisplayName("should exclude disabled company positions when includeDisabled is false")
        void getAllPositions_CompanyWideExcludeDisabled_FiltersDisabled() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(
                            companyId))
                    .thenReturn(List.of(position(UUID.randomUUID(), "OP1", "Operator", null)));

            var result = positionService.getAllPositions(companyId, null, false);

            assertThat(result).hasSize(1);
            assertThat(result).allMatch(PositionResponse -> PositionResponse.enabled());
            verify(positionRepository)
                    .findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(companyId);
            verify(positionRepository, never()).findByDepartmentCompanyIdOrderByDisplayOrderAscPositionCodeAsc(any());
        }

        @Test
        @DisplayName("should filter by department when departmentId is provided")
        void getAllPositions_DepartmentFilter_QueriesByDepartment() {
            companyExists();
            departmentExists();
            when(positionRepository.findByDepartmentIdOrderByDisplayOrderAscPositionCodeAsc(departmentId))
                    .thenReturn(List.of(position(UUID.randomUUID(), "OP1", "Operator", null)));

            var result = positionService.getAllPositions(companyId, departmentId, true);

            assertThat(result).hasSize(1);
            verify(departmentRepository).findByCompanyIdAndId(companyId, departmentId);
            verify(positionRepository).findByDepartmentIdOrderByDisplayOrderAscPositionCodeAsc(departmentId);
        }

        @Test
        @DisplayName("should apply the enabled filter inside the department filter")
        void getAllPositions_DepartmentFilterExcludeDisabled_QueriesByDepartmentAndEnabled() {
            companyExists();
            departmentExists();
            when(positionRepository.findByDepartmentIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(departmentId))
                    .thenReturn(List.of(position(UUID.randomUUID(), "OP1", "Operator", null)));

            var result = positionService.getAllPositions(companyId, departmentId, false);

            assertThat(result).hasSize(1);
            verify(positionRepository)
                    .findByDepartmentIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(departmentId);
            verify(positionRepository, never()).findByDepartmentIdOrderByDisplayOrderAscPositionCodeAsc(any());
        }

        @Test
        @DisplayName("should throw DepartmentNotFoundException when the department belongs to another company")
        void getAllPositions_DepartmentOfAnotherCompany_ThrowsNotFound() {
            var foreignDepartmentId = UUID.randomUUID();
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, foreignDepartmentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.getAllPositions(companyId, foreignDepartmentId, true))
                    .isInstanceOf(DepartmentNotFoundException.class)
                    .hasMessageContaining("Department not found with id");
            verify(positionRepository, never()).findByDepartmentIdOrderByDisplayOrderAscPositionCodeAsc(any());
        }

        @Test
        @DisplayName("should return an empty list when the company has no positions")
        void getAllPositions_Empty_ReturnsEmpty() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndEnabledTrueOrderByDisplayOrderAscPositionCodeAsc(
                            companyId))
                    .thenReturn(List.of());

            var result = positionService.getAllPositions(companyId, null, false);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPositionById")
    class GetPositionByIdTests {

        @Test
        @DisplayName("should return the scoped position with its company and department ids")
        void getPositionById_Success() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));

            var result = positionService.getPositionById(companyId, positionId);

            assertThat(result.positionCode()).isEqualTo("OP1");
            assertThat(result.positionName()).isEqualTo("Operator");
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.departmentId()).isEqualTo(departmentId);
        }

        @Test
        @DisplayName("should expose the reporting position id when the position reports to another")
        void getPositionById_ReportsTo_ExposesParentId() {
            var parentId = UUID.randomUUID();
            var parent = position(parentId, "MGR", "Manager", null);
            var child = position(positionId, "OP1", "Operator", parent);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(child));

            var result = positionService.getPositionById(companyId, positionId);

            assertThat(result.reportsToPositionId()).isEqualTo(parentId);
        }

        @Test
        @DisplayName("should throw PositionNotFoundException when the id does not exist")
        void getPositionById_NotFound_ThrowsException() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.getPositionById(companyId, positionId))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
        }

        @Test
        @DisplayName("should throw PositionNotFoundException for a position of another company")
        void getPositionById_OtherCompany_IsNotFound() {
            var foreignId = UUID.randomUUID();
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, foreignId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.getPositionById(companyId, foreignId))
                    .isInstanceOf(PositionNotFoundException.class);
            verify(positionRepository).findByDepartmentCompanyIdAndId(companyId, foreignId);
            verify(positionRepository, never()).findById(foreignId);
        }
    }

    @Nested
    @DisplayName("createPosition")
    class CreatePositionTests {

        @Test
        @DisplayName("should create the position when code and name are free in the department")
        void createPosition_Success() {
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(departmentId, "Operator"))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> {
                Position p = inv.getArgument(0);
                p.setId(positionId);
                return p;
            });

            var result = positionService.createPosition(companyId, testRequest);

            assertThat(result.positionCode()).isEqualTo("OP1");
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.departmentId()).isEqualTo(departmentId);
            assertThat(result.enabled()).isTrue();
            assertThat(result.reportsToPositionId()).isNull();
            verify(positionRepository).save(any(Position.class));
        }

        @Test
        @DisplayName("should store the reporting parent when it belongs to the same company")
        void createPosition_WithSameCompanyParent_StoresParent() {
            var parentId = UUID.randomUUID();
            var parent = position(parentId, "MGR", "Manager", null);
            var request = new PositionRequest(departmentId, "OP1", "Operator", null, parentId, 1, true);
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(departmentId, "Operator"))
                    .thenReturn(false);
            when(positionRepository.findById(parentId)).thenReturn(Optional.of(parent));
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> {
                Position p = inv.getArgument(0);
                p.setId(positionId);
                return p;
            });

            var result = positionService.createPosition(companyId, request);

            assertThat(result.reportsToPositionId()).isEqualTo(parentId);
        }

        @Test
        @DisplayName("should throw DuplicatePositionException naming code when the code exists in the department")
        void createPosition_DuplicateCode_ThrowsException() {
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(true);

            assertThatThrownBy(() -> positionService.createPosition(companyId, testRequest))
                    .isInstanceOf(DuplicatePositionException.class)
                    .hasMessageContaining("Position with code 'OP1' already exists for this department");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicatePositionException naming name when the name exists in the department")
        void createPosition_DuplicateName_ThrowsException() {
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(departmentId, "Operator"))
                    .thenReturn(true);

            assertThatThrownBy(() -> positionService.createPosition(companyId, testRequest))
                    .isInstanceOf(DuplicatePositionException.class)
                    .hasMessageContaining("Position with name 'Operator' already exists for this department");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should accept the same code in a different department because uniqueness is per department")
        void createPosition_SameCodeDifferentDepartment_IsAccepted() {
            var request = new PositionRequest(otherDepartmentId, "OP1", "Operator", null, null, 1, true);
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, otherDepartmentId))
                    .thenReturn(Optional.of(otherDepartment));
            when(positionRepository.existsByDepartmentIdAndPositionCode(otherDepartmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(otherDepartmentId, "Operator"))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> {
                Position p = inv.getArgument(0);
                p.setId(UUID.randomUUID());
                return p;
            });

            var result = positionService.createPosition(companyId, request);

            assertThat(result.departmentId()).isEqualTo(otherDepartmentId);
            verify(positionRepository).existsByDepartmentIdAndPositionCode(otherDepartmentId, "OP1");
            verify(positionRepository).save(any(Position.class));
        }

        @Test
        @DisplayName("should throw PositionNotFoundException when the reporting parent does not exist")
        void createPosition_ReportsToMissing_ThrowsNotFound() {
            var missingId = UUID.randomUUID();
            var request = new PositionRequest(departmentId, "OP1", "Operator", null, missingId, 1, true);
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(departmentId, "Operator"))
                    .thenReturn(false);
            when(positionRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.createPosition(companyId, request))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject a reporting parent that belongs to another company")
        void createPosition_ReportsToAnotherCompany_ThrowsBadRequest() {
            var foreignCompanyId = UUID.randomUUID();
            var foreignCompany = Company.builder()
                    .id(foreignCompanyId)
                    .companyKey("2")
                    .companyName("Other Company")
                    .rfc("XEXX010101000")
                    .enabled(true)
                    .build();
            var foreignDepartment = Department.builder()
                    .id(UUID.randomUUID())
                    .company(foreignCompany)
                    .departmentCode("EXT")
                    .departmentName("External")
                    .enabled(true)
                    .build();
            var foreignParentId = UUID.randomUUID();
            var foreignParent = Position.builder()
                    .id(foreignParentId)
                    .department(foreignDepartment)
                    .positionCode("EXT1")
                    .positionName("External Position")
                    .enabled(true)
                    .build();
            var request = new PositionRequest(departmentId, "OP1", "Operator", null, foreignParentId, 1, true);
            companyExists();
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCode(departmentId, "OP1"))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionName(departmentId, "Operator"))
                    .thenReturn(false);
            when(positionRepository.findById(foreignParentId)).thenReturn(Optional.of(foreignParent));

            assertThatThrownBy(() -> positionService.createPosition(companyId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same company");
            verify(positionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updatePosition")
    class UpdatePositionTests {

        @Test
        @DisplayName("should update the position when the keys are free in the department")
        void updatePosition_Success() {
            var request = new PositionRequest(departmentId, "OP2", "Senior Operator", null, null, 2, true);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP2", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(
                            departmentId, "Senior Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionService.updatePosition(companyId, positionId, request);

            assertThat(result.positionCode()).isEqualTo("OP2");
            assertThat(result.positionName()).isEqualTo("Senior Operator");
            verify(positionRepository).save(any(Position.class));
        }

        @Test
        @DisplayName("should allow keeping the position's own code and name")
        void updatePosition_OwnValues_Allowed() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionService.updatePosition(companyId, positionId, testRequest);

            assertThat(result.positionCode()).isEqualTo("OP1");
            verify(positionRepository).save(any(Position.class));
        }

        @Test
        @DisplayName("should throw PositionNotFoundException when the id does not exist")
        void updatePosition_NotFound_ThrowsException() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, testRequest))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw PositionNotFoundException for a position of another company")
        void updatePosition_OtherCompany_IsNotFound() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, testRequest))
                    .isInstanceOf(PositionNotFoundException.class);
            verify(positionRepository).findByDepartmentCompanyIdAndId(companyId, positionId);
        }

        @Test
        @DisplayName("should throw DuplicatePositionException naming code when another row owns it")
        void updatePosition_DuplicateCode_ThrowsException() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(true);

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, testRequest))
                    .isInstanceOf(DuplicatePositionException.class)
                    .hasMessageContaining("Position with code 'OP1' already exists for this department");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicatePositionException naming name when another row owns it")
        void updatePosition_DuplicateName_ThrowsException() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(true);

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, testRequest))
                    .isInstanceOf(DuplicatePositionException.class)
                    .hasMessageContaining("Position with name 'Operator' already exists for this department");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should accept a move when the code is free in the destination department")
        void updatePosition_MoveToAnotherDepartment_RechecksKeysAgainstDestination() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            when(departmentRepository.findByCompanyIdAndId(companyId, otherDepartmentId))
                    .thenReturn(Optional.of(otherDepartment));
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(otherDepartmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(
                            otherDepartmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
            var request = new PositionRequest(otherDepartmentId, "OP1", "Operator", null, null, 1, true);

            var result = positionService.updatePosition(companyId, positionId, request);

            assertThat(result.departmentId()).isEqualTo(otherDepartmentId);
            verify(positionRepository)
                    .existsByDepartmentIdAndPositionCodeAndIdNot(otherDepartmentId, "OP1", positionId);
        }

        @Test
        @DisplayName("should clear the reporting parent when reportsToPositionId is null")
        void updatePosition_ClearReportsTo_SetsNull() {
            var parentId = UUID.randomUUID();
            var parent = position(parentId, "MGR", "Manager", null);
            testPosition.setReportsToPosition(parent);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionService.updatePosition(companyId, positionId, testRequest);

            assertThat(result.reportsToPositionId()).isNull();
        }

        @Test
        @DisplayName("should throw PositionNotFoundException when the reporting parent does not exist")
        void updatePosition_ReportsToMissing_ThrowsNotFound() {
            var missingId = UUID.randomUUID();
            var request = new PositionRequest(departmentId, "OP1", "Operator", null, missingId, 1, true);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.findById(missingId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, request))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject a reporting parent that belongs to another company")
        void updatePosition_ReportsToAnotherCompany_ThrowsBadRequest() {
            var foreignCompanyId = UUID.randomUUID();
            var foreignCompany = Company.builder()
                    .id(foreignCompanyId)
                    .companyKey("2")
                    .companyName("Other Company")
                    .rfc("XEXX010101000")
                    .enabled(true)
                    .build();
            var foreignDepartment = Department.builder()
                    .id(UUID.randomUUID())
                    .company(foreignCompany)
                    .departmentCode("EXT")
                    .departmentName("External")
                    .enabled(true)
                    .build();
            var foreignParentId = UUID.randomUUID();
            var foreignParent = Position.builder()
                    .id(foreignParentId)
                    .department(foreignDepartment)
                    .positionCode("EXT1")
                    .positionName("External Position")
                    .enabled(true)
                    .build();
            var request = new PositionRequest(departmentId, "OP1", "Operator", null, foreignParentId, 1, true);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.findById(foreignParentId)).thenReturn(Optional.of(foreignParent));

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same company");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject making a position report to itself")
        void updatePosition_SelfReference_ThrowsBadRequest() {
            var request = new PositionRequest(departmentId, "OP1", "Operator", null, positionId, 1, true);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "OP1", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Operator", positionId))
                    .thenReturn(false);
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(testPosition));

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot report to itself");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject a deeper cycle: three-level chain A -> B -> C, making C report to A")
        void updatePosition_DeepChain_RejectsThreeLevelCycle() {
            // A reports to B, B reports to C, C reports to nobody. Making C report to A would close
            // the loop A -> B -> C -> A, so the walk must travel more than the parent hop.
            var aId = UUID.randomUUID();
            var bId = UUID.randomUUID();
            var positionC = position(positionId, "C", "Chief", null);
            var positionB = position(bId, "B", "Lead", positionC);
            var positionA = position(aId, "A", "Analyst", positionB);
            var request = new PositionRequest(departmentId, "C", "Chief", null, aId, 1, true);

            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(positionC));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "C", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Chief", positionId))
                    .thenReturn(false);
            when(positionRepository.findById(aId)).thenReturn(Optional.of(positionA));

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot report to itself or to one of its descendants");
            verify(positionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject a two-level cycle: A -> B, making B report to A")
        void updatePosition_TwoLevelChain_RejectsDirectCycle() {
            // A reports to B, B reports to nobody. Making B report to A would close the loop
            // A -> B -> A, so the walk must take the direct parent hop before it can see B.
            var aId = UUID.randomUUID();
            var positionB = position(positionId, "B", "Lead", null);
            var positionA = position(aId, "A", "Analyst", positionB);
            var request = new PositionRequest(departmentId, "B", "Lead", null, aId, 1, true);

            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(positionB));
            departmentExists();
            when(positionRepository.existsByDepartmentIdAndPositionCodeAndIdNot(departmentId, "B", positionId))
                    .thenReturn(false);
            when(positionRepository.existsByDepartmentIdAndPositionNameAndIdNot(departmentId, "Lead", positionId))
                    .thenReturn(false);
            when(positionRepository.findById(aId)).thenReturn(Optional.of(positionA));

            assertThatThrownBy(() -> positionService.updatePosition(companyId, positionId, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot report to itself or to one of its descendants");
            verify(positionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deletePosition")
    class DeletePositionTests {

        @Test
        @DisplayName("should soft-delete by setting enabled to false without removing the row")
        void deletePosition_Success_SoftDeletes() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            positionService.deletePosition(companyId, positionId);

            var captor = ArgumentCaptor.forClass(Position.class);
            verify(positionRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isFalse();
            assertThat(testPosition.getEnabled()).isFalse();
            verify(positionRepository, never()).delete(any());
            verify(positionRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("should throw PositionNotFoundException when the id does not exist")
        void deletePosition_NotFound_ThrowsException() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.deletePosition(companyId, positionId))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verify(positionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setPositionEnabled")
    class SetPositionEnabledTests {

        @Test
        @DisplayName("should re-enable a disabled position")
        void setPositionEnabled_Enable_Success() {
            testPosition.setEnabled(false);
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionService.setPositionEnabled(companyId, positionId, true);

            assertThat(result.enabled()).isTrue();
            verify(positionRepository).save(any(Position.class));
        }

        @Test
        @DisplayName("should disable an enabled position")
        void setPositionEnabled_Disable_Success() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.of(testPosition));
            when(positionRepository.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = positionService.setPositionEnabled(companyId, positionId, false);

            assertThat(result.enabled()).isFalse();
            verify(positionRepository).save(any(Position.class));
        }

        @Test
        @DisplayName("should throw PositionNotFoundException when the id does not exist")
        void setPositionEnabled_NotFound_ThrowsException() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> positionService.setPositionEnabled(companyId, positionId, true))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verify(positionRepository, never()).save(any());
        }
    }
}
