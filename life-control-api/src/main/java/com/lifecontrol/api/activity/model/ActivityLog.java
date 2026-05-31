package com.lifecontrol.api.activity.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable audit log entry recording a user action across any domain.
 * <p>
 * Does NOT extend {@link com.lifecontrol.api.common.model.Auditable} — logs are
 * write-once with only {@code created_at}. No updated_at, no lifecycle beyond creation.
 */
@Entity
@Table(name = "activity_logs")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "username")
    private String username;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "activity_process_id", nullable = false)
    private ActivityProcess activityProcess;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "activity_event_id", nullable = false)
    private ActivityEvent activityEvent;

    @Column(name = "http_method", nullable = false)
    private String httpMethod;

    @Column(name = "http_status", nullable = false)
    private Integer httpStatus;

    @Column(name = "request_path", nullable = false, length = 500)
    private String requestPath;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Default constructor for JPA
    protected ActivityLog() {}

    // Private constructor — instances created only through the Builder
    private ActivityLog(UUID id, String userId, String username,
                        ActivityProcess activityProcess, ActivityEvent activityEvent,
                        String httpMethod, Integer httpStatus, String requestPath,
                        String ipAddress, String userAgent, String payloadJson) {
        this.id = id;
        this.userId = userId;
        this.username = username;
        this.activityProcess = activityProcess;
        this.activityEvent = activityEvent;
        this.httpMethod = httpMethod;
        this.httpStatus = httpStatus;
        this.requestPath = requestPath;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.payloadJson = payloadJson;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // ── Getters (no setters — immutable) ─────────────────────

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public ActivityProcess getActivityProcess() {
        return activityProcess;
    }

    public ActivityEvent getActivityEvent() {
        return activityEvent;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // ── Builder ──────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID id;
        private String userId;
        private String username;
        private ActivityProcess activityProcess;
        private ActivityEvent activityEvent;
        private String httpMethod;
        private Integer httpStatus;
        private String requestPath;
        private String ipAddress;
        private String userAgent;
        private String payloadJson;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder activityProcess(ActivityProcess activityProcess) {
            this.activityProcess = activityProcess;
            return this;
        }

        public Builder activityEvent(ActivityEvent activityEvent) {
            this.activityEvent = activityEvent;
            return this;
        }

        public Builder httpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        public Builder httpStatus(Integer httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder requestPath(String requestPath) {
            this.requestPath = requestPath;
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder payloadJson(String payloadJson) {
            this.payloadJson = payloadJson;
            return this;
        }

        public ActivityLog build() {
            return new ActivityLog(id, userId, username, activityProcess, activityEvent,
                    httpMethod, httpStatus, requestPath, ipAddress, userAgent, payloadJson);
        }
    }
}
