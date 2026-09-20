import { HttpContextToken } from '@angular/common/http';

/**
 * HTTP context token that suppresses **only** the global error toast for a request.
 *
 * Set it on requests whose failure is part of the normal control flow — for
 * example a `404` that the caller turns into a `null` result. It never suppresses
 * the rethrow: `errorInterceptor` still propagates the error to the subscriber,
 * so the caller keeps full control of the outcome and the notification layer
 * stays silent. It has no effect on any other interceptor behaviour.
 */
export const SKIP_ERROR_NOTIFICATION = new HttpContextToken<boolean>(() => false);
