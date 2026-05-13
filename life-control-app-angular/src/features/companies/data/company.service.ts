import { inject, Injectable, signal } from '@angular/core';
import { Company, Page } from '../models/company.models';
import { Observable } from 'rxjs';
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
    
    return new Observable(subscriber => {
      this.http.post<Company>(this.apiUrl, data).subscribe({
        next: (result) => {
          this._loading.set(false);
          subscriber.next(result);
          subscriber.complete();
        },
        error: (err) => {
          this._loading.set(false);
          this._error.set('Error al crear la empresa');
          subscriber.error(err);
        }
      });
    });
  }

  updateCompany(data: Company): Observable<Company> {
    this._loading.set(true);
    this._error.set(null);
    
    return new Observable(subscriber => {
      this.http.put<Company>(this.apiUrl, data).subscribe({
        next: (result) => {
          this._loading.set(false);
          subscriber.next(result);
          subscriber.complete();
        },
        error: (err) => {
          this._loading.set(false);
          this._error.set('Error al actualizar la empresa');
          subscriber.error(err);
        }
      });
    });
  }

  deleteCompany(id: string): Observable<void> {
    this._loading.set(true);
    this._error.set(null);
    
    return new Observable(subscriber => {
      this.http.delete<void>(`${this.apiUrl}/${id}`).subscribe({
        next: () => {
          this._loading.set(false);
          subscriber.next();
          subscriber.complete();
        },
        error: (err) => {
          this._loading.set(false);
          this._error.set('Error al eliminar la empresa');
          subscriber.error(err);
        }
      });
    });
  }
  
  clearError(): void {
    this._error.set(null);
  }
}
