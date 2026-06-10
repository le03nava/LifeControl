package com.lifecontrol.api.salesorder.config;

import com.lifecontrol.api.status.model.Status;
import com.lifecontrol.api.status.model.StatusType;
import com.lifecontrol.api.status.repository.StatusRepository;
import com.lifecontrol.api.status.repository.StatusTypeRepository;
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

    private static final SeedStatus[] SALES_ORDER_STATUSES = {
        new SeedStatus("Borrador", 1),
        new SeedStatus("Enviada", 2),
        new SeedStatus("Cerrada", 3),
        new SeedStatus("Cancelada", 4)
    };

    private static final SeedStatus[] SALES_ORDER_ITEM_STATUSES = {
        new SeedStatus("Pendiente", 1),
        new SeedStatus("Agregado", 2),
        new SeedStatus("Cancelado", 3)
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
