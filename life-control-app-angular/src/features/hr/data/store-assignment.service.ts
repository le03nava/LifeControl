import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  CloseStoreAssignmentRequest,
  StoreAssignment,
  StoreAssignmentRequest,
} from '../models/store-assignment.models';

/**
 * The statuses these three endpoints actually produce, and the copy each one earns.
 *
 * Only the statuses the API documents are mapped, one message per status, with a per-path fallback
 * for anything else — the shape `EmployeeService` uses for its 409. This is deliberately **not** a
 * general taxonomy: the server stays the authority on why it refused a write, and the dialog prefers
 * the server's own envelope message when there is one.
 */
const CREATE_ASSIGNMENT_ERRORS = new Map<number, string>([
  [400, 'Los datos de la asignación no son válidos'],
  [403, 'No tenés permisos para asignar tiendas'],
  [404, 'No se encontró el empleado o la tienda'],
  [409, 'Ya existe una asignación en esa tienda que se superpone'],
]);

const CLOSE_ASSIGNMENT_ERRORS = new Map<number, string>([
  [400, 'La fecha de cierre no puede ser anterior al inicio'],
  [403, 'No tenés permisos para cerrar asignaciones'],
  [404, 'No se encontró la asignación'],
  [409, 'La asignación ya está cerrada'],
]);

const LOAD_ASSIGNMENTS_ERROR = 'Error al cargar las tiendas asignadas';
const CREATE_ASSIGNMENT_FALLBACK = 'Error al crear la asignación';
const CLOSE_ASSIGNMENT_FALLBACK = 'Error al cerrar la asignación';

/**
 * HTTP access to an employee's store-assignment history.
 *
 * Mirrors `ContractService`'s shape: `getAssignments` feeds the `assignments` signal (newest first,
 * as the API orders it), `createAssignment` prepends the created row to keep that order, and
 * `closeAssignment` replaces the closed row in place. Every one of the three clears `_error` on
 * entry, so a failed write followed by a successful one does not leave a stale banner.
 *
 * `includeDisabled` is always sent explicitly rather than left to the endpoint's default, so the
 * client's own request states which set it is asking for. The employee detail asks for the whole
 * history: a soft-deleted assignment is part of the record, and the section distinguishes it.
 *
 * There is no assignment `PUT` and no `DELETE` (`T5`): a transfer is a new assignment, a mistake is
 * closed, and the history is the point. Closing sends no body when no end date is given, which the
 * API reads as "close today" with today as the last day covered (`D5`).
 */
@Injectable({
  providedIn: 'root',
})
export class StoreAssignmentService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _assignments = signal<StoreAssignment[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Assignments read by the last history call; the detail page renders from its resource. */
  readonly assignments = this._assignments.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private assignmentsUrl(companyId: string, employeeId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/employees/${employeeId}/store-assignments`;
  }

  getAssignments(
    companyId: string,
    employeeId: string,
    includeDisabled = false,
  ): Observable<StoreAssignment[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = new HttpParams().set('includeDisabled', String(includeDisabled));
    return this.http
      .get<StoreAssignment[]>(this.assignmentsUrl(companyId, employeeId), { params })
      .pipe(
        tap((assignments) => this._assignments.set(assignments)),
        catchError((err) => {
          this._error.set(LOAD_ASSIGNMENTS_ERROR);
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  createAssignment(
    companyId: string,
    employeeId: string,
    request: StoreAssignmentRequest,
  ): Observable<StoreAssignment> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .post<StoreAssignment>(this.assignmentsUrl(companyId, employeeId), request)
      .pipe(
        tap((assignment) => {
          // The API orders newest first, so a freshly opened assignment goes to the front.
          this._assignments.set([assignment, ...this._assignments()]);
        }),
        catchError((err) => {
          this._error.set(
            statusMessage(CREATE_ASSIGNMENT_ERRORS, err.status, CREATE_ASSIGNMENT_FALLBACK),
          );
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * Closes an open assignment.
   *
   * When the request is omitted the call carries no body at all, which the API reads as "close
   * today" (`@RequestBody(required = false)`); when present, `endDate` is the last day covered,
   * inclusive.
   */
  closeAssignment(
    companyId: string,
    employeeId: string,
    id: string,
    request?: CloseStoreAssignmentRequest,
  ): Observable<StoreAssignment> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<StoreAssignment>(
        `${this.assignmentsUrl(companyId, employeeId)}/${id}/close`,
        request ?? null,
      )
      .pipe(
        tap((updated) => {
          this._assignments.set(
            this._assignments().map((assignment) => (assignment.id === id ? updated : assignment)),
          );
        }),
        catchError((err) => {
          this._error.set(
            statusMessage(CLOSE_ASSIGNMENT_ERRORS, err.status, CLOSE_ASSIGNMENT_FALLBACK),
          );
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  clearError(): void {
    this._error.set(null);
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
