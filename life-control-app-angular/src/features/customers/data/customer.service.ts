import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { Customer, CustomerRequest, Page } from '../models/customer.models';

/**
 * The customer collection: two reads and the two writes the screen needs.
 *
 * `GET /api/customers` (`CustomerController.java:43-51`) is page-parameterised with the API's own default of
 * 12 and takes an optional `search` that `CustomerRepository.java:44-52` matches against **name and email
 * only** — a customer's RFC is never a search key, and the screen's hint says so. `GET /api/customers/{id}`,
 * `POST /api/customers` and `PUT /api/customers/{id}` are the three endpoints the edit page calls; all six
 * methods of the controller are gated on `hasAnyRole('lc-admin','lc-sales')`, resolved by the route, not here.
 *
 * Every method returns an `Observable` and nothing else. `CompanyService` keeps a `loading`/`error` pair for
 * the mutations it offers, and this service deliberately does not: the list reads its states from
 * `rxResource`, and the edit page owns the one state a write needs (the server's refusal, which is per
 * request and belongs to the page, not to a service shared by every screen).
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

  /** One customer, with its fiscal profile — the read the edit page hydrates from. */
  getCustomerById(id: string): Observable<Customer> {
    return this.http.get<Customer>(`${this.apiUrl}/${id}`);
  }

  createCustomer(request: CustomerRequest): Observable<Customer> {
    return this.http.post<Customer>(this.apiUrl, request);
  }

  /**
   * Updates the row the route names.
   *
   * The API writes a fiscal field **only when the request carries it** (`CustomerService.updateCustomer`), and
   * `CustomerRequest` normalizes a blank to `null`: a field the request sends as `null` keeps whatever the row
   * stores. That is the API's contract and this service does not paper over it — see the note in
   * `customer-edit.ts` and `L5` of `odd/tasks/customers-fiscal-form.md`.
   */
  updateCustomer(id: string, request: CustomerRequest): Observable<Customer> {
    return this.http.put<Customer>(`${this.apiUrl}/${id}`, request);
  }
}
