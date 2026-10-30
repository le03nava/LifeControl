package com.lifecontrol.api.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifecontrol.api.common.address.dto.AddressRequest;
import com.lifecontrol.api.common.address.model.Address;
import com.lifecontrol.api.common.auth.CurrentUserContext;
import com.lifecontrol.api.company.dto.CompanyRequest;
import com.lifecontrol.api.company.dto.CompanyResponse;
import com.lifecontrol.api.company.dto.CompanySelectorResponse;
import com.lifecontrol.api.company.event.CompanyCreatedEvent;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.exception.DuplicateCompanyException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import com.lifecontrol.api.country.model.Country;
import com.lifecontrol.api.country.repository.CountryRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("CompanyService Tests")
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private CountryRepository countryRepository;

    /**
     * The transaction the service drives itself, because the tenant scope has to be stated before the
     * boundary hook reads it (ADR-0005, record L16). Running the callback instead of returning null keeps
     * every case below about the service's behaviour rather than about the manager's.
     */
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private CompanyService companyService;

    private Company testCompany;
    private CompanyRequest testCompanyRequest;
    private CompanyRequest testCompanyRequestWithAddress;
    private Address testAddress;
    private UUID testCompanyId;
    private UUID testCountryId;

    @BeforeEach
    void setUp() {
        // The claim path is the <b>only</b> path now (ADR-0005, record L16 mandated the per-claim reads, and
        // the unscoped `findAll` branch was removed in T5b), so every case below runs as a caller whose
        // token names one company by default. A case about another claim set re-stubs this.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        testCompanyId = UUID.randomUUID();
        testCountryId = UUID.randomUUID();
        lenient().when(currentUserContext.getCompanyIds()).thenReturn(new LinkedHashSet<>(List.of(testCompanyId)));

        testAddress = Address.builder()
                .street("Main St")
                .streetNumber("123")
                .internalNumber("A")
                .neighborhood("Downtown")
                .zipCode("12345")
                .city("Mexico City")
                .state("CDMX")
                .enabled(true)
                .build();

        testCompany = Company.builder()
                .id(testCompanyId)
                .companyKey("1")
                .companyName("Test Company")
                .tipoPersonaId(1)
                .razonSocial("Razon Social Test SA de CV")
                .rfc("XAXX010101000")
                .phone("+1234567890")
                .email("test@company.com")
                .enabled(true)
                .street("Main St")
                .streetNumber("123")
                .internalNumber("A")
                .neighborhood("Downtown")
                .zipCode("12345")
                .city("Mexico City")
                .state("CDMX")
                .countryId(testCountryId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        testCompanyRequest = new CompanyRequest(
                "1",
                "Test Company",
                1,
                "Razon Social Test SA de CV",
                "XAXX010101000",
                "+1234567890",
                "test@company.com",
                null,
                true,
                null);

        testCompanyRequestWithAddress = new CompanyRequest(
                "2",
                "Company With Address",
                1,
                "Addr S.A. de CV",
                "XAXX010101001",
                "+9876543210",
                "addr@company.com",
                null,
                true,
                new AddressRequest(
                        "Av. Reforma", "456", "B", "Juarez", "67890", "Guadalajara", "Jalisco", testCountryId));
    }

    @Nested
    @DisplayName("getAllCompanies")
    class GetAllCompaniesTests {

        @Test
        @DisplayName("getAllCompanies - should return paginated companies without search")
        void getAllCompanies_Paginated() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).companyName()).isEqualTo("Test Company");
            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getTotalPages()).isEqualTo(1);
            verify(companyRepository).findById(testCompanyId);
        }

        @Test
        @DisplayName("getAllCompanies - should return filtered companies with search")
        void getAllCompanies_WithSearch() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "Test");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).companyName()).isEqualTo("Test Company");
            verify(companyRepository).findById(testCompanyId);
            verify(companyRepository, never()).findBySearchTerm(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("getAllCompanies - should return empty page when search has no matches")
        void getAllCompanies_NoMatches() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "NonExistent");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(companyRepository).findById(testCompanyId);
        }

        @Test
        @DisplayName("getAllCompanies - should ignore whitespace-only search and return all")
        void getAllCompanies_BlankSearch() {
            // Arrange
            var pageable = PageRequest.of(0, 12);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "   ");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(companyRepository).findById(testCompanyId);
        }

        /**
         * The case that used to pin «an administrator sees every company» through the unscoped
         * {@code findAll}. The unscoped branch is gone, and the administrator's own list is the claims:
         * {@link CurrentUserContext#isAdmin()} is not consulted at all, which is what this case pins by
         * asserting it is never called.
         */
        @Test
        @DisplayName("getAllCompanies - an administrator reads exactly the claimed companies, one scoped read each")
        void getAllCompanies_AnAdministratorReadsOnlyTheClaimedCompanies() {
            reset(currentUserContext);
            var firstId = UUID.randomUUID();
            var secondId = UUID.randomUUID();
            var claimOrder = new LinkedHashSet<UUID>();
            claimOrder.add(firstId);
            claimOrder.add(secondId);

            lenient().when(currentUserContext.isAdmin()).thenReturn(true);
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(claimOrder);
            when(companyRepository.findById(firstId)).thenReturn(Optional.of(companyOf(firstId, "10", "First Admin")));
            when(companyRepository.findById(secondId))
                    .thenReturn(Optional.of(companyOf(secondId, "11", "Second Admin")));

            Page<CompanyResponse> result = companyService.getAllCompanies(PageRequest.of(0, 12), null);

            assertThat(result.getContent())
                    .as("the role does not widen the read: the token's claims are the administrator's own "
                            + "tenant list, and one transaction cannot state two tenants")
                    .extracting(CompanyResponse::id)
                    .containsExactlyElementsOf(claimOrder);
            verify(companyRepository).findById(firstId);
            verify(companyRepository).findById(secondId);
            verify(companyRepository, never()).findAll(any(Pageable.class));
            verify(currentUserContext, never()).isAdmin();
        }

        // ── Country-role filtering, one scoped read per claim (ADR-0005, record L16) ────

        @Test
        @DisplayName("getAllCompanies - one claim is one scoped read and one row")
        void getAllCompanies_CountryRole_OneClaimIsOneScopedRead() {
            reset(currentUserContext);
            var companyId = UUID.randomUUID();

            lenient().when(currentUserContext.getCompanyIds()).thenReturn(new LinkedHashSet<>(List.of(companyId)));

            var pageable = PageRequest.of(0, 12);
            var claimed = Company.builder()
                    .id(companyId)
                    .companyKey("7")
                    .companyName("Claimed Company")
                    .rfc("XAXX010101007")
                    .enabled(true)
                    .build();
            when(companyRepository.findById(companyId)).thenReturn(Optional.of(claimed));

            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, null);

            assertThat(result.getContent())
                    .as("the claim is the only company this caller may see, and the read is scoped to it")
                    .singleElement()
                    .extracting(CompanyResponse::id)
                    .isEqualTo(companyId);
            verify(companyRepository).findById(companyId);
            verify(companyRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("getAllCompanies - two claims are two rows, in the order the claim yields them")
        void getAllCompanies_CountryRole_TwoClaimsAreTwoRowsInClaimOrder() {
            reset(currentUserContext);
            var firstId = UUID.randomUUID();
            var secondId = UUID.randomUUID();
            var claimOrder = new LinkedHashSet<UUID>();
            claimOrder.add(firstId);
            claimOrder.add(secondId);

            lenient().when(currentUserContext.getCompanyIds()).thenReturn(claimOrder);

            when(companyRepository.findById(firstId)).thenReturn(Optional.of(companyOf(firstId, "8", "First Claimed")));
            when(companyRepository.findById(secondId))
                    .thenReturn(Optional.of(companyOf(secondId, "9", "Second Claimed")));

            Page<CompanyResponse> result = companyService.getAllCompanies(PageRequest.of(0, 12), null);

            assertThat(result.getContent())
                    .as("one transaction cannot state two tenants, so a caller with two claims gets one "
                            + "scoped read each — and never a widened policy")
                    .extracting(CompanyResponse::id)
                    .containsExactlyElementsOf(claimOrder);
            verify(companyRepository).findById(firstId);
            verify(companyRepository).findById(secondId);
        }

        @Test
        @DisplayName("getAllCompanies - should return empty page when country-role has no companyIds")
        void getAllCompanies_CountryRole_NoCompanyIds_ReturnsEmpty() {
            // Override the default claim set — simulate a caller that belongs to no company
            reset(currentUserContext);
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(Collections.emptySet());

            var pageable = PageRequest.of(0, 12);

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, null);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verify(companyRepository, never()).findById(any(UUID.class));
            verify(companyRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("getAllCompanies - the search is applied to the claim's own row")
        void getAllCompanies_CountryRole_WithSearch() {
            reset(currentUserContext);
            var ids = new LinkedHashSet<>(List.of(testCompanyId));
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(ids);

            var pageable = PageRequest.of(0, 12);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            Page<CompanyResponse> result = companyService.getAllCompanies(pageable, "Test");

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            verify(companyRepository).findById(testCompanyId);
            verify(companyRepository, never()).findBySearchTerm(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("getAllCompanies - a search the claim's row does not match yields no row")
        void getAllCompanies_CountryRole_WithSearchThatDoesNotMatch() {
            reset(currentUserContext);
            var ids = new LinkedHashSet<>(List.of(testCompanyId));
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(ids);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            Page<CompanyResponse> result = companyService.getAllCompanies(PageRequest.of(0, 12), "NonExistent");

            assertThat(result.getContent())
                    .as("the search is the same four fields the admin path asks the database for")
                    .isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        private Company companyOf(UUID id, String companyKey, String companyName) {
            return Company.builder()
                    .id(id)
                    .companyKey(companyKey)
                    .companyName(companyName)
                    .rfc("XAXX01010" + companyKey + "00")
                    .enabled(true)
                    .build();
        }
    }

    @Nested
    @DisplayName("getCompanySelector")
    class GetCompanySelectorTests {

        @Test
        @DisplayName("getCompanySelector - an administrator reads exactly the claimed companies, one scoped read each")
        void getCompanySelector_AnAdministratorReadsOnlyTheClaimedCompanies() {
            // Arrange
            Pageable pageable = PageRequest.of(0, 12);
            var secondId = UUID.randomUUID();
            var claimOrder = new LinkedHashSet<UUID>();
            claimOrder.add(testCompanyId);
            claimOrder.add(secondId);
            var second = Company.builder()
                    .id(secondId)
                    .companyKey("2")
                    .companyName("Second Claimed")
                    .rfc("XAXX010101002")
                    .enabled(true)
                    .build();

            lenient().when(currentUserContext.isAdmin()).thenReturn(true);
            lenient().when(currentUserContext.getCompanyIds()).thenReturn(claimOrder);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.findById(secondId)).thenReturn(Optional.of(second));

            // Act
            Page<CompanySelectorResponse> result = companyService.getCompanySelector(pageable, null);

            // Assert
            assertThat(result.getContent())
                    .as("the selector shares the claim path with getAllCompanies, so an administrator's "
                            + "selector is their claims — never the whole table")
                    .extracting(CompanySelectorResponse::id)
                    .containsExactlyElementsOf(claimOrder);
            assertThat(result.getContent().getFirst()).satisfies(selector -> {
                assertThat(selector.id()).isEqualTo(testCompanyId);
                assertThat(selector.companyKey()).isEqualTo("1");
                assertThat(selector.companyName()).isEqualTo("Test Company");
            });
            verify(companyRepository).findById(testCompanyId);
            verify(companyRepository).findById(secondId);
            verify(companyRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("getCompanySelector - shares the company_id claim filter with getAllCompanies")
        void getCompanySelector_NonAdminIsFilteredByTheClaim() {
            // Arrange
            Pageable pageable = PageRequest.of(0, 12);
            Set<UUID> ids = new LinkedHashSet<>(List.of(testCompanyId));
            when(currentUserContext.getCompanyIds()).thenReturn(ids);
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            Page<CompanySelectorResponse> result = companyService.getCompanySelector(pageable, null);

            // Assert
            assertThat(result.getContent())
                    .singleElement()
                    .extracting(CompanySelectorResponse::id)
                    .isEqualTo(testCompanyId);
            verify(companyRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("getCompanySelector - an empty claim yields an empty page and reads nothing")
        void getCompanySelector_EmptyClaimReturnsAnEmptyPage() {
            // Arrange
            Pageable pageable = PageRequest.of(0, 12);
            when(currentUserContext.getCompanyIds()).thenReturn(Set.of());

            // Act
            Page<CompanySelectorResponse> result = companyService.getCompanySelector(pageable, null);

            // Assert
            assertThat(result.getContent()).isEmpty();
            verify(companyRepository, never()).findAll(any(Pageable.class));
            verify(companyRepository, never()).findById(any(UUID.class));
        }
    }

    @Nested
    @DisplayName("getCompanyById")
    class GetCompanyByIdTests {

        @Test
        @DisplayName("getCompanyById - should return company when exists")
        void getCompanyById_Success() {
            // Arrange
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));

            // Act
            CompanyResponse result = companyService.getCompanyById(testCompanyId);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.companyKey()).isEqualTo(testCompany.getCompanyKey());
            assertThat(result.companyName()).isEqualTo(testCompany.getCompanyName());
            // Legacy inline fallback: no Address entity, reads from inline columns
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Main St");
            assertThat(result.address().city()).isEqualTo("Mexico City");
            assertThat(result.address().countryId()).isEqualTo(testCountryId);
        }

        @Test
        @DisplayName("getCompanyById - should throw CompanyNotFoundException when not exists")
        void getCompanyById_NotFound_ThrowsException() {
            // Arrange
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyService.getCompanyById(testCompanyId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
        }
    }

    @Nested
    @DisplayName("updateCompany")
    class UpdateCompanyTests {

        @Test
        @DisplayName("updateCompany - should update company successfully without changing address")
        void updateCompany_Success() {
            // Arrange
            CompanyRequest updateRequest = new CompanyRequest(
                    "1",
                    "Updated Company Name",
                    2,
                    "Nueva Razon Social SA de CV",
                    "XAXX010101000",
                    "+9876543210",
                    "updated@company.com",
                    null,
                    false,
                    null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.rfc(), testCompanyId))
                    .thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.companyName()).isEqualTo("Updated Company Name");
            assertThat(result.tipoPersonaId()).isEqualTo(2);
            assertThat(result.razonSocial()).isEqualTo("Nueva Razon Social SA de CV");
            assertThat(result.email()).isEqualTo("updated@company.com");
            assertThat(result.enabled()).isFalse();
            // Address should remain unchanged (null in request = leave as-is)
            // testCompany has inline address data, so fallback returns it
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Main St");
            assertThat(result.address().countryId()).isEqualTo(testCountryId);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should update address fields")
        void updateCompany_UpdatesAddressFields() {
            // Arrange
            CompanyRequest addressUpdateRequest = new CompanyRequest(
                    "1",
                    "Test Company",
                    null,
                    null,
                    "XAXX010101000",
                    null,
                    null,
                    null,
                    null,
                    new AddressRequest(
                            "Calle Nueva", "999", "1", "Col Nueva", "54321", "Monterrey", "Nuevo Leon", testCountryId));

            var country = Country.builder()
                    .id(testCountryId)
                    .countryCode("MX")
                    .countryName("Mexico")
                    .enabled(true)
                    .build();

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(any(), eq(testCompanyId)))
                    .thenReturn(false);
            when(countryRepository.findById(testCountryId)).thenReturn(Optional.of(country));
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, addressUpdateRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Calle Nueva");
            assertThat(result.address().streetNumber()).isEqualTo("999");
            assertThat(result.address().internalNumber()).isEqualTo("1");
            assertThat(result.address().neighborhood()).isEqualTo("Col Nueva");
            assertThat(result.address().zipCode()).isEqualTo("54321");
            assertThat(result.address().city()).isEqualTo("Monterrey");
            assertThat(result.address().state()).isEqualTo("Nuevo Leon");
            assertThat(result.address().countryId()).isEqualTo(testCountryId);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should throw CompanyNotFoundException when ID not exists")
        void updateCompany_NotFound_ThrowsException() {
            // Arrange
            UUID nonExistentId = UUID.randomUUID();
            when(companyRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyService.updateCompany(nonExistentId, testCompanyRequest))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
        }

        @Test
        @DisplayName("updateCompany - should throw DuplicateCompanyException when RFC is duplicate")
        void updateCompany_DuplicateRfc_ThrowsException() {
            // Arrange
            CompanyRequest duplicateRfcRequest =
                    new CompanyRequest("1", "Test Company", null, null, "DUPLICATE_RFC", null, null, null, null, null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot("DUPLICATE_RFC", testCompanyId))
                    .thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> companyService.updateCompany(testCompanyId, duplicateRfcRequest))
                    .isInstanceOf(DuplicateCompanyException.class)
                    .hasMessageContaining("Company with RFC");
        }

        @Test
        @DisplayName("updateCompany - should ignore companyKey from request and use path ID")
        void updateCompany_IgnoreCompanyKeyFromRequest() {
            // Arrange - request has different companyKey but should be ignored
            CompanyRequest updateRequest = new CompanyRequest(
                    "999", // Different from existing company
                    "Updated Company",
                    null,
                    null,
                    "XAXX010101000",
                    null,
                    null,
                    null,
                    null,
                    null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(updateRequest.rfc(), testCompanyId))
                    .thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result).isNotNull();
            // companyKey should remain "1", not "999" from request
            assertThat(result.companyKey()).isEqualTo("1");
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("updateCompany - should allow same RFC when updating same company")
        void updateCompany_SameRfcAllowed() {
            // Arrange - same RFC as current company should pass
            CompanyRequest sameRfcRequest = new CompanyRequest(
                    "1",
                    "Updated Name",
                    null,
                    null,
                    "XAXX010101000", // Same as existing
                    null,
                    null,
                    null,
                    null,
                    null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot("XAXX010101000", testCompanyId))
                    .thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act & Assert - should not throw
            CompanyResponse result = companyService.updateCompany(testCompanyId, sameRfcRequest);
            assertThat(result).isNotNull();
            assertThat(result.companyName()).isEqualTo("Updated Name");
        }

        @Test
        @DisplayName("updateCompany - should lowercase the email domain")
        void updateCompany_LowercasesEmailDomain() {
            // Arrange
            CompanyRequest updateRequest = new CompanyRequest(
                    "1",
                    "Test Company",
                    1,
                    "Razon Social",
                    "XAXX010101000",
                    "+1234567890",
                    "test@company.com",
                    "ACME.com",
                    true,
                    null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(any(), eq(testCompanyId)))
                    .thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result.emailDomain()).isEqualTo("acme.com");
        }

        /**
         * Defense-in-depth for a value the HTTP boundary cannot deliver: {@code @Pattern} rejects a
         * whitespace-only domain with a 400 before the service runs, so over HTTP only {@code null}
         * (the field omitted) and {@code ""} — the empty string the DTO accepts through its {@code ^$}
         * alternative — reach this method, and both clear to {@code null}.
         */
        @Test
        @DisplayName("updateCompany - should clear the email domain when blank or whitespace")
        void updateCompany_BlankEmailDomain_ClearsToNull() {
            // Arrange
            testCompany.setEmailDomain("acme.com");
            CompanyRequest updateRequest = new CompanyRequest(
                    "1",
                    "Test Company",
                    1,
                    "Razon Social",
                    "XAXX010101000",
                    "+1234567890",
                    "test@company.com",
                    "   ",
                    true,
                    null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(any(), eq(testCompanyId)))
                    .thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result.emailDomain()).isNull();
            assertThat(testCompany.getEmailDomain()).isNull();
        }

        @Test
        @DisplayName("updateCompany - should not crash when the email domain is null")
        void updateCompany_NullEmailDomain_NoCrash() {
            // Arrange
            testCompany.setEmailDomain("acme.com");
            CompanyRequest updateRequest = new CompanyRequest(
                    "1",
                    "Test Company",
                    1,
                    "Razon Social",
                    "XAXX010101000",
                    "+1234567890",
                    "test@company.com",
                    null,
                    true,
                    null);

            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.existsByRfcAndIdNot(any(), eq(testCompanyId)))
                    .thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.updateCompany(testCompanyId, updateRequest);

            // Assert
            assertThat(result.emailDomain()).isNull();
        }
    }

    @Nested
    @DisplayName("deleteCompany")
    class DeleteCompanyTests {

        @Test
        @DisplayName("deleteCompany - should soft-delete company by setting enabled to false")
        void deleteCompany_Success() {
            // Arrange
            when(companyRepository.findById(testCompanyId)).thenReturn(Optional.of(testCompany));
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            companyService.deleteCompany(testCompanyId);

            // Assert
            verify(companyRepository).findById(testCompanyId);
            verify(companyRepository).save(any(Company.class));
        }

        @Test
        @DisplayName("deleteCompany - should throw CompanyNotFoundException when company not exists")
        void deleteCompany_NotFound_ThrowsException() {
            // Arrange
            UUID nonExistentId = UUID.randomUUID();
            when(companyRepository.findById(nonExistentId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> companyService.deleteCompany(nonExistentId))
                    .isInstanceOf(CompanyNotFoundException.class)
                    .hasMessageContaining("Company not found with id");
            verify(companyRepository, never()).save(any(Company.class));
        }
    }

    @Nested
    @DisplayName("createCompany")
    class CreateCompanyTests {

        @Test
        @DisplayName("should publish CompanyCreatedEvent after saving company")
        void shouldPublishEvent() {
            // Arrange
            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(testCompany);

            // Act
            companyService.createCompany(testCompanyRequest);

            // Assert
            verify(eventPublisher).publishEvent(any(CompanyCreatedEvent.class));
        }

        @Test
        @DisplayName("should publish CompanyCreatedEvent with correct company data")
        void shouldPublishEventWithCorrectData() {
            // Arrange
            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(testCompany);

            // Act
            companyService.createCompany(testCompanyRequest);

            // Assert
            var captor = ArgumentCaptor.forClass(CompanyCreatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            var event = captor.getValue();
            assertThat(event.getId()).isEqualTo(testCompanyId);
            assertThat(event.getCompanyKey()).isEqualTo(testCompany.getCompanyKey());
            assertThat(event.getCompanyName()).isEqualTo(testCompany.getCompanyName());
        }

        @Test
        @DisplayName("should map address fields when creating company with address")
        void shouldMapAddressFields() {
            // Arrange
            var requestWithAddress = new CompanyRequest(
                    "2",
                    "Company With Address",
                    1,
                    "Addr S.A. de CV",
                    "XAXX010101001",
                    "+9876543210",
                    "addr@company.com",
                    null,
                    true,
                    new AddressRequest("Av. Reforma", "456", "B", "Juarez", "67890", "Guadalajara", "Jalisco", null));

            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.createCompany(requestWithAddress);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.address()).isNotNull();
            assertThat(result.address().street()).isEqualTo("Av. Reforma");
            assertThat(result.address().streetNumber()).isEqualTo("456");
            assertThat(result.address().internalNumber()).isEqualTo("B");
            assertThat(result.address().neighborhood()).isEqualTo("Juarez");
            assertThat(result.address().zipCode()).isEqualTo("67890");
            assertThat(result.address().city()).isEqualTo("Guadalajara");
            assertThat(result.address().state()).isEqualTo("Jalisco");
            assertThat(result.address().countryId()).isNull();
        }

        @Test
        @DisplayName("should create company with null address fields when not provided")
        void shouldCreateCompanyWithoutAddress() {
            // Arrange
            var companyWithoutAddress = Company.builder()
                    .id(UUID.randomUUID())
                    .companyKey("3")
                    .companyName("No Address Company")
                    .tipoPersonaId(1)
                    .razonSocial("No Addr S.A.")
                    .rfc("XAXX010101002")
                    .phone("+1111111111")
                    .email("noaddr@company.com")
                    .enabled(true)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            CompanyRequest noAddressRequest = new CompanyRequest(
                    "3",
                    "No Address Company",
                    1,
                    "No Addr S.A.",
                    "XAXX010101002",
                    "+1111111111",
                    "noaddr@company.com",
                    null,
                    true,
                    null);

            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenReturn(companyWithoutAddress);

            // Act
            CompanyResponse result = companyService.createCompany(noAddressRequest);

            // Assert
            assertThat(result).isNotNull();
            assertThat(result.address()).isNull();
        }

        @Test
        @DisplayName("createCompany - should store and return the normalized email domain")
        void createCompany_StoresEmailDomain() {
            // Arrange
            CompanyRequest request = new CompanyRequest(
                    "1",
                    "Test Company",
                    1,
                    "Razon Social",
                    "XAXX010101000",
                    "+1234567890",
                    "test@company.com",
                    "  Acme.COM  ",
                    true,
                    null);

            when(companyRepository.existsByCompanyKey(any())).thenReturn(false);
            when(companyRepository.existsByRfc(any())).thenReturn(false);
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            CompanyResponse result = companyService.createCompany(request);

            // Assert
            assertThat(result.emailDomain()).isEqualTo("acme.com");
            var captor = ArgumentCaptor.forClass(Company.class);
            verify(companyRepository).save(captor.capture());
            assertThat(captor.getValue().getEmailDomain()).isEqualTo("acme.com");
        }
    }
}
