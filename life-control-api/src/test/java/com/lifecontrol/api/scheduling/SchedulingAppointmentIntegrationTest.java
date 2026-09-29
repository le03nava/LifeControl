package com.lifecontrol.api.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.lifecontrol.api.scheduling.model.SchedulingSlot;
import com.lifecontrol.api.scheduling.repository.SchedulingActivityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAppointmentRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingAvailabilityRepository;
import com.lifecontrol.api.scheduling.repository.SchedulingSlotRepository;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.store.model.CompanyStore;
import com.lifecontrol.api.store.repository.CompanyStoreRepository;
import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
 * Persistence-level verification of the appointment booking lifecycle against real PostgreSQL with
 * Flyway enabled and {@code ddl-auto=validate}, so a green run proves {@code scheduling_appointments}
 * and its entity agree column for column and the {@code APPOINTMENT} status family is seeded.
 *
 * <p>Covers the end-to-end booking through HTTP with the JWT claims chain, the booking race that the
 * pessimistic slot lock is meant to serialize, the release path, and the capacity invariant read back
 * from the data.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Scheduling Appointment Integration Tests")
class SchedulingAppointmentIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String STORE_NAME = "Scheduling Appointment Test Store";
    private static final LocalDateTime SLOT_START = LocalDateTime.of(2026, 10, 5, 9, 0);

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
    private StoreChain chain;

    @BeforeEach
    void setUp() {
        // Leaf-first: appointments reference slots, activities and stores; slots and availability
        // reference activities. Leaked rows would break a later class's cleanup in this shared JVM.
        schedulingAppointmentRepository.deleteAll();
        schedulingSlotRepository.deleteAll();
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();

        seedCompanyHierarchy();
        chain = resolveChain();
        activity = schedulingActivityRepository.saveAndFlush(SchedulingActivity.builder()
                .companyStoreId(store.getId())
                .activityName("Appointment IT " + UUID.randomUUID().toString().substring(0, 8))
                .description("Appointment integration activity")
                .durationMinutes(60)
                .capacityPerSlot(1)
                .enabled(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        schedulingAppointmentRepository.deleteAll();
        schedulingSlotRepository.deleteAll();
        schedulingAvailabilityRepository.deleteAll();
        schedulingActivityRepository.deleteAll();
    }

    @Test
    @DisplayName("should seed the APPOINTMENT type with its five statuses")
    void appointmentStatusFamilyIsPresent() {
        for (var name : List.of("Scheduled", "Confirmed", "Completed", "Cancelled", "NoShow")) {
            var status = statusRepository.findByTypeNameAndStatusName("APPOINTMENT", name);
            assertThat(status).as("status %s", name).isPresent();
            assertThat(status.orElseThrow().getStatusType().getStatusTypeName()).isEqualTo("APPOINTMENT");
        }
    }

    @Test
    @DisplayName("should book end-to-end through HTTP and keep the capacity invariant")
    void booksThroughHttpAndHoldsInvariant() throws Exception {
        var slot = saveSlot(1);

        mockMvc.perform(book(slot.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotId").value(slot.getId().toString()))
                .andExpect(jsonPath("$.statusName").value("Scheduled"))
                .andExpect(jsonPath("$.startAt").value("2026-10-05T09:00:00"))
                .andExpect(jsonPath("$.endAt").value("2026-10-05T10:00:00"));

        var stored = schedulingSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(stored.getBooked()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo("Full");

        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should let exactly one of two concurrent bookings win on a capacity-1 slot")
    void concurrentBookingsSerialize() throws Exception {
        var slot = saveSlot(1);

        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var results = new BookingAttempt[2];

        var first = CompletableFuture.runAsync(() -> results[0] = performBook(slot.getId(), ready, release));
        var second = CompletableFuture.runAsync(() -> results[1] = performBook(slot.getId(), ready, release));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        assertThat(results).extracting(BookingAttempt::status).containsExactlyInAnyOrder(201, 409);

        // The database alone rejects the loser too: @Version on SchedulingSlot fails its UPDATE with an
        // optimistic-lock 409, and V17's ck_scheduling_slots_room CHECK fails the second increment with
        // a data-integrity 409. Both are generic 409s, so the status code alone cannot prove the lock
        // and the guard are load-bearing. Only the application room guard re-reads the row under
        // SELECT ... FOR UPDATE, sees booked == capacity and answers with its own distinctive message.
        // Asserting that substring makes deleting the lock (the loser then dies on @Version) or the
        // guard (the loser then dies on the CHECK) fail this test.
        var loser = Arrays.stream(results)
                .filter(attempt -> attempt.status() == 409)
                .findFirst()
                .orElseThrow();
        assertThat(loser.body())
                .as("the losing 409 must come from the application room guard, not a DB backstop")
                .contains("is not bookable: it is full");

        var stored = schedulingSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(stored.getBooked()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo("Full");
        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should answer 409 through HTTP when booking into a disabled slot")
    void disabledSlotIsConflictThroughHttp() throws Exception {
        var slot = saveSlot(1, false);

        var body = mockMvc.perform(book(slot.getId()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("disabled");
        assertThat(schedulingSlotRepository.findById(slot.getId()).orElseThrow().getBooked())
                .isZero();
        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should answer 409 through HTTP for an invalid transition and name the edge")
    void invalidTransitionIsConflictThroughHttp() throws Exception {
        var slot = saveSlot(1);
        mockMvc.perform(book(slot.getId())).andExpect(status().isCreated());
        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();
        mockMvc.perform(changeStatus(appointmentId, appointmentStatusId("Completed")))
                .andExpect(status().isOk());

        var body = mockMvc.perform(changeStatus(appointmentId, appointmentStatusId("Confirmed")))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("Invalid status transition: Completed");
        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should answer 400 through the real handler when a status belongs to another type")
    void foreignStatusTypeIsBadRequestThroughHttp() throws Exception {
        var slot = saveSlot(1);
        mockMvc.perform(book(slot.getId())).andExpect(status().isCreated());
        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();

        // A real SALES_ORDER status, so the 400 is produced by the shared StatusValidator and mapped
        // by GlobalExceptionHandler, not stubbed at the mock level.
        var foreignStatusId = statusRepository
                .findByTypeNameAndStatusName("SALES_ORDER", "Draft")
                .orElseThrow()
                .getId();

        var body = mockMvc.perform(changeStatus(appointmentId, foreignStatusId))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("APPOINTMENT");
        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should release the slot on cancel through HTTP")
    void cancelReleasesSlot() throws Exception {
        var slot = saveSlot(1);

        mockMvc.perform(book(slot.getId())).andExpect(status().isCreated());

        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();
        mockMvc.perform(delete("/api/scheduling/appointments/{id}", appointmentId)
                        .with(schedulingPrincipal()))
                .andExpect(status().isNoContent());

        var stored = schedulingSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(stored.getBooked()).isZero();
        assertThat(stored.getStatus()).isEqualTo("Available");
        assertThat(schedulingAppointmentRepository
                        .findById(appointmentId)
                        .orElseThrow()
                        .getEnabled())
                .isFalse();
        assertInvariant(slot.getId());

        // Idempotent: a second cancel must not decrement again.
        mockMvc.perform(delete("/api/scheduling/appointments/{id}", appointmentId)
                        .with(schedulingPrincipal()))
                .andExpect(status().isNoContent());
        assertThat(schedulingSlotRepository.findById(slot.getId()).orElseThrow().getBooked())
                .isZero();
    }

    @Test
    @DisplayName("should soft-delete a Completed appointment without rewriting its status or seat")
    void deleteCompletedKeepsStatusAndCapacity() throws Exception {
        var slot = saveSlot(1);
        mockMvc.perform(book(slot.getId())).andExpect(status().isCreated());
        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();

        var completedId = appointmentStatusId("Completed");
        mockMvc.perform(changeStatus(appointmentId, completedId)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/scheduling/appointments/{id}", appointmentId)
                        .with(schedulingPrincipal()))
                .andExpect(status().isNoContent());

        var storedAppointment =
                schedulingAppointmentRepository.findById(appointmentId).orElseThrow();
        assertThat(storedAppointment.getEnabled()).isFalse();
        assertThat(storedAppointment.getStatusId()).isEqualTo(completedId);

        var stored = schedulingSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(stored.getBooked()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo("Full");
        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should not decrement twice when deleting a NoShow appointment")
    void deleteNoShowDoesNotDecrementTwice() throws Exception {
        var slot = saveSlot(1);
        mockMvc.perform(book(slot.getId())).andExpect(status().isCreated());
        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();

        // The move to NoShow already released the seat, so booked is 0 before the DELETE.
        mockMvc.perform(changeStatus(appointmentId, appointmentStatusId("NoShow")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/scheduling/appointments/{id}", appointmentId)
                        .with(schedulingPrincipal()))
                .andExpect(status().isNoContent());

        var storedAppointment =
                schedulingAppointmentRepository.findById(appointmentId).orElseThrow();
        assertThat(storedAppointment.getEnabled()).isFalse();
        assertThat(schedulingSlotRepository.findById(slot.getId()).orElseThrow().getBooked())
                .isZero();
        assertInvariant(slot.getId());
    }

    @Test
    @DisplayName("should answer 409 when rescheduling a Completed appointment and leave both slots untouched")
    void rescheduleCompletedIsConflict() throws Exception {
        var source = saveSlot(1);
        mockMvc.perform(book(source.getId())).andExpect(status().isCreated());
        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();

        // Completed is the ONE status where terminality and capacity disagree: it still holds its
        // seat (D25) but is terminal in the transition map (D24). That is why this case exists: a
        // guard that asks the holding set would happily move it and rewrite what already happened.
        mockMvc.perform(changeStatus(appointmentId, appointmentStatusId("Completed")))
                .andExpect(status().isOk());

        var target = saveSlot(1, SLOT_START.plusHours(1));
        var sourceBefore = schedulingSlotRepository.findById(source.getId()).orElseThrow();
        var targetBefore = schedulingSlotRepository.findById(target.getId()).orElseThrow();

        mockMvc.perform(reschedule(appointmentId, target.getId())).andExpect(status().isConflict());

        var storedAppointment =
                schedulingAppointmentRepository.findById(appointmentId).orElseThrow();
        assertThat(storedAppointment.getSlotId()).isEqualTo(source.getId());
        assertThat(storedAppointment.getStatusId()).isEqualTo(appointmentStatusId("Completed"));

        var sourceAfter = schedulingSlotRepository.findById(source.getId()).orElseThrow();
        var targetAfter = schedulingSlotRepository.findById(target.getId()).orElseThrow();
        assertThat(sourceAfter.getBooked()).isEqualTo(sourceBefore.getBooked()).isEqualTo(1);
        assertThat(sourceAfter.getStatus()).isEqualTo(sourceBefore.getStatus()).isEqualTo("Full");
        assertThat(targetAfter.getBooked()).isEqualTo(targetBefore.getBooked()).isZero();
        assertThat(targetAfter.getStatus()).isEqualTo(targetBefore.getStatus()).isEqualTo("Available");
        assertInvariant(source.getId());
    }

    @Test
    @DisplayName("should release and cancel a Confirmed appointment on delete")
    void deleteConfirmedReleasesAndCancels() throws Exception {
        var slot = saveSlot(1);
        mockMvc.perform(book(slot.getId())).andExpect(status().isCreated());
        var appointmentId = schedulingAppointmentRepository.findAll().getFirst().getId();
        mockMvc.perform(changeStatus(appointmentId, appointmentStatusId("Confirmed")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/scheduling/appointments/{id}", appointmentId)
                        .with(schedulingPrincipal()))
                .andExpect(status().isNoContent());

        var stored = schedulingSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(stored.getBooked()).isZero();
        assertThat(stored.getStatus()).isEqualTo("Available");

        var storedAppointment =
                schedulingAppointmentRepository.findById(appointmentId).orElseThrow();
        assertThat(storedAppointment.getStatusId()).isEqualTo(appointmentStatusId("Cancelled"));
        assertThat(storedAppointment.getEnabled()).isFalse();
        assertInvariant(slot.getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private BookingAttempt performBook(UUID slotId, CountDownLatch ready, CountDownLatch release) {
        try {
            ready.countDown();
            release.await(5, TimeUnit.SECONDS);
            var result = mockMvc.perform(book(slotId)).andReturn();
            return new BookingAttempt(
                    result.getResponse().getStatus(), result.getResponse().getContentAsString());
        } catch (Exception e) {
            return new BookingAttempt(500, "");
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder book(UUID slotId) {
        return post("/api/scheduling/appointments")
                .with(schedulingPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + slotId + "\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changeStatus(
            UUID appointmentId, UUID statusId) {
        return patch("/api/scheduling/appointments/{id}/status", appointmentId)
                .with(schedulingPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statusId\":\"" + statusId + "\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reschedule(
            UUID appointmentId, UUID slotId) {
        return put("/api/scheduling/appointments/{id}", appointmentId)
                .with(schedulingPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotId\":\"" + slotId + "\"}");
    }

    private UUID appointmentStatusId(String name) {
        return statusRepository
                .findByTypeNameAndStatusName("APPOINTMENT", name)
                .orElseThrow()
                .getId();
    }

    private SchedulingSlot saveSlot(int capacity) {
        return schedulingSlotRepository.saveAndFlush(SchedulingSlot.builder()
                .activityId(activity.getId())
                .startAt(SLOT_START)
                .endAt(SLOT_START.plusMinutes(60))
                .capacity(capacity)
                .booked(0)
                .status("Available")
                .enabled(true)
                .build());
    }

    private SchedulingSlot saveSlot(int capacity, boolean enabled) {
        return schedulingSlotRepository.saveAndFlush(SchedulingSlot.builder()
                .activityId(activity.getId())
                .startAt(SLOT_START)
                .endAt(SLOT_START.plusMinutes(60))
                .capacity(capacity)
                .booked(0)
                .status("Available")
                .enabled(enabled)
                .build());
    }

    private SchedulingSlot saveSlot(int capacity, LocalDateTime startAt) {
        return schedulingSlotRepository.saveAndFlush(SchedulingSlot.builder()
                .activityId(activity.getId())
                .startAt(startAt)
                .endAt(startAt.plusMinutes(60))
                .capacity(capacity)
                .booked(0)
                .status("Available")
                .enabled(true)
                .build());
    }

    /** The invariant read back from the data: holding-status appointments equal {@code slot.booked}. */
    private void assertInvariant(UUID slotId) {
        var holdingIds = Set.of("Scheduled", "Confirmed", "Completed").stream()
                .map(name -> statusRepository
                        .findByTypeNameAndStatusName("APPOINTMENT", name)
                        .orElseThrow()
                        .getId())
                .collect(Collectors.toSet());

        var holding = schedulingAppointmentRepository.countBySlotIdAndStatusIdIn(slotId, holdingIds);
        // A soft-deleted Completed row is still a holding row: this count deliberately has no
        // `enabled` filter, so the invariant keeps holding after DELETE on a terminal appointment.
        var booked = schedulingSlotRepository.findById(slotId).orElseThrow().getBooked();
        assertThat(holding).as("holding appointments vs slot.booked").isEqualTo(booked);
    }

    /** Find-or-create the company &rarr; country &rarr; region &rarr; zone &rarr; store chain. */
    private void seedCompanyHierarchy() {
        var country = countryRepository.findByCountryCode("MX").orElseThrow();

        var company = companyRepository
                .findByCompanyKey("SCHEDULING-APPOINTMENT-KEY")
                .orElseGet(() -> companyRepository.save(Company.builder()
                        .companyKey("SCHEDULING-APPOINTMENT-KEY")
                        .companyName("Scheduling Appointment Test Company")
                        .rfc("SAP010101ABC")
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
                        .regionCode("SAP")
                        .regionName("Scheduling Appointment Region")
                        .enabled(true)
                        .build()));

        var companyZone = companyZoneRepository.findByCompanyRegionIdOrderByZoneNameAsc(region.getId()).stream()
                .findFirst()
                .orElseGet(() -> companyZoneRepository.save(CompanyZone.builder()
                        .companyRegion(region)
                        .zoneCode("SAP")
                        .zoneName("Scheduling Appointment Zone")
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

    private record BookingAttempt(int status, String body) {}
}
