package com.lifecontrol.api.common.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The numbering contract of ADR-0003, decision 5 and decision 6: the rendered string is presentation, it
 * is produced once, and the parts are what the database enforces.
 */
@DisplayName("Document numbering Tests")
class DocumentNumberRendererTest {

    @Nested
    @DisplayName("the closed doc_type vocabulary")
    class VocabularyTests {

        @Test
        @DisplayName("spells the three document types with the strings ADR-0004 must reuse")
        void spellsTheVocabulary() {
            assertThat(DocumentType.SALES_ORDER.code()).isEqualTo("SALES_ORDER");
            assertThat(DocumentType.PURCHASE_ORDER.code()).isEqualTo("PURCHASE_ORDER");
            assertThat(DocumentType.GOODS_RECEIPT.code()).isEqualTo("GOODS_RECEIPT");
        }

        @Test
        @DisplayName("only the period-numbered types may open a counter")
        void tellsPeriodNumberedFromDerived() {
            assertThat(DocumentType.SALES_ORDER.periodNumbered()).isTrue();
            assertThat(DocumentType.PURCHASE_ORDER.periodNumbered()).isTrue();
            assertThat(DocumentType.GOODS_RECEIPT.periodNumbered()).isFalse();
        }

        @Test
        @DisplayName("carries the prefix each rendered number starts with")
        void carriesThePrefix() {
            assertThat(DocumentType.SALES_ORDER.prefix()).isEqualTo("SO");
            assertThat(DocumentType.PURCHASE_ORDER.prefix()).isEqualTo("PO");
            assertThat(DocumentType.GOODS_RECEIPT.prefix()).isEqualTo("GR");
        }
    }

    @Nested
    @DisplayName("the period")
    class PeriodTests {

        @Test
        @DisplayName("is the document's own month, never the allocation's clock")
        void derivesTheMonthOfTheDocumentDate() {
            assertThat(DocumentNumberRenderer.periodOf(LocalDate.of(2026, 10, 31)))
                    .isEqualTo("202610");
            assertThat(DocumentNumberRenderer.periodOf(LocalDate.of(2026, 1, 1)))
                    .isEqualTo("202601");
        }
    }

    @Nested
    @DisplayName("a period-numbered document")
    class PeriodNumberTests {

        @Test
        @DisplayName("renders prefix, period and the value zero-padded to five")
        void rendersTheParts() {
            assertThat(DocumentNumberRenderer.renderPeriodNumber(DocumentType.SALES_ORDER, "202610", 1))
                    .isEqualTo("SO-202610-00001");
            assertThat(DocumentNumberRenderer.renderPeriodNumber(DocumentType.PURCHASE_ORDER, "202610", 42))
                    .isEqualTo("PO-202610-00042");
        }

        @Test
        @DisplayName("widens past five digits instead of truncating")
        void widensTheValue() {
            assertThat(DocumentNumberRenderer.renderPeriodNumber(DocumentType.SALES_ORDER, "202610", 123456))
                    .isEqualTo("SO-202610-123456");
        }

        @Test
        @DisplayName("refuses a type that has no counter, so a receipt cannot open one")
        void refusesADerivedType() {
            assertThatThrownBy(() -> DocumentNumberRenderer.renderPeriodNumber(DocumentType.GOODS_RECEIPT, "202610", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GOODS_RECEIPT");
        }

        @Test
        @DisplayName("refuses a value below the first one the schema can hand out")
        void refusesANonPositiveValue() {
            assertThatThrownBy(() -> DocumentNumberRenderer.renderPeriodNumber(DocumentType.SALES_ORDER, "202610", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("a derived document")
    class DerivedNumberTests {

        @Test
        @DisplayName("renders its parent's frozen number and its own ordinal")
        void rendersFromTheParent() {
            assertThat(DocumentNumberRenderer.renderDerivedNumber(DocumentType.GOODS_RECEIPT, "PO-202610-00007", 1))
                    .isEqualTo("GR-PO-202610-00007-01");
        }

        @Test
        @DisplayName("widens the ordinal past two digits")
        void widensTheOrdinal() {
            assertThat(DocumentNumberRenderer.renderDerivedNumber(DocumentType.GOODS_RECEIPT, "PO-202610-00007", 100))
                    .isEqualTo("GR-PO-202610-00007-100");
        }

        @Test
        @DisplayName("refuses to render without the parent's number")
        void refusesAMissingParentNumber() {
            assertThatThrownBy(() -> DocumentNumberRenderer.renderDerivedNumber(DocumentType.GOODS_RECEIPT, " ", 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the allocator")
    class AllocatorTests {

        @Test
        @DisplayName("refuses a derived type before reaching the database")
        void refusesADerivedType() {
            var allocator = new DocumentSequenceAllocator(null);

            assertThatThrownBy(() -> allocator.allocate(UUID.randomUUID(), DocumentType.GOODS_RECEIPT, "202610"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GOODS_RECEIPT");
        }

        @Test
        @DisplayName("refuses a period the schema's own CHECK would reject")
        void refusesAnImpossiblePeriod() {
            var allocator = new DocumentSequenceAllocator(null);

            assertThatThrownBy(() -> allocator.allocate(UUID.randomUUID(), DocumentType.SALES_ORDER, "2026-10"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2026-10");
        }

        @Test
        @DisplayName("refuses a missing company, because the counter row belongs to a tenant")
        void refusesAMissingCompany() {
            var allocator = new DocumentSequenceAllocator(null);

            assertThatThrownBy(() -> allocator.allocate(null, DocumentType.SALES_ORDER, "202610"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
