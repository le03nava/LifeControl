package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.PositionRequest;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.service.PositionService;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Persistence-level verification of the {@code positions.reports_to_position_id} self-reference
 * against real PostgreSQL, with Flyway enabled and {@code ddl-auto=validate}.
 *
 * <p>The service cycle walk navigates {@code reportsToPosition} lazily, so this test proves the chain
 * resolves inside the service's transaction against a real database, not only against in-memory
 * objects. It reuses the real {@link PositionService} and mocks only the company-scope authorization,
 * which is a different concern. A green run also proves the entity mapping validates against V19.</p>
 */
@SpringBootTest
@DisplayName("Position self-reference integration tests")
class PositionSelfReferenceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private PositionService positionService;

    @MockitoBean
    private CurrentUserContext currentUserContext;

    private UUID companyId;
    private UUID departmentId;

    @BeforeEach
    void setUp() {
        var company = companyRepository.save(Company.builder()
                .companyKey("POS-SELF-" + UUID.randomUUID())
                .companyName("Position Self FK Company")
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
        departmentId = department.getId();
    }

    @Test
    @DisplayName("walks a three-level lazy reports-to chain and rejects the deep cycle")
    void deepCycleIsRejectedAcrossLazyLoads() {
        var c = save("C", "Chief", null);
        var b = save("B", "Lead", c);
        var a = save("A", "Analyst", b);

        // A reports to B, B reports to C. Making C report to A closes the loop, so the service must
        // walk A.reportsTo -> B -> C lazily inside its transaction and reject it.
        var request = new PositionRequest(departmentId, "C", "Chief", null, a.getId(), 1, true);

        assertThatThrownBy(() -> positionService.updatePosition(companyId, c.getId(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot report to itself or to one of its descendants");

        var reloaded = positionRepository.findById(c.getId()).orElseThrow();
        assertThat(reloaded.getReportsToPosition()).isNull();
    }

    @Test
    @DisplayName("loads the reporting parent through the lazy association")
    void reportsToParentIsLazyLoaded() {
        var parent = save("MGR", "Manager", null);
        var child = save("OP1", "Operator", parent);

        var response = positionService.getPositionById(companyId, child.getId());

        assertThat(response.reportsToPositionId()).isEqualTo(parent.getId());
        assertThat(response.companyId()).isEqualTo(companyId);
        assertThat(response.departmentId()).isEqualTo(departmentId);
    }

    /** {@code companies.rfc} is a unique 13-char column, so each fixture needs its own value. */
    private String uniqueRfc() {
        return ("POSF" + UUID.randomUUID().toString().replace("-", "").substring(0, 9)).toUpperCase();
    }

    private Position save(String code, String name, Position reportsTo) {
        var department = departmentRepository.findById(departmentId).orElseThrow();
        return positionRepository.save(Position.builder()
                .department(department)
                .positionCode(code)
                .positionName(name)
                .reportsToPosition(reportsTo)
                .enabled(true)
                .build());
    }
}
