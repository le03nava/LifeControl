import { inject, Injectable, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, finalize, tap } from 'rxjs/operators';
import { ConfigService } from '@app/services/config.service';
import { CloseContractRequest, Contract, ContractRequest } from '../models/contract.models';
import { PositionSalaryBand } from '../models/position.models';

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
 * The remaining catalog read here is a **pure read**: it reserves nothing and
 * mutates no signal, so a failed catalog load is the dialog's to show and never
 * the history list's error banner. This is the `EmployeeService.suggestEmail`
 * idiom.
 *
 * There is no contract `PUT`: a salary change or a promotion is a new contract,
 * and the record defines no contract `DELETE`.
 *
 * ## The catalogue reads have one owner each
 *
 * The position and seniority-level lookups this service used to carry were
 * absorbed by their catalog services in `hr-org-structure`'s **W2b**:
 * `PositionService.getPositions` and `SeniorityLevelService.getSeniorityLevels`
 * are the single readers of those endpoints now, and the dialog injects them
 * directly. A second reader of the same URL was the kind of split that rots, and
 * the record had invited exactly this absorption when the lookups were added
 * here. The salary-band read stays on this service until the band editor
 * (**W3**) owns that resource.
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
   * The two catalogue lookups this service used to carry were **absorbed by the
   * catalog services** in the W2b slice: `PositionService.getPositions` and
   * `SeniorityLevelService.getSeniorityLevels` are the single readers of those
   * endpoints now, and the contract dialog injects them directly. What remains
   * here is the salary-band read, until the band editor (W3) owns it.
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
