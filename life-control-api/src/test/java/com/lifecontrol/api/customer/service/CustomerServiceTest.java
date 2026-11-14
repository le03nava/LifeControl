package com.lifecontrol.api.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.customer.dto.CustomerRequest;
import com.lifecontrol.api.customer.dto.CustomerResponse;
import com.lifecontrol.api.customer.exception.CustomerNotFoundException;
import com.lifecontrol.api.customer.model.Customer;
import com.lifecontrol.api.customer.repository.CustomerRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * The service's half of S4: every read names the caller's company, and the company comes from the operation's
 * scope and never from the request.
 *
 * <p>This suite states a tenant in {@code @BeforeEach} because that is what {@code TenantScopeFilter} does for
 * a path that names no company, and because the scope <b>is</b> the input this class has to read. A case that
 * needs "no tenant at all" clears it explicitly instead of leaving the previous case's value on the thread.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerService Tests")
class CustomerServiceTest {

    private static final UUID COMPANY_ID = UUID.fromString("0c000000-0000-4000-8000-00000000000c");

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    private Customer testCustomer;
    private CustomerRequest testCustomerRequest;
    private UUID testCustomerId;

    @BeforeEach
    void setUp() {
        TenantScope.set(COMPANY_ID, null);

        testCustomerId = UUID.randomUUID();
        var now = LocalDateTime.now();

        testCustomer = Customer.builder()
                .id(testCustomerId)
                .companyId(COMPANY_ID)
                .name("Juan Pérez")
                .email("juan@example.com")
                .phone("+525512345678")
                .rfc("PEGJ800101ABC")
                .salesChannel("TIENDA")
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        testCustomerRequest =
                new CustomerRequest("Juan Pérez", "juan@example.com", "+525512345678", "PEGJ800101ABC", "TIENDA", true);
    }

    @AfterEach
    void clearTheScope() {
        TenantScope.clear();
    }

    // ─────────────────────────────────────────────
    // getAllCustomers
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getAllCustomers")
    class GetAllCustomersTests {

        @Test
        @DisplayName("should return the company's enabled customers when no search provided")
        void getAllCustomers_Paginated_NoSearch() {
            var pageable = PageRequest.of(0, 12);
            var customers = List.of(testCustomer);
            var expectedPage = new PageImpl<>(customers, pageable, 1);

            when(customerRepository.findByCompanyIdAndEnabledTrueOrderByCreatedAtDesc(COMPANY_ID, pageable))
                    .thenReturn(expectedPage);

            Page<CustomerResponse> result = customerService.getAllCustomers(pageable, null);

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("Juan Pérez");
            assertThat(result.getContent().get(0).email()).isEqualTo("juan@example.com");
            assertThat(result.getContent().get(0).salesChannel()).isEqualTo("TIENDA");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(customerRepository).findByCompanyIdAndEnabledTrueOrderByCreatedAtDesc(COMPANY_ID, pageable);
        }

        @Test
        @DisplayName("should search inside the company and not across companies")
        void getAllCustomers_WithSearch() {
            var pageable = PageRequest.of(0, 12);
            var customers = List.of(testCustomer);
            var expectedPage = new PageImpl<>(customers, pageable, 1);

            when(customerRepository.findByCompanyIdAndSearchTerm(COMPANY_ID, "juan", pageable))
                    .thenReturn(expectedPage);

            Page<CustomerResponse> result = customerService.getAllCustomers(pageable, "juan");

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).name()).isEqualTo("Juan Pérez");
            verify(customerRepository).findByCompanyIdAndSearchTerm(COMPANY_ID, "juan", pageable);
        }

        @Test
        @DisplayName("should return empty page when search has no matches")
        void getAllCustomers_NoMatches() {
            var pageable = PageRequest.of(0, 12);
            var expectedPage = new PageImpl<Customer>(List.of(), pageable, 0);

            when(customerRepository.findByCompanyIdAndSearchTerm(COMPANY_ID, "nonexistent", pageable))
                    .thenReturn(expectedPage);

            Page<CustomerResponse> result = customerService.getAllCustomers(pageable, "nonexistent");

            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(customerRepository).findByCompanyIdAndSearchTerm(COMPANY_ID, "nonexistent", pageable);
        }

        @Test
        @DisplayName("should answer an empty page when the operation states no company")
        void getAllCustomers_WithoutATenant_AnswersNothing() {
            TenantScope.clear();

            Page<CustomerResponse> result = customerService.getAllCustomers(PageRequest.of(0, 12), null);

            assertThat(result.getContent())
                    .as("a caller whose token names no company has no tenant to read under, and an empty page "
                            + "is the honest answer instead of a query the policy would empty anyway")
                    .isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(customerRepository, never()).findByCompanyIdAndEnabledTrueOrderByCreatedAtDesc(any(), any());
            verify(customerRepository, never()).findByCompanyIdAndSearchTerm(any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────
    // getCustomerById
    // ─────────────────────────────────────────────
    @Nested
    @DisplayName("getCustomerById")
    class GetCustomerByIdTests {

        @Test
        @DisplayName("should return customer when found in the caller's company")
        void getCustomerById_Found() {
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.of(testCustomer));

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
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.getCustomerById(testCustomerId))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessageContaining("Customer not found with id");
        }

        @Test
        @DisplayName("another company's customer is a 404 and not a 403")
        void getCustomerById_OfAnotherCompany_IsNotFound() {
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.getCustomerById(testCustomerId))
                    .as("the tree's ResourceNotFoundException convention: in this company the row is not there, "
                            + "which is a 404 — a 403 would tell the caller the row exists elsewhere")
                    .isInstanceOf(CustomerNotFoundException.class)
                    .isNotInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when the operation states no company")
        void getCustomerById_WithoutATenant_IsNotFound() {
            TenantScope.clear();

            assertThatThrownBy(() -> customerService.getCustomerById(testCustomerId))
                    .isInstanceOf(CustomerNotFoundException.class);

            verify(customerRepository, never()).findByCompanyIdAndId(any(), any());
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
        @DisplayName("should stamp the operation's company on the row it writes")
        void createCustomer_StampsTheScopeCompany() {
            when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

            customerService.createCustomer(testCustomerRequest);

            var written = ArgumentCaptor.forClass(Customer.class);
            verify(customerRepository).save(written.capture());
            assertThat(written.getValue().getCompanyId())
                    .as("the tenant comes from the operation's scope and never from the request: a body that "
                            + "could name its own company would let one caller write into another tenant")
                    .isEqualTo(COMPANY_ID);
        }

        @Test
        @DisplayName("should refuse to create a customer when the operation states no company")
        void createCustomer_WithoutATenant_IsRefused() {
            TenantScope.clear();

            assertThatThrownBy(() -> customerService.createCustomer(testCustomerRequest))
                    .as("a customer with no company is a row the schema refuses, and a refusal is the honest "
                            + "answer instead of a 500 from the NOT NULL")
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("company cannot be determined");

            verify(customerRepository, never()).save(any(Customer.class));
        }

        @Test
        @DisplayName("should default enabled to true when not provided")
        void createCustomer_DefaultsEnabledToTrue() {
            var requestWithoutEnabled =
                    new CustomerRequest("María López", "maria@example.com", null, null, "ONLINE", null);
            var savedCustomer = Customer.builder()
                    .id(UUID.randomUUID())
                    .companyId(COMPANY_ID)
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
                    "Juan Actualizado", "updated@example.com", "+525599999999", "RFC-UPDATED", "ONLINE", false);

            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.of(testCustomer));
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
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, nonExistentId))
                    .thenReturn(Optional.empty());

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
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.of(testCustomer));
            when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

            customerService.deleteCustomer(testCustomerId);

            verify(customerRepository).findByCompanyIdAndId(COMPANY_ID, testCustomerId);
            verify(customerRepository).save(any(Customer.class));
        }

        @Test
        @DisplayName("should throw CustomerNotFoundException when customer not found")
        void deleteCustomer_NotFound_ThrowsException() {
            var nonExistentId = UUID.randomUUID();
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, nonExistentId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.deleteCustomer(nonExistentId))
                    .isInstanceOf(CustomerNotFoundException.class)
                    .hasMessageContaining("Customer not found with id");

            verify(customerRepository, never()).save(any(Customer.class));
        }

        @Test
        @DisplayName("should not delete another company's customer")
        void deleteCustomer_OfAnotherCompany_IsNotFound() {
            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> customerService.deleteCustomer(testCustomerId))
                    .isInstanceOf(CustomerNotFoundException.class);

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
                    .companyId(COMPANY_ID)
                    .name("Juan Pérez")
                    .email("juan@example.com")
                    .phone("+525512345678")
                    .rfc("PEGJ800101ABC")
                    .salesChannel("TIENDA")
                    .enabled(false)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            when(customerRepository.findByCompanyIdAndId(COMPANY_ID, testCustomerId))
                    .thenReturn(Optional.of(disabledCustomer));
            when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

            CustomerResponse result = customerService.enableCustomer(testCustomerId);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(testCustomerId);
            assertThat(result.enabled()).isTrue();
            verify(customerRepository).save(any(Customer.class));
        }
    }
}
