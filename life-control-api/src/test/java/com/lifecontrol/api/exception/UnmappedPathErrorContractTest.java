package com.lifecontrol.api.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifecontrol.api.config.ratelimit.RateLimitProperties;
import com.lifecontrol.api.country.controller.CountryController;
import com.lifecontrol.api.country.service.CountryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * A request whose path matches no handler is a client mistake, so it answers 404 with the standard
 * error envelope instead of the catch-all 500. The slice keeps this repository's static-resource
 * handling (the same {@code /**} handler chain the running application registers), because that is
 * the component that raises the exception: without it the request would never reach the advice.
 */
@WebMvcTest(CountryController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Unmapped path — error contract")
class UnmappedPathErrorContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CountryService countryService;

    @MockitoBean
    private RateLimitProperties rateLimitProperties;

    @Test
    @DisplayName("answers 404 with the error envelope for a path no handler maps")
    void unmappedPathAnswers404WithErrorEnvelope() throws Exception {
        var result = mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/no-such-path"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
    }

    @Test
    @DisplayName("does not answer 500 for a path no handler maps")
    void unmappedPathIsNotAServerError() throws Exception {
        mockMvc.perform(get("/actuator/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("keeps the mapped 400 for a malformed request parameter on a mapped path")
    void mappedPathKeepsItsOwnStatusCode() throws Exception {
        mockMvc.perform(get("/api/countries").param("includeDisabled", "not-a-boolean"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
