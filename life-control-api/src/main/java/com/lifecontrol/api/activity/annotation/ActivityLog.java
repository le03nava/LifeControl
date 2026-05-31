package com.lifecontrol.api.activity.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional annotation to override the auto-resolved process name and/or event
 * name on a controller method.
 * <p>
 * By default, the {@link com.lifecontrol.api.activity.aspect.ActivityLogAspect}
 * resolves the process from the controller's package name (e.g.
 * {@code com.lifecontrol.api.company.controller} &rarr; {@code COMPANY}) and
 * the event from the HTTP method (GET &rarr; READ, POST &rarr; CREATE, etc.).
 * Apply this annotation to override either value for a specific endpoint.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActivityLog {

    /**
     * Overrides the auto-resolved process name.
     * If empty (default), the aspect uses the package-based resolution.
     */
    String process() default "";

    /**
     * Overrides the auto-resolved event name.
     * If empty (default), the aspect uses the HTTP-method-based resolution.
     */
    String event() default "";
}
