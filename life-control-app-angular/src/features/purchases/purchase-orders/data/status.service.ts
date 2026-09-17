import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import type { SelectOption } from '../models/select-option.models';

/** Raw shape of a status returned by `GET /api/statuses`. */
interface StatusResponse {
  id: string;
  statusName: string;
}

interface StatusTypeResponse {
  id: string;
  statusTypeName: string;
}

interface Page<T> {
  content: T[];
}

/** HTTP service for the `/api/status-types` and `/api/statuses` catalogs. */
@Injectable({ providedIn: 'root' })
export class StatusService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);

  private get statusTypesUrl(): string {
    return `${this.configService.apiUrl}/status-types`;
  }

  private get statusesUrl(): string {
    return `${this.configService.apiUrl}/statuses`;
  }

  /** Resolves the UUID of a status type by its (case-insensitive) name. */
  getStatusTypeIdByName(name: string): Observable<string> {
    const params = new HttpParams().set('search', name).set('size', '1');
    return this.http.get<Page<StatusTypeResponse>>(this.statusTypesUrl, { params }).pipe(
      map((page) => {
        const match = page.content.find(
          (type) => type.statusTypeName.toUpperCase() === name.toUpperCase(),
        );
        if (!match) {
          throw new Error(`${name} status type not found`);
        }
        return match.id;
      }),
    );
  }

  /** Returns the statuses that belong to a status type. */
  getStatusesByTypeId(statusTypeId: string): Observable<SelectOption[]> {
    const params = new HttpParams().set('statusTypeId', statusTypeId);
    return this.http
      .get<StatusResponse[]>(this.statusesUrl, { params })
      .pipe(map((list) => list.map((status) => ({ id: status.id, name: status.statusName }))));
  }
}
