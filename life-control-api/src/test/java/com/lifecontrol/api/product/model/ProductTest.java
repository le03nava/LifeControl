package com.lifecontrol.api.product.model;

import com.lifecontrol.api.common.model.Auditable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Product Entity Tests")
class ProductTest {

    @Nested
    @DisplayName("Builder pattern")
    class BuilderTests {

        @Test
        @DisplayName("should build product with all fields set correctly")
        void buildProduct_AllFields() {
            UUID id = UUID.randomUUID();
            Map<String, Object> attrs = Map.of("color", "red", "size", "L");

            var product = Product.builder()
                    .id(id)
                    .sku("ABC-001")
                    .name("Test Widget")
                    .shortName("Widget")
                    .satCode("SAT-123")
                    .productType("ELECTRONICS")
                    .attributes(attrs)
                    .enabled(true)
                    .build();

            assertThat(product.getId()).isEqualTo(id);
            assertThat(product.getSku()).isEqualTo("ABC-001");
            assertThat(product.getName()).isEqualTo("Test Widget");
            assertThat(product.getShortName()).isEqualTo("Widget");
            assertThat(product.getSatCode()).isEqualTo("SAT-123");
            assertThat(product.getProductType()).isEqualTo("ELECTRONICS");
            assertThat(product.getAttributes()).isEqualTo(attrs);
            assertThat(product.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should default enabled to true when not explicitly set")
        void buildProduct_DefaultEnabled() {
            var product = Product.builder()
                    .sku("DEF-002")
                    .name("Default Product")
                    .build();

            assertThat(product.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("should allow attributes to be null")
        void buildProduct_NullAttributes() {
            var product = Product.builder()
                    .sku("GHI-003")
                    .name("No Attrs Product")
                    .attributes(null)
                    .build();

            assertThat(product.getAttributes()).isNull();
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should set and get all fields via setters")
        void setAndGet_AllFields() {
            var product = new Product();
            UUID id = UUID.randomUUID();
            Map<String, Object> attrs = Map.of("weight", "2kg");

            product.setId(id);
            product.setSku("SET-001");
            product.setName("Setter Product");
            product.setShortName("Set");
            product.setSatCode("SAT-999");
            product.setProductType("FOOD");
            product.setAttributes(attrs);
            product.setEnabled(false);

            assertThat(product.getId()).isEqualTo(id);
            assertThat(product.getSku()).isEqualTo("SET-001");
            assertThat(product.getName()).isEqualTo("Setter Product");
            assertThat(product.getShortName()).isEqualTo("Set");
            assertThat(product.getSatCode()).isEqualTo("SAT-999");
            assertThat(product.getProductType()).isEqualTo("FOOD");
            assertThat(product.getAttributes()).isEqualTo(attrs);
            assertThat(product.getEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Auditable inheritance")
    class AuditableTests {

        @Test
        @DisplayName("should extend Auditable base class")
        void extendsAuditable() {
            var product = new Product();
            assertThat(product).isInstanceOf(Auditable.class);
        }

        @Test
        @DisplayName("should allow setting timestamps via builder")
        void builder_Timestamps() {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime later = now.plusHours(1);

            var product = Product.builder()
                    .sku("TS-001")
                    .name("Timestamp Product")
                    .createdAt(now)
                    .updatedAt(later)
                    .build();

            assertThat(product.getCreatedAt()).isEqualTo(now);
            assertThat(product.getUpdatedAt()).isEqualTo(later);
        }
    }

    @Nested
    @DisplayName("JPA annotations")
    class JpaAnnotationsTests {

        @Test
        @DisplayName("should have default constructor for JPA")
        void hasDefaultConstructor() {
            var product = new Product();
            assertThat(product).isNotNull();
        }

        @Test
        @DisplayName("should be an entity with table name 'products'")
        void hasEntityAnnotation() {
            var entityAnnotation = Product.class.getAnnotation(jakarta.persistence.Entity.class);
            assertThat(entityAnnotation).isNotNull();

            var tableAnnotation = Product.class.getAnnotation(jakarta.persistence.Table.class);
            assertThat(tableAnnotation).isNotNull();
            assertThat(tableAnnotation.name()).isEqualTo("products");
        }

        @Test
        @DisplayName("should have UUID primary key with auto-generation")
        void hasIdAnnotation() throws NoSuchFieldException {
            var idField = Product.class.getDeclaredField("id");
            var idAnnotation = idField.getAnnotation(jakarta.persistence.Id.class);
            var generatedValue = idField.getAnnotation(jakarta.persistence.GeneratedValue.class);

            assertThat(idAnnotation).isNotNull();
            assertThat(generatedValue).isNotNull();
            assertThat(generatedValue.strategy())
                    .isEqualTo(jakarta.persistence.GenerationType.UUID);
        }

        @Test
        @DisplayName("should map attributes field as JSONB")
        void attributesField_IsJsonb() throws NoSuchFieldException {
            var attrField = Product.class.getDeclaredField("attributes");
            var jdbcTypeCode = attrField.getAnnotation(org.hibernate.annotations.JdbcTypeCode.class);

            assertThat(jdbcTypeCode).isNotNull();
        }
    }
}
