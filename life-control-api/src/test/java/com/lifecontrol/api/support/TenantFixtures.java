package com.lifecontrol.api.support;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The one way a test fixture persists a {@code companies} row, stating its tenant the way production does.
 *
 * <p><b>The rule.</b> A company's tenant <b>is its own id</b>: {@code companies}' ADR-0005 policy is
 * {@code id = current_setting('app.company_id', true)} for both {@code USING} and {@code WITH CHECK}. A
 * fixture that inserts one therefore has to state that id in the scope <b>before</b> the insert, exactly as
 * {@link com.lifecontrol.api.company.service.CompanyService#createCompany(com.lifecontrol.api.company.dto.CompanyRequest)}
 * does: generate the identity in
 * memory, wrap the save in {@link TenantScope#with(UUID, java.util.function.Supplier)}, and let the
 * repository's own transaction install it. There is deliberately no test-only bypass here — no
 * {@code SET LOCAL ROLE}, no {@code ALTER TABLE … NO FORCE}, no wider policy. A fixture that states its
 * tenant is the model the tests should teach.</p>
 *
 * <p><b>Why this helper exists.</b> Measured on the T6a run with the pool connected as
 * {@code lifecontrol_app}: <b>556 of 4237</b> backend tests failed and <b>537 shared one cause</b> — a
 * fixture inserted a {@code companies} row without stating a tenant, and PostgreSQL refused it with
 * SQLSTATE 42501 ({@code new row violates row-level security policy for table "companies"}). <b>535 of
 * those 537 carried no production stack frame</b>: production already did this right in
 * {@code CompanyService.createCompany}, and only the fixtures did not. This slice changes the fixtures,
 * not the production paths and not the call sites.</p>
 *
 * <p><b>The pool is not switched yet.</b> Until T6b the container's Superuser still backs the pool, so a
 * stated scope is inert and the suite stays green; the point of adopting the helper now is that the
 * fixtures already carry the fact the switch will require.</p>
 */
public final class TenantFixtures {

    /** The four columns every raw fixture needs; {@code enabled} keeps its schema default of {@code true}. */
    private static final String INSERT_COMPANY =
            "INSERT INTO companies (id, company_key, company_name, rfc) VALUES (?, ?, ?, ?)";

    private TenantFixtures() {}

    /**
     * Persists {@code company} through its repository, assigning the id first when the caller stated none
     * and wrapping the save in the tenant scope of that id.
     *
     * @param repository the repository the production path also uses
     * @param company the row to insert; its id is assigned in memory when absent
     * @return the persisted company, with the id the scope carried
     */
    public static Company persist(CompanyRepository repository, Company company) {
        var id = assignId(company);
        return TenantScope.with(id, () -> repository.save(company));
    }

    /**
     * Same as {@link #persist(CompanyRepository, Company)} for the fixtures that need the insert flushed
     * before the call returns.
     */
    public static Company persistAndFlush(CompanyRepository repository, Company company) {
        var id = assignId(company);
        return TenantScope.with(id, () -> repository.saveAndFlush(company));
    }

    /**
     * Persists {@code company} through an {@link EntityManager}, for the fixtures that build their graph by
     * hand instead of through a repository. The id is assigned in memory before the scope is stated, so the
     * value the scope carries is the value the insert writes.
     */
    public static Company persist(EntityManager entityManager, Company company) {
        var id = assignId(company);
        return TenantScope.with(id, () -> {
            entityManager.persist(company);
            return company;
        });
    }

    /**
     * Inserts a {@code companies} row through raw SQL, generating the id first and stating it around the
     * insert.
     *
     * <p><b>What this overload does and does not do yet.</b> It <i>states</i> the scope, so the call site
     * reads like the other three and every fixture carries the rule. What it cannot do alone is make the
     * statement reach the database with the scope installed: a plain {@code JdbcTemplate.update} draws its
     * own pooled connection and opens no transaction, so
     * {@code TenantScopeJpaTransactionManager.doBegin} never runs and {@code set_config} is never issued —
     * exactly the "SET LOCAL on somebody else's connection" ADR-0005 forbids. In this slice that costs
     * nothing, because the pool still connects as the container's superuser. T6b routes these fixtures
     * through a {@code TransactionTemplate} (which is what installs the scope and what makes the
     * {@code JdbcTemplate} reuse that connection); until it does, this overload is inert by construction
     * and the comment says so instead of implying otherwise.
     *</p>
     *
     * @return the id the row carries, so the caller can register it for cleanup or use it in the graph
     */
    public static UUID insertCompany(JdbcTemplate jdbcTemplate, String companyKey, String companyName, String rfc) {
        return insertCompany(jdbcTemplate, UUID.randomUUID(), companyKey, companyName, rfc);
    }

    /**
     * The same raw insert for the fixtures that mirror a migration and need to choose the id themselves.
     *
     * @return {@code id}, unchanged, so the two overloads read the same at the call site
     */
    public static UUID insertCompany(
            JdbcTemplate jdbcTemplate, UUID id, String companyKey, String companyName, String rfc) {
        TenantScope.with(id, () -> jdbcTemplate.update(INSERT_COMPANY, id, companyKey, companyName, rfc));
        return id;
    }

    /**
     * Assigns the identity in memory when the caller stated none, <b>before</b> any scope or insert, and
     * returns it. The entity's {@code @PrePersist} fallback runs inside the transaction, which is one
     * boundary too late for the scope, so the fixture has to state it here.
     */
    private static UUID assignId(Company company) {
        var id = company.getId();
        if (id == null) {
            id = UUID.randomUUID();
            company.setId(id);
        }
        return id;
    }
}
