import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { Employee, EmployeeEmailSuggestion, EmployeeRequest } from '../models/employee.models';

/**
 * HTTP access to the company-scoped employee registry.
 *
 * Mirrors `DepartmentService`'s signal-backed shape, with the differences the
 * employee contract carries:
 *
 * - the list takes a `search` term **and** a `statusId` filter, both server-side
 *   and both optional; `includeDisabled` is always forwarded so the "show
 *   disabled" toggle re-queries the API instead of filtering a truncated list
 *   client-side, and neither optional filter is ever sent as `undefined`/`null`;
 * - the list is deliberately unpaginated and unordered client-side: the API
 *   returns a plain ordered array (by employee number) and this service adds no
 *   client-side filtering, sorting, pagination, caching or memoization;
 * - `suggestEmail` is a **pure read**: it reserves nothing (decision T8) and
 *   therefore writes no signal and takes no lock;
 * - there is **no version precondition** (T2), so there is no `If-Match` header
 *   and no 412 branch.
 *
 * Disable is a soft delete: `DELETE` sets `enabled = false`, and the inverse is
 * `PATCH .../enable` with `{ "enabled": true }`.
 */
@Injectable({
  providedIn: 'root',
})
export class EmployeeService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _employees = signal<Employee[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Employees read by the last list call; the list page renders from its resource. */
  readonly employees = this._employees.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private employeesUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/employees`;
  }

  getEmployees(
    companyId: string,
    search?: string,
    statusId?: string,
    includeDisabled = false,
  ): Observable<Employee[]> {
    this._loading.set(true);
    this._error.set(null);
    let params = new HttpParams().set('includeDisabled', String(includeDisabled));
    if (search) {
      params = params.set('search', search);
    }
    if (statusId) {
      params = params.set('statusId', statusId);
    }
    return this.http.get<Employee[]>(this.employeesUrl(companyId), { params }).pipe(
      tap((employees) => this._employees.set(employees)),
      catchError((err) => {
        this._error.set('Error al cargar los empleados');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /**
   * Single read used by the edit screen to hydrate its form.
   *
   * It is not fed to the list signal: editing one row must not replace the
   * collection the list page is showing.
   */
  getEmployee(companyId: string, id: string): Observable<Employee> {
    this._error.set(null);
    return this.http.get<Employee>(`${this.employeesUrl(companyId)}/${id}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar el empleado');
        return throwError(() => err);
      }),
    );
  }

  /**
   * Asks the API for a candidate email for the given names.
   *
   * A pure read: it reserves nothing and mutates no state, so it deliberately
   * does not touch `_loading`, `_error` or the list signal. Exactly one of the
   * answer's fields is set — the candidate or a machine-readable reason.
   */
  suggestEmail(
    companyId: string,
    firstName: string,
    paternalLastName: string,
  ): Observable<EmployeeEmailSuggestion> {
    const params = new HttpParams()
      .set('firstName', firstName)
      .set('paternalLastName', paternalLastName);
    return this.http.get<EmployeeEmailSuggestion>(`${this.employeesUrl(companyId)}/suggest-email`, {
      params,
    });
  }

  addEmployee(companyId: string, request: EmployeeRequest): Observable<Employee> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<Employee>(this.employeesUrl(companyId), request).pipe(
      tap((employee) => {
        this._employees.set([...this._employees(), employee]);
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un empleado con ese número o correo'
            : 'Error al crear el empleado',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updateEmployee(companyId: string, id: string, request: EmployeeRequest): Observable<Employee> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<Employee>(`${this.employeesUrl(companyId)}/${id}`, request).pipe(
      tap((updated) => {
        this._employees.set(this._employees().map((e) => (e.id === id ? updated : e)));
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un empleado con ese número o correo'
            : 'Error al actualizar el empleado',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /**
   * Soft delete: the API sets `enabled = false` and keeps the row.
   *
   * Clears `_error` on entry — a deliberate deviation from the copied
   * `DepartmentService.removeDepartment` idiom, which leaves a stale error behind.
   */
  removeEmployee(companyId: string, id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.delete<void>(`${this.employeesUrl(companyId)}/${id}`).pipe(
      tap(() => {
        this._employees.set(this._employees().filter((e) => e.id !== id));
      }),
      catchError((err) => {
        this._error.set('Error al deshabilitar el empleado');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enableEmployee(companyId: string, id: string): Observable<Employee> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<Employee>(`${this.employeesUrl(companyId)}/${id}/enable`, { enabled: true })
      .pipe(
        tap((updated) => {
          this._employees.set(this._employees().map((e) => (e.id === id ? updated : e)));
        }),
        catchError((err) => {
          this._error.set('Error al reactivar el empleado');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  clearError(): void {
    this._error.set(null);
  }
}
