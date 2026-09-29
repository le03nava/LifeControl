package com.lifecontrol.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.lifecontrol.api.scheduling.repository.SchedulingAppointmentRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence-level verification of the calendar projection and the filtered appointment list against
 * real PostgreSQL with Flyway enabled and {@code ddl-auto=validate}.
 *
 * <p>Slots are materialized through the real {@code GET /slots}, appointments are booked through the
 * real {@code POST /appointments}, and the two reads are then exercised end to end. The test proves
 * D34's grouping, D36's soft-deleted visibility through {@code booked}, the appointment list's
 * {@code userId} filter and order, and D33/G19's central promise: the calendar read creates no slot
 * row.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Scheduling Calendar Integration Tests")
class SchedulingCalendarIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String STORE_NAME = "Scheduling Calendar Test Store";
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final LocalDateTime FROM = MONDAY.atStartOfDay();
    private static final LocalDateTime TO = MONDAY.atStartOfDay().plusDays(1);

    private static final SimpleGrantedAuthority ROLE_LC_SCHEDULING = new SimpleGrantedAuthority("ROLE_lc-scheduling");

    @Autowired
    private SchedulingAppointmentRepository schedulingAppointmentRepository;

    @Autowired
    private SchedulingSlotRepository schedulingSlotRepository;

    @Autowired
    private SchedulingAvailabilityRepository schedulingAvailabilityRepository;

    @Autowired
    private SchedulingActivityRepository schedulingActivityRepository;

    @Autowired
    private StatusRepository statusRepository;

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
    private MockMvc mockMvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private CompanyStore store;
    private SchedulingActivity activity;

    @BeforeEach
    void setUp() {
        // Leaf-first: appointments reference slots, activities and stores; slots and availability
        // reference activities. Leaked rows would break a later class's cleanup in this shared JVM.
        schedulingAppointmentRepository.deleteAll();
        schedulingSlotRepository.deleteAll();
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();

        seedCompanyHierarchy();
        activity = saveActivity("Calendar IT " + UUID.randomUUID().toString().substring(0, 8));
    }

    @AfterEach
    void tearDown() {
        schedulingAppointmentRepository.deleteAll();
        schedulingSlotRepository.deleteAll();
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();
    }

    @Test
    @DisplayName("should group each appointment under its slot, keep the empty slot and never materialize")
    void calendarGroupsAppointmentsAndDoesNotMaterialize() throws Exception {
        var slots = materializeSlots();
        book(slots.get(0).getId(), "employee-1");
        book(slots.get(1).getId(), "employee-2");

        // D33/G19: the calendar read is a projection. The row count must be identical before and after.
        var before = schedulingSlotRepository.count();

        mockMvc.perform(calendarRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"))
                .andExpect(jsonPath("$[0].endAt").value("2026-09-28T10:00:00"))
                .andExpect(jsonPath("$[0].activityName").value(activity.getActivityName()))
                .andExpect(jsonPath("$[0].appointments", hasSize(1)))
                .andExpect(jsonPath("$[0].appointments[0].userId").value("employee-1"))
                .andExpect(jsonPath("$[0].appointments[0].statusName").value("Scheduled"))
                .andExpect(jsonPath("$[0].booked").value(1))
                .andExpect(jsonPath("$[0].available").value(1))
                .andExpect(jsonPath("$[1].appointments", hasSize(1)))
                .andExpect(jsonPath("$[1].appointments[0].userId").value("employee-2"))
                // The empty slot is still an entry: a grid must render it.
                .andExpect(jsonPath("$[2].appointments", hasSize(0)))
                .andExpect(jsonPath("$[2].booked").value(0))
                .andExpect(jsonPath("$[2].available").value(2));

        var after = schedulingSlotRepository.count();
        // Second and weaker than the unmaterialized-range test that follows: this range is already
        // materialized, so an idempotent re-materialization (ON CONFLICT DO NOTHING) would leave the
        // count unchanged. It still catches an unguarded insert of a duplicate row.
        assertThat(after)
                .as("the calendar read leaves the materialized row count unchanged (D33/G19)")
                .isEqualTo(before);

        // The projection's booked/available agree with the slot rows themselves.
        var storedFirst =
                schedulingSlotRepository.findById(slots.get(0).getId()).orElseThrow();
        assertThat(storedFirst.getBooked()).isEqualTo(1);
        assertThat(schedulingSlotRepository
                        .findById(slots.get(1).getId())
                        .orElseThrow()
                        .getBooked())
                .isEqualTo(1);
        assertThat(schedulingSlotRepository
                        .findById(slots.get(2).getId())
                        .orElseThrow()
                        .getBooked())
                .isZero();
    }

    @Test
    @DisplayName("should not materialize an unmaterialized range: an untouched activity stays empty and owns zero rows")
    void calendarDoesNotMaterializeAnUnmaterializedRange() throws Exception {
        // setUp seeded the activity's Monday 09:00-12:00 window, but nothing has asked GET /slots for
        // it, so the store owns no slot row. This is the proof a count-before/after cannot give: there
        // the base is non-empty and an idempotent re-materialization would leave it unchanged.
        assertThat(schedulingSlotRepository.count()).isZero();

        mockMvc.perform(calendarRequest())
                .andExpect(status().isOk())
                // (a) the untouched activity contributes no entry to the projection.
                .andExpect(jsonPath("$", hasSize(0)));

        // (b) a calendar that materialized the range would have created the activity's three Monday
        // slots. This assertion fails the moment materialization is added to the read (D33/G19).
        assertThat(
                        schedulingSlotRepository
                                .findByActivityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                                        activity.getId(), FROM, TO))
                .as("the calendar read must create no slot row for the range it projects (D33/G19)")
                .isEmpty();
        // Second, weaker check, the store-wide form the materialized tests also assert.
        assertThat(schedulingSlotRepository.count()).isZero();
    }

    @Test
    @DisplayName("should filter by userId and order the appointment list by slot start")
    void appointmentListFiltersByUserAndOrdersBySlotStart() throws Exception {
        var slots = materializeSlots();
        book(slots.get(1).getId(), "employee-2");
        book(slots.get(0).getId(), "employee-1");

        mockMvc.perform(appointmentsRequest("employee-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value("employee-1"))
                .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"));

        mockMvc.perform(appointmentsRequest(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId").value("employee-1"))
                .andExpect(jsonPath("$[0].startAt").value("2026-09-28T09:00:00"))
                .andExpect(jsonPath("$[1].userId").value("employee-2"))
                .andExpect(jsonPath("$[1].startAt").value("2026-09-28T10:00:00"));
    }

    @Test
    @DisplayName("should return only the activityId's slots, and both activities when unfiltered")
    void activityIdFilterIsAppliedEndToEnd() throws Exception {
        var otherActivity = saveActivity(
                "Calendar IT Filter " + UUID.randomUUID().toString().substring(0, 8));
        var activitySlots = materializeSlots(activity);
        var otherSlots = materializeSlots(otherActivity);

        book(activitySlots.get(0).getId(), "employee-a");
        book(otherSlots.get(0).getId(), "employee-b");

        mockMvc.perform(calendarRequestWithActivity(activity.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                // A predicate that ignored activityId would return six entries with mixed activity ids.
                .andExpect(jsonPath("$[*].activityId")
                        .value(everyItem(is(activity.getId().toString()))));

        mockMvc.perform(calendarRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(6)))
                // A predicate that pinned one activity would drop the other.
                .andExpect(jsonPath("$[*].activityId")
                        .value(hasItems(
                                activity.getId().toString(),
                                otherActivity.getId().toString())));
    }

    @Test
    @DisplayName("should break a same-start tie on the appointment id in both list reads")
    void appointmentListBreaksSameStartTieOnId() throws Exception {
        var otherActivity =
                saveActivity("Calendar IT Tie " + UUID.randomUUID().toString().substring(0, 8));
        var activitySlots = materializeSlots(activity);
        var otherSlots = materializeSlots(otherActivity);

        // Both activities' first slots start at 2026-09-28T09:00, so the two appointments share a
        // start and only the a.id tie-break can order them. Same user, so the filtered finder is
        // exercised on the same pair.
        var firstOnSameStart = book(activitySlots.get(0).getId(), "employee-tie");
        var secondOnSameStart = book(otherSlots.get(0).getId(), "employee-tie");

        // Sort the canonical string forms: PostgreSQL orders a uuid column byte-wise (unsigned),
        // which the hex text order matches, while Java's UUID.compareTo is a signed-long comparison
        // and would put a high-bit-set id first.
        var expected = new ArrayList<>(List.of(firstOnSameStart.toString(), secondOnSameStart.toString()));
        expected.sort(Comparator.naturalOrder());
        var firstId = expected.get(0);
        var secondId = expected.get(1);

        // Unfiltered finder: exactly the (startAt, id)-sorted list, not merely non-decreasing starts.
        mockMvc.perform(appointmentsRequest(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].startAt").value(everyItem(is("2026-09-28T09:00:00"))))
                .andExpect(jsonPath("$[*].id").value(contains(firstId, secondId)));

        // Filtered finder: the same tie-break on the same pair.
        mockMvc.perform(appointmentsRequest("employee-tie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].startAt").value(everyItem(is("2026-09-28T09:00:00"))))
                .andExpect(jsonPath("$[*].id").value(contains(firstId, secondId)));
    }

    @Test
    @DisplayName("should keep a soft-deleted Completed appointment visible and still counted by booked")
    void softDeletedCompletedStaysInCalendarAndBooked() throws Exception {
        var slots = materializeSlots();
        var appointmentId = book(slots.get(0).getId(), "employee-1");

        var completedStatusId = appointmentStatusId("Completed");
        mockMvc.perform(patch("/api/scheduling/appointments/{id}/status", appointmentId)
                        .with(schedulingPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statusId\":\"" + completedStatusId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/scheduling/appointments/{id}", appointmentId)
                        .with(schedulingPrincipal()))
                .andExpect(status().isNoContent());

        mockMvc.perform(calendarRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].booked").value(1))
                .andExpect(jsonPath("$[0].available").value(1))
                .andExpect(jsonPath("$[0].appointments", hasSize(1)))
                .andExpect(jsonPath("$[0].appointments[0].enabled").value(false))
                .andExpect(jsonPath("$[0].appointments[0].statusName").value("Completed"));

        // The filtered list keeps the soft-deleted row too, with its enabled flag.
        mockMvc.perform(appointmentsRequest("employee-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    @DisplayName("should keep a booked slot after its activity is soft-deleted and expose activityEnabled=false (D37)")
    void bookedSlotSurvivesAnActivitySoftDelete() throws Exception {
        var slots = materializeSlots();
        book(slots.get(0).getId(), "employee-1");

        // Soft-delete the activity through the repository, the same row mutation the DELETE endpoint
        // performs. No production path is added for the test.
        activity.setEnabled(false);
        schedulingActivityRepository.saveAndFlush(activity);

        var before = schedulingSlotRepository.count();

        mockMvc.perform(calendarRequest())
                .andExpect(status().isOk())
                // D37: the calendar renders every materialized slot of the store inside the range,
                // whatever the activity's flag says, because a booked slot survives the soft delete.
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].activityEnabled").value(false))
                .andExpect(jsonPath("$[0].booked").value(1))
                .andExpect(jsonPath("$[0].available").value(1))
                .andExpect(jsonPath("$[0].appointments", hasSize(1)))
                .andExpect(jsonPath("$[0].appointments[0].userId").value("employee-1"));

        var after = schedulingSlotRepository.count();
        assertThat(after)
                .as("the calendar read must not create a slot row (D33/G19)")
                .isEqualTo(before);

        // The projection's booked/available still agree with the surviving slot row.
        var storedFirst =
                schedulingSlotRepository.findById(slots.get(0).getId()).orElseThrow();
        assertThat(storedFirst.getBooked()).isEqualTo(1);
    }

    @Test
    @DisplayName("should reject an inverted range on both reads through the real handler")
    void invertedRangeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/scheduling/calendar")
                        .param("storeId", store.getId().toString())
                        .param("from", TO.toString())
                        .param("to", FROM.toString())
                        .with(schedulingPrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to must be after from"));

        mockMvc.perform(get("/api/scheduling/appointments")
                        .param("storeId", store.getId().toString())
                        .param("from", TO.toString())
                        .param("to", FROM.toString())
                        .with(schedulingPrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to must be after from"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private List<SchedulingSlot> materializeSlots() throws Exception {
        return materializeSlots(activity);
    }

    private List<SchedulingSlot> materializeSlots(SchedulingActivity target) throws Exception {
        mockMvc.perform(get("/api/scheduling/slots")
                        .param("activityId", target.getId().toString())
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .with(schedulingPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        return schedulingSlotRepository.findByActivityIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                target.getId(), FROM, TO);
    }

    /**
     * Saves an enabled activity of {@link #store} with a Monday 09:00-12:00 window, so GET /slots
     * materializes three one-hour slots for it. Used for the two activities of the filter and
     * same-start tie-break cases.
     */
    private SchedulingActivity saveActivity(String name) {
        var saved = schedulingActivityRepository.saveAndFlush(SchedulingActivity.builder()
                .companyStoreId(store.getId())
                .activityName(name)
                .description("Calendar integration activity")
                .durationMinutes(60)
                .capacityPerSlot(2)
                .enabled(true)
                .build());

        schedulingAvailabilityRepository.saveAndFlush(SchedulingAvailability.builder()
                .activityId(saved.getId())
                .dayOfWeek((short) 1)
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(12, 0))
                .validFrom(MONDAY)
                .validTo(MONDAY)
                .enabled(true)
                .build());

        return saved;
    }

    private UUID book(UUID slotId, String userId) throws Exception {
        mockMvc.perform(post("/api/scheduling/appointments")
                        .with(schedulingPrincipal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotId\":\"" + slotId + "\",\"userId\":\"" + userId + "\"}"))
                .andExpect(status().isCreated());

        return schedulingAppointmentRepository.findAll().stream()
                .filter(appointment -> appointment.getSlotId().equals(slotId))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private MockHttpServletRequestBuilder calendarRequest() {
        return get("/api/scheduling/calendar")
                .param("storeId", store.getId().toString())
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .with(schedulingPrincipal());
    }

    private MockHttpServletRequestBuilder calendarRequestWithActivity(UUID activityId) {
        return calendarRequest().param("activityId", activityId.toString());
    }

    private MockHttpServletRequestBuilder appointmentsRequest(String userId) {
        var builder = get("/api/scheduling/appointments")
                .param("storeId", store.getId().toString())
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .with(schedulingPrincipal());
        return userId == null ? builder : builder.param("userId", userId);
    }

    private UUID appointmentStatusId(String name) {
        return statusRepository
                .findByTypeNameAndStatusName("APPOINTMENT", name)
                .orElseThrow()
                .getId();
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain. */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("SCHEDULING-CALENDAR-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("SCHEDULING-CALENDAR-KEY")
                        .companyName("Scheduling Calendar Test Company")
                        .rfc("SCA010101ABC")
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
                        .regionCode("SCA")
                        .regionName("Scheduling Calendar Region")
                        .enabled(true)
                        .build()));

        var companyZone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SCA")
                        .zoneName("Scheduling Calendar Zone")
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
        var chain = resolveChain();
        return jwt().authorities(ROLE_LC_SCHEDULING)
                .jwt(builder -> builder.claim("preferred_username", "scheduler")
                        .claim("company_id", chain.companyId().toString())
                        .claim("company_country_id", chain.companyCountryId().toString())
                        .claim("company_region_id", chain.regionId().toString())
                        .claim("company_zone_id", chain.zoneId().toString())
                        .claim("company_store_id", chain.storeId().toString()));
    }

    /** The real chain behind {@link #store}, resolved inside a transaction so the lazies are readable. */
    private StoreChain resolveChain() {
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
