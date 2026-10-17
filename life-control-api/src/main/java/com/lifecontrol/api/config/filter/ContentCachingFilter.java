package com.lifecontrol.api.config.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Wraps every request in a {@link ContentCachingRequestWrapper} so that the
 * request body can be read multiple times (once by the controller, once by the
 * activity-log aspect).
 * <p>
 * Must be registered early in the filter chain — see {@link ContentCachingConfig}.
 */
@Component
public class ContentCachingFilter extends OncePerRequestFilter {

    /**
     * Spring Framework 7 removed the single-argument {@link ContentCachingRequestWrapper}
     * constructor, which cached the whole body without a limit, in favour of an explicit
     * cache limit. The wrapper only enforces a limit when the value is {@code > 0}; a
     * non-positive value (passed here) reproduces the removed constructor exactly — same
     * unbounded buffer, same {@code Content-Length}-based initial capacity.
     * <p>
     * This cache stays unbounded on purpose: the activity-log aspect replays the cached body
     * verbatim, so any limit would silently truncate the audit payload for larger requests.
     * The wrapper never affects what the controller reads.
     */
    private static final int UNLIMITED_CONTENT_CACHE = -1;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        var wrappedRequest = new ContentCachingRequestWrapper(request, UNLIMITED_CONTENT_CACHE);
        filterChain.doFilter(wrappedRequest, response);
    }
}
