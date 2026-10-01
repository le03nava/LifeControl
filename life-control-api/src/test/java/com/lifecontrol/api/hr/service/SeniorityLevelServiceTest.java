package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.lifecontrol.api.hr.dto.SeniorityLevelRequest;
import com.lifecontrol.api.hr.exception.DuplicateSeniorityLevelException;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("SeniorityLevelService Tests")
class SeniorityLevelServiceTest {

    @Mock
    private SeniorityLevelRepository seniorityLevelRepository;

    @InjectMocks
    private SeniorityLevelService seniorityLevelService;

    private SeniorityLevel testSeniorityLevel;
    private SeniorityLevelRequest testSeniorityLevelRequest;
    private UUID testSeniorityLevelId;

    @BeforeEach
    void setUp() {
        testSeniorityLevelId = UUID.randomUUID();

        testSeniorityLevel = SeniorityLevel.builder()
                .id(testSeniorityLevelId)
                .levelCode("L1")
                .levelName("Junior")
                .rank(1)
                .enabled(true)
                .build();

        testSeniorityLevelRequest = new SeniorityLevelRequest("L1", "Junior", 1, true);
    }

    private SeniorityLevel level(UUID id, String code, String name, int rank, boolean enabled) {
        return SeniorityLevel.builder()
                .id(id)
                .levelCode(code)
                .levelName(name)
                .rank(rank)
                .enabled(enabled)
                .build();
    }

    @Nested
    @DisplayName("getAllSeniorityLevels")
    class GetAllSeniorityLevelsTests {

        @Test
        @DisplayName("should return every level ordered by rank ascending when includeDisabled is true")
        void getAllSeniorityLevels_IncludeDisabled_ReturnsAllOrderedByRank() {
            when(seniorityLevelRepository.findAllByOrderByRankAsc())
                    .thenReturn(List.of(
                            level(UUID.randomUUID(), "L1", "Junior", 1, true),
                            level(UUID.randomUUID(), "L2", "Semi Senior", 2, true),
                            level(UUID.randomUUID(), "L3", "Senior", 3, false)));

            var result = seniorityLevelService.getAllSeniorityLevels(true);

            assertThat(result).hasSize(3);
            assertThat(result).extracting(r -> r.rank()).containsExactly(1, 2, 3);
            verify(seniorityLevelRepository).findAllByOrderByRankAsc();
            verify(seniorityLevelRepository, never()).findByEnabledTrueOrderByRankAsc();
        }

        @Test
        @DisplayName("should exclude disabled levels when includeDisabled is false")
        void getAllSeniorityLevels_ExcludeDisabled_FiltersDisabled() {
            when(seniorityLevelRepository.findByEnabledTrueOrderByRankAsc())
                    .thenReturn(List.of(
                            level(UUID.randomUUID(), "L1", "Junior", 1, true),
                            level(UUID.randomUUID(), "L3", "Senior", 3, true)));

            var result = seniorityLevelService.getAllSeniorityLevels(false);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(r -> r.rank()).containsExactly(1, 3);
            assertThat(result).allMatch(r -> r.enabled());
            verify(seniorityLevelRepository).findByEnabledTrueOrderByRankAsc();
            verify(seniorityLevelRepository, never()).findAllByOrderByRankAsc();
        }

        @Test
        @DisplayName("should return an empty list when no levels exist")
        void getAllSeniorityLevels_Empty_ReturnsEmpty() {
            when(seniorityLevelRepository.findByEnabledTrueOrderByRankAsc()).thenReturn(List.of());

            var result = seniorityLevelService.getAllSeniorityLevels(false);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getSeniorityLevelById")
    class GetSeniorityLevelByIdTests {

        @Test
        @DisplayName("should return the level when it exists")
        void getSeniorityLevelById_Success() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));

            var result = seniorityLevelService.getSeniorityLevelById(testSeniorityLevelId);

            assertThat(result).isNotNull();
            assertThat(result.levelCode()).isEqualTo("L1");
            assertThat(result.levelName()).isEqualTo("Junior");
            assertThat(result.rank()).isEqualTo(1);
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw SeniorityLevelNotFoundException when it does not exist")
        void getSeniorityLevelById_NotFound_ThrowsException() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seniorityLevelService.getSeniorityLevelById(testSeniorityLevelId))
                    .isInstanceOf(SeniorityLevelNotFoundException.class)
                    .hasMessageContaining("Seniority level not found with id");
        }
    }

    @Nested
    @DisplayName("createSeniorityLevel")
    class CreateSeniorityLevelTests {

        @Test
        @DisplayName("should create the level when code, name and rank are free")
        void createSeniorityLevel_Success() {
            when(seniorityLevelRepository.existsByLevelCodeIgnoreCase("L1")).thenReturn(false);
            when(seniorityLevelRepository.existsByLevelNameIgnoreCase("Junior")).thenReturn(false);
            when(seniorityLevelRepository.existsByRank(1)).thenReturn(false);
            when(seniorityLevelRepository.save(any(SeniorityLevel.class))).thenAnswer(inv -> {
                SeniorityLevel sl = inv.getArgument(0);
                return SeniorityLevel.builder()
                        .id(testSeniorityLevelId)
                        .levelCode(sl.getLevelCode())
                        .levelName(sl.getLevelName())
                        .rank(sl.getRank())
                        .enabled(sl.getEnabled())
                        .build();
            });

            var result = seniorityLevelService.createSeniorityLevel(testSeniorityLevelRequest);

            assertThat(result).isNotNull();
            assertThat(result.levelCode()).isEqualTo("L1");
            assertThat(result.rank()).isEqualTo(1);
            assertThat(result.enabled()).isTrue();
            verify(seniorityLevelRepository).save(any(SeniorityLevel.class));
        }

        @Test
        @DisplayName("should throw DuplicateSeniorityLevelException naming code when the code exists")
        void createSeniorityLevel_DuplicateCode_ThrowsException() {
            when(seniorityLevelRepository.existsByLevelCodeIgnoreCase("L1")).thenReturn(true);

            assertThatThrownBy(() -> seniorityLevelService.createSeniorityLevel(testSeniorityLevelRequest))
                    .isInstanceOf(DuplicateSeniorityLevelException.class)
                    .hasMessageContaining("Seniority level with code 'L1' already exists");
            verify(seniorityLevelRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateSeniorityLevelException naming name when the name exists")
        void createSeniorityLevel_DuplicateName_ThrowsException() {
            when(seniorityLevelRepository.existsByLevelCodeIgnoreCase("L1")).thenReturn(false);
            when(seniorityLevelRepository.existsByLevelNameIgnoreCase("Junior")).thenReturn(true);

            assertThatThrownBy(() -> seniorityLevelService.createSeniorityLevel(testSeniorityLevelRequest))
                    .isInstanceOf(DuplicateSeniorityLevelException.class)
                    .hasMessageContaining("Seniority level with name 'Junior' already exists");
            verify(seniorityLevelRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateSeniorityLevelException naming rank when the rank exists")
        void createSeniorityLevel_DuplicateRank_ThrowsException() {
            when(seniorityLevelRepository.existsByLevelCodeIgnoreCase("L1")).thenReturn(false);
            when(seniorityLevelRepository.existsByLevelNameIgnoreCase("Junior")).thenReturn(false);
            when(seniorityLevelRepository.existsByRank(1)).thenReturn(true);

            assertThatThrownBy(() -> seniorityLevelService.createSeniorityLevel(testSeniorityLevelRequest))
                    .isInstanceOf(DuplicateSeniorityLevelException.class)
                    .hasMessageContaining("Seniority level with rank '1' already exists");
            verify(seniorityLevelRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateSeniorityLevel")
    class UpdateSeniorityLevelTests {

        @Test
        @DisplayName("should update the level when the three keys are free")
        void updateSeniorityLevel_Success() {
            var request = new SeniorityLevelRequest("L2", "Semi Senior", 2, true);
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelCodeIgnoreCase("L2")).thenReturn(Optional.empty());
            when(seniorityLevelRepository.findByLevelNameIgnoreCase("Semi Senior"))
                    .thenReturn(Optional.empty());
            when(seniorityLevelRepository.findByRank(2)).thenReturn(Optional.empty());
            when(seniorityLevelRepository.save(any(SeniorityLevel.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = seniorityLevelService.updateSeniorityLevel(testSeniorityLevelId, request);

            assertThat(result.levelCode()).isEqualTo("L2");
            assertThat(result.levelName()).isEqualTo("Semi Senior");
            assertThat(result.rank()).isEqualTo(2);
            verify(seniorityLevelRepository).save(any(SeniorityLevel.class));
        }

        @Test
        @DisplayName("should allow a self-update that keeps its own code, name and rank")
        void updateSeniorityLevel_OwnValues_Allowed() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelCodeIgnoreCase("L1")).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelNameIgnoreCase("Junior"))
                    .thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByRank(1)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.save(any(SeniorityLevel.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = seniorityLevelService.updateSeniorityLevel(testSeniorityLevelId, testSeniorityLevelRequest);

            assertThat(result.rank()).isEqualTo(1);
            verify(seniorityLevelRepository).save(any(SeniorityLevel.class));
        }

        @Test
        @DisplayName("should throw SeniorityLevelNotFoundException when the id does not exist")
        void updateSeniorityLevel_NotFound_ThrowsException() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                            seniorityLevelService.updateSeniorityLevel(testSeniorityLevelId, testSeniorityLevelRequest))
                    .isInstanceOf(SeniorityLevelNotFoundException.class)
                    .hasMessageContaining("Seniority level not found with id");
        }

        @Test
        @DisplayName("should throw DuplicateSeniorityLevelException naming code when another level owns it")
        void updateSeniorityLevel_DuplicateCode_ThrowsException() {
            var other = level(UUID.randomUUID(), "L2", "Other", 9, true);
            var request = new SeniorityLevelRequest("L2", "Junior", 1, true);
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelCodeIgnoreCase("L2")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> seniorityLevelService.updateSeniorityLevel(testSeniorityLevelId, request))
                    .isInstanceOf(DuplicateSeniorityLevelException.class)
                    .hasMessageContaining("Seniority level with code 'L2' already exists");
            verify(seniorityLevelRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateSeniorityLevelException naming name when another level owns it")
        void updateSeniorityLevel_DuplicateName_ThrowsException() {
            var other = level(UUID.randomUUID(), "L9", "Semi Senior", 9, true);
            var request = new SeniorityLevelRequest("L1", "Semi Senior", 1, true);
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelCodeIgnoreCase("L1")).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelNameIgnoreCase("Semi Senior"))
                    .thenReturn(Optional.of(other));

            assertThatThrownBy(() -> seniorityLevelService.updateSeniorityLevel(testSeniorityLevelId, request))
                    .isInstanceOf(DuplicateSeniorityLevelException.class)
                    .hasMessageContaining("Seniority level with name 'Semi Senior' already exists");
            verify(seniorityLevelRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw DuplicateSeniorityLevelException naming rank when another level owns it")
        void updateSeniorityLevel_DuplicateRank_ThrowsException() {
            var other = level(UUID.randomUUID(), "L9", "Other", 2, true);
            var request = new SeniorityLevelRequest("L1", "Junior", 2, true);
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelCodeIgnoreCase("L1")).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByLevelNameIgnoreCase("Junior"))
                    .thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.findByRank(2)).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> seniorityLevelService.updateSeniorityLevel(testSeniorityLevelId, request))
                    .isInstanceOf(DuplicateSeniorityLevelException.class)
                    .hasMessageContaining("Seniority level with rank '2' already exists");
            verify(seniorityLevelRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteSeniorityLevel")
    class DeleteSeniorityLevelTests {

        @Test
        @DisplayName("should soft-delete by setting enabled to false without removing the row")
        void deleteSeniorityLevel_Success_SoftDeletes() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.save(any(SeniorityLevel.class))).thenAnswer(inv -> inv.getArgument(0));

            seniorityLevelService.deleteSeniorityLevel(testSeniorityLevelId);

            var captor = ArgumentCaptor.forClass(SeniorityLevel.class);
            verify(seniorityLevelRepository).save(captor.capture());
            assertThat(captor.getValue().getEnabled()).isFalse();
            assertThat(testSeniorityLevel.getEnabled()).isFalse();
            verify(seniorityLevelRepository, never()).delete(any());
            verify(seniorityLevelRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("should throw SeniorityLevelNotFoundException when the id does not exist")
        void deleteSeniorityLevel_NotFound_ThrowsException() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seniorityLevelService.deleteSeniorityLevel(testSeniorityLevelId))
                    .isInstanceOf(SeniorityLevelNotFoundException.class)
                    .hasMessageContaining("Seniority level not found with id");
            verify(seniorityLevelRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("setSeniorityLevelEnabled")
    class SetSeniorityLevelEnabledTests {

        @Test
        @DisplayName("should re-enable a disabled level")
        void setSeniorityLevelEnabled_Enable_Success() {
            testSeniorityLevel.setEnabled(false);
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.save(any(SeniorityLevel.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = seniorityLevelService.setSeniorityLevelEnabled(testSeniorityLevelId, true);

            assertThat(result.enabled()).isTrue();
            verify(seniorityLevelRepository).save(any(SeniorityLevel.class));
        }

        @Test
        @DisplayName("should disable an enabled level")
        void setSeniorityLevelEnabled_Disable_Success() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.of(testSeniorityLevel));
            when(seniorityLevelRepository.save(any(SeniorityLevel.class))).thenAnswer(inv -> inv.getArgument(0));

            var result = seniorityLevelService.setSeniorityLevelEnabled(testSeniorityLevelId, false);

            assertThat(result.enabled()).isFalse();
            verify(seniorityLevelRepository).save(any(SeniorityLevel.class));
        }

        @Test
        @DisplayName("should throw SeniorityLevelNotFoundException when the id does not exist")
        void setSeniorityLevelEnabled_NotFound_ThrowsException() {
            when(seniorityLevelRepository.findById(testSeniorityLevelId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> seniorityLevelService.setSeniorityLevelEnabled(testSeniorityLevelId, true))
                    .isInstanceOf(SeniorityLevelNotFoundException.class)
                    .hasMessageContaining("Seniority level not found with id");
            verify(seniorityLevelRepository, never()).save(any());
        }
    }
}
