/**
 * Media query matching the "mobile" viewport band, below the first breakpoint.
 *
 * The SCSS source of truth is `$bp-mobile-max` in
 * `src/shared/styles/_variables.scss`, where the maximum is fixed at `575.98px`
 * and not `575px` so the `575.01–575.99px` gap is not left uncovered against
 * `min-width: $bp-sm`. A TypeScript string cannot import a Sass variable, so
 * this module mirrors that value for JavaScript consumers (`window.matchMedia`,
 * `BreakpointObserver`).
 *
 * `breakpoints.spec.ts` is what keeps the two definitions in sync: it reads
 * `$bp-mobile-max` straight from the SCSS and fails if either side moves alone.
 */
export const MOBILE_MAX_WIDTH_QUERY = '(max-width: 575.98px)';
