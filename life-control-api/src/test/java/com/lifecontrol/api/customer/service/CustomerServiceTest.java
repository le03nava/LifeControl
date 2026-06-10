package com.lifecontrol.api.customer.service;

import com.lifecontrol.api.customer.dto.CustomerRequest;
import com.lifecontrol.api.customer.dto.CustomerResponse;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerService Tests")
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    private Customer testCustomer;
    private CustomerRequest testCustomerRequest;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        testCustomerId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testCustomer = Customer.builder()
                .id(testCustomerId)
                .name("Juan Pérez")
                .email("juan@example.com")
                .phone("+525512345678")
                .rfc("PEGJ800101ABC")
                .salesChannel("TIENDA")
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

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
    // getAllCustomers
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getAllCustomers")
    class GetAllCustomersTests {

        @Test
        @DisplayName("should return paginated enabled customers when no search provided")
        void getAllCustomers_Paginated_NoSearch() {
            var pageable = PageRequest.of(0, 12);
            var customers = List.of(testCustomer);
            var expectedPage = new PageImpl<>(customers, pageable, 1);

            when(customerRepository.findByEnabledTrueOrderByCreatedAtDesc(pageable)).thenReturn(expectedPage);

            Page<CustomerResponse> result = customerService.getAllCustomers(pageable, null);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("Juan Pérez");
            assertThat(result.getContent().get(0).email()).isEqualTo("juan@example.com");
            assertThat(result.getContent().get(0).salesChannel()).isEqualTo("TIENDA");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(customerRepository).findByEnabledTrueOrderByCreatedAtDesc(pageable);
        }

        @Test
        @DisplayName("should return filtered customers when search term provided")
        void getAllCustomers_WithSearch() {
            var pageable = PageRequest.of(0, 12);
            var customers = List.of(testCustomer);
            var expectedPage = new PageImpl<>(customers, pageable, 1);

            when(customerRepository.findBySearchTerm(eq("juan"), eq(pageable))).thenReturn(expectedPage);

            Page<CustomerResponse> result = customerService.getAllCustomers(pageable, "juan");

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("Juan Pérez");
            verify(customerRepository).findBySearchTerm("juan", pageable);
        }

        @Test
        @DisplayName("should return empty page when search has no matches")
        void getAllCustomers_NoMatches() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<Customer>(List.of(), pageable, 0);

            when(customerRepository.findBySearchTerm(eq("nonexistent"), eq(pageable))).thenReturn(expectedPage);

            Page<CustomerResponse> result = customerService.getAllCustomers(pageable, "nonexistent");

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(customerRepository).findBySearchTerm("nonexistent", pageable);
        }
    }

    // ─────────────────────────────────────────────
    // getCustomerById
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getCustomerById")
    class GetCustomerByIdTests {

        @Test
        @DisplayName("should return customer when found")
        void getCustomerById_Found() {
            when(customerRepository.findById(testCustomerId)).thenReturn(Optional.of(testCustomer));

            CustomerResponse result = customerService.getCustomerById(testCustomerId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testCustomerId);
            assertThat(result.name()).isEqualTo("Juan Pérez");
            assertThat(result.email()).isEqualTo("juan@example.com");
            assertThat(result.salesChannel()).isEqualTo("TIENDA");
            assertThat(result.enabled()).isTrue();
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when not found")
        void getCustomerById_NotFound_ThrowsException() {
            when(customerRepository.findById(testCustomerId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.getCustomerById(testCustomerId))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessageContaining("Customer not found with id");
        }
    }

    // ─────────────────────────────────────────────
    // createCustomer
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("createCustomer")
    class CreateCustomerTests {

        @Test
        @DisplayName("should create customer and return response with generated ID")
        void createCustomer_Success() {
            when(customerRepository.save(any(Customer.class))).thenReturn(testCustomer);

            CustomerResponse result = customerService.createCustomer(testCustomerRequest);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testCustomerId);
            assertThat(result.name()).isEqualTo("Juan Pérez");
            assertThat(result.email()).isEqualTo("juan@example.com");
            assertThat(result.phone()).isEqualTo("+525512345678");
            assertThat(result.rfc()).isEqualTo("PEGJ800101ABC");
            assertThat(result.salesChannel()).isEqualTo("TIENDA");
            assertThat(result.enabled()).isTrue();
            verify(customerRepository).save(any(Customer.class));
        }

        @Test
        @DisplayName("should default enabled to true when not provided")
        void createCustomer_DefaultsEnabledToTrue() {
            var requestWithoutEnabled = new CustomerRequest(
                    "María López", "maria@example.com", null, null, "ONLINE", null
            );
            var savedCustomer = Customer.builder()
                    .id(UUID.randomUUID())
                    .name("María López")
                    .email("maria@example.com")
                    .salesChannel("ONLINE")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(customerRepository.save(any(Customer.class))).thenReturn(savedCustomer);

            CustomerResponse result = customerService.createCustomer(requestWithoutEnabled);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("María López");
            assertThat(result.enabled()).isTrue();
            verify(customerRepository).save(any(Customer.class));
        }
    }

    // ─────────────────────────────────────────────
    // updateCustomer
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("updateCustomer")
    class UpdateCustomerTests {

        @Test
        @DisplayName("should update customer fields and return response")
        void updateCustomer_Success() {
            var updateRequest = new CustomerRequest(
                    "Juan Actualizado", "updated@example.com", "+525599999999",
                    "RFC-UPDATED", "ONLINE", false
            );

            when(customerRepository.findById(testCustomerId)).thenReturn(Optional.of(testCustomer));
            when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

            CustomerResponse result = customerService.updateCustomer(testCustomerId, updateRequest);

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Juan Actualizado");
            assertThat(result.email()).isEqualTo("updated@example.com");
            assertThat(result.phone()).isEqualTo("+525599999999");
            assertThat(result.rfc()).isEqualTo("RFC-UPDATED");
            assertThat(result.salesChannel()).isEqualTo("ONLINE");
            assertThat(result.enabled()).isFalse();
            verify(customerRepository).save(any(Customer.class));
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when customer not found")
        void updateCustomer_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(customerRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.updateCustomer(nonExistentId, testCustomerRequest))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessageContaining("Customer not found with id");

            verify(customerRepository, never()).save(any(Customer.class));
        }
    }

    // ─────────────────────────────────────────────
    // deleteCustomer
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("deleteCustomer")
    class DeleteCustomerTests {

        @Test
        @DisplayName("should soft-delete customer by setting enabled to false")
        void deleteCustomer_Success() {
            when(customerRepository.findById(testCustomerId)).thenReturn(Optional.of(testCustomer));
            when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

            customerService.deleteCustomer(testCustomerId);

            verify(customerRepository).findById(testCustomerId);
            verify(customerRepository).save(any(Customer.class));
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when customer not found")
        void deleteCustomer_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(customerRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.deleteCustomer(nonExistentId))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessageContaining("Customer not found with id");

            verify(customerRepository, never()).save(any(Customer.class));
        }
    }

    // ─────────────────────────────────────────────
    // enableCustomer
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("enableCustomer")
    class EnableCustomerTests {

        @Test
        @DisplayName("should re-enable a disabled customer")
        void enableCustomer_Success() {
            var disabledCustomer = Customer.builder()
                    .id(testCustomerId)
                    .name("Juan Pérez")
                    .email("juan@example.com")
                    .phone("+525512345678")
                    .rfc("PEGJ800101ABC")
                    .salesChannel("TIENDA")
                    .enabled(false)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(customerRepository.findById(testCustomerId)).thenReturn(Optional.of(disabledCustomer));
            when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

            CustomerResponse result = customerService.enableCustomer(testCustomerId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testCustomerId);
            assertThat(result.enabled()).isTrue();
            verify(customerRepository).save(any(Customer.class));
        }
    }
}
