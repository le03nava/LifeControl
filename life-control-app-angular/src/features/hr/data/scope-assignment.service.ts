import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  CloseScopeAssignmentRequest,
  ScopeAssignment,
  ScopeAssignmentRequest,
} from '../models/scope-assignment.models';

/**
 * The statuses these three endpoints actually produce, and the copy each one earns.
 *
 * Only the statuses the API documents are mapped, one message per status, with a per-path fallback
 * for anything else — the shape `EmployeeService` uses for its 409. This is deliberately **not** a
 * general taxonomy: the server stays the authority on why it refused a write, and the dialog prefers
 * the server's own envelope message when there is one.
 *
 * The one 404 says "employee **or node**" on purpose, and it covers all four levels: a node id the
 * company does not own — a store included — is unknown here, so a store from another company earns
 * the same message as a country that does not exist. Telling the operator which of the two it was
 * would report a fact the API deliberately does not.
 */
const CREATE_ASSIGNMENT_ERRORS = new Map<number, string>([
  [400, 'Los datos de la asignación no son válidos'],
  [403, 'No tenés permisos para asignar alcance'],
  [404, 'No se encontró el empleado o el nodo'],
  [409, 'Ya existe una asignación de ese nodo que se superpone'],
]);

const CLOSE_ASSIGNMENT_ERRORS = new Map<number, string>([
  [400, 'La fecha de cierre no puede ser anterior al inicio'],
  [403, 'No tenés permisos para cerrar asignaciones'],
  [404, 'No se encontró la asignación'],
  [409, 'La asignación ya está cerrada'],
]);

const LOAD_ASSIGNMENTS_ERROR = 'Error al cargar el alcance asignado';
const CREATE_ASSIGNMENT_FALLBACK = 'Error al crear la asignación de alcance';
const CLOSE_ASSIGNMENT_FALLBACK = 'Error al cerrar la asignación de alcance';

/**
 * HTTP access to an employee's scope-assignment history: country, region, zone and store in one
 * table.
 *
 * `getAssignments` feeds the `assignments` signal (newest first, as the API orders it),
 * `createAssignment` prepends the created row to keep that order, and `closeAssignment` replaces the
 * closed row in place. Each of the three clears `_error` on entry, so a failed write followed by a
 * successful one does not leave a stale banner.
 *
 * The path is nested twice on purpose — `/api/companies/{companyId}/employees/{employeeId}/scope-assignments`
 * — because both scopes are part of the wire contract: the employee lives in a company and the
 * service resolves the company scope before doing anything. There is exactly **one** route for the
 * four levels: the level of a write is the node id the request carries, so a store assignment is not
 * a second endpoint.
 *
 * `includeDisabled` is always sent explicitly rather than left to the endpoint's default, so the
 * client's own request states which set it is asking for. The employee detail asks for the whole
 * history: a soft-deleted assignment is part of the record, and the section distinguishes it.
 *
 * There is no assignment `PUT` and no `DELETE`: a move is a new assignment, a mistake is closed, and
 * the history is the point. Closing sends no body when no end date is given, which the API reads as
 * "close today" with today as the last day covered.
 */
@Injectable({
  providedIn: 'root',
})
export class ScopeAssignmentService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _assignments = signal<ScopeAssignment[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Assignments read by the last history call; the detail page renders from its resource. */
  readonly assignments = this._assignments.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private assignmentsUrl(companyId: string, employeeId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/employees/${employeeId}/scope-assignments`;
  }

  getAssignments(
    companyId: string,
    employeeId: string,
    includeDisabled = false,
  ): Observable<ScopeAssignment[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = new HttpParams().set('includeDisabled', String(includeDisabled));
    return this.http
      .get<ScopeAssignment[]>(this.assignmentsUrl(companyId, employeeId), { params })
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
    request: ScopeAssignmentRequest,
  ): Observable<ScopeAssignment> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .post<ScopeAssignment>(this.assignmentsUrl(companyId, employeeId), request)
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
    request?: CloseScopeAssignmentRequest,
  ): Observable<ScopeAssignment> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<ScopeAssignment>(
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
