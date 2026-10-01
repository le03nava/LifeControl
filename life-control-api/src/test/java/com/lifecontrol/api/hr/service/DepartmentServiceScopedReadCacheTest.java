package com.lifecontrol.api.hr.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the decision that the company-scoped department reads are deliberately uncached.
 *
 * <p>A plain unit test cannot observe caching at all: the {@code @Cacheable}/{@code @CacheEvict}
 * annotations only take effect when the call travels through the Spring proxy. This test therefore
 * runs {@link DepartmentService} through its proxy — {@code @SpringBootTest} with the {@code test}
 * profile, so Redis is excluded and {@code spring.cache.type=simple} supplies the simple in-memory
 * cache manager. It asserts that two identical {@code getDepartmentById(companyId, id)} calls each
 * reach {@link CurrentUserContext#verifyCompanyAccess(UUID)}.</p>
 *
 * <p>The assertion is load-bearing: a later {@code @Cacheable(value = "departments", key = "#id")}
 * on {@code getDepartmentById} would serve the second call from the cache, skip {@code resolveCompany}
 * (and with it the scope check), and make this test fail with {@code Wanted 2 times but was 1}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("DepartmentService scoped reads are deliberately uncached")
class DepartmentServiceScopedReadCacheTest {

    @Autowired
    private DepartmentService departmentService;

    @MockitoBean
    private DepartmentRepository departmentRepository;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    @Test
    @DisplayName("two identical getDepartmentById calls each reach verifyCompanyAccess")
    void getDepartmentById_TwoIdenticalCalls_ReachVerifyCompanyAccessTwice() {
        var companyId = UUID.randomUUID();
        var departmentId = UUID.randomUUID();

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

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(departmentRepository.findByCompanyIdAndId(companyId, departmentId)).thenReturn(Optional.of(department));

        departmentService.getDepartmentById(companyId, departmentId);
        departmentService.getDepartmentById(companyId, departmentId);

        // A @Cacheable on getDepartmentById would serve the second call from the cache and skip the
        // scope check, turning this into "Wanted 2 times but was 1".
        verify(currentUserContext, times(2)).verifyCompanyAccess(companyId);
    }
}
