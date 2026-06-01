package com.lifecontrol.api.product.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Product Exception Tests")
class ProductExceptionTest {

    @Test
    @DisplayName("ProductNotFoundException should extend RuntimeException and contain UUID in message")
    void productNotFoundException_Message() {
        UUID id = UUID.randomUUID();
        var exception = new ProductNotFoundException(id);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("Product not found with id: " + id);
    }

    @Test
    @DisplayName("ProductNotFoundException should produce different messages for different UUIDs")
    void productNotFoundException_DifferentIds() {
        UUID id1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID id2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        var ex1 = new ProductNotFoundException(id1);
        var ex2 = new ProductNotFoundException(id2);

        assertThat(ex1.getMessage()).isNotEqualTo(ex2.getMessage());
        assertThat(ex1.getMessage()).contains(id1.toString());
        assertThat(ex2.getMessage()).contains(id2.toString());
    }

    @Test
    @DisplayName("DuplicateProductException should extend RuntimeException and preserve message")
    void duplicateProductException_Message() {
        var exception = new DuplicateProductException("SKU ABC-001 already exists");
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).isEqualTo("SKU ABC-001 already exists");
    }

    @Test
    @DisplayName("DuplicateProductException should preserve different messages")
    void duplicateProductException_DifferentMessages() {
        var msg1 = "SKU ABC-001 already exists";
        var msg2 = "Duplicate product SKU: XYZ-999";

        var ex1 = new DuplicateProductException(msg1);
        var ex2 = new DuplicateProductException(msg2);

        assertThat(ex1.getMessage()).isEqualTo(msg1);
        assertThat(ex2.getMessage()).isEqualTo(msg2);
    }
}
