import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import type { SelectOption } from '../models/select-option.models';

/** Raw shape returned by `GET /api/payment-methods`. */
interface PaymentMethodResponse {
  id: string;
  paymentMethodName: string;
}

/** HTTP service for the `/api/payment-methods` catalog. */
@Injectable({ providedIn: 'root' })
export class PaymentMethodService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private get baseUrl(): string {
    return `${this.configService.apiUrl}/payment-methods`;
  }

  /** Returns every payment method (small, unpaginated catalog). */
  getPaymentMethods(): Observable<SelectOption[]> {
    return this.http
      .get<PaymentMethodResponse[]>(this.baseUrl)
      .pipe(map((list) => list.map((pm) => ({ id: pm.id, name: pm.paymentMethodName }))));
  }
}
