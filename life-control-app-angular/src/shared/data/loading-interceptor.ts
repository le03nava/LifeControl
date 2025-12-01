import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { LoadingService } from './loading';

/**
 * Functional HTTP interceptor for loading states
 * Automatically manages loading indicators for HTTP requests
 */
export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  const loadingService = inject(LoadingService);
  const loadingKey = `${req.method}:${req.url}`;

  loadingService.startLoading(loadingKey);

  return next(req).pipe(finalize(() => loadingService.stopLoading(loadingKey)));
};

import { finalize } from 'rxjs/operators';
