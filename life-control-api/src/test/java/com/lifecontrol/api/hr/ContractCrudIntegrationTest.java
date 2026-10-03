package com.lifecontrol.api.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.hr.dto.CloseContractRequest;
import com.lifecontrol.api.hr.dto.ContractRequest;
import com.lifecontrol.api.hr.model.Department;
import com.lifecontrol.api.hr.model.Employee;
import com.lifecontrol.api.hr.model.Position;
import com.lifecontrol.api.hr.model.SeniorityLevel;
import com.lifecontrol.api.hr.repository.DepartmentRepository;
import com.lifecontrol.api.hr.repository.EmployeeRepository;
import com.lifecontrol.api.hr.repository.PositionRepository;
import com.lifecontrol.api.hr.repository.SeniorityLevelRepository;
import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end verification of the three contract routes over HTTP against real PostgreSQL, with the
 * V21 schema applied by Flyway and {@code ddl-auto=validate}.
 *
 * <p>The requests carry the {@code lc-admin} authority, so {@code CurrentUserContext.isAdmin()}
 * short-circuits the company-scope check the same way the store-scoped integration suites do; the
 * service's own scope logic is pinned by {@code ContractServiceTest}. What this suite proves is the
 * wire contract and the persistence semantics together: the 201/200/400/404/409 status codes, the
 * close-the-previous rule leaving exactly two rows with the predecessor closed the day before, and
 * the one overlap the service pre-check cannot see — a new open-ended contract reaching into a later
 * contract — ending as the repository's generic 409 with the whole transaction rolled back.</p>
 *
 * <p>Cleanup is leaf-first and wholesale over the tables this suite owns, mirroring
 * {@code EmployeeContractSchemaMigrationIntegrationTest}: the {@code @BeforeEach} deletes every row
 * of {@code employee_contracts}, then of {@code position_salary_bands}, {@code position_roles},
 * {@code positions}, {@code departments}, {@code seniority_levels} and {@code employees}, in that
 * order, and never touches {@code companies}, {@code statuses} or {@code status_types} (the shared
 * fixtures other suites depend on). The PostgreSQL container is shared per JVM, so a wholly owned
 * starting state is what keeps the row-count assertions deterministic.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Contract CRUD Integration Tests")
class ContractCrudIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String COMPANY_KEY = "EMP-CONTRACT-CRUD-KEY";
    private static final String COMPANY_RFC = "EMPCC010101AB";
    private static final String OTHER_COMPANY_KEY = "EMP-CONTRACT-OTHER-KEY";
    private static final String OTHER_COMPANY_RFC = "EMPCO010101AB";

    private static final String BASE_URL = "/api/companies/{companyId}/employees/{employeeId}/contracts";
    private static final SimpleGrantedAuthority ROLE_LC_ADMIN = new SimpleGrantedAuthority("ROLE_lc-admin");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private PositionRepository positionRepository;

    @Autowired
    private SeniorityLevelRepository seniorityLevelRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private StatusRepository statusRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private UUID companyId;
    private UUID employeeId;
    private Position position;
    private SeniorityLevel seniorityLevel;
    private UUID terminatedEmployeeId;
    private UUID foreignPositionId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM employee_contracts");
        jdbcTemplate.update("DELETE FROM position_salary_bands");
        jdbcTemplate.update("DELETE FROM position_roles");
        jdbcTemplate.update("DELETE FROM positions");
        jdbcTemplate.update("DELETE FROM departments");
        jdbcTemplate.update("DELETE FROM seniority_levels");
        jdbcTemplate.update("DELETE FROM employees");

        companyId = companyId(COMPANY_KEY, COMPANY_RFC, "Contract CRUD Test Company");
        var activeStatus = employeeStatus("Active");
        var terminatedStatus = employeeStatus("Terminated");

        var department = departmentRepository.save(Department.builder()
                .company(companyRepository.findById(companyId).orElseThrow())
                .departmentCode("OPS")
                .departmentName("Operations")
                .enabled(true)
                .build());
        position = positionRepository.save(Position.builder()
                .department(department)
                .positionCode("OP1")
                .positionName("Operator")
                .enabled(true)
                .build());
        seniorityLevel = seniorityLevelRepository.save(SeniorityLevel.builder()
                .levelCode("J" + sequence.incrementAndGet())
                .levelName("Junior")
                .rank(1)
                .enabled(true)
                .build());

        employeeId = employeeRepository
                .save(employee(companyId, activeStatus, "EMP-C-001", "contract.employee@example.com", null))
                .getId();
        terminatedEmployeeId = employeeRepository
                .save(employee(
                        companyId,
                        terminatedStatus,
                        "EMP-C-002",
                        "contract.terminated@example.com",
                        LocalDate.of(2026, 1, 1)))
                .getId();

        var otherCompanyId = companyId(OTHER_COMPANY_KEY, OTHER_COMPANY_RFC, "Contract CRUD Other Company");
        var otherDepartment = departmentRepository.save(Department.builder()
                .company(companyRepository.findById(otherCompanyId).orElseThrow())
                .departmentCode("OTHER")
                .departmentName("Other Operations")
                .enabled(true)
                .build());
        foreignPositionId = positionRepository
                .save(Position.builder()
                        .department(otherDepartment)
                        .positionCode("OPX")
                        .positionName("Foreign Operator")
                        .enabled(true)
                        .build())
                .getId();
    }

    private ResultActions postContract(UUID employee, ContractRequest request) throws Exception {
        return mockMvc.perform(post(BASE_URL, companyId, employee)
                .with(jwt().authorities(ROLE_LC_ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ContractRequest request(LocalDate start, LocalDate end) {
        return new ContractRequest(
                position.getId(), seniorityLevel.getId(), "PERMANENT", new BigDecimal("1500.00"), start, end);
    }

    @Nested
    @DisplayName("round trip")
    class RoundTripTests {

        @Test
        @DisplayName("creates a contract over HTTP and lists it back with its position and level names")
        void createThenList() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.employeeId").value(employeeId.toString()))
                    .andExpect(jsonPath("$.positionId").value(position.getId().toString()))
                    .andExpect(jsonPath("$.positionName").value("Operator"))
                    .andExpect(jsonPath("$.seniorityLevelId")
                            .value(seniorityLevel.getId().toString()))
                    .andExpect(jsonPath("$.seniorityLevelName").value("Junior"))
                    .andExpect(jsonPath("$.contractType").value("PERMANENT"))
                    .andExpect(jsonPath("$.enabled").value(true));

            mockMvc.perform(get(BASE_URL, companyId, employeeId).with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].startDate").value("2026-01-01"))
                    .andExpect(jsonPath("$[0].endDate").value(nullValue()));

            assertThat(countContracts(employeeId)).isEqualTo(1);
        }

        @Test
        @DisplayName("closes an open contract with the requested end date")
        void closeSetsTheEndDate() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null)).andExpect(status().isCreated());

            mockMvc.perform(patch(
                                    BASE_URL + "/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractIdAtStart(LocalDate.of(2026, 1, 1)))
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CloseContractRequest(LocalDate.of(2026, 6, 30)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.endDate").value("2026-06-30"));

            assertThat(endDateAtStart(employeeId, LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        @DisplayName("closing without a body defaults the end date to today")
        void closeWithoutBodyDefaultsToToday() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null)).andExpect(status().isCreated());

            mockMvc.perform(patch(
                                    BASE_URL + "/{id}/close",
                                    companyId,
                                    employeeId,
                                    contractIdAtStart(LocalDate.of(2026, 1, 1)))
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.endDate").value(LocalDate.now().toString()));
        }

        @Test
        @DisplayName("closing an already-closed contract is a 409")
        void closingTwiceIsAConflict() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null)).andExpect(status().isCreated());
            var id = contractIdAtStart(LocalDate.of(2026, 1, 1));

            mockMvc.perform(patch(BASE_URL + "/{id}/close", companyId, employeeId, id)
                            .with(jwt().authorities(ROLE_LC_ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new CloseContractRequest(LocalDate.of(2026, 6, 30)))))
                    .andExpect(status().isOk());

            mockMvc.perform(patch(BASE_URL + "/{id}/close", companyId, employeeId, id)
                            .with(jwt().authorities(ROLE_LC_ADMIN)))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("the close-the-previous rule (T13)")
    class CloseThePreviousTests {

        @Test
        @DisplayName("a second contract leaves two rows, the first closed the day before its start")
        void secondContractClosesTheFirst() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null)).andExpect(status().isCreated());

            postContract(employeeId, request(LocalDate.of(2026, 6, 1), null))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.startDate").value("2026-06-01"));

            assertThat(countContracts(employeeId)).isEqualTo(2);
            assertThat(endDateAtStart(employeeId, LocalDate.of(2026, 1, 1)))
                    .as("the predecessor is closed the day before the new start date")
                    .isEqualTo(LocalDate.of(2026, 5, 31));
            assertThat(endDateAtStart(employeeId, LocalDate.of(2026, 6, 1))).isNull();
        }

        @Test
        @DisplayName("a new contract starting on the predecessor's start date is a 400 and writes nothing")
        void equalStartDateIsA400() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null)).andExpect(status().isCreated());

            postContract(employeeId, request(LocalDate.of(2026, 1, 1), null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("previous contract")));

            assertThat(countContracts(employeeId)).isEqualTo(1);
            assertThat(endDateAtStart(employeeId, LocalDate.of(2026, 1, 1))).isNull();
        }
    }

    @Nested
    @DisplayName("the overlap the pre-check cannot see")
    class UnseenOverlapTests {

        @Test
        @DisplayName("a new open-ended contract reaching into a later contract ends as the generic 409")
        void openEndedContractReachingIntoALaterContractIsA409() throws Exception {
            // A covers the new start date (so the pre-check closes it) and C starts after A ends.
            postContract(employeeId, request(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                    .andExpect(status().isCreated());
            postContract(employeeId, request(LocalDate.of(2027, 1, 1), null)).andExpect(status().isCreated());
            assertThat(countContracts(employeeId)).isEqualTo(2);

            // Open-ended from 2026-06-01: the pre-check sees only A, closes it at 2026-05-31, and the
            // insert then overlaps C. The partial exclusion constraint refuses it; the handler maps
            // DataIntegrityViolationException to the generic 409, not a 500.
            postContract(employeeId, request(LocalDate.of(2026, 6, 1), null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("conflicts with an existing resource")));

            assertThat(countContracts(employeeId))
                    .as("the failed write leaves the history untouched")
                    .isEqualTo(2);
            assertThat(endDateAtStart(employeeId, LocalDate.of(2026, 1, 1)))
                    .as("the predecessor close rolled back with the refused insert")
                    .isEqualTo(LocalDate.of(2026, 12, 31));
        }
    }

    @Nested
    @DisplayName("rejections")
    class RejectionTests {

        @Test
        @DisplayName("an end date before the start date is a 400 and writes nothing")
        void endBeforeStartIsA400() throws Exception {
            postContract(employeeId, request(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 5, 31)))
                    .andExpect(status().isBadRequest());

            assertThat(countContracts(employeeId)).isZero();
        }

        @Test
        @DisplayName("a position outside the company is a 404")
        void foreignPositionIsA404() throws Exception {
            var request = new ContractRequest(
                    foreignPositionId,
                    seniorityLevel.getId(),
                    "PERMANENT",
                    new BigDecimal("1500.00"),
                    LocalDate.of(2026, 1, 1),
                    null);

            postContract(employeeId, request).andExpect(status().isNotFound());

            assertThat(countContracts(employeeId)).isZero();
        }

        @Test
        @DisplayName("an unknown seniority level is a 404")
        void unknownSeniorityLevelIsA404() throws Exception {
            var request = new ContractRequest(
                    position.getId(),
                    UUID.randomUUID(),
                    "PERMANENT",
                    new BigDecimal("1500.00"),
                    LocalDate.of(2026, 1, 1),
                    null);

            postContract(employeeId, request).andExpect(status().isNotFound());

            assertThat(countContracts(employeeId)).isZero();
        }

        @Test
        @DisplayName("an unknown contractType is a 400")
        void unknownContractTypeIsA400() throws Exception {
            var request = new ContractRequest(
                    position.getId(),
                    seniorityLevel.getId(),
                    "PART_TIME",
                    new BigDecimal("1500.00"),
                    LocalDate.of(2026, 1, 1),
                    null);

            postContract(employeeId, request).andExpect(status().isBadRequest());

            assertThat(countContracts(employeeId)).isZero();
        }

        @Test
        @DisplayName("a Terminated employee cannot open a contract (409)")
        void terminatedEmployeeIsA409() throws Exception {
            postContract(terminatedEmployeeId, request(LocalDate.of(2026, 1, 1), null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(containsString("Terminated")));

            assertThat(countContracts(terminatedEmployeeId)).isZero();
        }

        @Test
        @DisplayName("a negative monthly salary is a 400")
        void negativeSalaryIsA400() throws Exception {
            var request = new ContractRequest(
                    position.getId(),
                    seniorityLevel.getId(),
                    "PERMANENT",
                    new BigDecimal("-1.00"),
                    LocalDate.of(2026, 1, 1),
                    null);

            postContract(employeeId, request).andExpect(status().isBadRequest());

            assertThat(countContracts(employeeId)).isZero();
        }
    }

    private Employee employee(UUID companyId, Status status, String number, String email, LocalDate terminationDate) {
        return Employee.builder()
                .company(companyRepository.findById(companyId).orElseThrow())
                .employeeNumber(number)
                .firstName("Ada")
                .paternalLastName("Lovelace")
                .email(email)
                .birthDate(LocalDate.of(1990, 1, 1))
                .hireDate(LocalDate.of(2020, 1, 1))
                .terminationDate(terminationDate)
                .status(status)
                .enabled(true)
                .build();
    }

    private Status employeeStatus(String name) {
        return statusRepository
                .findByTypeNameAndStatusName("EMPLOYEE_STATUS", name)
                .orElseThrow();
    }

    private UUID companyId(String key, String rfc, String name) {
        return companyRepository
                .findByCompanyKey(key)
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey(key)
                        .companyName(name)
                        .rfc(rfc)
                        .enabled(true)
                        .build()))
                .getId();
    }

    private int countContracts(UUID employeeId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employee_contracts WHERE employee_id = ?", Integer.class, employeeId);
    }

    private UUID contractIdAtStart(LocalDate startDate) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM employee_contracts WHERE start_date = ?", UUID.class, startDate);
    }

    private LocalDate endDateAtStart(UUID employeeId, LocalDate startDate) {
        return jdbcTemplate.queryForObject(
                "SELECT end_date FROM employee_contracts WHERE employee_id = ? AND start_date = ?",
                (rs, rowNum) -> rs.getObject("end_date", LocalDate.class),
                employeeId,
                startDate);
    }
}
