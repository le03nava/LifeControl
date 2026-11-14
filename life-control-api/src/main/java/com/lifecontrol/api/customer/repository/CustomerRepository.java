package com.lifecontrol.api.customer.repository;

import com.lifecontrol.api.customer.model.Customer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The customer reads, every one of them keyed on the caller's tenant.
 *
 * <p><b>Why every finder names {@code companyId} and not only {@code id}.</b> {@code V105} armed the table,
 * so the policy already filters the rows a transaction may see — but the tenant is then a property of the
 * connection and not of the call. Naming {@code (companyId, id)} states the same fact in the query the
 * repository runs: a customer of another company is simply <b>not there</b> for this one, which is what makes
 * the answer a 404 instead of a 403 (the tree's {@code ResourceNotFoundException} convention, S4), and it
 * keeps the scoping observable in a suite that runs without a policy at all.</p>
 *
 * <p><b>There are deliberately no unscoped finders left.</b> {@code findByEnabledTrue()},
 * {@code findBySalesChannel(String)} and {@code findBySearchTerm(String, Pageable)} were the three the
 * pre-{@code V105} shape could get away with, and each of them answered every tenant's customers to anyone
 * holding {@code lc-sales}. A method nobody can call is the only way that stays true.</p>
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /** One customer of one company, or empty when it belongs to another one or to none. */
    Optional<Customer> findByCompanyIdAndId(UUID companyId, UUID id);

    /** Whether this company has this customer, without loading it. */
    boolean existsByCompanyIdAndId(UUID companyId, UUID id);

    /** The company's enabled customers, newest first: the list the endpoint serves. */
    Page<Customer> findByCompanyIdAndEnabledTrueOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /** The company's customers of one sales channel, which is the shape a fixture finds-or-creates by. */
    List<Customer> findByCompanyIdAndSalesChannel(UUID companyId, String salesChannel);

    /** The company's enabled customers, narrowed by name or email: the search the endpoint serves. */
    @Query("""
        SELECT c FROM Customer c
        WHERE c.companyId = :companyId
          AND c.enabled = true
          AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.createdAt DESC
        """)
    Page<Customer> findByCompanyIdAndSearchTerm(
            @Param("companyId") UUID companyId, @Param("search") String search, Pageable pageable);
}
