import { inject, Injectable, signal } from '@angular/core';
import { User } from '../models/user.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Signal para almacenar los usuarios
  private _users = signal<User[]>([]);
  
  // Signal de solo lectura para usar en componentes
  readonly users = this._users.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/users`;
  }

  getFormattedUsers(): User[] {
    return this._users();
  }

  getUsers(): void {
    this.http.get<User[]>(this.apiUrl).subscribe({
      next: (data) => this._users.set(data),
      error: (err) => {
        console.error('[UserService] Error loading users:', err);
        this._users.set([]);
      }
    });
  }

  getUserList(): void {
    this.getUsers();
  }

  getUserById(id: string): Observable<User> {
    return this.http.get<User>(`${this.apiUrl}/${id}`);
  }

  createUser(data: User): Observable<User> {
    return this.http.post<User>(`${this.apiUrl}`, data);
  }

  updateUser(data: User): Observable<User> {
    return this.http.put<User>(`${this.apiUrl}/${data.id}`, data);
  }

  deleteUser(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }

  changePassword(id: string, password: string): Observable<{ message: string }> {
    return this.http.patch<{ message: string }>(`${this.apiUrl}/${id}/password`, { password });
  }
}
