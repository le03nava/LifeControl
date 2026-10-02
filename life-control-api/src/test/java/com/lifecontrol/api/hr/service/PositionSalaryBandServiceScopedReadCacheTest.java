package com.lifecontrol.api.hr.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.PositionSalaryBandRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the decision that the company-scoped salary-band reads are deliberately uncached.
 *
 * <p>A plain unit test cannot observe caching at all: the {@code @Cacheable}/{@code @CacheEvict}
 * annotations only take effect when the call travels through the Spring proxy. This test therefore
 * runs {@link PositionSalaryBandService} through its proxy — {@code @SpringBootTest} with the
 * {@code test} profile, so Redis is excluded and {@code spring.cache.type=simple} supplies the simple
 * in-memory cache manager. It asserts that two identical {@code getSalaryBands(companyId, id)} calls
 * each reach {@link CurrentUserContext#verifyCompanyAccess(UUID)}.</p>
 *
 * <p>The assertion is load-bearing: a later {@code @Cacheable(value = "positionSalaryBands", ...)} on
 * {@code getSalaryBands} would serve the second call from the cache, skip {@code resolveCompany} (and
 * with it the scope check), and make this test fail with {@code Wanted 2 times but was 1}
 * (record T16).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PositionSalaryBandService scoped reads are deliberately uncached")
class PositionSalaryBandServiceScopedReadCacheTest {

    @Autowired
    private PositionSalaryBandService salaryBandService;

    @MockitoBean
    private PositionSalaryBandRepository salaryBandRepository;

    @MockitoBean
    private PositionRepository positionRepository;

    @MockitoBean
    private SeniorityLevelRepository seniorityLevelRepository;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    @Test
    @DisplayName("two identical getSalaryBands calls each reach verifyCompanyAccess")
    void getSalaryBands_TwoIdenticalCalls_ReachVerifyCompanyAccessTwice() {
        var companyId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();
        var positionId = UUID.randomUUID();

        var company = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .enabled(true)
                .build();
        var department = Department.builder()
                .id(departmentId)
                .company(company)
                .departmentCode("OPS")
                .departmentName("Operations")
                .enabled(true)
                .build();
        var position = Position.builder()
                .id(positionId)
                .department(department)
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(positionRepository.findByDepartmentCompanyIdAndId(companyId, positionId))
                .thenReturn(Optional.of(position));
        when(salaryBandRepository.findByPositionIdOrderBySeniorityLevelRankAsc(positionId))
                .thenReturn(List.of());

        salaryBandService.getSalaryBands(companyId, positionId);
        salaryBandService.getSalaryBands(companyId, positionId);

        // A @Cacheable on getSalaryBands would serve the second call from the cache and skip the
        // scope check, turning this into "Wanted 2 times but was 1".
        verify(currentUserContext, times(2)).verifyCompanyAccess(companyId);
    }
}
