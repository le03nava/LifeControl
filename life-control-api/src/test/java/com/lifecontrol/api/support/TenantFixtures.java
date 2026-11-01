package com.lifecontrol.api.support;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p><b>The third rule: where a write has no transaction of its own, the fixture opens one.</b> A scope
 * declared with no boundary installs nothing, and the T6b-2 baseline is what that costs: <b>545 of 4246</b>
 * tests failed with the pool on {@code lifecontrol_app}, and 163 of them were statements that never passed a
 * transaction boundary at all — a raw {@code JdbcTemplate} {@code INSERT} that drew its own pooled
 * connection, or a per-class seed saving through a {@code JdbcTemplate} nobody had opened a transaction for.
 * {@code set_config(..., true)} is {@code SET LOCAL}, so without a boundary there is nowhere to put it.</p>
 *
 * <p><b>Which entry points own the boundary, and which do not.</b> The distinction is measured, not
 * stylistic. {@link #persist(CompanyRepository, Company)}, {@link #persistAndFlush(CompanyRepository, Company)}
 * and the two {@code findOrCreate} shapes go through a Spring Data repository, and Spring Data's own
 * {@code @Transactional} is already a boundary — a real one that runs
 * {@link com.lifecontrol.api.common.tenant.TenantScopeJpaTransactionManager#doBegin} and therefore installs
 * the scope this helper states before calling it. Opening a second, {@code REQUIRES_NEW} transaction around
 * a repository call would change what the suite observes (the row would commit and survive a
 * {@code @Transactional} suite's rollback) to achieve nothing. So those four stay as they are, and the
 * javadoc says so rather than leaving it to be inferred.</p>
 *
 * <p>The {@code JdbcTemplate} entry points and {@link #inTenant(UUID, java.util.function.Supplier)} are the
 * ones that <b>do</b> open their own boundary, because a raw statement has none: it borrows a pooled
 * connection, opens no transaction, and {@code SET LOCAL} on it would be a locally-scoped no-op — exactly
 * the "SET LOCAL on somebody else's connection" ADR-0005 forbids. Inside a transaction Spring owns, the
 * {@code JdbcTemplate} reuses the transaction's connection instead, which is the only way the scope reaches
 * the statement.</p>
 *
 * <p>An {@code @Transactional} suite whose own transaction began before its fixture stated a scope is a
 * different defect with the same symptom (record L24, cause B: 26 failures), and it was T6b-3's. There the
 * boundary exists and cannot be retro-fitted with a scope, so neither entry point above helps: the scope has to
 * be stated <b>inside the transaction that is already open</b>, which is
 * {@link #stateTenantWithinTheOpenTransaction(UUID, JdbcOperations)}. This helper does not do that silently for
 * it, because the call is the one thing each of those suites has to state itself: which tenant its case works
 * on, and when it switches.</p>
 *
 * <p><b>Why the third rule can no longer be delegated to a later slice.</b> The rule was adopted before the
 * pool logged in as the application role, when a stated scope was inert and the suite stayed green without
 * it (record T6b-2). The pool now <b>is</b> the application role in every run — T6b-4 removed the switch that
 * made this optional — so a raw statement outside a boundary is refused by the policy instead of passing
 * silently, and S5's acceptance run is the one that keeps it that way.</p>
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

    /**
     * The currency every fixture company is stated in when its case does not choose one.
     *
     * <p>{@code V57__company_currency.sql} made {@code companies.currency} {@code NOT NULL} with no
     * {@code DEFAULT} — deliberately, because a default would write one country's currency onto another's
     * sales and never say so (ADR-0002 decision 2, D-c). The fixtures therefore have to state the value, and
     * they state the one row the migration seeds: the only currency the product names today
     * ({@code life-control-app-angular/src/app/app.config.ts:33}).</p>
     *
     * <p><b>This is a fixture default and not a product one.</b> The create path
     * ({@code CompanyRequest.currency}) is required and refuses an absent value with a 400; nothing in
     * {@code src/main} falls back to this. A fixture whose case is about a different currency states it
     * explicitly and this constant is never consulted.</p>
     */
    public static final String SEED_CURRENCY = "MXN";

    /**
     * The four columns a raw fixture needs against a database that <b>predates</b> {@code V57}:
     * {@code enabled} keeps its schema default of {@code true}, and {@code companies.currency} does not exist
     * yet.
     *
     * <p>Only a suite that holds a scratch database at an earlier head may use this shape — the migration
     * suites that seed a company before the column exists and then migrate. A fixture against the shared
     * container (or any database at head) states the currency through
     * {@link #insertCompany(JdbcTemplate, String, String, String, String)}, because there it is
     * {@code NOT NULL}.</p>
     */
    private static final String INSERT_COMPANY =
            "INSERT INTO companies (id, company_key, company_name, rfc) VALUES (?, ?, ?, ?)";

    /** The same insert for a database at head, where {@code V57} made the currency required. */
    private static final String INSERT_COMPANY_WITH_CURRENCY =
            "INSERT INTO companies (id, company_key, company_name, rfc, currency) VALUES (?, ?, ?, ?, ?)";

    private TenantFixtures() {}

    /**
     * The boundary every writing entry point opens, bound once by the PostgreSQL test base class.
     *
     * <p>A static binding for the same reason {@link TenantScope} is a static holder: the call sites are
     * fixtures that receive a repository, an {@code EntityManager} or a {@code JdbcTemplate} from Spring and
     * cannot receive a manager without every one of sixty-five classes growing a field for a fact that is
     * constant for the whole run. {@code AbstractPostgresIntegrationTest} binds the context's own manager
     * through an autowired method, so the boundary the fixtures open is the one the application itself uses
     * — the manager that installs the scope — and not a second one with different semantics.</p>
     */
    private static PlatformTransactionManager transactionManager;

    /**
     * Binds the manager the writing entry points open their transactions on.
     *
     * <p>Called by {@code AbstractPostgresIntegrationTest} for every PostgreSQL suite; a Mockito suite binds
     * its own recording manager so the boundary contract can be pinned without a database.</p>
     *
     * @param manager the context's transaction manager, which must be the one that installs the scope
     */
    public static void bindTransactionManager(PlatformTransactionManager manager) {
        transactionManager = manager;
    }

    /**
     * Removes the binding, so a suite that bound its own manager does not leave it on the JVM for the next
     * class. Never called by a production path: the application has no use for this helper at all.
     */
    public static void unbindTransactionManager() {
        transactionManager = null;
    }

    /**
     * Runs {@code work} inside one new transaction whose boundary states {@code companyId}, restoring the
     * thread's previous scope afterwards.
     *
     * <p>The order is the contract: the scope is stated first, the transaction begins second, the work runs
     * third. {@code doBegin} reads the holder, so a boundary opened before the scope is stated installs the
     * previous tenant or none, and the work then reads or writes another tenant's rows (or none, which is
     * the fail-closed direction and the reason this bug was loud rather than dangerous).</p>
     *
     * <p>{@code REQUIRES_NEW} and not the default {@code REQUIRED}: this entry point exists for the raw
     * statements that have no boundary, and a suite annotated {@code @Transactional} has already begun its
     * transaction before {@code @BeforeEach} runs — so joining it would mean joining a boundary that never
     * saw the scope, which is the shape that installs nothing. The consequence is declared rather than
     * hidden: a row a fixture writes this way is committed and survives that suite's rollback.</p>
     *
     * @param companyId the tenant of the work, which the policies compare against
     * @param work the statements to run inside that transaction
     * @return whatever {@code work} returns
     */
    public static <T> T inTenant(UUID companyId, java.util.function.Supplier<T> work) {
        var template = new TransactionTemplate(requireTransactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return TenantScope.with(companyId, () -> template.execute(status -> work.get()));
    }

    /**
     * Same as {@link #inTenant(UUID, java.util.function.Supplier)} for the fixtures that only write.
     */
    public static void inTenant(UUID companyId, Runnable work) {
        inTenant(companyId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * The one statement this helper runs: V52's own function, which is {@code set_config('app.company_id', …, true)}.
     */
    private static final String SET_TENANT_SCOPE = "select set_tenant_scope(cast(? as uuid))";

    /**
     * States {@code companyId} as the tenant of the transaction that is <b>already open</b> on this thread,
     * on that transaction's own connection, and through the same V52 function production's boundary runs.
     *
     * <p><b>Why it exists: the ordering.</b> A Spring {@code @Transactional} test begins its transaction
     * <b>before</b> {@code @BeforeEach} runs, so
     * {@link com.lifecontrol.api.common.tenant.TenantScopeJpaTransactionManager#doBegin} has already run —
     * with no scope — by the time a suite states one. Nothing can be retro-fitted at that boundary: the
     * transaction is open, its first statement may already have run, and {@link TenantScope#with} only
     * changes what a <b>later</b> boundary reads. What is still available is the transaction itself:
     * {@code set_tenant_scope} is {@code set_config(…, true)}, i.e. {@code SET LOCAL}, so it can be executed
     * inside the transaction that already exists and applies to every statement that transaction runs from
     * then on — which is exactly the set of statements the suite's fixtures and its {@code MockMvc} requests
     * join. Measured on T6b-3's baseline: 83 tests across the {@code @Transactional} suites failed with
     * SQLSTATE 42501 on their very first fixture insert (record L24, cause B).</p>
     *
     * <p><b>Why it is not {@link #inTenant(UUID, java.util.function.Supplier)}.</b> That one exists for a
     * statement with <b>no</b> boundary of its own: it opens a {@code REQUIRES_NEW} transaction whose
     * {@code doBegin} installs the scope. Here the boundary exists and is the test's own, so a
     * {@code REQUIRES_NEW} wrapper would install the scope in a <b>different</b> transaction — the test's own
     * reads and writes, and every {@code MockMvc} request that joins the test transaction, would still run
     * unscoped — and it would commit the fixture rows past the rollback the suite relies on to leave the
     * shared container clean. The two helpers answer two different defects and are not interchangeable.</p>
     *
     * <p><b>Why it is not a bypass.</b> It runs the SQL production runs, on the connection the transaction
     * already owns: {@code set_tenant_scope} is the function V52 defines and
     * {@code TenantScopeJpaTransactionManager#install} calls at every boundary. There is no {@code SET ROLE},
     * no {@code ALTER TABLE … NO FORCE}, no policy changed and no exception to one: a row the policy refuses
     * under this scope is still refused. A suite that states the wrong tenant sees the policy's zero rows, not
     * a wider answer.</p>
     *
     * <p><b>The call shape.</b> Once per {@code @Transactional} suite, with the tenant its case works on — at
     * the start of the test body or of its {@code @BeforeEach} — and <b>again before switching tenants</b>,
     * because one transaction carries one {@code SET LOCAL} value at a time and the last statement wins. A seed
     * that writes two companies therefore has to state each one's own id and flush in between: a company's
     * tenant is its own id, so no single stated value can admit two {@code companies} rows.</p>
     *
     * <p><b>Refusing outside a transaction is deliberate.</b> {@code SET LOCAL} outside a transaction block is
     * a warning PostgreSQL answers with success, so a call made with no transaction would silently state
     * nothing on a connection that is about to be returned to the pool — the failure mode this slice exists to
     * end.</p>
     *
     * @param companyId the tenant the open transaction states from here on
     * @param jdbc an operations handle bound to the test context; inside the open transaction it reuses that
     *     transaction's connection instead of borrowing another one, which is what makes the {@code SET LOCAL}
     *     reach the suite's statements
     * @throws IllegalStateException when no transaction is active on this thread
     */
    public static void stateTenantWithinTheOpenTransaction(UUID companyId, JdbcOperations jdbc) {
        if (companyId == null) {
            throw new IllegalArgumentException(
                    "stating the tenant of an open transaction needs the company it is stated for");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("No transaction is active on this thread, so stating " + companyId
                    + " would install nothing: set_config(..., true) is SET LOCAL, which outside a transaction"
                    + " block is a no-op PostgreSQL answers with success. An @Transactional suite calls this from"
                    + " its own test body or @BeforeEach; a write with no boundary of its own goes through"
                    + " TenantFixtures.inTenant(...) instead.");
        }
        jdbc.query(SET_TENANT_SCOPE, rs -> {}, companyId);
    }

    /**
     * The manager the writes open their transactions on, refusing when nothing was bound.
     *
     * <p>Refusing is deliberate. A fallback that ran the write with no transaction would insert with no
     * scope stated, and the policy would refuse it — which turns a fixture mistake into a policy finding, the
     * exact confusion T6b exists to end.</p>
     */
    private static PlatformTransactionManager requireTransactionManager() {
        var manager = transactionManager;
        if (manager == null) {
            throw new IllegalStateException("No transaction manager is bound to TenantFixtures: a fixture write "
                    + "needs the boundary its tenant is installed in. A PostgreSQL suite extends "
                    + "AbstractPostgresIntegrationTest, which binds it; a Mockito suite binds its own with "
                    + "TenantFixtures.bindTransactionManager(...).");
        }
        return manager;
    }

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
        return TenantScope.with(id, () -> repository.save(statingTheSeedCurrency(company)));
    }

    /**
     * Same as {@link #persist(CompanyRepository, Company)} for the fixtures that need the insert flushed
     * before the call returns.
     */
    public static Company persistAndFlush(CompanyRepository repository, Company company) {
        var id = assignId(company);
        return TenantScope.with(id, () -> repository.saveAndFlush(statingTheSeedCurrency(company)));
    }

    /**
     * Persists {@code company} through an {@link EntityManager}, for the fixtures that build their graph by
     * hand instead of through a repository. The id is assigned in memory before the scope is stated, so the
     * value the scope carries is the value the insert writes.
     */
    public static Company persist(EntityManager entityManager, Company company) {
        var id = assignId(company);
        statingTheSeedCurrency(company);
        return TenantScope.with(id, () -> {
            entityManager.persist(company);
            return company;
        });
    }

    /**
     * States {@link #SEED_CURRENCY} on a fixture company whose case did not choose one.
     *
     * <p>{@code V57} made {@code companies.currency} required, and every one of the sixty-odd classes that
     * seed a company through this helper would otherwise have to name a currency it is not testing. The
     * case that <b>is</b> about a currency sets {@code .currency(...)} on the builder and this method leaves
     * it alone — so the helper never overwrites a stated value, and no assertion about the currency is
     * weakened by the default.</p>
     */
    private static Company statingTheSeedCurrency(Company company) {
        if (company.getCurrency() == null) {
            company.setCurrency(SEED_CURRENCY);
        }
        return company;
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
     * <p><b>What this overload does now.</b> It states the scope <b>and</b> opens the boundary that installs
     * it: a plain {@code JdbcTemplate.update} draws its own pooled connection and opens no transaction, so
     * the statement only meets the scope inside a transaction Spring owns, where the {@code JdbcTemplate}
     * reuses the transaction's connection instead of borrowing another one.</p>
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
        inTenant(id, () -> jdbcTemplate.update(INSERT_COMPANY, id, companyKey, companyName, rfc));
        return id;
    }

    /**
     * The same raw insert for a database <b>at head</b>, where {@code V57} made the currency required.
     *
     * <p>The currency is a parameter and not {@link #SEED_CURRENCY} read inside, so a case that needs a second
     * currency states it instead of copying the literal. The catalog carries only the seeded code, so a case
     * that names another one inserts that row first — the same thing a migration would do.</p>
     *
     * @return the id the row carries, so the caller can register it for cleanup or use it in the graph
     */
    public static UUID insertCompany(
            JdbcTemplate jdbcTemplate, String companyKey, String companyName, String rfc, String currency) {
        return insertCompany(jdbcTemplate, derivedId(companyKey), companyKey, companyName, rfc, currency);
    }

    /**
     * The same raw insert for the fixtures that mirror a migration and need to choose the id themselves.
     *
     * @return {@code id}, unchanged, so the two overloads read the same at the call site
     */
    public static UUID insertCompany(
            JdbcTemplate jdbcTemplate, UUID id, String companyKey, String companyName, String rfc, String currency) {
        inTenant(
                id,
                () -> jdbcTemplate.update(INSERT_COMPANY_WITH_CURRENCY, id, companyKey, companyName, rfc, currency));
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
