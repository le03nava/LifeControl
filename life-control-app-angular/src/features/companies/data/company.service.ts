import { inject, Injectable, signal, computed } from '@angular/core';
import { Company } from '../models/company.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { ConfigService } from '@app/services/config.service';

@Injectable({
  providedIn: 'root',
})
export class CompanyService {
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  // Signal para almacenar las compañías
  private _companies = signal<Company[]>([]);
  
  // Signal para estado de carga
  private _loading = signal(false);
  
  // Signal para errores
  private _error = signal<string | null>(null);
  
  // Signals de solo lectura para usar en componentes
  readonly companies = this._companies.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  
  // Computed para saber si hay datos
  readonly hasCompanies = computed(() => this._companies().length > 0);

  get apiUrl(): string {
    return `${this.configService.apiUrl}/companies`;
  }

  getCompanies(): void {
    this._loading.set(true);
    this._error.set(null);
    
    this.http.get<Company[]>(this.apiUrl).subscribe({
      next: (data) => {
        this._companies.set(data);
        this._loading.set(false);
      },
      error: (err) => {
        console.error('[CompanyService] Error loading companies:', err);
        this._error.set('Error al cargar las empresas');
        this._companies.set([]);
        this._loading.set(false);
      }
    });
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
