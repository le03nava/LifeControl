package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.DepartmentRequest;
import com.lifecontrol.api.hr.exception.DepartmentNotFoundException;
import com.lifecontrol.api.hr.exception.DuplicateDepartmentException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
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
@DisplayName("DepartmentService Tests")
class DepartmentServiceTest {

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private DepartmentService departmentService;

    private UUID companyId;
    private UUID departmentId;
    private Company testCompany;
    private Department testDepartment;
    private DepartmentRequest testRequest;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        departmentId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();

        testDepartment = department(departmentId, "OPS", "Operations", 1, true);

        testRequest = new DepartmentRequest("OPS", "Operations", "Operations department", 1, true);
    }

    private Department department(UUID id, String code, String name, Integer order, boolean enabled) {
        return Department.builder()
                .id(id)
                .company(testCompany)
                .departmentCode(code)
                .departmentName(name)
                .displayOrder(order)
                .enabled(enabled)
                .build();
    }

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    @Nested
    @DisplayName("company scope contract")
    class CompanyScopeContractTests {

        @Test
        @DisplayName("getAllDepartments verifies company access before loading the company")
        void getAllDepartments_VerifiesCompanyAccessFirst() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(companyId))
                    .thenReturn(List.of());

            departmentService.getAllDepartments(companyId, false);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("getDepartmentById verifies company access before loading the company")
        void getDepartmentById_VerifiesCompanyAccessFirst() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));

            departmentService.getDepartmentById(companyId, departmentId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("createDepartment verifies company access before loading the company")
        void createDepartment_VerifiesCompanyAccessFirst() {
            companyExists();
            when(departmentRepository.existsByCompanyIdAndDepartmentCode(companyId, "OPS"))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentName(companyId, "Operations"))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            departmentService.createDepartment(companyId, testRequest);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("updateDepartment verifies company access before loading the company")
        void updateDepartment_VerifiesCompanyAccessFirst() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(companyId, "OPS", departmentId))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentNameAndIdNot(companyId, "Operations", departmentId))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            departmentService.updateDepartment(companyId, departmentId, testRequest);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("deleteDepartment verifies company access before loading the company")
        void deleteDepartment_VerifiesCompanyAccessFirst() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            departmentService.deleteDepartment(companyId, departmentId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("setDepartmentEnabled verifies company access before loading the company")
        void setDepartmentEnabled_VerifiesCompanyAccessFirst() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            departmentService.setDepartmentEnabled(companyId, departmentId, true);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException when the company does not exist")
        void getAllDepartments_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.getAllDepartments(companyId, false))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }
    }

    @Nested
    @DisplayName("getAllDepartments")
    class GetAllDepartmentsTests {

        @Test
        @DisplayName("should return every department in repository order when includeDisabled is true")
        void getAllDepartments_IncludeDisabled_ReturnsAllOrdered() {
            companyExists();
            when(departmentRepository.findByCompanyIdOrderByDisplayOrderAscDepartmentCodeAsc(companyId))
                    .thenReturn(List.of(
                            department(UUID.randomUUID(), "OPS", "Operations", 1, true),
                            department(UUID.randomUUID(), "FIN", "Finance", 2, true),
                            department(UUID.randomUUID(), "LEG", "Legal", 3, false)));

            var result = departmentService.getAllDepartments(companyId, true);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(r -> r.departmentCode()).containsExactly("OPS", "FIN", "LEG");
            verify(departmentRepository).findByCompanyIdOrderByDisplayOrderAscDepartmentCodeAsc(companyId);
            verify(departmentRepository, never())
                    .findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(any());
        }

        @Test
        @DisplayName("should exclude disabled departments when includeDisabled is false")
        void getAllDepartments_ExcludeDisabled_FiltersDisabled() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(companyId))
                    .thenReturn(List.of(
                            department(UUID.randomUUID(), "OPS", "Operations", 1, true),
                            department(UUID.randomUUID(), "FIN", "Finance", 2, true)));

            var result = departmentService.getAllDepartments(companyId, false);

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(DepartmentResponse -> DepartmentResponse.enabled());
            verify(departmentRepository)
                    .findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(companyId);
            verify(departmentRepository, never()).findByCompanyIdOrderByDisplayOrderAscDepartmentCodeAsc(any());
        }

        @Test
        @DisplayName("should return an empty list when the company has no departments")
        void getAllDepartments_Empty_ReturnsEmpty() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndEnabledTrueOrderByDisplayOrderAscDepartmentCodeAsc(companyId))
                    .thenReturn(List.of());

            var result = departmentService.getAllDepartments(companyId, false);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getDepartmentById")
    class GetDepartmentByIdTests {

        @Test
        @DisplayName("should return the scoped department with its company id")
        void getDepartmentById_Success() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));

            var result = departmentService.getDepartmentById(companyId, departmentId);

            assertThat(result.departmentCode()).isEqualTo("OPS");
            assertThat(result.departmentName()).isEqualTo("Operations");
            assertThat(result.companyId()).isEqualTo(companyId);
        }

        @Test
        @DisplayName("should throw DepartmentNotFoundException when the id does not exist")
        void getDepartmentById_NotFound_ThrowsException() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.getDepartmentById(companyId, departmentId))
                    .isInstanceOf(DepartmentNotFoundException.class)
                    .hasMessageContaining("Department not found with id");
        }

        @Test
        @DisplayName("should throw DepartmentNotFoundException for a department of another company")
        void getDepartmentById_OtherCompany_IsNotFound() {
            var foreignId = UUID.randomUUID();
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, foreignId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.getDepartmentById(companyId, foreignId))
                    .isInstanceOf(DepartmentNotFoundException.class);
            verify(departmentRepository).findByCompanyIdAndId(companyId, foreignId);
            verify(departmentRepository, never()).findById(foreignId);
        }
    }

    @Nested
    @DisplayName("createDepartment")
    class CreateDepartmentTests {

        @Test
        @DisplayName("should create the department when code and name are free in the company")
        void createDepartment_Success() {
            companyExists();
            when(departmentRepository.existsByCompanyIdAndDepartmentCode(companyId, "OPS"))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentName(companyId, "Operations"))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> {
                Department d = inv.getArgument(0);
                d.setId(departmentId);
                return d;
            });

            var result = departmentService.createDepartment(companyId, testRequest);

            assertThat(result.departmentCode()).isEqualTo("OPS");
            assertThat(result.departmentName()).isEqualTo("Operations");
            assertThat(result.companyId()).isEqualTo(companyId);
            assertThat(result.enabled()).isTrue();
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        @DisplayName("should throw DuplicateDepartmentException naming code when the code exists in the company")
        void createDepartment_DuplicateCode_ThrowsException() {
            companyExists();
            when(departmentRepository.existsByCompanyIdAndDepartmentCode(companyId, "OPS"))
                    .thenReturn(true);

            assertThatThrownBy(() -> departmentService.createDepartment(companyId, testRequest))
                    .isInstanceOf(DuplicateDepartmentException.class)
                    .hasMessageContaining("Department with code 'OPS' already exists for this company");
            verify(departmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateDepartmentException naming name when the name exists in the company")
        void createDepartment_DuplicateName_ThrowsException() {
            companyExists();
            when(departmentRepository.existsByCompanyIdAndDepartmentCode(companyId, "OPS"))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentName(companyId, "Operations"))
                    .thenReturn(true);

            assertThatThrownBy(() -> departmentService.createDepartment(companyId, testRequest))
                    .isInstanceOf(DuplicateDepartmentException.class)
                    .hasMessageContaining("Department with name 'Operations' already exists for this company");
            verify(departmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should accept the same code in a different company because uniqueness is per company")
        void createDepartment_SameCodeDifferentCompany_IsAccepted() {
            var otherCompanyId = UUID.randomUUID();
            var otherCompany = Company.builder()
                    .id(otherCompanyId)
                    .companyKey("2")
                    .companyName("Other Company")
                    .rfc("XEXX010101000")
                    .enabled(true)
                    .build();
            when(companyRepository.findById(otherCompanyId)).thenReturn(Optional.of(otherCompany));
            when(departmentRepository.existsByCompanyIdAndDepartmentCode(otherCompanyId, "OPS"))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentName(otherCompanyId, "Operations"))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> {
                Department d = inv.getArgument(0);
                d.setId(UUID.randomUUID());
                return d;
            });

            var result = departmentService.createDepartment(otherCompanyId, testRequest);

            assertThat(result.companyId()).isEqualTo(otherCompanyId);
            verify(departmentRepository).existsByCompanyIdAndDepartmentCode(otherCompanyId, "OPS");
            verify(departmentRepository).save(any(Department.class));
        }
    }

    @Nested
    @DisplayName("updateDepartment")
    class UpdateDepartmentTests {

        @Test
        @DisplayName("should update the department when the keys are free in the company")
        void updateDepartment_Success() {
            var request = new DepartmentRequest("FIN", "Finance", "Finance department", 2, true);
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(companyId, "FIN", departmentId))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentNameAndIdNot(companyId, "Finance", departmentId))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = departmentService.updateDepartment(companyId, departmentId, request);

            assertThat(result.departmentCode()).isEqualTo("FIN");
            assertThat(result.departmentName()).isEqualTo("Finance");
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        @DisplayName("should allow keeping the department's own code and name")
        void updateDepartment_OwnValues_Allowed() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(companyId, "OPS", departmentId))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentNameAndIdNot(companyId, "Operations", departmentId))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = departmentService.updateDepartment(companyId, departmentId, testRequest);

            assertThat(result.departmentCode()).isEqualTo("OPS");
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        @DisplayName("should throw DepartmentNotFoundException when the id does not exist")
        void updateDepartment_NotFound_ThrowsException() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.updateDepartment(companyId, departmentId, testRequest))
                    .isInstanceOf(DepartmentNotFoundException.class)
                    .hasMessageContaining("Department not found with id");
            verify(departmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateDepartmentException naming code when another row owns it")
        void updateDepartment_DuplicateCode_ThrowsException() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(companyId, "OPS", departmentId))
                    .thenReturn(true);

            assertThatThrownBy(() -> departmentService.updateDepartment(companyId, departmentId, testRequest))
                    .isInstanceOf(DuplicateDepartmentException.class)
                    .hasMessageContaining("Department with code 'OPS' already exists for this company");
            verify(departmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateDepartmentException naming name when another row owns it")
        void updateDepartment_DuplicateName_ThrowsException() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(companyId, "OPS", departmentId))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentNameAndIdNot(companyId, "Operations", departmentId))
                    .thenReturn(true);

            assertThatThrownBy(() -> departmentService.updateDepartment(companyId, departmentId, testRequest))
                    .isInstanceOf(DuplicateDepartmentException.class)
                    .hasMessageContaining("Department with name 'Operations' already exists for this company");
            verify(departmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("should accept a code that exists in another company")
        void updateDepartment_SameCodeDifferentCompany_IsAccepted() {
            var otherCompanyId = UUID.randomUUID();
            var otherCompany = Company.builder()
                    .id(otherCompanyId)
                    .companyKey("2")
                    .companyName("Other Company")
                    .rfc("XEXX010101000")
                    .enabled(true)
                    .build();
            when(companyRepository.findById(otherCompanyId)).thenReturn(Optional.of(otherCompany));
            when(departmentRepository.findByCompanyIdAndId(otherCompanyId, departmentId))
                    .thenReturn(Optional.of(Department.builder()
                            .id(departmentId)
                            .company(otherCompany)
                            .departmentCode("FIN")
                            .departmentName("Finance")
                            .enabled(true)
                            .build()));
            when(departmentRepository.existsByCompanyIdAndDepartmentCodeAndIdNot(otherCompanyId, "OPS", departmentId))
                    .thenReturn(false);
            when(departmentRepository.existsByCompanyIdAndDepartmentNameAndIdNot(
                            otherCompanyId, "Operations", departmentId))
                    .thenReturn(false);
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = departmentService.updateDepartment(otherCompanyId, departmentId, testRequest);

            assertThat(result.companyId()).isEqualTo(otherCompanyId);
            verify(departmentRepository)
                    .existsByCompanyIdAndDepartmentCodeAndIdNot(otherCompanyId, "OPS", departmentId);
            verify(departmentRepository).save(any(Department.class));
        }
    }

    @Nested
    @DisplayName("deleteDepartment")
    class DeleteDepartmentTests {

        @Test
        @DisplayName("should soft-delete by setting enabled to false without removing the row")
        void deleteDepartment_Success_SoftDeletes() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            departmentService.deleteDepartment(companyId, departmentId);

            var captor = ArgumentCaptor.forClass(Department.class);
            verify(departmentRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isFalse();
            assertThat(testDepartment.getEnabled()).isFalse();
            verify(departmentRepository, never()).delete(any());
            verify(departmentRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("should throw DepartmentNotFoundException when the id does not exist")
        void deleteDepartment_NotFound_ThrowsException() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.deleteDepartment(companyId, departmentId))
                    .isInstanceOf(DepartmentNotFoundException.class)
                    .hasMessageContaining("Department not found with id");
            verify(departmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setDepartmentEnabled")
    class SetDepartmentEnabledTests {

        @Test
        @DisplayName("should re-enable a disabled department")
        void setDepartmentEnabled_Enable_Success() {
            testDepartment.setEnabled(false);
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = departmentService.setDepartmentEnabled(companyId, departmentId, true);

            assertThat(result.enabled()).isTrue();
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        @DisplayName("should disable an enabled department")
        void setDepartmentEnabled_Disable_Success() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.of(testDepartment));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = departmentService.setDepartmentEnabled(companyId, departmentId, false);

            assertThat(result.enabled()).isFalse();
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        @DisplayName("should throw DepartmentNotFoundException when the id does not exist")
        void setDepartmentEnabled_NotFound_ThrowsException() {
            companyExists();
            when(departmentRepository.findByCompanyIdAndId(companyId, departmentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.setDepartmentEnabled(companyId, departmentId, true))
                    .isInstanceOf(DepartmentNotFoundException.class)
                    .hasMessageContaining("Department not found with id");
            verify(departmentRepository, never()).save(any());
        }
    }
}
