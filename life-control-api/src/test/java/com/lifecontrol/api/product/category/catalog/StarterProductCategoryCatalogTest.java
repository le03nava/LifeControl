package com.lifecontrol.api.product.category.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the two copies of the starter catalog to each other: {@link StarterProductCategoryCatalog} in
 * Java and the value rows of {@code V35__product_category_starter_catalog.sql}.
 *
 * <p>The duplication is deliberate — the migration reaches the companies that exist when it runs, the
 * Java list reaches the ones created afterwards — so the only thing that keeps them from diverging is
 * this test. It reads the migration as a classpath resource, which is what makes it check the file that
 * actually ships, and not a copy the test owns.</p>
 *
 * <p>This is a plain JUnit test on purpose: the catalog is a static list, so a Spring context or a
 * database would add nothing. The remaining cases are the invariants the applier and the database rely
 * on — two levels, unique sibling names, an eight-digit-or-absent SAT key, and no enabled entry under a
 * disabled parent.</p>
 */
@DisplayName("Starter Product Category Catalog Tests")
class StarterProductCategoryCatalogTest {

    private static final String MIGRATION_RESOURCE = "/db/migration/V35__product_category_starter_catalog.sql";

    /** A V35 value row: an indented parenthesised tuple with an optional trailing comma. */
    private static final Pattern VALUE_ROW = Pattern.compile("^\\s*\\(.*\\)[,;]?$");

    @Test
    @DisplayName("holds the whole catalog: 39 enabled entries and 18 disabled proposals")
    void holdsFiftySevenEntries() {
        var entries = StarterProductCategoryCatalog.entries();

        assertThat(entries)
                .as("12 enabled roots, 27 enabled children, 6 disabled roots and 12 disabled children")
                .hasSize(57);
        assertThat(entries.stream()
                        .filter(StarterProductCategoryCatalog.Entry::enabled)
                        .count())
                .as("enabled entries")
                .isEqualTo(39);
        assertThat(entries.stream().filter(entry -> !entry.enabled()).count())
                .as("disabled proposals")
                .isEqualTo(18);
        assertThat(StarterProductCategoryCatalog.roots())
                .as("every entry is either a root or a child")
                .hasSize(18);
        assertThat(StarterProductCategoryCatalog.children()).hasSize(39);
    }

    @Test
    @DisplayName("is exactly two levels deep: every parentName names a root entry")
    void everyParentNamesARoot() {
        var rootsByName = new HashMap<String, StarterProductCategoryCatalog.Entry>();
        for (var entry : StarterProductCategoryCatalog.roots()) {
            rootsByName.put(normalize(entry.name()), entry);
        }

        var problems = new ArrayList<String>();
        for (var entry : StarterProductCategoryCatalog.children()) {
            var parent = rootsByName.get(normalize(entry.parentName()));
            if (parent == null) {
                problems.add(entry.name() + " names an absent root: " + entry.parentName());
            } else if (parent.parentName() != null) {
                problems.add(entry.name() + " hangs from a child: " + entry.parentName());
            }
        }

        assertThat(problems)
                .as("a child of a child would break the applier's roots-then-children pass")
                .isEmpty();
    }

    @Test
    @DisplayName("repeats no sibling name, ignoring case, so the unique index cannot reject an insert")
    void siblingNamesAreUniqueIgnoringCase() {
        var seen = new HashSet<String>();
        var duplicates = new ArrayList<String>();
        for (var entry : StarterProductCategoryCatalog.entries()) {
            var key = normalize(entry.parentName()) + "\u0000" + normalize(entry.name());
            if (!seen.add(key)) {
                duplicates.add(entry.parentName() + " > " + entry.name());
            }
        }

        assertThat(duplicates)
                .as("uq_product_categories_sibling_name rejects a repeated sibling")
                .isEmpty();
    }

    @Test
    @DisplayName("carries only NULL or an eight-digit SAT key, matching ck_product_categories_sat_code_format")
    void satCodesAreNullOrEightDigits() {
        var malformed = StarterProductCategoryCatalog.entries().stream()
                .filter(entry -> entry.satCode() != null)
                .filter(entry -> !entry.satCode().matches("^[0-9]{8}$"))
                .map(entry -> entry.name() + " -> " + entry.satCode())
                .toList();

        assertThat(malformed)
                .as("a key the CHECK rejects would fail the insert at seed time")
                .isEmpty();
    }

    @Test
    @DisplayName("keeps a disabled parent and its children consistently disabled")
    void disabledRootsCarryOnlyDisabledChildrenAndNothingEnabledHangsFromADisabledParent() {
        var rootsByName = new HashMap<String, Boolean>();
        for (var root : StarterProductCategoryCatalog.roots()) {
            rootsByName.put(normalize(root.name()), root.enabled());
        }

        var problems = new ArrayList<String>();
        for (var root : StarterProductCategoryCatalog.roots()) {
            if (root.enabled()) {
                continue;
            }
            StarterProductCategoryCatalog.children().stream()
                    .filter(child -> normalize(child.parentName()).equals(normalize(root.name())))
                    .filter(StarterProductCategoryCatalog.Entry::enabled)
                    .forEach(child -> problems.add(child.name() + " is enabled under disabled " + root.name()));
        }
        for (var child : StarterProductCategoryCatalog.children()) {
            var parentEnabled = rootsByName.get(normalize(child.parentName()));
            if (Boolean.FALSE.equals(parentEnabled) && child.enabled()) {
                problems.add(child.name() + " is enabled under a disabled parent");
            }
        }

        assertThat(problems)
                .as("an enabled branch under a disabled root is unreachable")
                .isEmpty();
    }

    @Test
    @DisplayName("matches V35 in both directions as a set of rows, whatever their indentation")
    void matchesTheMigrationInBothDirections() throws IOException {
        var migration = readMigration();
        // Both directions compare stripped rows, so re-indenting V35 is not a false RED while a real
        // divergence still is.
        var sqlRows = valueRows(migration);
        var javaRows = StarterProductCategoryCatalog.entries().stream()
                .map(StarterProductCategoryCatalog::toMigrationTuple)
                .map(String::strip)
                .toList();

        assertThat(sqlRows)
                .as("an entry the Java list has and V35 does not would seed a new company differently")
                .containsAll(javaRows);

        // The other direction compared as sets and not as a count: a count lets a stray row with other
        // indentation (two spaces, a tab) pass while V35 really seeds one more category than the Java
        // list, which is the exact divergence this test exists to catch.
        assertThat(sqlRows)
                .as("a V35 row the Java list lost, or a Java entry V35 never wrote")
                .containsExactlyInAnyOrderElementsOf(javaRows);
    }

    @Test
    @DisplayName("repeats no V35 value row: the set comparison cannot see a duplicate")
    void migrationHasNoDuplicateValueRow() throws IOException {
        // A list and not a set: `matchesTheMigrationInBothDirections` collects into a set, which
        // collapses a duplicated row. A duplicate is not cosmetic — V35 would fail
        // `uq_product_categories_sibling_name` while applying, on every deployed database.
        assertThat(valueRows(readMigration()))
                .as("one row per catalog entry, none twice")
                .hasSize(StarterProductCategoryCatalog.entries().size())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("carries the user's glosses as descriptions, not just the right names and keys")
    void keepsTheUsersDescriptions() {
        // The description is the parenthetical the user wrote after each SAT key (L1). It is user-facing
        // text on the Categories screen and the only catalog claim that would pass every other test if it
        // were wrong in both copies, so the twelve roots are pinned literally here. Children descriptions
        // stay pinned only across the two copies, which is the trade-off recorded in the feature record.
        var expected = new HashMap<String, String>();
        expected.put("Ferretería", "Herramientas de mano");
        expected.put("Materiales de Construcción", "Concreto, cemento y yeso");
        expected.put("Plomería", "Distribución de fluidos y gas");
        expected.put("Baños y Cocinas", "Instalaciones de fontanería");
        expected.put("Electrodomésticos", "Aparatos electrodomésticos");
        expected.put("Electrónica y TV", "Equipos de TI y telecomunicaciones");
        expected.put("Alimentos", "Alimentos, Bebidas y Tabaco");
        expected.put("Calzado para Dama", "Calzado");
        expected.put("Calzado para Caballero", "Calzado");
        expected.put("Calzado Deportivo y Confort", "Calzado");
        expected.put("Calzado Infantil (Niños y Bebés)", "Calzado");
        expected.put("Accesorios y Cuidado", null);

        var actual = new HashMap<String, String>();
        StarterProductCategoryCatalog.roots().stream()
                // The user's twelve branches only: the disabled proposals carry descriptions this feature
                // wrote, so pinning them here would assert the feature against itself.
                .filter(StarterProductCategoryCatalog.Entry::enabled)
                .forEach(root -> actual.put(root.name(), root.description()));

        assertThat(actual).isEqualTo(expected);
    }

    /** The V35 value rows as stripped tuples, trailing separator removed. */
    private static List<String> valueRows(String migration) {
        return migration
                .lines()
                .map(String::strip)
                .filter(line -> VALUE_ROW.matcher(line).matches())
                .map(line -> line.replaceFirst("[,;]$", ""))
                .toList();
    }

    @Test
    @DisplayName("declares the user's 27 children with their parents and SAT keys, verbatim")
    void keepsTheNamedChildrenAndKeys() {
        var expected = List.of(
                "Ferretería > Herramientas Manuales = 27111600",
                "Ferretería > Herramientas Eléctricas = 27112700",
                "Materiales de Construcción > Fijaciones y Tornillería = 31161500",
                "Materiales de Construcción > Cerraduras y Herrajes = 31162400",
                "Plomería > Tubos y Conexiones = 40141700",
                "Plomería > Válvulas y Llaves = 40141600",
                "Baños y Cocinas > Muebles de Baño = 30181500",
                "Baños y Cocinas > Grifería y Mezcladoras = 30181700",
                "Electrodomésticos > Lavadoras y Secadoras = 52141600",
                "Electrodomésticos > Cocina (Microondas/Licuadoras) = 52141500",
                "Electrónica y TV > Pantallas y Video = 45111800",
                "Electrónica y TV > Cómputo y Accesorios = 43211500",
                "Alimentos > Panadería y Dulces = 50181900",
                "Alimentos > Lácteos y Huevos = 50131700",
                "Calzado para Dama > Zapatos de Tacón y Plataformas = 53111600",
                "Calzado para Dama > Baletas y Flats = 53111500",
                "Calzado para Dama > Botas y Botines = 53111800",
                "Calzado para Dama > Sandalias y Pantuflas = 53112100",
                "Calzado para Caballero > Zapatos de Vestir (Oxford, Mocasines) = 53111600",
                "Calzado para Caballero > Calzado Casual y Clásico = 53111500",
                "Calzado para Caballero > Botas Casuales o de Trabajo = 53111800",
                "Calzado Deportivo y Confort > Tenis para Correr / Entrenamiento = 53111900",
                "Calzado Deportivo y Confort > Tenis Casuales (Urbano) = 53111500",
                "Calzado Infantil (Niños y Bebés) > Calzado Escolar = 53111500",
                "Calzado Infantil (Niños y Bebés) > Zapatitos para Bebé = 53111500",
                "Accesorios y Cuidado > Bolsos y Carteras = 53121600",
                "Accesorios y Cuidado > Limpieza y Cuidado del Calzado = 53112300");

        var actual = StarterProductCategoryCatalog.children().stream()
                .filter(StarterProductCategoryCatalog.Entry::enabled)
                .map(entry -> entry.parentName() + " > " + entry.name() + " = " + entry.satCode())
                .toList();

        assertThat(actual)
                .as("an enabled child differs from the branch the user wrote in the request")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("renders a tuple exactly like a V35 value row, trailing comma aside")
    void rendersTheTupleShape() {
        var root = new StarterProductCategoryCatalog.Entry(
                null, "Ferretería", "Herramientas de mano", 10, "27110000", true);
        var proposal = new StarterProductCategoryCatalog.Entry(
                "Mascotas", "Accesorios para Mascotas", "Correas", 20, null, false);

        assertThat(StarterProductCategoryCatalog.toMigrationTuple(root))
                .isEqualTo("    (NULL, 'Ferretería', 'Herramientas de mano', 10, '27110000', true)");
        assertThat(StarterProductCategoryCatalog.toMigrationTuple(proposal))
                .isEqualTo("    ('Mascotas', 'Accesorios para Mascotas', 'Correas', 20, NULL, false)");
    }

    @Test
    @DisplayName("declares the user's twelve branches with their SAT keys, verbatim")
    void keepsTheNamedBranchesAndKeys() {
        var expected = Map.ofEntries(
                Map.entry("Ferretería", "27110000"),
                Map.entry("Materiales de Construcción", "30110000"),
                Map.entry("Plomería", "40140000"),
                Map.entry("Baños y Cocinas", "30180000"),
                Map.entry("Electrodomésticos", "52140000"),
                Map.entry("Electrónica y TV", "43220000"),
                Map.entry("Alimentos", "50000000"),
                Map.entry("Calzado para Dama", "53110000"),
                Map.entry("Calzado para Caballero", "53110000"),
                Map.entry("Calzado Deportivo y Confort", "53110000"),
                Map.entry("Calzado Infantil (Niños y Bebés)", "53110000"),
                Map.entry("Accesorios y Cuidado", "53000000"));

        var enabledRoots = StarterProductCategoryCatalog.roots().stream()
                .filter(StarterProductCategoryCatalog.Entry::enabled)
                .filter(root -> root.satCode() != null)
                .collect(java.util.stream.Collectors.toMap(
                        StarterProductCategoryCatalog.Entry::name, StarterProductCategoryCatalog.Entry::satCode));

        assertThat(enabledRoots).isEqualTo(expected);
    }

    private static String readMigration() throws IOException {
        try (InputStream stream = StarterProductCategoryCatalogTest.class.getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(stream)
                    .as("the migration %s is on the test classpath", MIGRATION_RESOURCE)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
