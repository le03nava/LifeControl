import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { SeniorityLevel, SeniorityLevelRequest } from '../models/seniority-level.models';

/**
 * HTTP access to the **global** seniority-level catalog.
 *
 * Global reference data, not company-scoped (record D1): its path carries no
 * `companyId`, it has no `ScopeLevel` role and disabling a level affects every
 * company. That is why this service is the only one in the feature with no
 * company argument anywhere — the two `companies/**` catalog services take one
 * on every method, and `DepartmentService` resolves the company before it
 * loads. The per-company policy it feeds is the **salary band**, which is scoped
 * through its position, not here.
 *
 * The shape of the writes mirrors the company-scoped catalogs on purpose: one
 * request type for create and update because the table has no `version` column
 * and therefore no 412 precondition (T2), `DELETE` as a soft delete and
 * `PATCH .../enable` as its inverse.
 */
@Injectable({
  providedIn: 'root',
})
export class SeniorityLevelService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _levels = signal<SeniorityLevel[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Levels read by the last list call; the list page renders from its resource. */
  readonly levels = this._levels.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private levelsUrl(): string {
    return `${this.configService.apiUrl}/seniority-levels`;
  }

  /**
   * The whole catalog, ordered by `rank` on the server.
   *
   * `includeDisabled` is forwarded on every call: turning the toggle on
   * re-queries the API instead of filtering an already-enabled-only list in the
   * browser, which is the trap the live regions page falls into.
   */
  getSeniorityLevels(includeDisabled = false): Observable<SeniorityLevel[]> {
    this._loading.set(true);
    this._error.set(null);
    const params = { includeDisabled: String(includeDisabled) };
    return this.http.get<SeniorityLevel[]>(this.levelsUrl(), { params }).pipe(
      tap((levels) => this._levels.set(levels)),
      catchError((err) => {
        this._error.set('Error al cargar los niveles de antigüedad');
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
  getSeniorityLevel(id: string): Observable<SeniorityLevel> {
    this._error.set(null);
    return this.http.get<SeniorityLevel>(`${this.levelsUrl()}/${id}`).pipe(
      catchError((err) => {
        this._error.set('Error al cargar el nivel de antigüedad');
        return throwError(() => err);
      }),
    );
  }

  addSeniorityLevel(request: SeniorityLevelRequest): Observable<SeniorityLevel> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<SeniorityLevel>(this.levelsUrl(), request).pipe(
      tap((level) => {
        this._levels.set([...this._levels(), level]);
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un nivel de antigüedad con ese código, nombre o rango'
            : 'Error al crear el nivel de antigüedad',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  updateSeniorityLevel(
    id: string,
    request: SeniorityLevelRequest,
  ): Observable<SeniorityLevel> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.put<SeniorityLevel>(`${this.levelsUrl()}/${id}`, request).pipe(
      tap((updated) => {
        this._levels.set(this._levels().map((level) => (level.id === id ? updated : level)));
      }),
      catchError((err) => {
        this._error.set(
          err.status === 409
            ? 'Ya existe un nivel de antigüedad con ese código, nombre o rango'
            : 'Error al actualizar el nivel de antigüedad',
        );
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /**
   * Soft delete: the API sets `enabled = false` and keeps the row.
   *
   * The row is kept server-side because existing salary bands point at it; a
   * level that disappeared would leave those bands unresolvable.
   */
  removeSeniorityLevel(id: string): Observable<void> {
    this._loading.set(true);
    return this.http.delete<void>(`${this.levelsUrl()}/${id}`).pipe(
      tap(() => {
        this._levels.set(this._levels().filter((level) => level.id !== id));
      }),
      catchError((err) => {
        this._error.set('Error al deshabilitar el nivel de antigüedad');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  enableSeniorityLevel(id: string): Observable<SeniorityLevel> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.patch<SeniorityLevel>(`${this.levelsUrl()}/${id}/enable`, { enabled: true }).pipe(
      tap((updated) => {
        this._levels.set(this._levels().map((level) => (level.id === id ? updated : level)));
      }),
      catchError((err) => {
        this._error.set('Error al reactivar el nivel de antigüedad');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
