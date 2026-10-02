package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.PositionSalaryBandRequest;
import com.lifecontrol.api.hr.dto.PositionSalaryBandsRequest;
import com.lifecontrol.api.hr.exception.SeniorityLevelNotFoundException;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionSalaryBandRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import com.lifecontrol.api.hr.service.PositionSalaryBandService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-level verification of the full-set salary-band save against real PostgreSQL, with Flyway
 * enabled and {@code ddl-auto=validate}.
 *
 * <p>The upsert semantics of decisions T10/D13 are only real if the database agrees: this test proves
 * that a stored natural key is updated in place (same row id, no duplicate), that an omitted stored
 * key is disabled and still exists, and that the row count after a save is exactly what the model
 * predicts. It reuses the real repositories and service and mocks only the company-scope
 * authorization, which is a different concern. A green run also proves the entity mapping validates
 * against V19.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Position salary-band persistence integration tests")
class PositionSalaryBandIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PositionSalaryBandRepository salaryBandRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private SeniorityLevelRepository seniorityLevelRepository;

    @Autowired
    private PositionSalaryBandService salaryBandService;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    private UUID companyId;
    private UUID positionId;

    @BeforeEach
    void setUp() {
        var company = companyRepository.save(Company.builder()
                .companyKey("SAL-" + UUID.randomUUID())
                .companyName("Salary Band Company")
                .rfc(uniqueRfc())
                .enabled(true)
                .build());
        companyId = company.getId();

        var department = departmentRepository.save(Department.builder()
                .company(company)
                .departmentCode("OPS")
                .departmentName("Operations")
                .enabled(true)
                .build());

        var position = positionRepository.save(Position.builder()
                .department(department)
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build());
        positionId = position.getId();
    }

    @Test
    @DisplayName("upserts a stored natural key in place without duplicating the row")
    void existingNaturalKeyUpdatesInPlace() {
        var junior = level("J", "Junior");

        var first =
                salaryBandService.replaceSalaryBands(companyId, positionId, request(item(junior, "100.00", "200.00")));
        var firstId = first.get(0).id();

        var second =
                salaryBandService.replaceSalaryBands(companyId, positionId, request(item(junior, "150.00", "250.00")));

        assertThat(second).hasSize(1);
        assertThat(second.get(0).id()).isEqualTo(firstId);
        assertThat(second.get(0).minimumSalary()).isEqualByComparingTo("150.00");
        assertThat(second.get(0).maximumSalary()).isEqualByComparingTo("250.00");
        assertThat(salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId))
                .hasSize(1);
    }

    @Test
    @DisplayName("disables an omitted stored key and keeps the row and its id")
    void omittedKeyIsDisabledButStillExists() {
        var junior = level("J", "Junior");
        var senior = level("S", "Senior");

        var first = salaryBandService.replaceSalaryBands(
                companyId,
                positionId,
                new PositionSalaryBandsRequest(
                        List.of(item(junior, "100.00", "200.00"), item(senior, "300.00", "400.00"))));
        var seniorId = first.stream()
                .filter(band -> band.seniorityLevelId().equals(senior.getId()))
                .findFirst()
                .orElseThrow()
                .id();

        salaryBandService.replaceSalaryBands(companyId, positionId, request(item(junior, "100.00", "200.00")));

        var stored = salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId);
        assertThat(stored).hasSize(2);

        var seniorBand = stored.stream()
                .filter(band -> band.getSeniorityLevel().getId().equals(senior.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(seniorBand.getId()).isEqualTo(seniorId);
        assertThat(seniorBand.getEnabled()).isFalse();

        // The read returns disabled rows too, so "explicitly cleared" stays distinguishable.
        var read = salaryBandService.getSalaryBands(companyId, positionId);
        assertThat(read).hasSize(2);
        assertThat(read.stream()
                        .filter(band -> band.seniorityLevelId().equals(senior.getId()))
                        .findFirst()
                        .orElseThrow()
                        .enabled())
                .isFalse();
    }

    @Test
    @DisplayName("a rejected multi-item save writes nothing to the table")
    void rejectedMultiItemSaveWritesNothing() {
        var junior = level("J", "Junior");
        var unknown = UUID.randomUUID();

        var request = new PositionSalaryBandsRequest(List.of(
                item(junior, "100.00", "200.00"),
                new PositionSalaryBandRequest(unknown, new BigDecimal("300.00"), new BigDecimal("400.00"))));

        assertThatThrownBy(() -> salaryBandService.replaceSalaryBands(companyId, positionId, request))
                .isInstanceOf(SeniorityLevelNotFoundException.class);

        // The valid first item must not have been persisted: references resolve before any write.
        assertThat(salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId))
                .isEmpty();
    }

    private SeniorityLevel level(String codePrefix, String name) {
        var suffix = UUID.randomUUID().toString().substring(0, 6);
        return seniorityLevelRepository.save(SeniorityLevel.builder()
                .levelCode(codePrefix + suffix)
                .levelName(name + " " + suffix)
                .rank(ThreadLocalRandom.current().nextInt(100_000, 900_000))
                .enabled(true)
                .build());
    }

    private PositionSalaryBandRequest item(SeniorityLevel level, String min, String max) {
        return new PositionSalaryBandRequest(level.getId(), new BigDecimal(min), new BigDecimal(max));
    }

    private PositionSalaryBandsRequest request(PositionSalaryBandRequest item) {
        return new PositionSalaryBandsRequest(List.of(item));
    }

    /** {@code companies.rfc} is a unique 13-char column, so each fixture needs its own value. */
    private String uniqueRfc() {
        return ("SALF" + UUID.randomUUID().toString().replace("-", "").substring(0, 9)).toUpperCase();
    }
}
