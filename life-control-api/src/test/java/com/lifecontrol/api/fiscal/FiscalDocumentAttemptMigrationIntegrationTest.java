package com.lifecontrol.api.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifecontrol.api.support.AbstractPostgresIntegrationTest;
import com.lifecontrol.api.support.TenantFixtures;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code V110__fiscal_document_attempt.sql}: the attempt key ({@code S11} of
 * {@code odd/tasks/cfdi-fiscal-document.md}, decision {@code L12(h)} of the same record).
 *
 * <p><b>What the file makes true, and therefore what is asserted.</b> One attempt is one document: the key is
 * unique per {@code (company_id, source_type, source_id)}, so a replayed attempt finds its own row and writes
 * nothing. The key is <b>nullable</b>, and that is the second half of the same rule: a cancellation and a
 * replacement ({@code S7}, {@code T8}) are rows of this table that are not attempts and must not have to
 * invent a key, and PostgreSQL treats {@code NULL}s as distinct in a unique constraint, so any number of
 * keyless rows of one sale is legal.
 *
 * <p><b>Why the shape is read from the catalog and not from a row count.</b> A {@code UNIQUE} constraint over
 * the wrong four columns, or one under the right name holding three, would refuse a legal pair and pass every
 * reader that only looked for the name. So each case reads {@code pg_constraint}'s own column list back, in
 * order, and the vocabulary of the behaviour is observed by writing the rows the rule is about.</p>
 *
 * <p><b>Why the rows are written here and not through the writer.</b> The constraint is the schema's, and the
 * writer's idempotency is the sibling suite's subject. This one states the four pairs the key is about —
 * same key twice, {@code NULL} twice, one key on two sales — as raw inserts, which is the only way to observe
 * what the database does without a service deciding for it first.</p>
 */
@SpringBootTest
@Transactional
@DisplayName("Fiscal document attempt key (V110)")
class FiscalDocumentAttemptMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The id an attempt key is about; {@code source_id} carries no foreign key, on purpose (`V108`). */
    private static final UUID FIRST_SALE = UUID.fromString("0b3f1d52-4e29-4a4b-9d3e-6b8f0a1c2d3e");

    private static final UUID SECOND_SALE = UUID.fromString("7c5f2a90-1b8c-4d2e-8f47-9a0e1b2c3d4f");

    @Autowired
    private JdbcTemplate jdbc;

    private UUID company;

    @BeforeEach
    void seedAnIssuerToWriteUnder() {
        company = UUID.randomUUID();
        var suffix = company.toString().replace("-", "").substring(0, 8).toUpperCase();
        TenantFixtures.stateTenantWithinTheOpenTransaction(company, jdbc);
        jdbc.update(
                "INSERT INTO companies (id, company_key, company_name, razon_social, rfc, currency, regimen_fiscal,"
                        + " fiscal_zip_code) VALUES (?, ?, ?, ?, ?, 'MXN', '601', '06700')",
                company,
                "V110-ATTEMPT-" + suffix,
                "V110 Attempt Company",
                "V110 Attempt SA de CV",
                "RFC" + suffix);
    }

    @Test
    @DisplayName("V110: the attempt key is a VARCHAR(64) that admits NULL, because a cancellation is a row of "
            + "this table and not an attempt")
    void theAttemptKeyIsNullableAndSixtyFourCharactersWide() {
        var column = jdbc.queryForMap(
                "SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'fiscal_document' AND column_name = 'attempt_key'");

        assertThat(column.get("data_type")).isEqualTo("character varying");
        assertThat(column.get("character_maximum_length")).isEqualTo(64);
        assertThat(column.get("is_nullable"))
                .as("T8's cancellation and replacement rows state no attempt, so the column cannot be required")
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("V110/L12(h): the key is unique over (company_id, source_type, source_id, attempt_key) and not "
            + "over any other set of columns")
    void theUniqueKeyCoversTheCompanyTheSourceAndTheAttempt() {
        var columns = jdbc.queryForList("""
                SELECT a.attname AS name
                  FROM pg_constraint c
                  CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, position)
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                 WHERE c.conname = 'uq_fiscal_document_attempt'
                   AND c.conrelid = 'fiscal_document'::regclass
                   AND c.contype = 'u'
                 ORDER BY k.position
                """, String.class);

        assertThat(columns)
                .as("a key that omitted the source would make one attempt name every sale, and one that omitted "
                        + "the company would collide across tenants")
                .containsExactly("company_id", "source_type", "source_id", "attempt_key");
    }

    @Test
    @DisplayName("S11: a second row of the same sale with the same attempt key is refused by the database and "
            + "the first row stands")
    void aDuplicateAttemptKeyIsRefusedAndTheFirstRowStands() {
        insertDraft("attempt-1", FIRST_SALE);
        assertThat(countOf(FIRST_SALE)).isEqualTo(1);

        jdbc.execute("SAVEPOINT duplicate_attempt");
        assertThatThrownBy(() -> insertDraft("attempt-1", FIRST_SALE))
                .as("the refusal is the schema's, so it arrives as the constraint's own name")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_fiscal_document_attempt");
        jdbc.execute("ROLLBACK TO SAVEPOINT duplicate_attempt");

        assertThat(countOf(FIRST_SALE)).as("the refused insert wrote nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("S7: any number of keyless rows of one sale is legal, because a cancellation and a replacement "
            + "are not attempts")
    void rowsWithoutAnAttemptKeyDoNotCompeteForTheKey() {
        insertDraft(null, FIRST_SALE);
        insertDraft(null, FIRST_SALE);

        assertThat(countOf(FIRST_SALE))
                .as("PostgreSQL treats NULLs as distinct, which is exactly the shape a cancelled document and "
                        + "its replacement need")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("V110/L12(h): one attempt key on two different sales is legal, because the key names an "
            + "attempt of a sale and never a global attempt")
    void theSameAttemptKeyOnAnotherSaleIsLegal() {
        insertDraft("attempt-1", FIRST_SALE);
        insertDraft("attempt-1", SECOND_SALE);

        assertThat(countOf(FIRST_SALE)).isEqualTo(1);
        assertThat(countOf(SECOND_SALE)).isEqualTo(1);
    }

    /** One {@code DRAFT} document of the current company, stating only the columns the schema requires. */
    private void insertDraft(String attemptKey, UUID sale) {
        jdbc.update("""
                INSERT INTO fiscal_document
                    (company_id, source_type, source_id, status, issued_by, attempt_key,
                     receptor_rfc, receptor_name, receptor_regimen, receptor_zip_code, receptor_uso_cfdi,
                     emisor_rfc, emisor_name, emisor_regimen, lugar_expedicion, currency)
                VALUES (?, 'SALES_ORDER', ?, 'DRAFT', 'suite:actor', ?,
                        'RECE010101AB1', 'V110 Receiver SA de CV', '612', '44100', 'G03',
                        'ISE010101AB2', 'V110 Issuer SA de CV', '601', '06700', 'MXN')
                """, company, sale, attemptKey);
    }

    private long countOf(UUID sale) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fiscal_document WHERE company_id = ? AND source_id = ?",
                Long.class,
                company,
                sale);
    }
}
