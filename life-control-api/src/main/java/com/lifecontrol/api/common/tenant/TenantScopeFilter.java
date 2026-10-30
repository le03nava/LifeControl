package com.lifecontrol.api.common.tenant;

import com.lifecontrol.api.common.auth.CurrentUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
 * thrown.</p>
 *
 * <p><b>A path that names no company takes the tenant from the claim when the claim is determinate, unless
 * the path is exempt.</b> Most of the sales domain ({@code /api/purchase-orders}, and every sibling that
 * reads a table ADR-0005 arms) has no {@code {companyId}} segment, so refusing them or leaving them
 * company-less would empty them the moment the policy lands. For an authenticated caller this filter
 * therefore states:</p>
 *
 * <ul>
 *   <li><b>a tenant-agnostic path</b> — no company, whatever the claim count: the path needs no tenant
 *       (see the list below).</li>
 *   <li><b>one {@code company_id} claim</b> on any other path — that company. One claim is a determinate
 *       tenant.</li>
 *   <li><b>no claim</b> on any other path — no company, exactly as before: a caller who belongs to none
 *       reads no tenant row, which is the honest answer.</li>
 *   <li><b>more than one claim</b> on a path that is not exempt — a refusal. Two claims and no tenant in
 *       the path is not a wider scope, it is an undeterminable one, and the request is refused
 *       <b>before</b> any scope is stated, so no query runs under a scope the caller's intent cannot
 *       justify.</li>
 * </ul>
 *
 * <p><b>The tenant-agnostic list is one statement: "no read on this path touches a tenant table".</b>
 * Each entry is a prefix matched segment-safely against the path —</p>
 *
 * <ul>
 *   <li>{@code /api/profile} — the subject's own row, and the caller's chosen company context;</li>
 *   <li>{@code /api/countries}, {@code /api/measure-units}, {@code /api/payment-methods},
 *       {@code /api/statuses}, {@code /api/status-types}, {@code /api/seniority-levels} — global catalogs
 *       with no {@code company_id} column;</li>
 *   <li>{@code /api/activity-logs} — no tenant table;</li>
 *   <li>{@code /api/users-admin} — Keycloak administration, and it writes another subject's preferences;
 *       </li>
 *   <li>{@code /api/companies} — the list resolves the claims itself, one scoped read per claim, and the
 *       create states its own id.</li>
 * </ul>
 *
 * <p><b>A path missing from that list fails loud instead of reading nothing.</b> That is the whole point
 * of the closed list: a multi-company caller on a company-less path that does read tenant tables gets a
 * 403 an operator can see, rather than a zero-row page that looks like an empty database. The refusal is
 * the same shape as the out-of-claim one — {@link AccessDeniedException} through
 * {@code ExceptionTranslationFilter} — and it is logged with the trace id before it is thrown.</p>
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

    /**
     * The paths whose reads touch no tenant table, so a company is neither needed nor derivable there.
     * Matched segment-safely: {@code /api/countries} and {@code /api/countries/123} match,
     * {@code /api/countries-archive} does not. Every entry is in the class javadoc with its reason, and a
     * path missing from here fails loud (403) instead of silently reading nothing.
     */
    private static final List<String> TENANT_AGNOSTIC_PATHS = List.of(
            "/api/profile",
            "/api/countries",
            "/api/measure-units",
            "/api/payment-methods",
            "/api/statuses",
            "/api/status-types",
            "/api/seniority-levels",
            "/api/activity-logs",
            "/api/users-admin",
            "/api/companies");

    private final CurrentUserContext currentUserContext;

    public TenantScopeFilter(CurrentUserContext currentUserContext) {
        this.currentUserContext = currentUserContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var path = pathOf(request);
            var companyId = companyIdOf(path);
            if (hasAuthenticatedCaller()) {
                if (companyId != null) {
                    verifyClaimedCompany(request, companyId);
                    TenantScope.set(companyId, currentUserContext.getUserId());
                } else {
                    TenantScope.set(companyOfTheClaims(request, path), currentUserContext.getUserId());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantScope.clear();
        }
    }

    /**
     * The company a path that names none inherits from the caller's claims, or {@code null}.
     *
     * <p>The tenant-agnostic list is consulted <b>first</b>, whatever the claim count: an exempt path states
     * no company even when the token carries exactly one, because the exemption means "this path needs no
     * tenant", not "this path cannot pick one". The company list is the case that makes the difference —
     * it reads one claimed company at a time under its own scope, so a company stated here would be a
     * second, unused statement of the same fact.</p>
     *
     * <p>Anywhere else, one claim is a determinate tenant and is stated; no claim states none; and more
     * than one claim is <b>refused</b>, because a company-less path that reads tenant tables would
     * otherwise answer every list with zero rows once the policies are armed. The refusal happens here,
     * before {@link TenantScope#set}, so the request never carries a half-stated scope into a
     * transaction.</p>
     *
     * @throws AccessDeniedException when the token carries several companies and the path is not
     *     tenant-agnostic, i.e. when the tenant cannot be determined
     */
    private UUID companyOfTheClaims(HttpServletRequest request, String path) {
        if (isTenantAgnostic(path)) {
            return null;
        }
        var claims = currentUserContext.getCompanyIds();
        if (claims.size() <= 1) {
            return claims.stream().findFirst().orElse(null);
        }
        log.warn(
                "Refusing {} {}: the caller's token carries {} company_id claims and this path names no "
                        + "company, so the tenant cannot be determined (trace {})",
                request.getMethod(),
                request.getRequestURI(),
                claims.size(),
                MDC.get("traceId"));
        throw new AccessDeniedException("The operation's company cannot be determined from a token carrying "
                + claims.size() + " company_id claims and a path that names none");
    }

    /** Whether this path is on the closed list whose reads touch no tenant table. */
    private static boolean isTenantAgnostic(String path) {
        return TENANT_AGNOSTIC_PATHS.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
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
     * The request's path with the context path removed, which is the form every prefix here is written in.
     * The tree is deployed without a context path today; the strip is what keeps the match correct if one
     * is configured, exactly as {@link #companyIdOf} relies on.
     */
    private static String pathOf(HttpServletRequest request) {
        var path = request.getRequestURI();
        var contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
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
    private static UUID companyIdOf(String path) {
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
