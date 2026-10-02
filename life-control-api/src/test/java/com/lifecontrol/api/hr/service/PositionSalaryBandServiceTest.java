package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.PositionSalaryBandRequest;
import com.lifecontrol.api.hr.dto.PositionSalaryBandsRequest;
import com.lifecontrol.api.hr.exception.PositionNotFoundException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.PositionSalaryBand;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionSalaryBandRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import java.math.BigDecimal;
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
@DisplayName("PositionSalaryBandService Tests")
class PositionSalaryBandServiceTest {

    @Mock
    private PositionSalaryBandRepository salaryBandRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private SeniorityLevelRepository seniorityLevelRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CurrentUserContext currentUserContext;

    @InjectMocks
    private PositionSalaryBandService salaryBandService;

    private UUID companyId;
    private UUID departmentId;
    private UUID positionId;
    private Company testCompany;
    private Position testPosition;
    private SeniorityLevel junior;
    private SeniorityLevel senior;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        positionId = UUID.randomUUID();

        testCompany = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();
        testPosition = Position.builder()
                .id(positionId)
                .department(Department.builder()
                        .id(departmentId)
                        .company(testCompany)
                        .departmentCode("OPS")
                        .departmentName("Operations")
                        .enabled(true)
                        .build())
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build();

        junior = level(UUID.randomUUID(), "JUN", "Junior", 1);
        senior = level(UUID.randomUUID(), "SEN", "Senior", 3);
    }

    private SeniorityLevel level(UUID id, String code, String name, int rank) {
        return SeniorityLevel.builder()
                .id(id)
                .levelCode(code)
                .levelName(name)
                .rank(rank)
                .enabled(true)
                .build();
    }

    private PositionSalaryBand stored(UUID id, SeniorityLevel level, String min, String max, boolean enabled) {
        return PositionSalaryBand.builder()
                .id(id)
                .position(testPosition)
                .seniorityLevel(level)
                .minimumSalary(new BigDecimal(min))
                .maximumSalary(new BigDecimal(max))
                .enabled(enabled)
                .build();
    }

    private PositionSalaryBandRequest item(SeniorityLevel level, String min, String max) {
        return new PositionSalaryBandRequest(level.getId(), new BigDecimal(min), new BigDecimal(max));
    }

    private PositionSalaryBandsRequest request(PositionSalaryBandRequest... items) {
        return new PositionSalaryBandsRequest(List.of(items));
    }

    private void companyExists() {
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(testCompany));
    }

    private void positionExists() {
        when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                .thenReturn(Optional.of(testPosition));
    }

    private void storedBands(PositionSalaryBand... bands) {
        when(salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId))
                .thenReturn(List.of(bands));
    }

    /**
     * Pins the whole {@code delete*} family inherited from {@link org.springframework.data.jpa.repository.JpaRepository},
     * not just the three overloads a naive implementation would reach for: this resource may never
     * delete a band, an omitted one is disabled instead (decision T10).
     */
    private void verifyNoBandDeletes() {
        verify(salaryBandRepository, never()).delete(any(PositionSalaryBand.class));
        verify(salaryBandRepository, never()).deleteById(any(UUID.class));
        verify(salaryBandRepository, never()).deleteAll();
        verify(salaryBandRepository, never()).deleteAll(anyList());
        verify(salaryBandRepository, never()).deleteAllById(anyList());
        verify(salaryBandRepository, never()).deleteAllInBatch();
        verify(salaryBandRepository, never()).deleteAllInBatch(anyList());
        verify(salaryBandRepository, never()).deleteAllByIdInBatch(anyList());
        verify(salaryBandRepository, never()).deleteInBatch(anyList());
    }

    @Nested
    @DisplayName("company scope contract")
    class CompanyScopeContractTests {

        @Test
        @DisplayName("getSalaryBands verifies company access before loading the company")
        void getSalaryBands_VerifiesCompanyAccessFirst() {
            companyExists();
            positionExists();
            storedBands();

            salaryBandService.getSalaryBands(companyId, positionId);

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("replaceSalaryBands verifies company access before loading the company")
        void replaceSalaryBands_VerifiesCompanyAccessFirst() {
            companyExists();
            positionExists();
            storedBands();
            when(seniorityLevelRepository.findById(junior.getId())).thenReturn(Optional.of(junior));
            when(salaryBandRepository.findByPositionIdAndSeniorityLevelId(positionId, junior.getId()))
                    .thenReturn(Optional.empty());
            when(salaryBandRepository.save(any(PositionSalaryBand.class))).thenAnswer(inv -> inv.getArgument(0));

            salaryBandService.replaceSalaryBands(companyId, positionId, request(item(junior, "100", "200")));

            InOrder inOrder = inOrder(currentUserContext, companyRepository);
            inOrder.verify(currentUserContext).verifyCompanyAccess(companyId);
            inOrder.verify(companyRepository).findById(companyId);
        }

        @Test
        @DisplayName("throws CompanyNotFoundException and still checks access when the company does not exist")
        void getSalaryBands_CompanyMissing_ThrowsException() {
            when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salaryBandService.getSalaryBands(companyId, positionId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(currentUserContext).verifyCompanyAccess(companyId);
        }
    }

    @Nested
    @DisplayName("getSalaryBands")
    class GetSalaryBandsTests {

        @Test
        @DisplayName("returns every stored band including disabled ones")
        void getSalaryBands_ReturnsDisabledRowsToo() {
            var enabledId = UUID.randomUUID();
            var disabledId = UUID.randomUUID();
            companyExists();
            positionExists();
            storedBands(
                    stored(enabledId, junior, "100.00", "200.00", true),
                    stored(disabledId, senior, "300.00", "400.00", false));

            var result = salaryBandService.getSalaryBands(companyId, positionId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.seniorityLevelId()).containsExactly(junior.getId(), senior.getId());
            assertThat(result.get(0).enabled()).isTrue();
            assertThat(result.get(1).enabled()).isFalse();
            verify(salaryBandRepository).findByPositionIdOrderBySeniorityLevelRankAsc(positionId);
        }

        @Test
        @DisplayName("returns an empty list when the position has no bands")
        void getSalaryBands_None_ReturnsEmpty() {
            companyExists();
            positionExists();
            storedBands();

            assertThat(salaryBandService.getSalaryBands(companyId, positionId)).isEmpty();
        }

        @Test
        @DisplayName("throws PositionNotFoundException for a position of another company")
        void getSalaryBands_OtherCompanyPosition_IsNotFound() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> salaryBandService.getSalaryBands(companyId, positionId))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verifyNoInteractions(salaryBandRepository);
        }
    }

    @Nested
    @DisplayName("replaceSalaryBands")
    class ReplaceSalaryBandsTests {

        @Test
        @DisplayName("inserts a new band when the natural key is not stored")
        void replaceSalaryBands_NewKey_Inserts() {
            var newId = UUID.randomUUID();
            companyExists();
            positionExists();
            storedBands();
            when(seniorityLevelRepository.findById(junior.getId())).thenReturn(Optional.of(junior));
            when(salaryBandRepository.findByPositionIdAndSeniorityLevelId(positionId, junior.getId()))
                    .thenReturn(Optional.empty());
            when(salaryBandRepository.save(any(PositionSalaryBand.class))).thenAnswer(inv -> {
                PositionSalaryBand band = inv.getArgument(0);
                band.setId(newId);
                return band;
            });

            var result = salaryBandService.replaceSalaryBands(
                    companyId, positionId, request(item(junior, "100.00", "200.00")));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(newId);
            assertThat(result.get(0).positionId()).isEqualTo(positionId);
            assertThat(result.get(0).seniorityLevelId()).isEqualTo(junior.getId());
            assertThat(result.get(0).minimumSalary()).isEqualByComparingTo("100.00");
            assertThat(result.get(0).maximumSalary()).isEqualByComparingTo("200.00");
            assertThat(result.get(0).enabled()).isTrue();
            verify(salaryBandRepository).save(any(PositionSalaryBand.class));
        }

        @Test
        @DisplayName("updates the stored row in place when the natural key already exists")
        void replaceSalaryBands_ExistingKey_UpdatesInPlace() {
            var bandId = UUID.randomUUID();
            var existing = stored(bandId, junior, "100.00", "200.00", true);
            companyExists();
            positionExists();
            storedBands(existing);
            when(seniorityLevelRepository.findById(junior.getId())).thenReturn(Optional.of(junior));
            when(salaryBandRepository.findByPositionIdAndSeniorityLevelId(positionId, junior.getId()))
                    .thenReturn(Optional.of(existing));
            when(salaryBandRepository.save(any(PositionSalaryBand.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = salaryBandService.replaceSalaryBands(
                    companyId, positionId, request(item(junior, "150.00", "250.00")));

            assertThat(existing.getMinimumSalary()).isEqualByComparingTo("150.00");
            assertThat(existing.getMaximumSalary()).isEqualByComparingTo("250.00");
            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo(bandId);
            verify(salaryBandRepository).save(existing);
        }

        @Test
        @DisplayName("re-enables a stored disabled band that the request resubmits")
        void replaceSalaryBands_ResubmittedDisabledKey_Reenables() {
            var bandId = UUID.randomUUID();
            var disabled = stored(bandId, junior, "100.00", "200.00", false);
            companyExists();
            positionExists();
            storedBands(disabled);
            when(seniorityLevelRepository.findById(junior.getId())).thenReturn(Optional.of(junior));
            when(salaryBandRepository.findByPositionIdAndSeniorityLevelId(positionId, junior.getId()))
                    .thenReturn(Optional.of(disabled));
            when(salaryBandRepository.save(any(PositionSalaryBand.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = salaryBandService.replaceSalaryBands(
                    companyId, positionId, request(item(junior, "100.00", "200.00")));

            assertThat(disabled.getEnabled()).isTrue();
            assertThat(result.get(0).enabled()).isTrue();
        }

        @Test
        @DisplayName("disables a stored band the request omits and keeps the row")
        void replaceSalaryBands_OmittedKey_DisablesButKeepsRow() {
            var juniorBand = stored(UUID.randomUUID(), junior, "100.00", "200.00", true);
            var seniorBand = stored(UUID.randomUUID(), senior, "300.00", "400.00", true);
            companyExists();
            positionExists();
            storedBands(juniorBand, seniorBand);
            when(seniorityLevelRepository.findById(junior.getId())).thenReturn(Optional.of(junior));
            when(salaryBandRepository.findByPositionIdAndSeniorityLevelId(positionId, junior.getId()))
                    .thenReturn(Optional.of(juniorBand));
            when(salaryBandRepository.save(any(PositionSalaryBand.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = salaryBandService.replaceSalaryBands(
                    companyId, positionId, request(item(junior, "100.00", "200.00")));

            assertThat(juniorBand.getEnabled()).isTrue();
            assertThat(seniorBand.getEnabled()).isFalse();
            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.enabled()).containsExactly(true, false);
            verify(salaryBandRepository).save(seniorBand);
            verifyNoBandDeletes();
        }

        @Test
        @DisplayName("an empty request disables every stored band without deleting a single row")
        void replaceSalaryBands_EmptyRequest_DisablesAllNeverDeletes() {
            var existing = stored(UUID.randomUUID(), junior, "100.00", "200.00", true);
            companyExists();
            positionExists();
            storedBands(existing);
            when(salaryBandRepository.save(any(PositionSalaryBand.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = salaryBandService.replaceSalaryBands(
                    companyId, positionId, new PositionSalaryBandsRequest(List.of()));

            assertThat(existing.getEnabled()).isFalse();
            assertThat(result).hasSize(1);
            assertThat(result.get(0).enabled()).isFalse();
            verify(salaryBandRepository).save(existing);
            verifyNoBandDeletes();
            verifyNoInteractions(seniorityLevelRepository);
        }

        @Test
        @DisplayName("an empty request with no stored bands saves nothing")
        void replaceSalaryBands_EmptyRequestNothingStored_SavesNothing() {
            companyExists();
            positionExists();
            storedBands();

            var result = salaryBandService.replaceSalaryBands(
                    companyId, positionId, new PositionSalaryBandsRequest(List.of()));

            assertThat(result).isEmpty();
            verify(salaryBandRepository, never()).save(any());
            verifyNoInteractions(seniorityLevelRepository);
        }

        @Test
        @DisplayName("throws PositionNotFoundException for a position of another company before any band work")
        void replaceSalaryBands_OtherCompanyPosition_IsNotFound() {
            companyExists();
            when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(
                            companyId, positionId, request(item(junior, "100.00", "200.00"))))
                    .isInstanceOf(PositionNotFoundException.class)
                    .hasMessageContaining("Position not found with id");
            verifyNoInteractions(salaryBandRepository, seniorityLevelRepository);
        }

        @Test
        @DisplayName("throws SeniorityLevelNotFoundException when a submitted level does not resolve")
        void replaceSalaryBands_UnknownLevel_IsNotFound() {
            var unknownId = UUID.randomUUID();
            companyExists();
            positionExists();
            storedBands();
            when(seniorityLevelRepository.findById(unknownId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(
                            companyId,
                            positionId,
                            request(new PositionSalaryBandRequest(
                                    unknownId, new BigDecimal("100"), new BigDecimal("200")))))
                    .isInstanceOf(SeniorityLevelNotFoundException.class)
                    .hasMessageContaining("Seniority level not found with id");
            verify(salaryBandRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects an unknown level in a later item before writing the earlier valid item")
        void replaceSalaryBands_UnknownLaterLevel_NeverWritesEarlierItem() {
            var unknownId = UUID.randomUUID();
            companyExists();
            positionExists();
            storedBands();
            when(seniorityLevelRepository.findById(junior.getId())).thenReturn(Optional.of(junior));
            when(seniorityLevelRepository.findById(unknownId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(
                            companyId,
                            positionId,
                            request(
                                    item(junior, "100.00", "200.00"),
                                    new PositionSalaryBandRequest(
                                            unknownId, new BigDecimal("300.00"), new BigDecimal("400.00")))))
                    .isInstanceOf(SeniorityLevelNotFoundException.class)
                    .hasMessageContaining("Seniority level not found with id");

            // Every reference is resolved before the write loop, so the valid first item is never saved.
            verify(salaryBandRepository, never()).save(any(PositionSalaryBand.class));
            verify(salaryBandRepository, never()).findByPositionIdAndSeniorityLevelId(any(), any());
        }

        @Test
        @DisplayName("throws IllegalArgumentException when maximumSalary is below minimumSalary")
        void replaceSalaryBands_MaxBelowMin_IsBadRequest() {
            companyExists();
            positionExists();
            storedBands();

            assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(
                            companyId, positionId, request(item(junior, "200.00", "100.00"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximumSalary must be greater than or equal to minimumSalary");
            verifyNoInteractions(seniorityLevelRepository);
            verify(salaryBandRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws IllegalArgumentException when minimumSalary is negative")
        void replaceSalaryBands_NegativeMinimum_IsBadRequest() {
            companyExists();
            positionExists();
            storedBands();

            assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(
                            companyId, positionId, request(item(junior, "-1.00", "100.00"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minimumSalary must be greater than or equal to 0");
            verifyNoInteractions(seniorityLevelRepository);
            verify(salaryBandRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws IllegalArgumentException when one request repeats a seniority level")
        void replaceSalaryBands_DuplicateLevel_IsBadRequest() {
            companyExists();
            positionExists();
            storedBands();

            assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(
                            companyId,
                            positionId,
                            request(item(junior, "100.00", "200.00"), item(junior, "300.00", "400.00"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Duplicate seniorityLevelId");
            verifyNoInteractions(seniorityLevelRepository);
            verify(salaryBandRepository, never()).save(any());
        }
    }
}
