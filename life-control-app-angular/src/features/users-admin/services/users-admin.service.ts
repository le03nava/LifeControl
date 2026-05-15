import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { catchError, finalize, Observable, tap } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import {
  Role,
  RoleRequest,
  UserSearchResult,
  PageResponse,
  UserAttribute,
  RoleAssignmentRequest,
  ChildRoleRequest,
} from '../models/users-admin.models';

@Injectable({
  providedIn: 'root',
})
export class UsersAdminService {
  private readonly http = inject(HttpClient);
  private readonly configService = inject(ConfigService);

  private readonly _roles = signal<Role[]>([]);
  private readonly _users = signal<UserSearchResult[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly roles = this._roles.asReadonly();
  readonly users = this._users.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/users-admin`;
  }

  // ─── Realm Roles ──────────────────────────────────────────

  loadRealmRoles(): void {
    this._loading.set(true);
    this._error.set(null);
    this.http
      .get<Role[]>(`${this.baseUrl}/roles/realm`)
      .pipe(
        tap((roles) => this._roles.set(roles)),
        catchError((err) => {
          this._error.set(err.message ?? 'Error loading realm roles');
          return [];
        }),
        finalize(() => this._loading.set(false)),
      )
      .subscribe();
  }

  createRealmRole(request: RoleRequest): Observable<Role> {
    return this.http.post<Role>(`${this.baseUrl}/roles/realm`, request);
  }

  getRealmRole(name: string): Observable<Role> {
    return this.http.get<Role>(`${this.baseUrl}/roles/realm/${encodeURIComponent(name)}`);
  }

  updateRealmRole(name: string, request: RoleRequest): Observable<Role> {
    return this.http.put<Role>(`${this.baseUrl}/roles/realm/${encodeURIComponent(name)}`, request);
  }

  deleteRealmRole(name: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/roles/realm/${encodeURIComponent(name)}`);
  }

  // ─── Composite Children ───────────────────────────────────

  addChildRole(parentName: string, request: ChildRoleRequest): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/roles/realm/${encodeURIComponent(parentName)}/children`,
      request,
    );
  }

  removeChildRole(parentName: string, childName: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/roles/realm/${encodeURIComponent(parentName)}/children/${encodeURIComponent(childName)}`,
    );
  }

  // ─── Client Roles ─────────────────────────────────────────

  loadClientRoles(clientId: string): void {
    this._loading.set(true);
    this._error.set(null);
    this.http
      .get<Role[]>(`${this.baseUrl}/roles/client/${encodeURIComponent(clientId)}`)
      .pipe(
        tap((roles) => this._roles.set(roles)),
        catchError((err) => {
          this._error.set(err.message ?? 'Error loading client roles');
          return [];
        }),
        finalize(() => this._loading.set(false)),
      )
      .subscribe();
  }

  createClientRole(clientId: string, request: RoleRequest): Observable<Role> {
    return this.http.post<Role>(
      `${this.baseUrl}/roles/client/${encodeURIComponent(clientId)}`,
      request,
    );
  }

  // ─── User Search ──────────────────────────────────────────

  searchUsers(query: string): void {
    if (!query.trim()) {
      this._users.set([]);
      return;
    }
    this._loading.set(true);
    this._error.set(null);
    const params = new HttpParams().set('search', query).set('page', '0').set('size', '20');
    this.http
      .get<PageResponse<UserSearchResult>>(`${this.baseUrl}/users`, { params })
      .pipe(
        tap((page) => this._users.set(page.content)),
        catchError((err) => {
          this._error.set(err.message ?? 'Error searching users');
          return [];
        }),
        finalize(() => this._loading.set(false)),
      )
      .subscribe();
  }

  // ─── User Roles ───────────────────────────────────────────

  getUserRoles(userId: string): Observable<Role[]> {
    return this.http.get<Role[]>(`${this.baseUrl}/users/${encodeURIComponent(userId)}/roles`);
  }

  assignRealmRole(userId: string, request: RoleAssignmentRequest): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/roles/realm`,
      request,
    );
  }

  assignClientRole(userId: string, clientId: string, request: RoleAssignmentRequest): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/roles/client/${encodeURIComponent(clientId)}`,
      request,
    );
  }

  removeRealmRole(userId: string, roleName: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/roles/realm/${encodeURIComponent(roleName)}`,
    );
  }

  removeClientRole(userId: string, clientId: string, roleName: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/roles/client/${encodeURIComponent(clientId)}/${encodeURIComponent(roleName)}`,
    );
  }

  // ─── User Attributes ──────────────────────────────────────

  getUserAttributes(userId: string): Observable<Record<string, string[]>> {
    return this.http.get<Record<string, string[]>>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/attributes`,
    );
  }

  updateUserAttribute(userId: string, key: string, values: string[]): Observable<void> {
    return this.http.put<void>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/attributes/${encodeURIComponent(key)}`,
      { values },
    );
  }

  deleteUserAttribute(userId: string, key: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/users/${encodeURIComponent(userId)}/attributes/${encodeURIComponent(key)}`,
    );
  }
}
