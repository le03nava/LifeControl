package com.lifecontrol.api.measureunit.config;

import com.lifecontrol.api.measureunit.model.MeasureUnit;
import com.lifecontrol.api.measureunit.model.MeasureUnitType;
import com.lifecontrol.api.measureunit.repository.MeasureUnitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the {@code measure_units} reference table on application startup.
 * Idempotent — skips rows that already exist by SAT code.
 */
@Component
public class MeasureUnitInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MeasureUnitInitializer.class);

    private static final List<SeedRecord> SEED_RECORDS = List.of(
            new SeedRecord(MeasureUnitType.PRODUCT, "Pieza", "Pieza", "H87", "Para artículos individuales: ropa, electrónicos, juguetes, etc."),
            new SeedRecord(MeasureUnitType.PRODUCT, "Kilogramo", "Kg", "KGM", "Para alimentos, materiales a granel, insumos industriales."),
            new SeedRecord(MeasureUnitType.PRODUCT, "Litro", "L", "LTR", "Para líquidos como bebidas, aceites, productos químicos."),
            new SeedRecord(MeasureUnitType.PRODUCT, "Metro", "M", "MTR", "Para textiles, cableado, materiales de construcción."),
            new SeedRecord(MeasureUnitType.PRODUCT, "Caja", "Caja", "XBX", "Para productos empacados en cajas: botellas, empaques, etc."),
            new SeedRecord(MeasureUnitType.PRODUCT, "Docena", "Docena", "DZN", "Para productos que se venden en múltiplos de 12."),
            new SeedRecord(MeasureUnitType.PRODUCT, "Paquete", "Paquete", "XPK", "Cuando se vende un conjunto de artículos como una sola unidad."),
            new SeedRecord(MeasureUnitType.SERVICE, "Unidad de servicio", "Servicio", "E48", "Para servicios en general: consultoría, diseño, mantenimiento."),
            new SeedRecord(MeasureUnitType.SERVICE, "Hora", "Hora", "HUR", "Servicios por tiempo: clases, asesorías, soporte técnico."),
            new SeedRecord(MeasureUnitType.SERVICE, "Día", "Día", "DAY", "Servicios que se cobran por jornada: renta de equipo, hospedaje."),
            new SeedRecord(MeasureUnitType.SERVICE, "Mes", "Mes", "MON", "Servicios por periodo mensual: suscripciones, arrendamientos.")
    );

    private final MeasureUnitRepository measureUnitRepository;

    public MeasureUnitInitializer(MeasureUnitRepository measureUnitRepository) {
        this.measureUnitRepository = measureUnitRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (var record : SEED_RECORDS) {
            if (measureUnitRepository.existsBySatCode(record.satCode())) {
                log.debug("Measure unit already exists with SAT code: {}", record.satCode());
                continue;
            }

            var measureUnit = MeasureUnit.builder()
                    .measureUnitName(record.name())
                    .measureUnitShortName(record.shortName())
                    .unitType(record.type())
                    .satCode(record.satCode())
                    .description(record.description())
                    .enabled(true)
                    .build();

            measureUnitRepository.save(measureUnit);
            log.debug("Seeded measure unit: {} ({})", record.name(), record.satCode());
        }
    }

    private record SeedRecord(MeasureUnitType type, String name, String shortName, String satCode, String description) {}
}
