package com.lifecontrol.api.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.customer.model.Customer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The issue-time half of S2: {@link Customer#fiscalProfileComplete()} — the one question the document slice (A2)
 * asks before it builds a document, and decision L8(f) of {@code odd/tasks/cfdi-fiscal-document.md}, which made
 * "required at issue" a predicate instead of an exception thrown nowhere.
 *
 * <p>It is a unit test on purpose. The predicate reads a row and nothing else, and the four facts it consults are
 * the same four the write path requires together, so the only things worth asserting are the answers: a profile
 * with all four is complete, one missing any of them is not, and the optional half changes nothing in either
 * direction. The HTTP suite covers the write-time refusal and the round-trip; this one covers the question
 * {@code A2} will call.</p>
 */
@DisplayName("The receiver's fiscal profile is complete when the four facts a stamp consumes are declared")
class CustomerFiscalProfilePredicateTest {

    private static final Map<String, Consumer<Customer.Builder>> REQUIRED_FACTS = requiredFacts();

    private static Map<String, Consumer<Customer.Builder>> requiredFacts() {
        var facts = new LinkedHashMap<String, Consumer<Customer.Builder>>();
        facts.put("legalName", builder -> builder.legalName("Fiscal Receiver SA"));
        facts.put("regimenFiscal", builder -> builder.regimenFiscal("601"));
        facts.put("fiscalZipCode", builder -> builder.fiscalZipCode("06700"));
        facts.put("defaultUsoCfdi", builder -> builder.defaultUsoCfdi("G03"));
        return facts;
    }

    @Test
    @DisplayName("a customer with all four facts declared is complete, whether or not the optional half is stated")
    void allFourFactsDeclaredIsComplete() {
        assertThat(completeProfile().fiscalProfileComplete())
                .as("the four facts a stamp consumes, and nothing else")
                .isTrue();

        assertThat(allFour().build())
                .extracting(Customer::fiscalProfileComplete)
                .as("the optional half is carried and optional (L8(e)): a profile without a street, a state or a "
                        + "country is a profile")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("a customer missing any one of the four facts is not complete, and the missing one is named")
    void anyMissingFactIsIncomplete() {
        REQUIRED_FACTS.forEach((missing, stated) -> {
            var builder = Customer.builder()
                    .id(UUID.randomUUID())
                    .name("Receiver")
                    .salesChannel("STORE")
                    .enabled(true);
            REQUIRED_FACTS.forEach((field, apply) -> {
                if (!field.equals(missing)) {
                    apply.accept(builder);
                }
            });

            assertThat(builder.build().fiscalProfileComplete())
                    .as(
                            "%s is one of the four facts a stamp consumes, so its absence makes the profile "
                                    + "incomplete at issue",
                            missing)
                    .isFalse();
        });
    }

    @Test
    @DisplayName("a customer with no profile at all is not complete, which is exactly a cash customer")
    void noProfileIsIncomplete() {
        assertThat(Customer.builder()
                        .id(UUID.randomUUID())
                        .name("Cash customer")
                        .salesChannel("STORE")
                        .enabled(true)
                        .build()
                        .fiscalProfileComplete())
                .as("a cash customer states no profile and is created normally; what it cannot be is a receiver")
                .isFalse();
    }

    @Test
    @DisplayName("a blank fact is not a declared one, for a row written by somebody other than this API")
    void blankIsNotDeclared() {
        assertThat(Customer.builder()
                        .id(UUID.randomUUID())
                        .name("Receiver")
                        .salesChannel("STORE")
                        .enabled(true)
                        .legalName("Fiscal Receiver SA")
                        .regimenFiscal("601")
                        .fiscalZipCode("06700")
                        .defaultUsoCfdi("  ")
                        .build()
                        .fiscalProfileComplete())
                .as("the canonical constructor of CustomerRequest collapses blank to null, and a row written by a "
                        + "writer that did not still answers the same question the same way")
                .isFalse();
    }

    private static Customer completeProfile() {
        return allFour()
                .legalName("Fiscal Receiver SA")
                .fiscalStreet("Av. Reforma 1")
                .fiscalState("Ciudad de México")
                .build();
    }

    private static Customer.Builder allFour() {
        var builder = Customer.builder()
                .id(UUID.randomUUID())
                .name("Receiver")
                .salesChannel("STORE")
                .enabled(true);
        REQUIRED_FACTS.values().forEach(apply -> apply.accept(builder));
        return builder;
    }
}
