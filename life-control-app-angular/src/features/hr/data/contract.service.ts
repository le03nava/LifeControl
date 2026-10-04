import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import {
  CloseContractRequest,
  Contract,
  ContractRequest,
  Position,
  PositionSalaryBand,
  SeniorityLevel,
} from '../models/contract.models';

/**
 * HTTP access to an employee's contract history and the catalogs the contract
 * dialog reads.
 *
 * Mirrors `EmployeeService`'s signal-backed shape for the history itself:
 * `getContracts` feeds the `contracts` signal (newest first, as the API orders
 * it), `addContract` prepends the created row to keep that order, and
 * `closeContract` replaces the closed row in place. Every one of those three
 * clears `_error` on entry, so a failed write followed by a successful one does
 * not leave a stale banner.
 *
 * The three catalog reads are **pure reads**: they reserve nothing and mutate no
 * signal, so a failed catalog load is the dialog's to show and never the history
 * list's error banner. This is the `EmployeeService.suggestEmail` idiom.
 *
 * There is no contract `PUT`: a salary change or a promotion is a new contract,
 * and the record defines no contract `DELETE`.
 *
 * ## The catalog endpoints this service reads already exist and merged
 *
 * `GET …/positions`, `GET /api/seniority-levels` and
 * `GET …/positions/{positionId}/salary-bands` are `hr-org-structure`'s backend
 * and are already in `main`; this unit only adds the frontend lookups the
 * contract dialog needs. The **Positions CRUD frontend catalog** (the screen,
 * not the endpoint) arrives with `hr-org-structure` **W2b** and may reuse or
 * absorb this lookup when it lands. This unit writes **no line** of that
 * record's surface: `position.service.ts`, its models and its pages are W2b's,
 * and nothing here anticipates them beyond the read contract above.
 */
@Injectable({
  providedIn: 'root',
})
export class ContractService {
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly _contracts = signal<Contract[]>([]);
  private readonly _loading = signal(false);
  private readonly _error = signal<string | null>(null);

  /** Contracts read by the last history call; the detail page renders from its resource. */
  readonly contracts = this._contracts.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();

  private contractsUrl(companyId: string, employeeId: string): string {
    return `${this.configService.apiUrl}/companies/${companyId}/employees/${employeeId}/contracts`;
  }

  getContracts(companyId: string, employeeId: string): Observable<Contract[]> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.get<Contract[]>(this.contractsUrl(companyId, employeeId)).pipe(
      tap((contracts) => this._contracts.set(contracts)),
      catchError((err) => {
        this._error.set('Error al cargar los contratos');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  addContract(
    companyId: string,
    employeeId: string,
    request: ContractRequest,
  ): Observable<Contract> {
    this._loading.set(true);
    this._error.set(null);
    return this.http.post<Contract>(this.contractsUrl(companyId, employeeId), request).pipe(
      tap((contract) => {
        // The API orders newest first, so a freshly opened contract goes to the front.
        this._contracts.set([contract, ...this._contracts()]);
      }),
      catchError((err) => {
        this._error.set('Error al crear el contrato');
        return throwError(() => err);
      }),
      finalize(() => this._loading.set(false)),
    );
  }

  /**
   * Closes an open-ended contract.
   *
   * When `endDate` is omitted the request carries no body at all, which the API
   * reads as "close today" (`@RequestBody(required = false)`); when present,
   * `endDate` is the last day covered, inclusive.
   */
  closeContract(
    companyId: string,
    employeeId: string,
    contractId: string,
    endDate?: string,
  ): Observable<Contract> {
    this._loading.set(true);
    this._error.set(null);
    const body: CloseContractRequest | null = endDate ? { endDate } : null;
    return this.http
      .patch<Contract>(`${this.contractsUrl(companyId, employeeId)}/${contractId}/close`, body)
      .pipe(
        tap((updated) => {
          this._contracts.set(this._contracts().map((c) => (c.id === contractId ? updated : c)));
        }),
        catchError((err) => {
          this._error.set('Error al cerrar el contrato');
          return throwError(() => err);
        }),
        finalize(() => this._loading.set(false)),
      );
  }

  /**
   * Company-scoped position lookup for the contract dialog.
   *
   * A pure read: no signal is touched. The endpoint is `hr-org-structure`'s and
   * already merged; the Positions CRUD frontend catalog (W2b of that record) may
   * reuse or absorb this lookup, but this unit writes no line of its surface.
   */
  getPositions(companyId: string): Observable<Position[]> {
    return this.http.get<Position[]>(
      `${this.configService.apiUrl}/companies/${companyId}/positions`,
    );
  }

  /**
   * Global seniority-level lookup for the contract dialog.
   *
   * A pure read, and deliberately **not** company-scoped: seniority levels are
   * global reference data, so the path carries no `companyId`.
   */
  getSeniorityLevels(): Observable<SeniorityLevel[]> {
    return this.http.get<SeniorityLevel[]>(`${this.configService.apiUrl}/seniority-levels`);
  }

  /**
   * Salary bands of one position, read by the contract dialog to warn — never
   * block (T14) — when the typed salary falls outside the band.
   *
   * A pure read.
   */
  getSalaryBands(companyId: string, positionId: string): Observable<PositionSalaryBand[]> {
    return this.http.get<PositionSalaryBand[]>(
      `${this.configService.apiUrl}/companies/${companyId}/positions/${positionId}/salary-bands`,
    );
  }

  clearError(): void {
    this._error.set(null);
  }
}
