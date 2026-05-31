package com.lifecontrol.api.activity.config;

import com.lifecontrol.api.activity.model.ActivityEvent;
import com.lifecontrol.api.activity.model.ActivityProcess;
import com.lifecontrol.api.activity.repository.ActivityEventRepository;
import com.lifecontrol.api.activity.repository.ActivityProcessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds the reference tables {@code activity_processes} and {@code activity_events}
 * on application startup. Idempotent — skips rows that already exist.
 */
@Component
public class ActivityLogInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogInitializer.class);

    private static final List<String> PROCESSES = List.of(
            "COMPANY", "ORDER", "INVENTORY", "PRODUCT", "NOTIFICATION", "AUTH", "SECURITY"
    );

    private static final List<String> EVENTS = List.of(
            "CREATE", "READ", "UPDATE", "DELETE"
    );

    private final ActivityProcessRepository processRepository;
    private final ActivityEventRepository eventRepository;

    public ActivityLogInitializer(ActivityProcessRepository processRepository,
                                  ActivityEventRepository eventRepository) {
        this.processRepository = processRepository;
        this.eventRepository = eventRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedProcesses();
        seedEvents();
    }

    private void seedProcesses() {
        for (var name : PROCESSES) {
            if (processRepository.findByName(name).isEmpty()) {
                var process = ActivityProcess.builder().name(name).build();
                processRepository.save(process);
                log.debug("Seeded activity process: {}", name);
            }
        }
    }

    private void seedEvents() {
        for (var name : EVENTS) {
            if (eventRepository.findByName(name).isEmpty()) {
                var event = ActivityEvent.builder().name(name).build();
                eventRepository.save(event);
                log.debug("Seeded activity event: {}", name);
            }
        }
    }
}
