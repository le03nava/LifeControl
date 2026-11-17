import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { Customer, Page } from '../models/customer.models';

/**
 * The customer collection, one read and nothing else (`S5` of `odd/tasks/customers-screen.md`).
 *
 * `GET /api/customers` (`CustomerController.java:49-51`) is page-parameterised with the API's own
 * default of 12 and takes an optional `search` that `CustomerRepository.java:44-52` matches against
 * **name and email only** — a customer's RFC is never a search key, and the screen's hint says so
 * (`S4`). The endpoint is gated on `hasAnyRole('lc-admin','lc-sales')`, resolved by the route, not here.
 *
 * There is no write path and therefore no local state: `CompanyService` keeps a `loading`/`error` pair
 * for the mutations it offers, and a read-only service that carried the same signals would be state
 * nothing can ever set. The page reads its states from `rxResource` instead.
 */
@Injectable({
  providedIn: 'root',
})
export class CustomerService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  get apiUrl(): string {
    return `${this.configService.apiUrl}/customers`;
  }

  getCustomers(page = 0, size = 12, search?: string): Observable<Page<Customer>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<Page<Customer>>(this.apiUrl, { params });
  }
}
