import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { Department, DepartmentRequest } from '../models/department.models';

/**
 * HTTP access to the company-scoped department catalog.
 *
 * Mirrors `CompanyRegionService` (`getRegions`'s signal-backed shape and its
 * `includeDisabled` query string), with two deliberate differences:
 *
 * - the toggle **reaches the request**: `getDepartments` always forwards
 *   `includeDisabled`, so turning "show disabled" on re-queries the API instead
 *   of filtering an already-enabled-only list client-side (the trap the live
 *   regions page falls into);
 * - there is **no version precondition** (T2), so there is no `If-Match` header
 *   and no 412 branch.
 *
 * Disable is a soft delete: `DELETE` sets `enabled = false`, and the inverse is
 * `PATCH .../enable` with `{ "enabled": true }`.
 */
@Injectable({
  providedIn: 'root',
})
export class DepartmentService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _departments = signal<Department[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Departments read by the last list call; the list page renders from its resource. */
  readonly departments = this._departments.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private departmentsUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/departments`;
  }

  getDepartments(companyId: string, includeDisabled = false): Observable<Department[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http.get<Department[]>(this.departmentsUrl(companyId), { params }).pipe(
      tap((departments) => this._departments.set(departments)),
      catchError((err) => {
        this._error.set('Error al cargar los departamentos');
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
  getDepartment(companyId: string, id: string): Observable<Department> {
    this._error.set(null);
    return this.http.get<Department>(`${this.departmentsUrl(companyId)}/${id}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar el departamento');
        return throwError(() => err);
      }),
    );
  }

  addDepartment(companyId: string, request: DepartmentRequest): Observable<Department> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<Department>(this.departmentsUrl(companyId), request).pipe(
      tap((department) => {
        this._departments.set([...this._departments(), department]);
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un departamento con ese código o nombre'
            : 'Error al crear el departamento',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updateDepartment(
    companyId: string,
    id: string,
    request: DepartmentRequest,
  ): Observable<Department> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<Department>(`${this.departmentsUrl(companyId)}/${id}`, request).pipe(
      tap((updated) => {
        this._departments.set(this._departments().map((d) => (d.id === id ? updated : d)));
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un departamento con ese código o nombre'
            : 'Error al actualizar el departamento',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /** Soft delete: the API sets `enabled = false` and keeps the row. */
  removeDepartment(companyId: string, id: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.departmentsUrl(companyId)}/${id}`).pipe(
      tap(() => {
        this._departments.set(this._departments().filter((d) => d.id !== id));
      }),
      catchError((err) => {
        this._error.set('Error al deshabilitar el departamento');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enableDepartment(companyId: string, id: string): Observable<Department> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<Department>(`${this.departmentsUrl(companyId)}/${id}/enable`, { enabled: true })
      .pipe(
        tap((updated) => {
          this._departments.set(this._departments().map((d) => (d.id === id ? updated : d)));
        }),
        catchError((err) => {
          this._error.set('Error al reactivar el departamento');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  clearError(): void {
    this._error.set(null);
  }
}
