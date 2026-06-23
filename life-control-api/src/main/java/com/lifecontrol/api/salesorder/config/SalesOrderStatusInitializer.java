package com.lifecontrol.api.salesorder.config;

import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SalesOrderStatusInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderStatusInitializer.class);

    private record SeedStatus(String name, int order) {}

    private static final Map<String, String> MIGRATIONS = Map.of(
        // Sales order statuses
        "Borrador", "Draft",
        "Enviada", "Pending",
        "Cerrada", "Completed",
        "Cancelada", "Cancelled",
        // Sales order item statuses
        "Pendiente", "Pending",
        "Agregado", "Added",
        "Cancelado", "Cancelled"
    );

    private static final SeedStatus[] SALES_ORDER_STATUSES = {
        new SeedStatus("Draft", 1),
        new SeedStatus("Active", 2),
        new SeedStatus("Pending", 3),
        new SeedStatus("Completed", 4),
        new SeedStatus("Cancelled", 5)
    };

    private static final SeedStatus[] SALES_ORDER_ITEM_STATUSES = {
        new SeedStatus("Pending", 1),
        new SeedStatus("Added", 2),
        new SeedStatus("Cancelled", 3)
    };

    private final StatusTypeRepository statusTypeRepository;
    private final StatusRepository statusRepository;

    public SalesOrderStatusInitializer(StatusTypeRepository statusTypeRepository,
                                        StatusRepository statusRepository) {
        this.statusTypeRepository = statusTypeRepository;
        this.statusRepository = statusRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedStatusType("SALES_ORDER", SALES_ORDER_STATUSES);
        seedStatusType("SALES_ORDER_ITEM", SALES_ORDER_ITEM_STATUSES);
    }

    private void seedStatusType(String typeName, SeedStatus[] statuses) {
        var statusType = statusTypeRepository.findByStatusTypeNameIgnoreCase(typeName)
                .orElseGet(() -> {
                    var newType = StatusType.builder()
                            .statusTypeName(typeName)
                            .enabled(true)
                            .build();
                    var saved = statusTypeRepository.save(newType);
                    log.debug("Seeded status type: {}", typeName);
                    return saved;
                });

        for (var entry : MIGRATIONS.entrySet()) {
            var oldName = entry.getKey();
            var newName = entry.getValue();
            var oldStatus = statusRepository.findByTypeNameAndStatusName(typeName, oldName);
            if (oldStatus.isPresent()) {
                if (!statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId(newName, statusType.getId())) {
                    var status = oldStatus.get();
                    status.setStatusName(newName);
                    statusRepository.save(status);
                    log.info("Migrated status name: '{}' → '{}' ({})", oldName, newName, typeName);
                } else {
                    log.warn("Cannot migrate '{}' → '{}' ({}) — new name already exists; old status left as-is", oldName, newName, typeName);
                }
            }
        }

        for (var seed : statuses) {
            if (!statusRepository.existsByStatusNameIgnoreCaseAndStatusTypeId(seed.name(), statusType.getId())) {
                var status = Status.builder()
                        .statusName(seed.name())
                        .statusType(statusType)
                        .enabled(true)
                        .build();
                statusRepository.save(status);
                log.debug("Seeded status: {} ({})", seed.name(), typeName);
            }
        }
    }
}
