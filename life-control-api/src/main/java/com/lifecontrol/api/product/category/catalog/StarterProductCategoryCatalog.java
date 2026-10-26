package com.lifecontrol.api.product.category.catalog;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The starter product category catalog: the twelve branches the user listed with their SAT
 * {@code ClaveProdServ}, plus the disabled proposals the feature adds, as one immutable list.
 *
 * <p><b>This list is the source of truth for companies created after
 * {@code V35__product_category_starter_catalog.sql}</b>. The migration reaches only the companies that
 * exist when it runs, so a company created tomorrow is seeded by
 * {@link StarterProductCategoryListener} and it reads the catalog from here.</p>
 *
 * <p>The catalog therefore exists twice — once as SQL in V35, once as this list — and the duplication
 * is <b>pinned by {@link com.lifecontrol.api.product.category.catalog.StarterProductCategoryCatalogTest}</b>
 * through {@link #toMigrationTuple(Entry)}, which renders an entry exactly like a V35 value row: the
 * test parses the migration and compares both directions. <b>A change here needs a new migration, never
 * an edit to V35</b>: V35 has already run on every deployed database, so editing it changes nothing
 * there while this list changes for every company created afterwards — the two halves would diverge
 * silently, which is the failure the parity test exists to convert into a red build.</p>
 *
 * <p>Shape, and each rule is checked by that test:</p>
 *
 * <ul>
 *   <li>Two levels only. {@code parentName} names a root entry, so a child of a child is impossible by
 *       construction: the catalog is a classification, not a tree editor.</li>
 *   <li>A root has {@code parentName == null}; a child carries its parent's name verbatim, which is what
 *       the applier joins on.</li>
 *   <li>No duplicate sibling names, ignoring case, for the same reason V31's expression index forbids
 *       them: {@code uq_product_categories_sibling_name} would reject the second insert.</li>
 *   <li>{@code satCode} is {@code null} or exactly eight digits, matching V34's
 *       {@code ck_product_categories_sat_code_format}. A guessed {@code ClaveProdServ} is worse than an
 *       absent one, which is why the proposals carry {@code null}.</li>
 *   <li>A disabled root carries only disabled children, and nothing enabled hangs from a disabled
 *       parent: the applier inserts both levels, so a mismatch would produce a tree whose enabled part
 *       is unreachable through the service's cascade.</li>
 * </ul>
 *
 * <p>Names and descriptions are the user's, verbatim, including the parentheticals that are part of a
 * name ({@code "Cocina (Microondas/Licuadoras)"}) and the Spanish accents. The parenthetical the user
 * wrote after a SAT key is the row's {@code description}.</p>
 */
public final class StarterProductCategoryCatalog {

    /** One catalog row: {@code parentName} is {@code null} for a root. */
    public record Entry(
            String parentName, String name, String description, int displayOrder, String satCode, boolean enabled) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry(null, "Ferretería", "Herramientas de mano", 10, "27110000", true),
            new Entry(
                    "Ferretería",
                    "Herramientas Manuales",
                    "Herramientas de mano no asistidas / Martillos, pinzas",
                    10,
                    "27111600",
                    true),
            new Entry(
                    "Ferretería",
                    "Herramientas Eléctricas",
                    "Herramientas eléctricas / Taladros, esmeriles",
                    20,
                    "27112700",
                    true),
            new Entry(null, "Materiales de Construcción", "Concreto, cemento y yeso", 20, "30110000", true),
            new Entry("Materiales de Construcción", "Fijaciones y Tornillería", "Tornillos", 10, "31161500", true),
            new Entry(
                    "Materiales de Construcción",
                    "Cerraduras y Herrajes",
                    "Candados y cerraduras",
                    20,
                    "31162400",
                    true),
            new Entry(null, "Plomería", "Distribución de fluidos y gas", 30, "40140000", true),
            new Entry("Plomería", "Tubos y Conexiones", "Tuberías y tubos", 10, "40141700", true),
            new Entry("Plomería", "Válvulas y Llaves", "Válvulas", 20, "40141600", true),
            new Entry(null, "Baños y Cocinas", "Instalaciones de fontanería", 40, "30180000", true),
            new Entry("Baños y Cocinas", "Muebles de Baño", "Inodoros, lavabos y tinas", 10, "30181500", true),
            new Entry("Baños y Cocinas", "Grifería y Mezcladoras", "Grifos y tarjas", 20, "30181700", true),
            new Entry(null, "Electrodomésticos", "Aparatos electrodomésticos", 50, "52140000", true),
            new Entry(
                    "Electrodomésticos",
                    "Lavadoras y Secadoras",
                    "Aparatos de lavandería domésticos",
                    10,
                    "52141600",
                    true),
            new Entry(
                    "Electrodomésticos",
                    "Cocina (Microondas/Licuadoras)",
                    "Aparatos de cocina domésticos",
                    20,
                    "52141500",
                    true),
            new Entry(null, "Electrónica y TV", "Equipos de TI y telecomunicaciones", 60, "43220000", true),
            new Entry("Electrónica y TV", "Pantallas y Video", "Televisión y video", 10, "45111800", true),
            new Entry("Electrónica y TV", "Cómputo y Accesorios", "Computadoras e impresoras", 20, "43211500", true),
            new Entry(null, "Alimentos", "Alimentos, Bebidas y Tabaco", 70, "50000000", true),
            new Entry("Alimentos", "Panadería y Dulces", "Pan dulce, galletas y bollería", 10, "50181900", true),
            new Entry("Alimentos", "Lácteos y Huevos", "Productos de leche y mantequilla", 20, "50131700", true),
            new Entry(null, "Calzado para Dama", "Calzado", 80, "53110000", true),
            new Entry(
                    "Calzado para Dama",
                    "Zapatos de Tacón y Plataformas",
                    "Calzado de etiqueta / Zapatos de vestir y tacones",
                    10,
                    "53111600",
                    true),
            new Entry(
                    "Calzado para Dama",
                    "Baletas y Flats",
                    "Calzado informal / Zapatos de piso y casuales",
                    20,
                    "53111500",
                    true),
            new Entry("Calzado para Dama", "Botas y Botines", "Botas", 30, "53111800", true),
            new Entry("Calzado para Dama", "Sandalias y Pantuflas", "Sandalias y pantuflas", 40, "53112100", true),
            new Entry(null, "Calzado para Caballero", "Calzado", 90, "53110000", true),
            new Entry(
                    "Calzado para Caballero",
                    "Zapatos de Vestir (Oxford, Mocasines)",
                    "Calzado de etiqueta",
                    10,
                    "53111600",
                    true),
            new Entry("Calzado para Caballero", "Calzado Casual y Clásico", "Calzado informal", 20, "53111500", true),
            new Entry("Calzado para Caballero", "Botas Casuales o de Trabajo", "Botas", 30, "53111800", true),
            new Entry(null, "Calzado Deportivo y Confort", "Calzado", 100, "53110000", true),
            new Entry(
                    "Calzado Deportivo y Confort",
                    "Tenis para Correr / Entrenamiento",
                    "Calzado deportivo / Sneakers, running",
                    10,
                    "53111900",
                    true),
            new Entry(
                    "Calzado Deportivo y Confort", "Tenis Casuales (Urbano)", "Calzado informal", 20, "53111500", true),
            new Entry(null, "Calzado Infantil (Niños y Bebés)", "Calzado", 110, "53110000", true),
            new Entry("Calzado Infantil (Niños y Bebés)", "Calzado Escolar", "Calzado informal", 10, "53111500", true),
            new Entry(
                    "Calzado Infantil (Niños y Bebés)",
                    "Zapatitos para Bebé",
                    "Calzado informal",
                    20,
                    "53111500",
                    true),
            new Entry(null, "Accesorios y Cuidado", null, 120, "53000000", true),
            new Entry("Accesorios y Cuidado", "Bolsos y Carteras", "Maletas, bolsos y mochilas", 10, "53121600", true),
            new Entry(
                    "Accesorios y Cuidado",
                    "Limpieza y Cuidado del Calzado",
                    "Accesorios para calzado / Agujetas, plantillas, cremas, cepillos",
                    20,
                    "53112300",
                    true),
            new Entry(null, "Limpieza y Químicos del Hogar", "Productos de limpieza para el hogar", 130, null, false),
            new Entry(
                    "Limpieza y Químicos del Hogar",
                    "Detergentes y Limpiadores",
                    "Detergentes, jabones y limpiadores",
                    10,
                    null,
                    false),
            new Entry(
                    "Limpieza y Químicos del Hogar",
                    "Escobas y Cepillos",
                    "Escobas, trapeadores y cepillos",
                    20,
                    null,
                    false),
            new Entry(null, "Papelería y Oficina", "Útiles escolares y de oficina", 140, null, false),
            new Entry(
                    "Papelería y Oficina",
                    "Artículos de Escritorio",
                    "Organizadores, grapas y accesorios",
                    10,
                    null,
                    false),
            new Entry(
                    "Papelería y Oficina", "Escritura y Corrección", "Plumas, lápices y correctores", 20, null, false),
            new Entry(null, "Eléctrico e Iluminación", "Material eléctrico, cables y luminarias", 150, null, false),
            new Entry(
                    "Eléctrico e Iluminación",
                    "Cables y Conductores",
                    "Cables, conectores e interruptores",
                    10,
                    null,
                    false),
            new Entry("Eléctrico e Iluminación", "Focos y Luminarias", "Focos, lámparas y paneles", 20, null, false),
            new Entry(null, "Jardinería y Exteriores", "Herramientas y accesorios de jardín", 160, null, false),
            new Entry(
                    "Jardinería y Exteriores",
                    "Herramientas de Jardín",
                    "Palas, rastrillos y mangueras",
                    10,
                    null,
                    false),
            new Entry(
                    "Jardinería y Exteriores",
                    "Macetas y Sustratos",
                    "Macetas, tierra y fertilizantes",
                    20,
                    null,
                    false),
            new Entry(null, "Automotriz", "Accesorios y mantenimiento para automóvil", 170, null, false),
            new Entry("Automotriz", "Accesorios para Auto", "Tapetes, fundas y refacciones", 10, null, false),
            new Entry(
                    "Automotriz",
                    "Aceites y Fluidos",
                    "Aceites, anticongelantes y líquido limpiaparabrisas",
                    20,
                    null,
                    false),
            new Entry(null, "Mascotas", "Alimento y accesorios para mascotas", 180, null, false),
            new Entry("Mascotas", "Alimento para Mascotas", "Croquetas y alimento húmedo", 10, null, false),
            new Entry("Mascotas", "Accesorios para Mascotas", "Correas, platos y juguetes", 20, null, false));

    private StarterProductCategoryCatalog() {}

    /** Every catalog row, roots and children, in the order V35 lists them. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The root entries only, in display order. */
    public static List<Entry> roots() {
        return ENTRIES.stream().filter(entry -> entry.parentName() == null).collect(Collectors.toUnmodifiableList());
    }

    /** The child entries only, in display order. */
    public static List<Entry> children() {
        return ENTRIES.stream().filter(entry -> entry.parentName() != null).collect(Collectors.toUnmodifiableList());
    }

    /**
     * Renders one entry exactly like a V35 value row, so
     * {@link com.lifecontrol.api.product.category.catalog.StarterProductCategoryCatalogTest} can compare
     * the two copies of the catalog literally instead of structurally. The trailing comma of a value row
     * is omitted: SQL cannot terminate the last row of a {@code VALUES} list with one, so the rendered
     * line is asserted as a substring of the migration and every row matches, the last included.
     */
    public static String toMigrationTuple(Entry entry) {
        return "    (" + quote(entry.parentName())
                + ", " + quote(entry.name())
                + ", " + quote(entry.description())
                + ", " + entry.displayOrder()
                + ", " + quote(entry.satCode())
                + ", " + entry.enabled()
                + ")";
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }
}
