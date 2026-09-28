package com.lifecontrol.api.scheduling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import com.lifecontrol.api.exception.VersionPreconditionException;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityRequest;
import com.lifecontrol.api.scheduling.dto.SchedulingActivityResponse;
import com.lifecontrol.api.scheduling.exception.SchedulingActivityNotFoundException;
import com.lifecontrol.api.scheduling.service.SchedulingActivityService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Controller-level verification of the scheduling activity endpoints: paths, serialized contract and errors. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulingActivityController Tests")
class SchedulingActivityControllerTest {

    private static final String BASE_URL = "/api/scheduling/activities";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private SchedulingActivityService schedulingActivityService;

    @InjectMocks
    private SchedulingActivityController controller;

    private UUID activityId;
    private UUID storeId;
    private SchedulingActivityResponse response;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        activityId = UUID.randomUUID();
        storeId = UUID.randomUUID();
        response = new SchedulingActivityResponse(
                activityId,
                storeId,
                "employee-1",
                "Yoga",
                "A 60 minute yoga class",
                60,
                8,
                true,
                0L,
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private SchedulingActivityRequest validRequest() {
        return new SchedulingActivityRequest(
                storeId, "employee-1", "Yoga", "A 60 minute yoga class", 60, 8, true, null);
    }

    // ─── GET list ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllActivitiesTests {

        @Test
        @DisplayName("should return 200 with the paged response shape")
        void returns200WithPage() throws Exception {
            var page = new PageImpl<>(List.of(response), PageRequest.of(0, 12), 1);
            when(schedulingActivityService.getActivities(eq(storeId), eq(false), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get(BASE_URL).param("storeId", storeId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(activityId.toString()))
                    .andExpect(jsonPath("$.content[0].activityName").value("Yoga"))
                    .andExpect(jsonPath("$.content[0].companyStoreId").value(storeId.toString()))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should forward storeId, includeDisabled, page and size to the service")
        void forwardsQueryParameters() throws Exception {
            var page = new PageImpl<>(List.of(response), PageRequest.of(1, 5), 6);
            when(schedulingActivityService.getActivities(eq(storeId), eq(true), any(Pageable.class)))
                    .thenReturn(page);

            mockMvc.perform(get(BASE_URL)
                            .param("storeId", storeId.toString())
                            .param("includeDisabled", "true")
                            .param("page", "1")
                            .param("size", "5"))
                    .andExpect(status().isOk());

            var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(schedulingActivityService).getActivities(eq(storeId), eq(true), pageableCaptor.capture());
            assertEquals(1, pageableCaptor.getValue().getPageNumber());
            assertEquals(5, pageableCaptor.getValue().getPageSize());
        }
    }

    // ─── GET by id ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetActivityByIdTests {

        @Test
        @DisplayName("should return 200 with the activity")
        void returns200() throws Exception {
            when(schedulingActivityService.getActivity(activityId)).thenReturn(response);

            mockMvc.perform(get(BASE_URL + "/{id}", activityId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(activityId.toString()))
                    .andExpect(jsonPath("$.activityName").value("Yoga"))
                    .andExpect(jsonPath("$.durationMinutes").value(60))
                    .andExpect(jsonPath("$.capacityPerSlot").value(8))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 404 when the activity is unknown")
        void returns404() throws Exception {
            when(schedulingActivityService.getActivity(activityId))
                    .thenThrow(new SchedulingActivityNotFoundException(activityId));

            mockMvc.perform(get(BASE_URL + "/{id}", activityId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Scheduling activity not found with id: " + activityId));
        }
    }

    // ─── POST create ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateActivityTests {

        @Test
        @DisplayName("should return 201 Created with the created activity")
        void returns201() throws Exception {
            when(schedulingActivityService.create(any(SchedulingActivityRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(activityId.toString()))
                    .andExpect(jsonPath("$.activityName").value("Yoga"))
                    .andExpect(jsonPath("$.companyStoreId").value(storeId.toString()));
        }

        @Test
        @DisplayName("should return 400 when the body fails validation")
        void returns400WhenBodyInvalid() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.activityName").exists())
                    .andExpect(jsonPath("$.errors.durationMinutes").exists())
                    .andExpect(jsonPath("$.errors.capacityPerSlot").exists());
        }
    }

    // ─── PUT update ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateActivityTests {

        @Test
        @DisplayName("should return 200 with the updated activity")
        void returns200() throws Exception {
            when(schedulingActivityService.update(eq(activityId), any(SchedulingActivityRequest.class)))
                    .thenReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(activityId.toString()))
                    .andExpect(jsonPath("$.activityName").value("Yoga"));
        }

        @Test
        @DisplayName("should return 412 when the version precondition fails")
        void returns412() throws Exception {
            when(schedulingActivityService.update(eq(activityId), any(SchedulingActivityRequest.class)))
                    .thenThrow(new VersionPreconditionException(
                            "The scheduling activity conflicts with the current server state; reload and try again"));

            var stale = new SchedulingActivityRequest(
                    null, "employee-1", "Yoga", "A 60 minute yoga class", 60, 8, null, 7L);

            mockMvc.perform(put(BASE_URL + "/{id}", activityId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stale)))
                    .andExpect(status().isPreconditionFailed())
                    .andExpect(
                            jsonPath("$.message")
                                    .value(
                                            "The scheduling activity conflicts with the current server state; reload and try again"));
        }
    }

    // ─── PATCH enable ───────────────────────────────────────────────────

    @Nested
    @DisplayName("PATCH " + BASE_URL + "/{id}/enable")
    class EnableActivityTests {

        @Test
        @DisplayName("should return 200 with the re-enabled activity")
        void returns200() throws Exception {
            when(schedulingActivityService.enable(activityId)).thenReturn(response);

            mockMvc.perform(patch(BASE_URL + "/{id}/enable", activityId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(activityId.toString()))
                    .andExpect(jsonPath("$.enabled").value(true));
        }
    }

    // ─── DELETE ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteActivityTests {

        @Test
        @DisplayName("should return 204 No Content and delegate the soft delete")
        void returns204() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/{id}", activityId)).andExpect(status().isNoContent());

            verify(schedulingActivityService).delete(activityId);
        }
    }
}
