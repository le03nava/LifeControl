import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  Position,
  PositionRequest,
  PositionSalaryBand,
  PositionSalaryBandsRequest,
} from '../models/position.models';

/**
 * HTTP access to the company-scoped position catalog.
 *
 * Mirrors `DepartmentService` — the catalog mold this feature already uses —
 * with three differences this catalog forces:
 *
 * - the list read takes a **department filter** as well as the disabled toggle,
 *   and **both reach the request**: `includeDisabled` is always forwarded and
 *   `departmentId` is sent only when one is chosen, so the filter is resolved by
 *   the API instead of by a client-side pass over an already-narrowed list;
 * - `reportsToPositionId` and `displayOrder` are part of the write payload, and
 *   the *server* is what rejects a cycle, a cross-company parent or a duplicate
 *   code per department (record T5, G6): this service maps the statuses it is
 *   given and never re-implements those rules;
 * - there is **no version precondition** (T2), so there is no `If-Match` header
 *   and no 412 branch on either write.
 *
 * Disable is a soft delete: `DELETE` sets `enabled = false` and the inverse is
 * `PATCH .../enable` with `{ "enabled": true }`.
 */
@Injectable({
  providedIn: 'root',
})
export class PositionService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _positions = signal<Position[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Positions read by the last list call; the list page renders from its resource. */
  readonly positions = this._positions.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private positionsUrl(companyId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/positions`;
  }

  /**
   * The catalog list, optionally narrowed by department.
   *
   * `includeDisabled` is forwarded on every call — the toggle re-queries the API
   * rather than filtering an already-enabled-only list client-side, which is the
   * trap the live regions page falls into — and `departmentId` is omitted from
   * the query string entirely when no department is chosen.
   */
  getPositions(
    companyId: string,
    departmentId?: string,
    includeDisabled = false,
  ): Observable<Position[]> {
    this._loading.set(true);
    this._error.set(null);
    const params: Record<string, string> = { includeDisabled: String(includeDisabled) };
    if (departmentId) {
      params['departmentId'] = departmentId;
    }
    return this.http.get<Position[]>(this.positionsUrl(companyId), { params }).pipe(
      tap((positions) => this._positions.set(positions)),
      catchError((err) => {
        this._error.set('Error al cargar los puestos');
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
  getPosition(companyId: string, id: string): Observable<Position> {
    this._error.set(null);
    return this.http.get<Position>(`${this.positionsUrl(companyId)}/${id}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar el puesto');
        return throwError(() => err);
      }),
    );
  }

  addPosition(companyId: string, request: PositionRequest): Observable<Position> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<Position>(this.positionsUrl(companyId), request).pipe(
      tap((position) => {
        this._positions.set([...this._positions(), position]);
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un puesto con ese código en el departamento'
            : 'Error al crear el puesto',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updatePosition(
    companyId: string,
    id: string,
    request: PositionRequest,
  ): Observable<Position> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<Position>(`${this.positionsUrl(companyId)}/${id}`, request).pipe(
      tap((updated) => {
        this._positions.set(this._positions().map((p) => (p.id === id ? updated : p)));
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un puesto con ese código en el departamento'
            : 'Error al actualizar el puesto',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /** Soft delete: the API sets `enabled = false` and keeps the row. */
  removePosition(companyId: string, id: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.positionsUrl(companyId)}/${id}`).pipe(
      tap(() => {
        this._positions.set(this._positions().filter((p) => p.id !== id));
      }),
      catchError((err) => {
        this._error.set('Error al deshabilitar el puesto');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enablePosition(companyId: string, id: string): Observable<Position> {
    this._loading.set(true);
    this._error.set(null);
    return this.http
      .patch<Position>(`${this.positionsUrl(companyId)}/${id}/enable`, { enabled: true })
      .pipe(
        tap((updated) => {
          this._positions.set(this._positions().map((p) => (p.id === id ? updated : p)));
        }),
        catchError((err) => {
          this._error.set('Error al reactivar el puesto');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * The position's **complete** stored band set, disabled rows included.
   *
   * This is the editor's source of truth (D13): "no band configured for this
   * level" is a stored row with `enabled = false`, not a missing row, so the
   * screen edits the rows it is handed instead of rebuilding the set from the
   * global catalog — a screen that did the latter would resurrect an omitted
   * level on the very next save.
   *
   * It deliberately mutates no service state (the list signal is the list page's)
   * and sets no shared error, because the settings screen owns its own banner.
   */
  getSalaryBands(companyId: string, positionId: string): Observable<PositionSalaryBand[]> {
    return this.http.get<PositionSalaryBand[]>(this.salaryBandsUrl(companyId, positionId));
  }

  /**
   * Replaces the position's complete band set (T10/D13) and returns the stored
   * set **after** the save, which is what the editor re-seeds its rows from: the
   * response is the authority on which levels ended up enabled.
   */
  saveSalaryBands(
    companyId: string,
    positionId: string,
    request: PositionSalaryBandsRequest,
  ): Observable<PositionSalaryBand[]> {
    return this.http.put<PositionSalaryBand[]>(
      this.salaryBandsUrl(companyId, positionId),
      request,
    );
  }

  private salaryBandsUrl(companyId: string, positionId: string): string {
    return `${this.positionsUrl(companyId)}/${positionId}/salary-bands`;
  }

  clearError(): void {
    this._error.set(null);
  }
}
