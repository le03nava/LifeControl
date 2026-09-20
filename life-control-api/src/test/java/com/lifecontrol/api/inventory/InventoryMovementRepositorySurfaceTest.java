package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifecontrol.api.inventory.repository.InventoryMovementRepository;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

/**
 * Pins the append-only contract of the inventory ledger at the repository surface.
 *
 * <p>The ledger is a fact log: production code only ever inserts a movement. This test asserts that
 * the exposed surface offers no update and no delete path of its own, so a future change cannot
 * quietly add one. It is a surface assertion on purpose — the table has no runtime guard, and the
 * inherited Spring Data CRUD methods stay available for framework and test-fixture use.</p>
 */
@DisplayName("InventoryMovementRepository surface")
class InventoryMovementRepositorySurfaceTest {

    private static final List<String> MUTATION_PREFIXES = List.of("save", "delete", "remove", "update", "insert");

    @Test
    @DisplayName("should stay a plain JpaRepository so the ledger has no extra query surface")
    void repositoryIsAPlainJpaRepository() {
        assertThat(InventoryMovementRepository.class.getInterfaces())
                .as("InventoryMovementRepository must extend JpaRepository and nothing else")
                .containsExactly(JpaRepository.class);
    }

    @Test
    @DisplayName("should declare no update or delete path of its own")
    void declaresNoUpdateOrDeletePath() {
        for (var method : InventoryMovementRepository.class.getDeclaredMethods()) {
            assertThat(isMutationOfItsOwn(method))
                    .as("declared method %s must not be an update or delete path of the append-only ledger", method)
                    .isFalse();
        }
    }

    private boolean isMutationOfItsOwn(Method method) {
        return method.isAnnotationPresent(Modifying.class)
                || MUTATION_PREFIXES.stream()
                        .anyMatch(prefix -> method.getName().startsWith(prefix));
    }
}
