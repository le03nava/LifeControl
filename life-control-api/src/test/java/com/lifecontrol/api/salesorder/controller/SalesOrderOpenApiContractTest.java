package com.lifecontrol.api.salesorder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the published {@code 409} of the sales-order endpoints against what the service can actually
 * answer (F-8 of {@code odd/tasks/sales-location-aware-stock.md}).
 *
 * <p>The document emitted at {@code springdoc.api-docs.path} is a contract other people generate
 * clients from, and nothing looked at it: F-3 removed a rule while the Swagger page kept describing
 * the old one, and F-7 fixed the delete endpoint's {@code 409}. Reading this controller after F-7
 * showed the same defect on four more endpoints — two of them declared <em>no</em> {@code 409} at
 * all while returning it — so this test states the promise as a set of causes per endpoint instead
 * of leaving it to prose nobody re-reads. It asserts fragments, not sentences: the wording stays
 * free, the promise does not. Each endpoint is its own case so a regression names the endpoint that
 * lost the declaration.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Sales order OpenAPI contract")
class SalesOrderOpenApiContractTest {

    /** {@code springdoc.api-docs.path} on {@code application.properties}; public per SecurityConfig. */
    private static final String DOC_PATH = "/api-docs";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("should declare insufficient stock on create")
    void createSalesOrder_DeclaresInsufficientStock() throws Exception {
        assertConflictNames("post", "/api/sales-orders", "stock");
    }

    @Test
    @DisplayName("should declare the terminal order, the store reassignment and insufficient stock on update")
    void updateSalesOrder_DeclaresTerminalReassignmentAndStock() throws Exception {
        assertConflictNames("put", "/api/sales-orders/{id}", "terminal", "reassign", "stock");
    }

    @Test
    @DisplayName("should declare the terminal order and insufficient stock on add item")
    void addItem_DeclaresTerminalAndStock() throws Exception {
        assertConflictNames("post", "/api/sales-orders/{id}/items", "terminal", "stock");
    }

    @Test
    @DisplayName("should declare the terminal order, the line rule and insufficient stock on update item")
    void updateItem_DeclaresTerminalLineAndStock() throws Exception {
        // "soft-deleted" and not "Cancelled": the terminal phrase already carries "Completed or
        // Cancelled", so a fragment on that word would pass on a description that never names the
        // line rule at all — a green proving less than it claims (F19 of the record).
        assertConflictNames("put", "/api/sales-orders/{id}/items/{itemId}", "terminal", "soft-deleted", "stock");
    }

    @Test
    @DisplayName("should declare the terminal order and the line rule on delete item")
    void deleteItem_DeclaresTerminalAndLine() throws Exception {
        assertConflictNames("delete", "/api/sales-orders/{id}/items/{itemId}", "terminal", "soft-deleted");
    }

    @Test
    @DisplayName("should declare the invalid order transition on update status")
    void updateSalesOrderStatus_DeclaresInvalidTransition() throws Exception {
        assertConflictNames("patch", "/api/sales-orders/{id}/status", "transition");
    }

    @Test
    @DisplayName("should declare the invalid item transition on update item status")
    void updateItemStatus_DeclaresInvalidTransition() throws Exception {
        assertConflictNames("patch", "/api/sales-orders/{id}/items/{itemId}/status", "transition");
    }

    private void assertConflictNames(String method, String path, String... causes) throws Exception {
        var conflict =
                readOpenApiDocument().path(path).path(method).path("responses").path("409");
        assertThat(conflict.isMissingNode())
                .as("%s %s declares the 409 it can answer", method.toUpperCase(), path)
                .isFalse();
        var description = conflict.path("description").asText();
        for (var cause : causes) {
            assertThat(description)
                    .as("%s %s: the declared 409 must name %s", method.toUpperCase(), path, cause)
                    .contains(cause);
        }
    }

    private JsonNode readOpenApiDocument() throws Exception {
        var body = mockMvc.perform(get(DOC_PATH))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var paths = objectMapper.readTree(body).path("paths");
        assertThat(paths.isMissingNode()).as("the OpenAPI document lists paths").isFalse();
        return paths;
    }
}
