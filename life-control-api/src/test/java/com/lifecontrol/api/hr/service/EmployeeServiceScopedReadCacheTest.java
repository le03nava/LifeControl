package com.lifecontrol.api.hr.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.address.repository.AddressRepository;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the decision that the company-scoped employee reads are deliberately uncached.
 *
 * <p>A plain unit test cannot observe caching at all: the {@code @Cacheable}/{@code @CacheEvict}
 * annotations only take effect when the call travels through the Spring proxy. This test therefore
 * runs {@link EmployeeService} through its proxy — {@code @SpringBootTest} with the {@code test}
 * profile, so Redis is excluded and {@code spring.cache.type=simple} supplies the simple in-memory
 * cache manager. It asserts that two identical {@code getEmployeeById(companyId, id)} calls each
 * reach {@link CurrentUserContext#verifyCompanyAccess(UUID)}.</p>
 *
 * <p>The assertion is load-bearing: a later {@code @Cacheable(value = "employees", key = "#id")} on
 * {@code getEmployeeById} would serve the second call from the cache, skip {@code resolveCompany}
 * (and with it the scope check), and make this test fail with {@code Wanted 2 times but was 1}.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("EmployeeService scoped reads are deliberately uncached")
class EmployeeServiceScopedReadCacheTest {

    @Autowired
    private EmployeeService employeeService;

    @MockitoBean
    private EmployeeRepository employeeRepository;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private StatusRepository statusRepository;

    @MockitoBean
    private AddressRepository addressRepository;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    @Test
    @DisplayName("two identical getEmployeeById calls each reach verifyCompanyAccess")
    void getEmployeeById_TwoIdenticalCalls_ReachVerifyCompanyAccessTwice() {
        var companyId = UUID.randomUUID();
        var employeeId = UUID.randomUUID();

        var company = Company.builder()
                .id(companyId)
                .companyKey("1")
                .companyName("Test Company")
                .rfc("XAXX010101000")
                .emailDomain("example.com")
                .enabled(true)
                .build();
        var status = Status.builder()
                .id(UUID.randomUUID())
                .statusName("Active")
                .statusType(StatusType.builder()
                        .id(UUID.randomUUID())
                        .statusTypeName("EMPLOYEE_STATUS")
                        .enabled(true)
                        .build())
                .enabled(true)
                .build();
        var employee = Employee.builder()
                .id(employeeId)
                .company(company)
                .employeeNumber("EMP-001")
                .firstName("Juan")
                .paternalLastName("Pérez")
                .email("juan.perez@example.com")
                .birthDate(LocalDate.of(1990, 1, 1))
                .hireDate(LocalDate.of(2020, 1, 1))
                .status(status)
                .enabled(true)
                .build();

        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));
        when(employeeRepository.findByIdAndCompanyId(employeeId, companyId)).thenReturn(Optional.of(employee));

        employeeService.getEmployeeById(companyId, employeeId);
        employeeService.getEmployeeById(companyId, employeeId);

        // A @Cacheable on getEmployeeById would serve the second call from the cache and skip the
        // scope check, turning this into "Wanted 2 times but was 1".
        verify(currentUserContext, times(2)).verifyCompanyAccess(companyId);
    }
}
