package com.lifecontrol.api.hr.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Spec of the pure email generator (record T6). No Spring, no Mockito: the generator is a static
 * helper and every rule here is a pure function of its inputs.
 *
 * <p>Two defects are proven by construction rather than asserted in prose: without the NFD
 * normalizer plus {@code \p{M}} stripping, {@code Pérez} becomes {@code prez}; and without
 * {@link Locale#ROOT}, lowercasing under a Turkish default turns {@code I} into {@code ı}. The
 * second is proven by temporarily setting the JVM default locale and restoring it in a
 * {@code finally} block.</p>
 */
@DisplayName("EmployeeEmailGenerator Tests")
class EmployeeEmailGeneratorTest {

    private static final int PART_CAP = 40;
    private static final int LOCAL_PART_CAP = 64;

    @Nested
    @DisplayName("localPart")
    class LocalPartTests {

        @Test
        @DisplayName("strips accents instead of dropping the accented letter")
        void localPart_Accents_AreTransliterated() {
            assertThat(EmployeeEmailGenerator.localPart("Juan", "Pérez")).isEqualTo("juan.perez");
            assertThat(EmployeeEmailGenerator.localPart("Juan", "Pérez")).isNotEqualTo("juan.prez");
        }

        @Test
        @DisplayName("strips the tilde and the accent in a hyphenated name")
        void localPart_TildeAndAccentAndHyphen_AreStripped() {
            assertThat(EmployeeEmailGenerator.localPart("Ana", "Peña-Ríos")).isEqualTo("ana.penarios");
        }

        @Test
        @DisplayName("removes an apostrophe")
        void localPart_Apostrophe_IsRemoved() {
            assertThat(EmployeeEmailGenerator.localPart("Brian", "O'Connor")).isEqualTo("brian.oconnor");
        }

        @Test
        @DisplayName("concatenates the particles of a last name")
        void localPart_Particles_AreConcatenated() {
            assertThat(EmployeeEmailGenerator.localPart("Ana", "De la Cruz")).isEqualTo("ana.delacruz");
        }

        @Test
        @DisplayName("keeps only the first given name")
        void localPart_SeveralGivenNames_KeepsTheFirst() {
            assertThat(EmployeeEmailGenerator.localPart("Juan Carlos Pérez", "López"))
                    .isEqualTo("juan.lopez");
        }

        @Test
        @DisplayName("lowercases with Locale.ROOT even when the default locale is Turkish")
        void localPart_TurkishDefaultLocale_StillLowercasesWithRoot() {
            var previous = Locale.getDefault();
            try {
                Locale.setDefault(new Locale("tr", "TR"));
                // Under a Turkish default, "Ivan".toLowerCase() is "ıvan" (dotless ı).
                assertThat("Ivan".toLowerCase()).isNotEqualTo("ivan");
                assertThat(EmployeeEmailGenerator.localPart("Ivan", "Ipek")).isEqualTo("ivan.ipek");
            } finally {
                Locale.setDefault(previous);
            }
        }

        @Test
        @DisplayName("returns the given part alone when the paternal part normalizes to empty")
        void localPart_EmptyPaternal_ReturnsGivenOnly() {
            assertThat(EmployeeEmailGenerator.localPart("Juan", "###")).isEqualTo("juan");
        }

        @Test
        @DisplayName("returns the paternal part alone when the given part normalizes to empty")
        void localPart_EmptyGiven_ReturnsPaternalOnly() {
            assertThat(EmployeeEmailGenerator.localPart("!!!", "Pérez")).isEqualTo("perez");
        }

        @Test
        @DisplayName("returns an empty string when both parts normalize to empty")
        void localPart_BothEmpty_ReturnsEmpty() {
            assertThat(EmployeeEmailGenerator.localPart("!!!", "###")).isEmpty();
        }

        @Test
        @DisplayName("caps each part at 40 characters before the join")
        void localPart_EachPartIsCappedAtForty() {
            // Each side is measured on its own: the other side stays short so the joined cap of 64
            // does not mask the per-part cap of 40.
            var given = EmployeeEmailGenerator.localPart("a".repeat(50), "b");
            var paternal = EmployeeEmailGenerator.localPart("a", "b".repeat(50));

            assertThat(given).isEqualTo("a".repeat(PART_CAP) + ".b");
            assertThat(paternal).isEqualTo("a." + "b".repeat(PART_CAP));
        }

        @Test
        @DisplayName("caps the joined local part at 64 characters")
        void localPart_JoinedIsCappedAtSixtyFour() {
            var result = EmployeeEmailGenerator.localPart("a".repeat(40), "b".repeat(40));

            assertThat(result).isEqualTo("a".repeat(40) + "." + "b".repeat(23));
            assertThat(result).hasSize(LOCAL_PART_CAP);
        }
    }

    @Nested
    @DisplayName("localPartFromToken")
    class LocalPartFromTokenTests {

        @Test
        @DisplayName("strips everything outside [a-z0-9] without splitting on whitespace")
        void localPartFromToken_PunctuatedToken_IsStrippedWhole() {
            assertThat(EmployeeEmailGenerator.localPartFromToken("EMP 001")).isEqualTo("emp001");
            assertThat(EmployeeEmailGenerator.localPartFromToken("a@b")).isEqualTo("ab");
            assertThat(EmployeeEmailGenerator.localPartFromToken("EMP-001")).isEqualTo("emp001");
        }

        @Test
        @DisplayName("applies the same accent and Locale.ROOT rules as a name part")
        void localPartFromToken_AccentsAndLocale_AreHandled() {
            var previous = Locale.getDefault();
            try {
                Locale.setDefault(new Locale("tr", "TR"));
                assertThat(EmployeeEmailGenerator.localPartFromToken("Pérezİ")).isEqualTo("perezi");
            } finally {
                Locale.setDefault(previous);
            }
        }

        @Test
        @DisplayName("caps the token at the part cap and returns empty for an all-punctuation token")
        void localPartFromToken_IsCappedAndMayBeEmpty() {
            assertThat(EmployeeEmailGenerator.localPartFromToken("a".repeat(50)))
                    .isEqualTo("a".repeat(PART_CAP));
            assertThat(EmployeeEmailGenerator.localPartFromToken("!!!")).isEmpty();
        }
    }

    @Nested
    @DisplayName("address")
    class AddressTests {

        @Test
        @DisplayName("joins the local part and the lowercased domain")
        void address_JoinsAndLowercasesDomain() {
            assertThat(EmployeeEmailGenerator.address("juan.perez", "Example.COM"))
                    .isEqualTo("juan.perez@example.com");
        }

        @Test
        @DisplayName("returns null when either side is null or blank")
        void address_BlankSide_ReturnsNull() {
            assertThat(EmployeeEmailGenerator.address(null, "example.com")).isNull();
            assertThat(EmployeeEmailGenerator.address("juan", null)).isNull();
            assertThat(EmployeeEmailGenerator.address("  ", "example.com")).isNull();
            assertThat(EmployeeEmailGenerator.address("juan", "  ")).isNull();
        }
    }

    @Nested
    @DisplayName("suffixed")
    class SuffixedTests {

        @Test
        @DisplayName("returns the plain local part for the first attempt")
        void suffixed_FirstAttempt_IsPlain() {
            assertThat(EmployeeEmailGenerator.suffixed("juan.perez", 1)).isEqualTo("juan.perez");
            assertThat(EmployeeEmailGenerator.suffixed("juan.perez", 0)).isEqualTo("juan.perez");
        }

        @Test
        @DisplayName("appends the attempt number from the second attempt on")
        void suffixed_LaterAttempts_AreNumbered() {
            assertThat(EmployeeEmailGenerator.suffixed("juan.perez", 2)).isEqualTo("juan.perez2");
            assertThat(EmployeeEmailGenerator.suffixed("juan.perez", 20)).isEqualTo("juan.perez20");
        }
    }

    @Nested
    @DisplayName("normalized")
    class NormalizedTests {

        @Test
        @DisplayName("trims and lowercases with Locale.ROOT")
        void normalized_TrimsAndLowercases() {
            assertThat(EmployeeEmailGenerator.normalized(" Juan.Perez@Example.COM "))
                    .isEqualTo("juan.perez@example.com");
        }

        @Test
        @DisplayName("returns null only for a null input")
        void normalized_Null_ReturnsNull() {
            assertThat(EmployeeEmailGenerator.normalized(null)).isNull();
            assertThat(EmployeeEmailGenerator.normalized("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("domainOf")
    class DomainOfTests {

        @Test
        @DisplayName("returns the lowercased substring after the last at-sign")
        void domainOf_ReturnsLowercasedDomain() {
            assertThat(EmployeeEmailGenerator.domainOf("Juan@Example.COM")).isEqualTo("example.com");
            assertThat(EmployeeEmailGenerator.domainOf("a@b@example.com")).isEqualTo("example.com");
        }

        @Test
        @DisplayName("returns null when there is no at-sign")
        void domainOf_NoAtSign_ReturnsNull() {
            assertThat(EmployeeEmailGenerator.domainOf("nodomain")).isNull();
            assertThat(EmployeeEmailGenerator.domainOf(null)).isNull();
        }

        @Test
        @DisplayName("returns an empty string when the at-sign is last")
        void domainOf_AtSignLast_ReturnsEmpty() {
            assertThat(EmployeeEmailGenerator.domainOf("a@")).isEmpty();
        }
    }
}
