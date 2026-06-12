package com.lifecontrol.api.purchaseorder.config;

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
public class PurchaseOrderStatusInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderStatusInitializer.class);

    private record SeedStatus(String name, int order) {}

    private static final SeedStatus[] PURCHASE_ORDER_STATUSES = {
        new SeedStatus("Draft", 1),
        new SeedStatus("Sent", 2),
        new SeedStatus("Accepted", 3),
        new SeedStatus("In Transit", 4),
        new SeedStatus("Received", 5),
        new SeedStatus("Billed", 6),
        new SeedStatus("Closed", 7),
        new SeedStatus("Rejected", 8)
    };

    private static final SeedStatus[] PURCHASE_ORDER_DETAIL_STATUSES = {
        new SeedStatus("Pending", 1),
        new SeedStatus("In Process", 2),
        new SeedStatus("In Transit", 3),
        new SeedStatus("Partial Received", 4),
        new SeedStatus("Received", 5),
        new SeedStatus("Rejected", 6),
        new SeedStatus("Cancelled", 7)
    };

    private final StatusTypeRepository statusTypeRepository;
    private final StatusRepository statusRepository;

    public PurchaseOrderStatusInitializer(StatusTypeRepository statusTypeRepository,
                                          StatusRepository statusRepository) {
        this.statusTypeRepository = statusTypeRepository;
        this.statusRepository = statusRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedStatusType("PURCHASE_ORDER", PURCHASE_ORDER_STATUSES);
        seedStatusType("PURCHASE_ORDER_DETAIL", PURCHASE_ORDER_DETAIL_STATUSES);
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
