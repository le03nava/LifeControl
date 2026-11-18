package com.lifecontrol.api.store.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the removal of the three nested single-read endpoints (#898, S3), so a later change cannot
 * silently re-add them.
 *
 * <p>Each of {@link StoreAreaController}, {@link StoreZoneController} and
 * {@link StoreLocationController} used to serve a {@code GET /{id}} beside its list, marked
 * {@code @Deprecated}, whose javadoc said the flat route was canonical and that the nested one was
 * «scheduled for removal in a later slice». What is gone is that <strong>operation</strong>, and the
 * two places that would notice a regression are asserted below.</p>
 *
 * <p><strong>The request half answers 405, never 404, and that is the fact rather than a weakened
 * assertion.</strong> The same nested path keeps {@code PUT /{id}}, {@code DELETE /{id}} and
 * {@code PATCH /{id}/enable}, so the route still exists for those verbs and Spring refuses the verb
 * that is gone with {@code 405 Method Not Allowed}. A 404 is only reachable by deleting the whole
 * nested controller, which is the issue's own non-goal, so asserting 404 here would pin behaviour the
 * tree cannot hold.</p>
 *
 * <p>The document half is the other thing a 405 cannot say: {@code paths[<nested>]} stays published —
 * for the verbs that remain — while its {@code get} operation does not, so nothing can be generated
 * into a client. Each case carries two positive controls: the nested path still publishes its
 * {@code put}, and the canonical flat read is still published. Without them a document that lost the
 * whole controller would make every absence assertion pass while proving the opposite of what it
 * claims. Filters are disabled for the request half for the reason {@code
 * UnmappedPathErrorContractTest} documents — a routing answer is not a security answer, and with the
 * chain on every request, mapped or not, an anonymous caller would be refused before routing saw it,
 * so the verb check this test is about would never run.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@DisplayName("Store tree — the nested single-read removal")
class StoreTreeSingleReadRemovalContractTest {

    /** {@code springdoc.api-docs.path} on {@code application.properties}. */
    private static final String DOC_PATH = "/api-docs";

    private static final String AREAS =
            "/api/companies/{companyId}/countries/{companyCountryId}/regions/{regionId}/zones/{zoneId}"
                    + "/stores/{storeId}/areas";
    private static final String ZONES = AREAS + "/{areaId}/store-zones";
    private static final String LOCATIONS = ZONES + "/{storeZoneId}/store-locations";

    /**
     * The paths each controller used to answer a single read on. The path stays mapped for the other
     * verbs; the {@code get} operation on it must never come back.
     */
    private static final String NESTED_AREA_READ = AREAS + "/{areaId}";

    private static final String NESTED_ZONE_READ = ZONES + "/{storeZoneId}";
    private static final String NESTED_LOCATION_READ = LOCATIONS + "/{storeLocationId}";

    /** The canonical reads that stay, one per resource, so the removal did not take the survivor too. */
    private static final String FLAT_AREA_READ = "/api/store-areas/{areaId}";

    private static final String FLAT_ZONE_READ = "/api/store-zones/{storeZoneId}";
    private static final String FLAT_LOCATION_READ = "/api/store-locations/{storeLocationId}";

    private static final UUID ANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("answers 405 on the nested area read")
    void nestedAreaReadAnswers405() throws Exception {
        mockMvc.perform(get(concrete(NESTED_AREA_READ)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("answers 405 on the nested zone read")
    void nestedZoneReadAnswers405() throws Exception {
        mockMvc.perform(get(concrete(NESTED_ZONE_READ)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("answers 405 on the nested location read")
    void nestedLocationReadAnswers405() throws Exception {
        mockMvc.perform(get(concrete(NESTED_LOCATION_READ)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("publishes the nested area path without its get and keeps the flat read")
    void nestedAreaReadIsNotPublished() throws Exception {
        var paths = readOpenApiPaths();
        assertOperationAbsent(paths, NESTED_AREA_READ, "get");
        assertOperationPublished(paths, NESTED_AREA_READ, "put");
        assertPublished(paths, FLAT_AREA_READ);
    }

    @Test
    @DisplayName("publishes the nested zone path without its get and keeps the flat read")
    void nestedZoneReadIsNotPublished() throws Exception {
        var paths = readOpenApiPaths();
        assertOperationAbsent(paths, NESTED_ZONE_READ, "get");
        assertOperationPublished(paths, NESTED_ZONE_READ, "put");
        assertPublished(paths, FLAT_ZONE_READ);
    }

    @Test
    @DisplayName("publishes the nested location path without its get and keeps the flat read")
    void nestedLocationReadIsNotPublished() throws Exception {
        var paths = readOpenApiPaths();
        assertOperationAbsent(paths, NESTED_LOCATION_READ, "get");
        assertOperationPublished(paths, NESTED_LOCATION_READ, "put");
        assertPublished(paths, FLAT_LOCATION_READ);
    }

    private void assertOperationAbsent(JsonNode paths, String path, String verb) {
        assertThat(paths.path(path).path(verb).isMissingNode())
                .as("%s %s is not published: the nested single read was removed", verb, path)
                .isTrue();
    }

    private void assertOperationPublished(JsonNode paths, String path, String verb) {
        assertThat(paths.path(path).path(verb).isMissingNode())
                .as("%s %s is still published: the removal took more than the single read", verb, path)
                .isFalse();
    }

    private void assertPublished(JsonNode paths, String... expected) {
        for (var path : expected) {
            assertThat(paths.path(path).isMissingNode())
                    .as("%s is still published: the removal took more than the single read", path)
                    .isFalse();
        }
    }

    private JsonNode readOpenApiPaths() throws Exception {
        var body = mockMvc.perform(get(DOC_PATH))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var paths = objectMapper.readTree(body).path("paths");
        assertThat(paths.isMissingNode()).as("the OpenAPI document lists paths").isFalse();
        return paths;
    }

    /** The document and the mappings spell variables as {@code {name}}; a request needs values. */
    private static String concrete(String template) {
        return template.replaceAll("\\{[^}]+}", ANY_ID.toString());
    }
}
