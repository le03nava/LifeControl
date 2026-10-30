package com.lifecontrol.api.common.tenant;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * States the tenant of an HTTP operation: the company named by the request's path, verified against the
 * caller's own claims, and the caller's {@code sub} as the subject.
 *
 * <p><b>It runs after authentication, and that is not a detail.</b> The chain's other two filters run
 * before Spring Security (record L2.4 of {@code odd/tasks/tenant-isolation-rls.md}), so neither of them
 * can see a principal. This one is registered
 * {@code addFilterAfter(..., BearerTokenAuthenticationFilter.class)}, so the {@code Jwt} it reads is the
 * one the resource server validated, and it sits before {@code ExceptionTranslationFilter}, which is what
 * turns the refusal below into the same HTTP 403 the rest of the tree answers with.</p>
 *
 * <p><b>The company comes from the URL, never from the claim alone</b> (record L13.1). There is no
 * {@code @RequestHeader} anywhere in this tree: the company of an operation is the {@code {companyId}}
 * path variable, and a token may legitimately carry several companies — the Keycloak mapper is
 * multi-valued and two stores is its documented case. Taking the company from the claim would therefore
 * refuse a multi-company caller, so the path names the operation's tenant and this filter <b>verifies</b>
 * it through {@link CurrentUserContext#verifyCompanyAccess(UUID)}, the same call the 150 service-level
 * checks make. One definition of "does this company belong to this token", not a second one that can
 * drift.</p>
 *
 * <p><b>A path naming a company outside the claims is a refusal, never a wider scope.</b> The scope is
 * not set and the request does not reach a controller, so no query runs inside a transaction that names a
 * tenant the caller cannot claim. The refusal is logged with the trace id before it is rethrown, because
 * the request id is the only handle an operator has on a 403 that never reached the audit aspect.</p>
 *
 * <p><b>A request without claims is not a request without a scope.</b> Health, discovery and the Keycloak
 * callbacks reach this chain with no principal at all; for them no scope is set and no exception is
 * thrown. A path with no company segment (every catalog endpoint, and the whole sales domain) states the
 * subject alone: {@code user_preferences} is read before a company is known, and inventing one from a
 * multi-valued claim is exactly the widening this slice refuses.</p>
 *
 * <p><b>The holder is always cleared</b>, in a {@code finally}, whatever happened above — a 403, a
 * controller exception, or a normal completion. A thread that serves the next request must not inherit
 * this one's tenant, and {@code TenantScopeHolderTest} plus the filter's own integration suite pin
 * that.</p>
 */
public class TenantScopeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantScopeFilter.class);

    /** The prefix every company-scoped endpoint in this tree shares (109 `{companyId}` endpoints). */
    private static final String COMPANY_PATH_PREFIX = "/api/companies/";

    private final CurrentUserContext currentUserContext;

    public TenantScopeFilter(CurrentUserContext currentUserContext) {
        this.currentUserContext = currentUserContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var companyId = companyIdOf(request);
            if (hasAuthenticatedCaller()) {
                if (companyId != null) {
                    verifyClaimedCompany(request, companyId);
                }
                TenantScope.set(companyId, currentUserContext.getUserId());
            }
            chain.doFilter(request, response);
        } finally {
            TenantScope.clear();
        }
    }

    /**
     * Refuses the request when the company it names is not one the caller may claim.
     *
     * <p>Verification is {@link CurrentUserContext#verifyCompanyAccess(UUID)} and not a local loop over
     * the claim: an administrator is admitted by that method without a per-company claim, and a second
     * implementation here would either refuse every administrator or quietly disagree with the 150 checks
     * that follow. The same call also {@code warn}s; this one adds the trace id and the request, which is
     * what an operator needs to find the request that was refused before any controller saw it.</p>
     */
    private void verifyClaimedCompany(HttpServletRequest request, UUID companyId) {
        try {
            currentUserContext.verifyCompanyAccess(companyId);
        } catch (AccessDeniedException refused) {
            log.warn(
                    "Refusing {} {}: company {} is not in the caller's token (trace {})",
                    request.getMethod(),
                    request.getRequestURI(),
                    companyId,
                    MDC.get("traceId"));
            throw refused;
        }
    }

    /**
     * The company named by this request's path, or {@code null} when the path names none.
     *
     * <p>Derived from the URI and not from a header, because the tree has no company header: the only
     * vocabulary is {@code /api/companies/{companyId}/...}. A segment that is not a UUID is not treated as
     * a company at all — no controller can reach a tenant table with it (the path variable is typed
     * {@code UUID}, so the request fails before the mapping), and refusing here would change a 400 into a
     * 403 for a caller who may well be allowed.</p>
     */
    private static UUID companyIdOf(HttpServletRequest request) {
        var path = request.getRequestURI();
        var contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!path.startsWith(COMPANY_PATH_PREFIX)) {
            return null;
        }
        var segment = path.substring(COMPANY_PATH_PREFIX.length());
        var end = segment.indexOf('/');
        if (end >= 0) {
            segment = segment.substring(0, end);
        }
        if (segment.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(segment);
        } catch (IllegalArgumentException notACompanyId) {
            return null;
        }
    }

    /**
     * Whether the request carries a caller this filter may speak for.
     *
     * <p>The test is "the principal is a {@code Jwt}", which is exactly what
     * {@link CurrentUserContext} reads its claims from — one definition of "this caller has claims", not a
     * second one that can drift. Anything else — an anonymous token, a test's {@code @WithMockUser}, no
     * authentication at all on the public paths — means "no claims to verify and no subject to state", so
     * the request proceeds with no scope. It is deliberately not an exception: a missing principal is the
     * shape of every unauthenticated request in this tree, and the endpoints behind it are exactly the ones
     * that never read a tenant table.</p>
     */
    private static boolean hasAuthenticatedCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt;
    }
}
