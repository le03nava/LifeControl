import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  AccessProvisioningOverview,
  AccessProvisioningQueueEntry,
  AccessProvisioningRejectionRequest,
} from '../models/access-provisioning.models';

/**
 * The statuses these eight endpoints actually produce, and the copy each one earns.
 *
 * Only the statuses the API documents are mapped, one message per status, with a per-path fallback
 * for anything else — the shape `StoreAssignmentService` uses. This is deliberately **not** a
 * general taxonomy: the server stays the authority on why it refused a write.
 *
 * `getDecisionQueue` has no map of its own: it is a pure read (see the class doc), so it keeps no
 * error state to fill.
 */
const LOAD_OVERVIEW_ERRORS = new Map<number, string>([
  [403, 'No tenés permisos para ver el acceso'],
  [404, 'No se encontró el empleado'],
]);

const APPLY_ERRORS = new Map<number, string>([
  [403, 'No tenés permisos para solicitar el acceso'],
  [404, 'No se encontró el empleado'],
  [409, 'El empleado ya tiene una solicitud abierta o no puede recibir el acceso'],
]);

const DEACTIVATE_ERRORS = new Map<number, string>([
  [403, 'No tenés permisos para revocar el acceso'],
  [404, 'No se encontró el empleado'],
  [409, 'El empleado ya tiene una solicitud abierta'],
]);

const RETRY_ERRORS = new Map<number, string>([
  [403, 'No tenés permisos para reintentar la solicitud'],
  [404, 'No se encontró la solicitud abierta'],
  [409, 'La solicitud abierta no está fallada'],
]);

const RESEND_ERRORS = new Map<number, string>([
  [403, 'No tenés permisos para reenviar la invitación'],
  [404, 'No se encontró el empleado'],
  [409, 'No hay una cuenta vinculada o la empresa no tiene dominio de correo'],
]);

const APPROVE_ERRORS = new Map<number, string>([
  [400, 'La decisión no tiene un usuario que la tome'],
  [403, 'No tenés permisos para aprobar la solicitud'],
  [404, 'No se encontró la solicitud'],
  [409, 'No podés aprobar tu propia solicitud o la solicitud no espera aprobación'],
]);

const REJECT_ERRORS = new Map<number, string>([
  [400, 'La decisión no tiene un usuario que la tome o falta el motivo'],
  [403, 'No tenés permisos para rechazar la solicitud'],
  [404, 'No se encontró la solicitud'],
  [409, 'La solicitud no espera aprobación'],
]);

const LOAD_OVERVIEW_FALLBACK = 'Error al cargar el acceso';
const APPLY_FALLBACK = 'Error al solicitar el acceso';
const DEACTIVATE_FALLBACK = 'Error al revocar el acceso';
const RETRY_FALLBACK = 'Error al reintentar la solicitud';
const RESEND_FALLBACK = 'Error al reenviar la invitación';
const APPROVE_FALLBACK = 'Error al aprobar la solicitud';
const REJECT_FALLBACK = 'Error al rechazar la solicitud';

/**
 * HTTP access to an employee's access-provisioning state and to the company's decision queue.
 *
 * The seven employee-scoped routes all answer the same `AccessProvisioningOverview`, and this service
 * keeps **no** overview state of its own: the caller renders its own employee-scoped resource and
 * adopts a write's answer with `set(...)` (record T75), so a shared, employee-blind cache could only
 * ever hand one employee's state to another. It keeps only the call's *outcome*: `loading`, and the
 * shared `error` message both pages read as their last fallback, cleared on entry to every call.
 *
 * `getDecisionQueue` is a **pure read**, the `ContractService.getPositions` idiom: it reserves
 * nothing and mutates no signal, so the company-wide list cannot clobber the employee the section is
 * showing. Its caller (the `Solicitudes` tab) owns its own list state.
 *
 * The two role names every one of these routes requires are `lc-admin` and `lc-employee-access`
 * (`ACCESS_READ_ROLES`); the API's `@PreAuthorize` is the gate and the screens gate their controls on
 * the same pair.
 *
 * **No route carries a query parameter**, so this service builds no `HttpParams`: every route is
 * addressed by path alone. The endpoints that take a body are exactly the reject route
 * (`{ reason }`) and none other — apply, deactivate, retry, resend and approve all send no body.
 */
@Injectable({
  providedIn: 'root',
})
export class AccessProvisioningService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private accessUrl(companyId: string, employeeId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/employees/${employeeId}/access`;
  }

  private queueUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/access/requests`;
  }

  getOverview(companyId: string, employeeId: string): Observable<AccessProvisioningOverview> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<AccessProvisioningOverview>(this.accessUrl(companyId, employeeId)).pipe(
      catchError((err) => {
        this._error.set(statusMessage(LOAD_OVERVIEW_ERRORS, err.status, LOAD_OVERVIEW_FALLBACK));
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /** Creates the `ACTIVATE` task; the API answers the reloaded overview. */
  apply(companyId: string, employeeId: string): Observable<AccessProvisioningOverview> {
    return this.reload(
      this.http.post<AccessProvisioningOverview>(
        `${this.accessUrl(companyId, employeeId)}/apply`,
        null,
      ),
      APPLY_ERRORS,
      APPLY_FALLBACK,
    );
  }

  /** Creates the `DEACTIVATE` task; the API answers the reloaded overview. */
  deactivate(companyId: string, employeeId: string): Observable<AccessProvisioningOverview> {
    return this.reload(
      this.http.post<AccessProvisioningOverview>(
        `${this.accessUrl(companyId, employeeId)}/deactivate`,
        null,
      ),
      DEACTIVATE_ERRORS,
      DEACTIVATE_FALLBACK,
    );
  }

  /** Returns the failed open task to the queue; the API answers the reloaded overview. */
  retry(companyId: string, employeeId: string): Observable<AccessProvisioningOverview> {
    return this.reload(
      this.http.post<AccessProvisioningOverview>(
        `${this.accessUrl(companyId, employeeId)}/retry`,
        null,
      ),
      RETRY_ERRORS,
      RETRY_FALLBACK,
    );
  }

  /** Re-sends the activation invitation; the API answers the reloaded overview. */
  resend(companyId: string, employeeId: string): Observable<AccessProvisioningOverview> {
    return this.reload(
      this.http.post<AccessProvisioningOverview>(
        `${this.accessUrl(companyId, employeeId)}/resend`,
        null,
      ),
      RESEND_ERRORS,
      RESEND_FALLBACK,
    );
  }

  /** Approves a gated request; the API answers the reloaded overview. */
  approve(
    companyId: string,
    employeeId: string,
    taskId: string,
  ): Observable<AccessProvisioningOverview> {
    return this.reload(
      this.http.post<AccessProvisioningOverview>(
        `${this.accessUrl(companyId, employeeId)}/requests/${taskId}/approve`,
        null,
      ),
      APPROVE_ERRORS,
      APPROVE_FALLBACK,
    );
  }

  /** Rejects a gated request with its reason; the API answers the reloaded overview. */
  reject(
    companyId: string,
    employeeId: string,
    taskId: string,
    request: AccessProvisioningRejectionRequest,
  ): Observable<AccessProvisioningOverview> {
    return this.reload(
      this.http.post<AccessProvisioningOverview>(
        `${this.accessUrl(companyId, employeeId)}/requests/${taskId}/reject`,
        request,
      ),
      REJECT_ERRORS,
      REJECT_FALLBACK,
    );
  }

  /**
   * The company's pending decisions, oldest request first.
   *
   * A pure read: it mutates no signal, so a queue load never replaces the employee overview the
   * section is rendering.
   */
  getDecisionQueue(companyId: string): Observable<AccessProvisioningQueueEntry[]> {
    return this.http.get<AccessProvisioningQueueEntry[]>(this.queueUrl(companyId));
  }

  clearError(): void {
    this._error.set(null);
  }

  /** The shared shape of the six writes: clear the outcome, run the request, map the failure. */
  private reload(
    request: Observable<AccessProvisioningOverview>,
    messages: ReadonlyMap<number, string>,
    fallback: string,
  ): Observable<AccessProvisioningOverview> {
    this._loading.set(true);
    this._error.set(null);
    return request.pipe(
      catchError((err) => {
        this._error.set(statusMessage(messages, err.status, fallback));
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }
}

/** The mapped message for `status`, or the path's own fallback. */
function statusMessage(
  messages: ReadonlyMap<number, string>,
  status: number,
  fallback: string,
): string {
  return messages.get(status) ?? fallback;
}
