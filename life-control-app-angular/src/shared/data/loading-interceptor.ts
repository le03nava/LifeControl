import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { timer } from 'rxjs';
import { finalize } from 'rxjs/operators';
import { LoadingService } from './loading';

/**
 * How long a request may stay in flight before the global overlay appears.
 *
 * The overlay is full-screen and opaque, so painting it for a request that
 * resolves in a few milliseconds blanks the whole app for a frame — an
 * instantaneous white flash, repeated once per request. Below this threshold the
 * request is expected to resolve unnoticed; the page's own skeletons cover that
 * gap.
 */
export const LOADING_INDICATOR_DELAY_MS = 200;

/**
 * Functional HTTP interceptor for loading states.
 *
 * Starts the global indicator only after the request has been in flight for
 * {@link LOADING_INDICATOR_DELAY_MS}, so a request that resolves faster than that
 * never paints it. The pending start is cancelled as soon as the request settles:
 * a timer that outlived its request would paint the overlay on an idle screen,
 * which is worse than the flash this delay removes.
 */
export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  const loadingService = inject(LoadingService);
  const loadingKey = `${req.method}:${req.url}`;

  const delayedStart = timer(LOADING_INDICATOR_DELAY_MS).subscribe(() =>
    loadingService.startLoading(loadingKey),
  );

  return next(req).pipe(
    finalize(() => {
      delayedStart.unsubscribe();
      // A request that finished under the threshold never called startLoading, so
      // this is the common path and must stay a no-op for an unknown key.
      loadingService.stopLoading(loadingKey);
    }),
  );
};
