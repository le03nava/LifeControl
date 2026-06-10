package com.lifecontrol.api.customer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifecontrol.api.customer.dto.CustomerRequest;
import com.lifecontrol.api.customer.dto.CustomerResponse;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.service.CustomerService;
import com.lifecontrol.api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerController Tests")
class CustomerControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CustomerService customerService;

    @InjectMocks
    private CustomerController customerController;

    private CustomerResponse testCustomerResponse;
    private CustomerRequest testCustomerRequest;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(customerController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        testCustomerId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testCustomerResponse = new CustomerResponse(
                testCustomerId,
                "Juan Pérez",
                "juan@example.com",
                "+525512345678",
                "PEGJ800101ABC",
                "TIENDA",
                true,
                now,
                now
        );

        testCustomerRequest = new CustomerRequest(
                "Juan Pérez",
                "juan@example.com",
                "+525512345678",
                "PEGJ800101ABC",
                "TIENDA",
                true
        );
    }

    // ─────────────────────────────────────────────
    // GET /api/customers
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/customers")
    class GetAllCustomersTests {

        @Test
        @DisplayName("should return 200 with paginated customers without search")
        void getAllCustomers_Paginated() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var customers = List.of(testCustomerResponse);
            var page = new PageImpl<>(customers, pageable, 1);

            when(customerService.getAllCustomers(any(Pageable.class), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/customers")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.content[0].name").value("Juan Pérez"))
                    .andExpect(jsonPath("$.content[0].email").value("juan@example.com"))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(jsonPath("$.number").value(0))
                    .andExpect(jsonPath("$.size").value(12));
        }

        @Test
        @DisplayName("should return 200 and filter by search term")
        void getAllCustomers_WithSearch() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var customers = List.of(testCustomerResponse);
            var page = new PageImpl<>(customers, pageable, 1);

            when(customerService.getAllCustomers(any(Pageable.class), eq("juan"))).thenReturn(page);

            mockMvc.perform(get("/api/customers")
                            .param("page", "0")
                            .param("size", "12")
                            .param("search", "juan"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].name").value("Juan Pérez"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("should return 200 with empty page when no customers match")
        void getAllCustomers_EmptyPage() throws Exception {
            var pageable = PageRequest.of(0, 12);
            var page = new PageImpl<CustomerResponse>(List.of(), pageable, 0);

            when(customerService.getAllCustomers(any(Pageable.class), eq(null))).thenReturn(page);

            mockMvc.perform(get("/api/customers")
                            .param("page", "0")
                            .param("size", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));
        }
    }

    // ─────────────────────────────────────────────
    // GET /api/customers/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("GET /api/customers/{id}")
    class GetCustomerByIdTests {

        @Test
        @DisplayName("should return 200 with customer when found")
        void getCustomerById_Found_Returns200() throws Exception {
            when(customerService.getCustomerById(testCustomerId)).thenReturn(testCustomerResponse);

            mockMvc.perform(get("/api/customers/{id}", testCustomerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testCustomerId.toString()))
                    .andExpect(jsonPath("$.name").value("Juan Pérez"))
                    .andExpect(jsonPath("$.email").value("juan@example.com"))
                    .andExpect(jsonPath("$.salesChannel").value("TIENDA"));
        }

        @Test
        @DisplayName("should return 404 when customer not found")
        void getCustomerById_NotFound_Returns404() throws Exception {
            when(customerService.getCustomerById(testCustomerId))
                    .thenThrow(new CustomerNotFoundException(testCustomerId));

            mockMvc.perform(get("/api/customers/{id}", testCustomerId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Customer not found with id: " + testCustomerId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/customers
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("POST /api/customers")
    class CreateCustomerTests {

        @Test
        @DisplayName("should return 201 with created customer when valid request")
        void createCustomer_ValidRequest_Returns201() throws Exception {
            when(customerService.createCustomer(any(CustomerRequest.class)))
                    .thenReturn(testCustomerResponse);

            mockMvc.perform(post("/api/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCustomerRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(testCustomerId.toString()))
                    .andExpect(jsonPath("$.name").value("Juan Pérez"))
                    .andExpect(jsonPath("$.email").value("juan@example.com"))
                    .andExpect(jsonPath("$.salesChannel").value("TIENDA"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }

        @Test
        @DisplayName("should return 400 when required fields are missing")
        void createCustomer_MissingRequiredFields_Returns400() throws Exception {
            var invalidRequest = new CustomerRequest("", null, null, null, "", null);

            mockMvc.perform(post("/api/customers")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.name").exists())
                    .andExpect(jsonPath("$.errors.salesChannel").exists())
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PUT /api/customers/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PUT /api/customers/{id}")
    class UpdateCustomerTests {

        @Test
        @DisplayName("should return 200 with updated customer")
        void updateCustomer_Success_Returns200() throws Exception {
            when(customerService.updateCustomer(eq(testCustomerId), any(CustomerRequest.class)))
                    .thenReturn(testCustomerResponse);

            mockMvc.perform(put("/api/customers/{id}", testCustomerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCustomerRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testCustomerId.toString()))
                    .andExpect(jsonPath("$.name").value("Juan Pérez"));
        }

        @Test
        @DisplayName("should return 404 when customer not found")
        void updateCustomer_NotFound_Returns404() throws Exception {
            when(customerService.updateCustomer(eq(testCustomerId), any(CustomerRequest.class)))
                    .thenThrow(new CustomerNotFoundException(testCustomerId));

            mockMvc.perform(put("/api/customers/{id}", testCustomerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(testCustomerRequest)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Customer not found with id: " + testCustomerId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // DELETE /api/customers/{id}
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("DELETE /api/customers/{id}")
    class DeleteCustomerTests {

        @Test
        @DisplayName("should return 204 on successful soft delete")
        void deleteCustomer_Success_Returns204() throws Exception {
            doNothing().when(customerService).deleteCustomer(testCustomerId);

            mockMvc.perform(delete("/api/customers/{id}", testCustomerId))
                    .andExpect(status().isNoContent());
            verify(customerService).deleteCustomer(testCustomerId);
        }

        @Test
        @DisplayName("should return 404 when customer not found")
        void deleteCustomer_NotFound_Returns404() throws Exception {
            doThrow(new CustomerNotFoundException(testCustomerId))
                    .when(customerService).deleteCustomer(testCustomerId);

            mockMvc.perform(delete("/api/customers/{id}", testCustomerId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.message").value("Customer not found with id: " + testCustomerId))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ─────────────────────────────────────────────
    // PATCH /api/customers/{id}/enable
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("PATCH /api/customers/{id}/enable")
    class EnableCustomerTests {

        @Test
        @DisplayName("should return 200 with re-enabled customer")
        void enableCustomer_Success_Returns200() throws Exception {
            when(customerService.enableCustomer(testCustomerId)).thenReturn(testCustomerResponse);

            mockMvc.perform(patch("/api/customers/{id}/enable", testCustomerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(testCustomerId.toString()))
                    .andExpect(jsonPath("$.name").value("Juan Pérez"))
                    .andExpect(jsonPath("$.enabled").value(true));
        }
    }
}
