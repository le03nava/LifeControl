import { DestroyRef, inject, signal, type Signal } from '@angular/core';
import { MOBILE_MAX_WIDTH_QUERY } from '@shared/constants/breakpoints';

/**
 * Tracks whether the viewport currently sits in the mobile band.
 *
 * The band mirrors `$bp-mobile-max` (the SCSS source of truth) through
 * `MOBILE_MAX_WIDTH_QUERY`, so the media query string stays in one place; see
 * `@shared/constants/breakpoints` and its source guard.
 *
 * The returned signal starts at the current match state and follows the media
 * query afterwards. The registered `change` listener is removed on destroy via
 * `DestroyRef`: closing that leak is the defect this helper exists to fix,
 * because the three list pages used to leave the listener attached to a
 * destroyed component.
 *
 * Must be called from an injection context (a component field initializer or
 * constructor): it resolves `DestroyRef` through `inject()`.
 */
export function observeMobileViewport(): Signal<boolean> {
  const isMobile = signal(false);

  if (typeof window !== 'undefined') {
    const mql = window.matchMedia(MOBILE_MAX_WIDTH_QUERY);
    isMobile.set(mql.matches);

    const onChange = (event: MediaQueryListEvent): void => isMobile.set(event.matches);
    mql.addEventListener('change', onChange);
    inject(DestroyRef).onDestroy(() => mql.removeEventListener('change', onChange));
  }

  return isMobile.asReadonly();
}
