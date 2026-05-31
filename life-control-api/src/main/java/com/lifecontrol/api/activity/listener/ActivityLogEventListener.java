package com.lifecontrol.api.activity.listener;

import com.lifecontrol.api.activity.event.ActivityLogEvent;
import com.lifecontrol.api.activity.service.ActivityLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link ActivityLogEvent} published by the aspect and persists
 * the log entry via {@link ActivityLogService}.
 * <p>
 * Uses {@link TransactionPhase#AFTER_COMMIT} so the log is written only after
 * the originating transaction commits. Falls back to immediate execution if no
 * transaction is active (which is the common case since the aspect runs in the
 * controller layer).
 */
@Component
public class ActivityLogEventListener {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogEventListener.class);

    private final ActivityLogService activityLogService;

    public ActivityLogEventListener(ActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityLogEvent(ActivityLogEvent event) {
        try {
            activityLogService.save(event);
        } catch (Exception e) {
            // Activity logging is best-effort — never propagate failures
            log.warn("Failed to save activity log entry: {}", e.getMessage());
        }
    }
}
