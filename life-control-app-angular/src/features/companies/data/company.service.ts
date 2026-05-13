import { inject, Injectable, signal } from '@angular/core';
import { Company, Page } from '../models/company.models';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { HttpClient, HttpParams } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Signal para estado de carga
  private _loading = signal(false);
  
  // Signal para errores
  private _error = signal<string | null>(null);
  
  // Signals de solo lectura para usar en componentes
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  get apiUrl(): string {
    return `${this.configService.apiUrl}/companies`;
  }

  getCompanies(page: number = 0, size: number = 12, search?: string): Observable<Page<Company>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Company>>(this.apiUrl, { params });
  }

  getCompanyById(id: string): Observable<Company> {
    return this.http.get<Company>(`${this.apiUrl}/${id}`);
  }

  createCompany(data: Company): Observable<Company> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.post<Company>(this.apiUrl, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al crear la empresa');
        return throwError(() => err);
      }),
    );
  }

  updateCompany(data: Company): Observable<Company> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.put<Company>(this.apiUrl, data).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al actualizar la empresa');
        return throwError(() => err);
      }),
    );
  }

  deleteCompany(id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);

    return this.http.delete<void>(`${this.apiUrl}/${id}`).pipe(
      finalize(() => this._loading.set(false)),
      catchError(err => {
        this._error.set('Error al eliminar la empresa');
        return throwError(() => err);
      }),
    );
  }
  
  clearError(): void {
    this._error.set(null);
  }
}
