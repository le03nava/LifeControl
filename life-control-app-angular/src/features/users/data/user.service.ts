import { inject, Injectable } from '@angular/core';
import { User } from '../models/user.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { httpResource } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class UserService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  private _usersResource = httpResource<User[]>(
    () => this.apiUrl,
    { defaultValue: [] }
  );

  readonly users = this._usersResource.value;

  get apiUrl(): string {
    return `${this.configService.apiUrl}/users`;
  }

  getFormattedUsers(): User[] {
    return this._usersResource.value() ?? [];
  }

  getUsers(): void {
    this._usersResource.reload();
  }

  getUserList(): void {
    this._usersResource.reload();
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
