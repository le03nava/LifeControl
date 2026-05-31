package com.lifecontrol.api.activity.event;

import org.springframework.context.ApplicationEvent;

/**
 * Event published by {@link com.lifecontrol.api.activity.aspect.ActivityLogAspect}
 * after a controller method completes successfully.
 * <p>
 * Carries all fields required to persist an entry in {@code activity_logs}.
 * The {@link com.lifecontrol.api.activity.listener.ActivityLogEventListener}
 * sanitizes the payload and delegates to the service layer.
 */
public class ActivityLogEvent extends ApplicationEvent {

    private final String userId;
    private final String username;
    private final String processName;
    private final String eventName;
    private final String httpMethod;
    private final int httpStatus;
    private final String requestPath;
    private final String ipAddress;
    private final String userAgent;
    private final String payloadJson;

    public ActivityLogEvent(Object source,
                            String userId, String username,
                            String processName, String eventName,
                            String httpMethod, int httpStatus,
                            String requestPath, String ipAddress,
                            String userAgent, String payloadJson) {
        super(source);
        this.userId = userId;
        this.username = username;
        this.processName = processName;
        this.eventName = eventName;
        this.httpMethod = httpMethod;
        this.httpStatus = httpStatus;
        this.requestPath = requestPath;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.payloadJson = payloadJson;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getProcessName() {
        return processName;
    }

    public String getEventName() {
        return eventName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public int getHttpStatus() {
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
}
