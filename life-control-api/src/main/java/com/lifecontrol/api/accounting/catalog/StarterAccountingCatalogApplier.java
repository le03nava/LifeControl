package com.lifecontrol.api.accounting.catalog;

import com.lifecontrol.api.common.tenant.TenantScope;
import com.lifecontrol.api.company.exception.CompanyNotFoundException;
import com.lifecontrol.api.company.model.Company;
import com.lifecontrol.api.company.repository.CompanyRepository;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies {@link StarterAccountingCatalog} to one company: the same eight accounts, three posting rules and
 * seven rule lines the starter migrations seed into the companies that already existed when they ran (S9 of
 * {@code odd/tasks/derived-accounting.md}).
 *
 * <p><b>Why plain SQL and no JPA entities.</b> The three catalogs are written once per tenant and read back by
 * nothing in this slice — a report, when it exists, reads them through its own query, and a posting resolves
 * them through a rule. An entity set would exist only to be mapped, would need a strategy to load a row by
 * {@code (companyId, code)} or by {@code (ruleId, side, selector)}, and would invite an {@code UPDATE} of a row
 * whose only content is the seed. This is the shape the tree already chose for the append-only
 * {@code status_transition} ({@code StatusTransitionRecorder}): one {@code INSERT}, no entity.</p>
 *
 * <p><b>Every guard is one of the tenant's own keys, and that is also the idempotency contract.</b> A second run
 * inserts nothing and returns zero, because the accounts are matched by {@code (company_id, code)} —
 * {@code uq_account_company_code} — the rules by {@code (company_id, source_type, posting)} —
 * {@code uq_posting_rule_company_source_posting} — and the lines by {@code (rule_id, side, amount_selector)},
 * which is {@code uq_posting_rule_line_company_rule_side_selector} without the {@code company_id} the rule
 * already carries. That is what makes the listener's retry, an operator's re-run after a failed boot and a
 * restored dump all cost nothing, and it is the same property the migrations' {@code NOT EXISTS} guards hold for
 * the tenants that existed earlier.</p>
 *
 * <p><b>Ids are read back and never invented.</b> The accounts and the rules this run inserts are
 * database-generated {@code gen_random_uuid()} values, so the account ids are read back inside the same
 * transaction and matched to the catalog by code, and a rule's own id comes from the {@code INSERT … RETURNING
 * id} that created it. A company that already had one of the accounts by hand keeps that row and both the rules
 * and the lines point at it, which is why the read happens after the inserts and not from the catalog.</p>
 *
 * <p><b>The tenant is stated here, inside the transaction this class owns.</b> That boundary is the only place
 * the scope can be installed — {@code TenantScopeJpaTransactionManager#doBegin} reads the thread's
 * {@link TenantScope} when the transaction begins — and it begins <b>before</b> anything the caller stated could
 * matter. Two of the callers have no scope to hand over at all: an operator script, and the
 * {@code @TransactionalEventListener(fallbackExecution = true)} path, which by definition runs with no
 * transaction and no request scope. Relying on the caller meant the read of {@code companies} and the writes to
 * the armed {@code account}, {@code posting_rule} and {@code posting_rule_line} answered zero rows for exactly
 * those, and the applier reported a company that plainly exists as {@link CompanyNotFoundException}. So the id
 * the caller already knows is stated here, around the transaction, and a caller that states one too is
 * unaffected: the inner scope replaces it for the call and the outer one is restored afterwards.</p>
 *
 * <p>{@code REQUIRED}, because the seed is the company's book and the company must not exist without it: the
 * production caller is a {@code @TransactionalEventListener(BEFORE_COMMIT)}, so this write joins the transaction
 * that creates the company and a failure rolls the creation back. When a caller has no transaction — a test, an
 * operator script — {@code REQUIRED} opens one, which is why the same entry point serves both and why the
 * propagation is still chosen here and not with {@code @Transactional}: the scope has to be stated around the
 * boundary, in that order.</p>
 */
@Service
public class StarterAccountingCatalogApplier {

    /** The insert of one account. {@code created_at} is the schema's: the seed does not backdate it. */
    private static final String INSERT_ACCOUNT = """
            INSERT INTO account (company_id, code, name, kind, enabled)
            VALUES (?, ?, ?, ?, ?)
            """;

    /**
     * The insert of one rule, returning the id the database generated it with — the lines need it, and reading
     * it back here is what keeps the seed from needing an id of its own.
     */
    private static final String INSERT_POSTING_RULE = """
            INSERT INTO posting_rule (company_id, source_type, posting, enabled)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """;

    /** The insert of one line: which part of the source the rule moves, and where.
     * {@code created_at} is the schema's, as on the account. */
    private static final String INSERT_POSTING_RULE_LINE = """
            INSERT INTO posting_rule_line (company_id, rule_id, account_id, side, amount_selector)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ACCOUNT_CODES = "SELECT code FROM account WHERE company_id = ?";

    private static final String SELECT_ACCOUNT_IDS = "SELECT code, id FROM account WHERE company_id = ?";

    private static final String SELECT_RULE_IDS =
            "SELECT source_type, posting, id FROM posting_rule WHERE company_id = ?";

    private static final String SELECT_RULE_LINE_KEYS =
            "SELECT rule_id, side, amount_selector FROM posting_rule_line WHERE company_id = ?";

    private final CompanyRepository companyRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate seedTransaction;
    private final EntityManager entityManager;

    public StarterAccountingCatalogApplier(
            CompanyRepository companyRepository,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            EntityManager entityManager) {
        this.companyRepository = companyRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.seedTransaction = new TransactionTemplate(transactionManager);
        this.seedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * Inserts the catalog rows the company does not have yet and returns how many were inserted — accounts,
     * rules and rule lines — so a second run answers zero.
     *
     * <p>The order is the contract: the scope is stated first, the transaction begins second, the seed runs
     * third. {@code doBegin} reads the holder, so a boundary opened before the scope is stated would install
     * the previous tenant or none, and none is the fail-closed direction that makes this defect loud instead
     * of dangerous.</p>
     *
     * @return the number of rows inserted, {@code 0} when the company already had the whole catalog
     * @throws CompanyNotFoundException when the given id is not a company
     */
    public int applyTo(UUID companyId) {
        return TenantScope.with(companyId, () -> seedInTransaction(companyId));
    }

    /**
     * One {@code REQUIRED} transaction whose boundary installs the scope stated around it — joining the
     * caller's when there is one, so the company and its book commit or roll back together.
     *
     * <p>{@code execute} is declared nullable because a callback may return {@code null}; this one always
     * counts, and the guard keeps that fact out of an unboxing the compiler cannot check.</p>
     */
    private int seedInTransaction(UUID companyId) {
        var inserted = seedTransaction.execute(status -> seed(companyId));
        return inserted == null ? 0 : inserted;
    }

    private int seed(UUID companyId) {
        // The listener runs at BEFORE_COMMIT, which is before Hibernate flushes the company it just persisted:
        // without this flush the row is in the persistence context and not in the table, and every raw insert
        // below refuses with `account_company_id_fkey`. Flushing here is the seed stating that it needs to see
        // the company it is seeding, rather than the creation path knowing about the seed.
        entityManager.flush();

        var company = companyRepository.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));

        var existingCodes = new HashSet<>(jdbcTemplate.queryForList(SELECT_ACCOUNT_CODES, String.class, companyId));

        var inserted = 0;
        for (var entry : StarterAccountingCatalog.accounts()) {
            if (existingCodes.contains(entry.code())) {
                continue;
            }
            jdbcTemplate.update(INSERT_ACCOUNT, companyId, entry.code(), entry.name(), entry.kind(), true);
            existingCodes.add(entry.code());
            inserted++;
        }

        // Read back, because the ids of the rows this run inserted are the database's. The map is built from
        // the company's own rows, so a hand-made account is what a rule points at when its code matches.
        var accountIdsByCode = accountIdsByName(companyId);

        // The rules that already exist, by anchor, with their ids: a rule the company already had keeps its row
        // and its id, and its lines are matched against that id rather than against one this run would create.
        var ruleIdsByAnchor = new HashMap<String, UUID>();
        for (var row : jdbcTemplate.query(
                SELECT_RULE_IDS,
                (resultSet, rowNumber) -> Map.entry(
                        anchor(resultSet.getString("source_type"), resultSet.getString("posting")),
                        resultSet.getObject("id", UUID.class)),
                companyId)) {
            ruleIdsByAnchor.put(row.getKey(), row.getValue());
        }

        for (var rule : StarterAccountingCatalog.postingRules()) {
            var key = anchor(rule.sourceType(), rule.posting());
            if (ruleIdsByAnchor.containsKey(key)) {
                continue;
            }
            var ruleId = jdbcTemplate.queryForObject(
                    INSERT_POSTING_RULE, UUID.class, companyId, rule.sourceType(), rule.posting(), true);
            if (ruleId == null) {
                throw new IllegalStateException("posting_rule returned no id for company " + company.getId()
                        + " and anchor " + rule.sourceType() + "/" + rule.posting() + ": without it the rule's"
                        + " lines cannot be written, and a rule with no lines posts nothing.");
            }
            ruleIdsByAnchor.put(key, ruleId);
            inserted++;
        }

        // And the lines, matched by their own key inside the rule. Read before the loop so a line the company
        // already had — including the ones the migrations backfilled — is skipped instead of refused by
        // uq_posting_rule_line_company_rule_side_selector.
        var existingLines = new HashSet<String>();
        for (var row : jdbcTemplate.query(
                SELECT_RULE_LINE_KEYS,
                (resultSet, rowNumber) -> lineKey(
                        resultSet.getObject("rule_id", UUID.class),
                        resultSet.getString("side"),
                        resultSet.getString("amount_selector")),
                companyId)) {
            existingLines.add(row);
        }

        for (var rule : StarterAccountingCatalog.postingRules()) {
            var ruleId = ruleIdsByAnchor.get(anchor(rule.sourceType(), rule.posting()));
            if (ruleId == null) {
                throw new IllegalStateException("the accounting starter catalog declares the rule "
                        + rule.sourceType() + "/" + rule.posting() + " and company " + company.getId()
                        + " has no such rule after the seed: its "
                        + rule.lines().size() + " line(s) would point"
                        + " at nothing, and a rule with no lines posts nothing.");
            }
            for (var line : rule.lines()) {
                var key = lineKey(ruleId, line.side(), line.amountSelector());
                if (existingLines.contains(key)) {
                    continue;
                }
                jdbcTemplate.update(
                        INSERT_POSTING_RULE_LINE,
                        companyId,
                        ruleId,
                        requireAccount(accountIdsByCode, line.accountCode(), company),
                        line.side(),
                        line.amountSelector());
                existingLines.add(key);
                inserted++;
            }
        }

        return inserted;
    }

    private Map<String, UUID> accountIdsByName(UUID companyId) {
        var ids = new HashMap<String, UUID>();
        for (var row : jdbcTemplate.query(
                SELECT_ACCOUNT_IDS,
                (resultSet, rowNumber) -> Map.entry(resultSet.getString("code"), resultSet.getObject("id", UUID.class)),
                companyId)) {
            ids.put(row.getKey(), row.getValue());
        }
        return ids;
    }

    /**
     * The account one line of a rule names. A missing code is impossible once the accounts above are inserted,
     * so it stays an exception rather than a skip: a line silently pointing at nothing would post the right
     * amount nowhere, and the log line the listener writes is the only record a tenant would ever get.
     */
    private static UUID requireAccount(Map<String, UUID> accountIdsByCode, String code, Company company) {
        var id = accountIdsByCode.get(code);
        if (id == null) {
            throw new IllegalStateException("The accounting starter catalog names account " + code
                    + " and company " + company.getId() + " has no such account: the posting rule it belongs to"
                    + " cannot be seeded, and a rule that names no account is a rule that posts nowhere.");
        }
        return id;
    }

    private static String anchor(String sourceType, String posting) {
        return sourceType + "\u0000" + posting;
    }

    /**
     * A line's identity inside its rule: the two columns of {@code uq_posting_rule_line_company_rule_side_selector}
     * that a rule does not already fix. One line per side and selector is what makes a posting deterministic, and
     * it is also the guard that makes a second run insert nothing.
     */
    private static String lineKey(UUID ruleId, String side, String amountSelector) {
        return ruleId + "\u0000" + side + "\u0000" + amountSelector;
    }
}
