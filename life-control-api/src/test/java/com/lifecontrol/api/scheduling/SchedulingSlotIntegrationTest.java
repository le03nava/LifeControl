package com.lifecontrol.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.model.CompanyCountry;
import com.lifecontrol.api.company.model.CompanyRegion;
import com.lifecontrol.api.company.model.CompanyZone;
import com.lifecontrol.api.company.repository.CompanyCountryRepository;
import com.lifecontrol.api.company.repository.CompanyRegionRepository;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.company.repository.CompanyZoneRepository;
import com.lifecontrol.api.country.repository.CountryRepository;
import com.lifecontrol.api.scheduling.model.SchedulingActivity;
import com.lifecontrol.api.scheduling.model.SchedulingAvailability;
import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
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
 * Persistence-level verification of the slot instance against real PostgreSQL with Flyway enabled and
 * {@code ddl-auto=validate}, so a green run proves {@code scheduling_slots} and
 * {@link SchedulingSlot} agree column for column — the mapping the schema carried without an entity
 * until now (G13).
 *
 * <p>Covers the idempotent {@code ON CONFLICT DO NOTHING} upsert against the real
 * {@code UNIQUE(activity_id, start_at)} key, the {@code LocalDateTime} round trip, the ISO weekday
 * agreement end to end, and the reconciliation that leaves booked slots alone.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Scheduling Slot Integration Tests")
class SchedulingSlotIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String STORE_NAME = "Scheduling Slot Test Store";
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 27);
    private static final LocalDateTime FROM = MONDAY.atStartOfDay();
    private static final LocalDateTime TO = MONDAY.atTime(0, 0).plusDays(1);

    private static final SimpleGrantedAuthority ROLE_LC_SCHEDULING = new SimpleGrantedAuthority("ROLE_lc-scheduling");

    @Autowired
    private SchedulingSlotRepository schedulingSlotRepository;

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

    private CompanyStore store;
    private SchedulingActivity activity;

    @BeforeEach
    void setUp() {
        // scheduling_slots and scheduling_availability both reference scheduling_activities with no
        // cascade, so clean leaf-first before and after; leaked rows would break the deleteAll() of a
        // later class in this shared JVM.
        schedulingSlotRepository.deleteAll();
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();

        seedCompanyHierarchy();
        activity = schedulingActivityRepository.saveAndFlush(SchedulingActivity.builder()
                .companyStoreId(store.getId())
                .activityName("Slot IT " + UUID.randomUUID().toString().substring(0, 8))
                .description("Slot integration activity")
                .durationMinutes(60)
                .capacityPerSlot(4)
                .enabled(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        schedulingSlotRepository.deleteAll();
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();
    }

    @Test
    @DisplayName("should materialize a Monday range and return exact LocalDateTime values")
    void materializesRangeWithExactDateTimes() throws Exception {
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(11, 0));

        mockMvc.perform(getSlots(FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"))
                .andExpect(jsonPath("$[0].endAt").value("2026-09-28T10:00:00"))
                .andExpect(jsonPath("$[0].capacity").value(4))
                .andExpect(jsonPath("$[0].booked").value(0))
                .andExpect(jsonPath("$[0].available").value(4))
                .andExpect(jsonPath("$[0].status").value("Available"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].startAt").value("2026-09-28T10:00:00"))
                .andExpect(jsonPath("$[1].endAt").value("2026-09-28T11:00:00"));

        var stored = range(FROM, TO);
        assertThat(stored).hasSize(2);
        assertThat(stored.getFirst().getStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 28, 9, 0));
        assertThat(stored.getFirst().getEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 28, 10, 0));
        assertThat(stored.get(1).getStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 28, 10, 0));
    }

    @Test
    @DisplayName("should materialize the same range twice without creating duplicates")
    void secondMaterializationIsIdempotent() throws Exception {
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(11, 0));

        mockMvc.perform(getSlots(FROM, TO)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        // The second read hits UNIQUE(activity_id, start_at); ON CONFLICT DO NOTHING must swallow the
        // conflict instead of failing the request or duplicating the row.
        mockMvc.perform(getSlots(FROM, TO)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));

        assertThat(schedulingSlotRepository.count()).isEqualTo(2);
        var stored = range(FROM, TO);
        assertThat(stored).extracting(SchedulingSlot::getStartAt).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("should delete unbooked slots on availability replace and keep the booked one")
    void reconciliationKeepsBookedSlots() throws Exception {
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(11, 0));
        mockMvc.perform(getSlots(FROM, TO)).andExpect(status().isOk());

        var created = range(FROM, TO);
        var booked = created.get(1);
        booked.setBooked(2);
        schedulingSlotRepository.saveAndFlush(booked);

        // The replacement removes the whole template, so the windows that produced the slots are
        // gone; the booked row must survive it because an appointment depends on it.
        mockMvc.perform(put("/api/scheduling/activities/{activityId}/availability", activity.getId())
                        .with(schedulingPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windows\":[]}"))
                .andExpect(status().isOk());

        var remaining = range(FROM, TO);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.getFirst().getId()).isEqualTo(booked.getId());
        assertThat(remaining.getFirst().getBooked()).isEqualTo(2);
    }

    @Test
    @DisplayName("should land a dayOfWeek=1 window on Mondays and no other weekday")
    void isoDayConventionLandsOnMonday() throws Exception {
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(11, 0), SUNDAY, LocalDate.of(2026, 10, 5));

        // The range spans Sunday and Monday: if the weekday convention were off by one, the window
        // would land on the Sunday instead.
        mockMvc.perform(getSlots(SUNDAY.atStartOfDay(), MONDAY.atTime(0, 0).plusDays(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"));

        var stored = range(SUNDAY.atStartOfDay(), MONDAY.atTime(0, 0).plusDays(1));
        assertThat(stored).isNotEmpty();
        assertThat(stored).allSatisfy(slot -> {
            assertThat(slot.getStartAt().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(slot.getStartAt().toLocalDate()).isEqualTo(MONDAY);
        });
    }

    @Test
    @DisplayName("should keep an existing slot's booked count when the same range is materialized again")
    void reMaterializationNeverTouchesBooked() throws Exception {
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(11, 0));
        mockMvc.perform(getSlots(FROM, TO)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));

        var before = range(FROM, TO);
        var booked = before.get(1);
        booked.setBooked(3);
        schedulingSlotRepository.saveAndFlush(booked);

        // The same range again hits ON CONFLICT DO NOTHING on UNIQUE(activity_id, start_at); the
        // existing rows must be left exactly as they were, booked included.
        mockMvc.perform(getSlots(FROM, TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].booked").value(3))
                .andExpect(jsonPath("$[1].available").value(1));

        var after = range(FROM, TO);
        assertThat(after).hasSize(2);
        assertThat(after.get(1).getId()).isEqualTo(booked.getId());
        assertThat(after.get(1).getBooked()).isEqualTo(3);
        assertThat(after.get(1).getCapacity()).isEqualTo(4);
        assertThat(after.get(0).getId()).isEqualTo(before.get(0).getId());
        assertThat(schedulingSlotRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("should materialize no slot for a Monday after the window's validTo")
    void validToUpperBoundIsHonoured() throws Exception {
        // The window is valid only on the first Monday; the requested range also contains the next
        // Monday, which must contribute nothing.
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(10, 0), MONDAY, MONDAY);

        var nextMonday = MONDAY.plusWeeks(1);
        mockMvc.perform(getSlots(MONDAY.atStartOfDay(), nextMonday.atTime(0, 0).plusDays(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"));

        var stored = range(MONDAY.atStartOfDay(), nextMonday.atTime(0, 0).plusDays(1));
        assertThat(stored).extracting(SchedulingSlot::getStartAt).containsExactly(MONDAY.atTime(9, 0));
    }

    @Test
    @DisplayName("should reject an inverted range and a span over 90 days end to end, and accept exactly 90")
    void rangeGuardReachesHttp() throws Exception {
        saveWindow(1, LocalTime.of(9, 0), LocalTime.of(10, 0));

        mockMvc.perform(get("/api/scheduling/slots")
                        .param("activityId", activity.getId().toString())
                        .param("from", MONDAY.atTime(11, 0).toString())
                        .param("to", MONDAY.atTime(9, 0).toString())
                        .with(schedulingPrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to must be after from"));

        mockMvc.perform(get("/api/scheduling/slots")
                        .param("activityId", activity.getId().toString())
                        .param("from", MONDAY.atStartOfDay().toString())
                        .param("to", MONDAY.atStartOfDay().plusDays(91).toString())
                        .with(schedulingPrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("the range must not exceed 90 days"));

        mockMvc.perform(get("/api/scheduling/slots")
                        .param("activityId", activity.getId().toString())
                        .param("from", MONDAY.atStartOfDay().toString())
                        .param("to", MONDAY.atStartOfDay().plusDays(90).toString())
                        .with(schedulingPrincipal()))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getSlots(
            LocalDateTime from, LocalDateTime to) {
        return get("/api/scheduling/slots")
                .param("activityId", activity.getId().toString())
                .param("from", from.toString())
                .param("to", to.toString())
                .with(schedulingPrincipal());
    }

    private List<SchedulingSlot> range(LocalDateTime from, LocalDateTime to) {
        return schedulingSlotRepository.findByActivityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                activity.getId(), from, to);
    }

    private void saveWindow(int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        saveWindow(dayOfWeek, startTime, endTime, MONDAY, LocalDate.of(2026, 12, 31));
    }

    private void saveWindow(
            int dayOfWeek, LocalTime startTime, LocalTime endTime, LocalDate validFrom, LocalDate validTo) {
        schedulingAvailabilityRepository.saveAndFlush(SchedulingAvailability.builder()
                .activityId(activity.getId())
                .dayOfWeek((short) dayOfWeek)
                .startTime(startTime)
                .endTime(endTime)
                .validFrom(validFrom)
                .validTo(validTo)
                .enabled(true)
                .build());
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

    private record StoreChain(UUID companyId, UUID companyCountryId, UUID regionId, UUID zoneId, UUID storeId) {}
}
