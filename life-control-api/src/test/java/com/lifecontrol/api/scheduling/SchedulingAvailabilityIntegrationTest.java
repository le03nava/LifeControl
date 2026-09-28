package com.lifecontrol.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingAvailabilityWindowRequest;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence-level verification of the availability template against real PostgreSQL with Flyway
 * enabled and {@code ddl-auto=validate}, so a green run proves {@code V17__scheduling_availability_slots.sql}
 * and {@link SchedulingAvailability} agree column for column — including the first {@code TIME} and
 * {@code DATE} columns in the schema, which this is the only test to round-trip.
 *
 * <p>Covers the PUT-then-GET round trip of real windows through the HTTP surface with a store-scoped
 * principal, the {@code (activity_id, day_of_week, start_time)} unique key and the two CHECKs the
 * entity can violate (weekday below 1 and an inverted window).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Scheduling Availability Integration Tests")
class SchedulingAvailabilityIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String STORE_NAME = "Scheduling Availability Test Store";

    private static final SimpleGrantedAuthority ROLE_LC_SCHEDULING = new SimpleGrantedAuthority("ROLE_lc-scheduling");

    @Autowired
    private SchedulingAvailabilityRepository schedulingAvailabilityRepository;

    @Autowired
    private SchedulingActivityRepository schedulingActivityRepository;

    @Autowired
    private CompanyStoreRepository companyStoreRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private CompanyCountryRepository companyCountryRepository;

    @Autowired
    private CompanyRegionRepository companyRegionRepository;

    @Autowired
    private CompanyZoneRepository companyZoneRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private CompanyStore store;
    private SchedulingActivity activity;

    @BeforeEach
    void setUp() {
        // scheduling_availability references scheduling_activities with no cascade, so clean
        // leaf-first before and after; leaked rows would break the deleteAll() of later classes in
        // this shared JVM.
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();

        seedCompanyHierarchy();
        activity = schedulingActivityRepository.saveAndFlush(SchedulingActivity.builder()
                .companyStoreId(store.getId())
                .activityName("Availability IT " + UUID.randomUUID().toString().substring(0, 8))
                .description("Availability integration activity")
                .durationMinutes(60)
                .capacityPerSlot(4)
                .enabled(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();
    }

    @Test
    @DisplayName("should PUT an unsorted body and return the same window order as the following GET")
    void putThenGetRoundTripsTimesAndDatesExactly() throws Exception {
        // Deliberately unsorted: weekday 3 before 1, and 14:00 before 09:00 on weekday 1. The
        // response must come back ordered by day then start time, matching the reloaded GET.
        var request = new SchedulingAvailabilityRequest(List.of(
                new SchedulingAvailabilityWindowRequest(
                        3,
                        LocalTime.of(14, 30),
                        LocalTime.of(18, 0),
                        LocalDate.of(2026, 9, 28),
                        LocalDate.of(2026, 12, 31)),
                new SchedulingAvailabilityWindowRequest(
                        1,
                        LocalTime.of(9, 0),
                        LocalTime.of(13, 0),
                        LocalDate.of(2026, 9, 28),
                        LocalDate.of(2026, 12, 31)),
                new SchedulingAvailabilityWindowRequest(
                        1,
                        LocalTime.of(14, 0),
                        LocalTime.of(16, 0),
                        LocalDate.of(2026, 9, 28),
                        LocalDate.of(2026, 12, 31))));

        // The write runs through the real @PreAuthorize, the service's delete-then-insert and the
        // store-scoped walk-up, not through the admin short-circuit. Its response is the ordered
        // read-back, not the request order.
        var putResult = mockMvc.perform(put("/api/scheduling/activities/{activityId}/availability", activity.getId())
                        .with(schedulingPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(activity.getId().toString()))
                .andExpect(jsonPath("$.windows", hasSize(3)))
                .andExpect(jsonPath("$.windows[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.windows[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.windows[0].endTime").value("13:00:00"))
                .andExpect(jsonPath("$.windows[0].validFrom").value("2026-09-28"))
                .andExpect(jsonPath("$.windows[0].validTo").value("2026-12-31"))
                .andExpect(jsonPath("$.windows[1].dayOfWeek").value(1))
                .andExpect(jsonPath("$.windows[1].startTime").value("14:00:00"))
                .andExpect(jsonPath("$.windows[2].dayOfWeek").value(3))
                .andExpect(jsonPath("$.windows[2].startTime").value("14:30:00"))
                .andExpect(jsonPath("$.windows[2].endTime").value("18:00:00"))
                .andReturn();

        var getResult = mockMvc.perform(get("/api/scheduling/activities/{activityId}/availability", activity.getId())
                        .with(schedulingPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows", hasSize(3)))
                .andReturn();

        // One contract, one order: the PUT response is the exact array the GET returns.
        var putWindows = objectMapper
                .readTree(putResult.getResponse().getContentAsString())
                .get("windows");
        var getWindows = objectMapper
                .readTree(getResult.getResponse().getContentAsString())
                .get("windows");
        assertThat(putWindows).isEqualTo(getWindows);

        // The database round trip is asserted too: the JSON could be right while the TIME/DATE
        // mapping truncated something on the way in.
        var stored = schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(activity.getId());
        assertThat(stored).hasSize(3);
        assertThat(stored.getFirst().getDayOfWeek()).isEqualTo((short) 1);
        assertThat(stored.getFirst().getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(stored.getFirst().getEndTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(stored.getFirst().getValidFrom()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(stored.getFirst().getValidTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(stored.get(1).getStartTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(stored.get(2).getDayOfWeek()).isEqualTo((short) 3);
        assertThat(stored).allSatisfy(window -> assertThat(window.getEnabled()).isTrue());
    }

    @Test
    @DisplayName("should accept a second PUT whose keys the first PUT already created")
    void secondPutOverExistingKeysReplacesTheSet() throws Exception {
        mockMvc.perform(put("/api/scheduling/activities/{activityId}/availability", activity.getId())
                        .with(schedulingPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchedulingAvailabilityRequest(List.of(
                                new SchedulingAvailabilityWindowRequest(
                                        1,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(13, 0),
                                        LocalDate.of(2026, 9, 28),
                                        LocalDate.of(2026, 12, 31)),
                                new SchedulingAvailabilityWindowRequest(
                                        3,
                                        LocalTime.of(14, 30),
                                        LocalTime.of(18, 0),
                                        LocalDate.of(2026, 9, 28),
                                        LocalDate.of(2026, 12, 31)))))))
                .andExpect(status().isOk());

        // The second PUT reuses the same (activity_id, day_of_week, start_time) keys with different
        // end times. If the delete did not reach the database before the inserts, the re-insert
        // would collide with UNIQUE(activity_id, day_of_week, start_time) and this PUT would fail.
        mockMvc.perform(put("/api/scheduling/activities/{activityId}/availability", activity.getId())
                        .with(schedulingPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SchedulingAvailabilityRequest(List.of(
                                new SchedulingAvailabilityWindowRequest(
                                        1,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(12, 0),
                                        LocalDate.of(2026, 9, 28),
                                        LocalDate.of(2026, 12, 31)),
                                new SchedulingAvailabilityWindowRequest(
                                        3,
                                        LocalTime.of(14, 30),
                                        LocalTime.of(17, 0),
                                        LocalDate.of(2026, 9, 28),
                                        LocalDate.of(2026, 12, 31)))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows", hasSize(2)))
                .andExpect(jsonPath("$.windows[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.windows[0].endTime").value("12:00:00"))
                .andExpect(jsonPath("$.windows[1].startTime").value("14:30:00"))
                .andExpect(jsonPath("$.windows[1].endTime").value("17:00:00"));

        var stored = schedulingAvailabilityRepository.findByActivityIdOrderByDayOfWeekAscStartTimeAsc(activity.getId());
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).getEndTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(stored.get(1).getEndTime()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    @DisplayName("should reject a duplicate (activity_id, day_of_week, start_time) at the database level")
    void duplicateActivityDayStartIsRejected() {
        schedulingAvailabilityRepository.saveAndFlush(storedWindow(1, LocalTime.of(9, 0), LocalTime.of(13, 0)));

        assertThatThrownBy(() -> schedulingAvailabilityRepository.saveAndFlush(
                        storedWindow(1, LocalTime.of(9, 0), LocalTime.of(13, 0))))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        .as("database unique violation for the duplicate window")
                        .isTrue());

        assertThat(schedulingAvailabilityRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject day_of_week = 0 through the CHECK constraint")
    void dayOfWeekBelowRangeIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> schedulingAvailabilityRepository.saveAndFlush(
                        storedWindow(0, LocalTime.of(9, 0), LocalTime.of(13, 0))))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        .as("CHECK rejects day_of_week = 0")
                        .isTrue());

        assertThat(schedulingAvailabilityRepository.count()).isZero();
    }

    @Test
    @DisplayName("should reject an inverted window through the CHECK constraint")
    void invertedWindowIsRejectedByCheckConstraint() {
        assertThatThrownBy(() -> schedulingAvailabilityRepository.saveAndFlush(
                        storedWindow(1, LocalTime.of(13, 0), LocalTime.of(9, 0))))
                .satisfies(thrown -> assertThat(hasConstraintViolationCause(thrown))
                        .as("CHECK rejects end_time <= start_time")
                        .isTrue());

        assertThat(schedulingAvailabilityRepository.count()).isZero();
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain. */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("SCHEDULING-AVAILABILITY-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("SCHEDULING-AVAILABILITY-KEY")
                        .companyName("Scheduling Availability Test Company")
                        .rfc("SAT010101ABC")
                        .enabled(true)
                        .build()));

        var companyCountry = companyCountryRepository
                .findByCompanyIdAndCountryId(company.getId(), country.getId())
                .orElseGet(() -> companyCountryRepository.save(CompanyCountry.builder()
                        .company(company)
                        .country(country)
                        .build()));

        var region = companyRegionRepository.findByCompanyCountryIdOrderByRegionNameAsc(companyCountry.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyRegionRepository.save(CompanyRegion.builder()
                        .companyCountry(companyCountry)
                        .regionCode("SAT")
                        .regionName("Scheduling Availability Region")
                        .enabled(true)
                        .build()));

        var companyZone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SAT")
                        .zoneName("Scheduling Availability Zone")
                        .enabled(true)
                        .build()));

        store = companyStoreRepository.findByCompanyZoneId(companyZone.getId()).stream()
                .filter(candidate -> STORE_NAME.equals(candidate.getStoreName()))
                .findFirst()
                .orElseGet(() -> companyStoreRepository.save(CompanyStore.builder()
                        .companyZone(companyZone)
                        .storeName(STORE_NAME)
                        .enabled(true)
                        .build()));
    }

    private SchedulingAvailability storedWindow(int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        return SchedulingAvailability.builder()
                .activityId(activity.getId())
                .dayOfWeek((short) dayOfWeek)
                .startTime(startTime)
                .endTime(endTime)
                .validFrom(LocalDate.of(2026, 9, 28))
                .validTo(LocalDate.of(2026, 12, 31))
                .enabled(true)
                .build();
    }

    /**
     * A store-scoped scheduling principal: the role passes the controller's {@code @PreAuthorize} and
     * the claims pass the service's real {@code verifyCompanyStoreAccess} walk-up.
     */
    private RequestPostProcessor schedulingPrincipal() {
        var chain = storeChain();
        return jwt().authorities(ROLE_LC_SCHEDULING)
                .jwt(builder -> builder.claim("preferred_username", "scheduler")
                        .claim("company_id", chain.companyId().toString())
                        .claim("company_country_id", chain.companyCountryId().toString())
                        .claim("company_region_id", chain.regionId().toString())
                        .claim("company_zone_id", chain.zoneId().toString())
                        .claim("company_store_id", chain.storeId().toString()));
    }

    /** The real chain behind {@link #store}, resolved inside a transaction so the lazies are readable. */
    private StoreChain storeChain() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var s = companyStoreRepository.findById(store.getId()).orElseThrow();
            var z = s.getCompanyZone();
            var r = z.getCompanyRegion();
            var c = r.getCompanyCountry();
            return new StoreChain(c.getCompany().getId(), c.getId(), r.getId(), z.getId(), s.getId());
        });
    }

    private static boolean hasConstraintViolationCause(Throwable thrown) {
        for (var cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
