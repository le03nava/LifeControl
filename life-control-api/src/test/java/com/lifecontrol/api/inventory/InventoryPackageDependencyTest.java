package com.lifecontrol.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the direction of the {@code inventory} ↔ {@code salesorder} dependency: {@code inventory} never
 * imports {@code salesorder}.
 *
 * <p>The cycle exists today for one reason and it is not a design: {@code InsufficientStockException} — the
 * 409 a sale is refused by — lives in {@code salesorder.exception}, so every inventory class that raises it
 * imports the sales-order package. The stock ledger is the thing that decides whether a sale may happen, so
 * the exception belongs to the side that raises it and {@code salesorder} is the caller. {@code
 * odd/tasks/ledger-mirror-retirement.md} S6 makes that the rule, together with the deletion of {@code
 * StockLedgerEngine}; this test is the half that cannot be expressed as a compile error, because a cycle of
 * two packages compiles happily.</p>
 *
 * <p>It scans the module's own sources rather than class files: an unused import would not survive into the
 * bytecode, and an unused import to a package the file no longer builds against is exactly the shape a later
 * reader copies. The scan is bounded to {@code src/main/java} — tests may name both sides freely, and the
 * sales-order suites do. Two anchors keep it from passing vacuously: the inventory package must be found and
 * must contain a nonempty number of files, and the scan must still see a {@code package} line per file, which
 * is what tells a real source apart from a resource that happens to end in {@code .java}.</p>
 */
@DisplayName("inventory package dependencies")
class InventoryPackageDependencyTest {

    /** The package that must not be imported from {@code inventory}. */
    private static final String SALES_ORDER_PACKAGE = "import com.lifecontrol.api.salesorder.";

    /** The package whose imports are scanned, relative to the module's source root. */
    private static final String INVENTORY_SOURCE_ROOT = "src/main/java/com/lifecontrol/api/inventory";

    @Test
    @DisplayName("should not import anything from com.lifecontrol.api.salesorder")
    void inventoryDoesNotDependOnSalesOrder() throws IOException {
        var files = javaSources();
        assertThat(files)
                .as("the scan must find the inventory sources, or it would pass without reading anything")
                .isNotEmpty();

        var offenders = new TreeSet<String>();
        for (var file : files) {
            var source = Files.readString(file);
            assertThat(source)
                    .as("%s must carry a package declaration", file)
                    .contains("package com.lifecontrol.api.inventory");
            if (source.contains(SALES_ORDER_PACKAGE)) {
                offenders.add(relative(file) + " imports " + SALES_ORDER_PACKAGE + "*");
            }
        }

        assertThat(offenders)
                .as("inventory is the lower package: InsufficientStockException belongs to inventory.exception and "
                        + "salesorder imports it, never the reverse (odd/tasks/ledger-mirror-retirement.md, S6)")
                .isEmpty();
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(moduleRoot().resolve(INVENTORY_SOURCE_ROOT))) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
    }

    /**
     * The module directory, found by walking up from the working directory: Gradle runs a test with the module
     * as {@code user.dir}, and an IDE may run it from the repository root.
     */
    private static Path moduleRoot() {
        var start = Path.of("").toAbsolutePath().normalize();
        for (var candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve(INVENTORY_SOURCE_ROOT))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Module root not found from " + start + ": no " + INVENTORY_SOURCE_ROOT);
    }

    private static String relative(Path file) {
        return moduleRoot().relativize(file).toString();
    }
}
