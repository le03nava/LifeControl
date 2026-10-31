package com.lifecontrol.api.support;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
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
 *
 * <p><b>The second rule: a find-or-create seed derives the company's id from its natural key.</b> Many
 * suites seed a company idempotently — read it by {@code company_key}, insert it when absent — and once the
 * pool connects as {@code lifecontrol_app} that shape breaks in a specific way: the read runs with no
 * {@code app.company_id} stated, {@code companies}' policy answers <b>no</b> rows, the row another test in
 * the same class already inserted is invisible, and the insert is refused by
 * {@code companies_company_key_key}. Measured on T6b's baseline: <b>376 of 545</b> application-role failures
 * were exactly that message. {@link #findOrCreate(CompanyRepository, String, String, String)} is the shape
 * that fixes it, and its derivation is {@link #derivedId(String)}: the id is
 * {@code UUID.nameUUIDFromBytes(companyKey.getBytes(UTF_8))}, so the same key yields the same id in every
 * class, in every JVM, in every run.</p>
 *
 * <p><b>Why a derivation and not a convenience.</b> A company's tenant <b>is its own id</b>, so the derived
 * id can be stated as the scope <b>before the read</b> and the read then agrees with the policy that filters
 * it: the seed finds the row a previous seed inserted instead of inserting a second one. Two seeds only
 * agree, however, if <b>every</b> creator of that key uses the same derivation — a class that writes
 * {@code TEST-COMPANY} with a random id and another that derives the id from {@code TEST-COMPANY} still
 * collide, with a different id, which is the same bug wearing a new message. That is why the derivation
 * lives here, once, and why every creator of a key a find-or-create seed reads has to go through this
 * helper (record L25 carries the agreement check for the keys the suite seeds today). <b>Production keeps
 * generating random ids</b>: {@code CompanyService#createCompany} states the identity of a company a real
 * caller just created, and nothing outside the tests needs two writers of one key to agree.</p>
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
     * Inserts a {@code companies} row through raw SQL at the id the row's <b>natural key</b> derives, stating
     * that id around the insert.
     *
     * <p>The id is {@link #derivedId(String)}, the same function
     * {@link #findOrCreate(CompanyRepository, String, String, String)} uses, and for the same reason: a raw
     * fixture that inserts a key a repository seed later reads has to land on the same id, or the seed's
     * scoped read misses the row and its insert collides on {@code companies_company_key_key}. Generating a
     * random id here would be a second creator of the key with a second identity, which is the bug this rule
     * exists to make impossible.</p>
     *
     * <p><b>What this overload does and does not do yet.</b> It <i>states</i> the scope, so the call site
     * reads like the other three and every fixture carries the rule. What it cannot do alone is make the
     * statement reach the database with the scope installed: a plain {@code JdbcTemplate.update} draws its
     * own pooled connection and opens no transaction, so
     * {@code TenantScopeJpaTransactionManager.doBegin} never runs and {@code set_config} is never issued —
     * exactly the "SET LOCAL on somebody else's connection" ADR-0005 forbids. In this slice that costs
     * nothing, because the pool still connects as the container's superuser. T6b-2 routes these fixtures
     * through a {@code TransactionTemplate} (which is what installs the scope and what makes the
     * {@code JdbcTemplate} reuse that connection); until it does, this overload is inert by construction
     * and the comment says so instead of implying otherwise.</p>
     *
     * @return the id the row carries, so the caller can register it for cleanup or use it in the graph
     */
    public static UUID insertCompany(JdbcTemplate jdbcTemplate, String companyKey, String companyName, String rfc) {
        return insertCompany(jdbcTemplate, derivedId(companyKey), companyKey, companyName, rfc);
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
     * The id a seed derives from a company's natural key:
     * {@code UUID.nameUUIDFromBytes(companyKey.getBytes(UTF_8))}.
     *
     * <p><b>This is the one derivation, and it is deterministic on purpose</b> — not for convenience. A
     * company's tenant is its own id (ADR-0005 isolates {@code companies} by
     * {@code id = current_setting('app.company_id', true)}), so a find-or-create seed can state the id of the
     * row it is about to look for <b>before</b> looking for it, and then the read is scoped to exactly the row
     * it would insert. Two seeds of the same key agree only if both use this function: it is a single named
     * implementation so that no call site can invent a second one. {@code UUID.nameUUIDFromBytes} is
     * name-based version 3 (MD5), stable across JVMs and runs, and its input is the key's UTF-8 bytes — never
     * the name, the RFC or another column, which a fixture may rewrite.</p>
     *
     * <p>Call it directly only when a raw fixture has to insert a company at the id a find-or-create seed of
     * the same key derives; every other fixture goes through
     * {@link #findOrCreate(CompanyRepository, String, String, String)}.</p>
     *
     * @param companyKey the company's natural key, which is the value two writers have to agree on
     * @return the id every fixture of that key derives
     */
    public static UUID derivedId(String companyKey) {
        if (companyKey == null) {
            throw new IllegalArgumentException("a derived company id needs the natural key it is derived from");
        }
        return UUID.nameUUIDFromBytes(companyKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Finds the company with {@code companyKey} inside the tenant scope of its <b>derived</b> id, and inserts
     * it at that same id when it is absent.
     *
     * <p>This is the shape every idempotent company seed has to use. Stating the derived id before the read is
     * what makes the read and the insert agree: the policy filters {@code companies} to that id, so the seed
     * sees the row a previous seed inserted — in this test, in this class, or in any other class sharing the
     * container — instead of inserting a duplicate that {@code companies_company_key_key} refuses. Once the
     * pool connects as {@code lifecontrol_app}, a seed that reads with no scope stated sees <b>zero</b> rows and
     * fails exactly there (record L25: 376 of 545 failures).</p>
     *
     * <p>The <b>agreement</b> this depends on is not local: the key must not be written anywhere else at a
     * random id, or the scoped read misses that row and the insert collides again. Adopt this helper for
     * <b>every</b> creator of such a key; see {@link #derivedId(String)}.</p>
     *
     * @param repository the repository the production path also uses
     * @param companyKey the natural key the id is derived from and the read filters on
     * @param companyName the name to write when the row is absent
     * @param rfc the RFC to write when the row is absent
     * @return the found or persisted company, whose id is {@code derivedId(companyKey)}
     */
    public static Company findOrCreate(
            CompanyRepository repository, String companyKey, String companyName, String rfc) {
        return findOrCreate(repository, seedOf(companyKey, companyName, rfc));
    }

    /**
     * The same rule for the seeds that set more than the three required columns (an {@code emailDomain}, for
     * instance): the prototype carries everything the caller wants written and the helper replaces only its
     * id with {@link #derivedId(String)} of the prototype's own {@code companyKey}.
     *
     * <p>One implementation, so a caller cannot derive its own id and drift from the other seeds of that key.
     * The prototype is the entity that gets saved, and it carries the derived id before the scope is
     * stated.</p>
     *
     * @param repository the repository the production path also uses
     * @param seed the company to write when the key is absent; its {@code companyKey} is the derivation input
     * @return the found or persisted company, whose id is {@code derivedId(seed.getCompanyKey())}
     */
    public static Company findOrCreate(CompanyRepository repository, Company seed) {
        var id = derivedId(seed.getCompanyKey());
        seed.setId(id);
        return TenantScope.with(
                id, () -> repository.findByCompanyKey(seed.getCompanyKey()).orElseGet(() -> persist(repository, seed)));
    }

    /**
     * Same as {@link #findOrCreate(CompanyRepository, String, String, String)} for the seeds that need the
     * insert flushed before the call returns.
     */
    public static Company findOrCreateAndFlush(
            CompanyRepository repository, String companyKey, String companyName, String rfc) {
        var seed = seedOf(companyKey, companyName, rfc);
        var id = derivedId(seed.getCompanyKey());
        seed.setId(id);
        return TenantScope.with(
                id,
                () -> repository
                        .findByCompanyKey(seed.getCompanyKey())
                        .orElseGet(() -> persistAndFlush(repository, seed)));
    }

    /** The three required columns of a company, at the id its key derives. */
    private static Company seedOf(String companyKey, String companyName, String rfc) {
        return Company.builder()
                .id(derivedId(companyKey))
                .companyKey(companyKey)
                .companyName(companyName)
                .rfc(rfc)
                .build();
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
