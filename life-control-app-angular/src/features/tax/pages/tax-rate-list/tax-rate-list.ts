import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { DecimalPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { CompanyContextService, NotificationService, httpErrorMessage } from '@shared/data';
import { ConfirmDialog, ConfirmDialogData, PageHeader } from '@shared/ui';
import {
  TaxRateDeclareDialog,
  TaxRateDeclareDialogData,
} from '../../components/tax-rate-declare-dialog/tax-rate-declare-dialog';
import {
  TaxRateSupersedeDialog,
  TaxRateSupersedeDialogData,
} from '../../components/tax-rate-supersede-dialog/tax-rate-supersede-dialog';
import { COMPANY_WIDE_SCOPE, formatDay, TaxRate } from '../../data/tax-rate.models';
import { TaxRateService } from '../../data/tax-rate.service';

/** A row plus the one fact the server cannot state: whether it is today's answer for its own scope. */
export interface TaxRateRow extends TaxRate {
  resolvesToday: boolean;
}

/**
 * `YYYY-MM-DD` in the **operator's** calendar, not UTC.
 *
 * `toISOString().slice(0, 10)` is the trap this function exists to close: in Mexico (UTC-6) every local
 * evening is already tomorrow in UTC, so a rate declared at 18:30 would be compared against the wrong day and
 * the "resolves today" mark would move at dinner time. Composing the three local components is exact and
 * needs no time zone.
 */
export function todayIso(now: Date = new Date()): string {
  const month = `${now.getMonth() + 1}`.padStart(2, '0');
  const day = `${now.getDate()}`.padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * The half of the key a row competes in: the region it names, or the empty string for the company-wide rate.
 *
 * Precedence between the two scopes is the resolver's (`TaxRateService.resolve`), not this screen's: a region
 * that has any row for a code is authoritative for its places and never falls back to the company-wide one.
 * What this key states is narrower and true: **within one scope**, the newest enabled row dated up to today is
 * the answer, and the list marks exactly those.
 */
function scopeKey(rate: TaxRate): string {
  return rate.regionId ?? '';
}

/**
 * The `Impuestos` screen: the company's tax-rate book, read from
 * `GET /api/companies/{companyId}/tax-rates` (`S3`).
 *
 * **The scope is a column, never implied.** It is the half of the key that decides precedence, so an operator
 * who cannot see it finds the newest row, "edits" it, and watches a number that does not move.
 *
 * **The row that resolves today is marked**, per code and per scope, computed here because no endpoint answers
 * it: the resolver is asked per document, with a date and a store. Marking is what turns the list from a log of
 * inserts into the answer to "which rate is being charged right now".
 *
 * **The order is the screen's, not the answer's.** The server orders by code and newest first; grouping by
 * scope as well is what makes two rates for one code and day readable side by side, and it is a display
 * decision, so it lives with the display.
 *
 * Retired rows are hidden by default (`includeDisabled=false`) and appear with their own state when the toggle
 * asks for them — the row is never deleted, because a document dated before its successor keeps resolving it.
 */
@Component({
  selector: 'app-tax-rate-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DecimalPipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatSlideToggleModule,
    MatTableModule,
    PageHeader,
  ],
  templateUrl: './tax-rate-list.html',
  styleUrl: './tax-rate-list.scss',
})
export class TaxRateList {
  private readonly route = inject(ActivatedRoute);
  private readonly companyContext = inject(CompanyContextService);
  private readonly taxRateService = inject(TaxRateService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);

  /** The route's own company, captured once; it wins over the session's selection when it is there. */
  private readonly routeCompanyId = this.route.snapshot.queryParamMap.get('companyId');

  readonly companyId = computed(
    () => this.routeCompanyId ?? this.companyContext.currentCompany()?.id ?? null,
  );

  readonly includeDisabled = signal(false);

  private readonly ratesResource = rxResource({
    params: () => {
      const companyId = this.companyId();
      return companyId ? { companyId, includeDisabled: this.includeDisabled() } : undefined;
    },
    stream: ({ params }) =>
      this.taxRateService.getTaxRates(params.companyId, params.includeDisabled),
  });

  readonly rows = computed<TaxRateRow[]>(() => {
    const rates = this.ratesResource.hasValue() ? this.ratesResource.value() : [];
    const today = todayIso();

    // One winner per (code, scope): the newest enabled row that applies from today or earlier.
    const answering = new Map<string, TaxRate>();
    for (const rate of rates) {
      if (!rate.enabled || rate.effectiveFrom > today) {
        continue;
      }
      const key = `${rate.taxCode}|${scopeKey(rate)}`;
      const current = answering.get(key);
      if (!current || rate.effectiveFrom > current.effectiveFrom) {
        answering.set(key, rate);
      }
    }

    return [...rates]
      .sort((left, right) => {
        if (left.taxCode !== right.taxCode) {
          return left.taxCode.localeCompare(right.taxCode);
        }
        const leftScope = scopeKey(left);
        const rightScope = scopeKey(right);
        if (leftScope !== rightScope) {
          return leftScope.localeCompare(rightScope);
        }
        if (left.effectiveFrom !== right.effectiveFrom) {
          return right.effectiveFrom.localeCompare(left.effectiveFrom);
        }
        return left.id.localeCompare(right.id);
      })
      .map((rate) => ({
        ...rate,
        resolvesToday: answering.get(`${rate.taxCode}|${scopeKey(rate)}`)?.id === rate.id,
      }));
  });

  readonly loading = this.ratesResource.isLoading;

  readonly error = computed(() => {
    const error = this.ratesResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  readonly displayedColumns = [
    'taxCode',
    'scope',
    'rate',
    'effectiveFrom',
    'state',
    'today',
    'actions',
  ];

  /** The scope in words: the region's name, or the sentence that says there is none. */
  readonly scopeLabel = (rate: TaxRate): string => rate.regionName ?? COMPANY_WIDE_SCOPE;

  /**
   * The wire's `LocalDate` rendered as a day, without a `Date`.
   *
   * `new Date('2026-11-10')` is UTC midnight, and `DatePipe` would print the **9th** for any operator west of
   * Greenwich. The string already is the day the company stated, so it is formatted as three parts.
   */
  readonly dayLabel = (isoDate: string): string => formatDay(isoDate);

  toggleRetired(include: boolean): void {
    this.includeDisabled.set(include);
  }

  /**
   * Declares a rate, then re-reads the list.
   *
   * The reload is not cosmetic: this service caches nothing on purpose, and the row the server appended is the
   * only proof the operator's own write landed. A dismissed dialog closes with `null` and reloads nothing —
   * nothing changed, and a re-read would throw away the scroll position for free.
   */
  declareRate(): void {
    const companyId = this.companyId();
    if (!companyId) {
      return;
    }

    this.dialog
      .open<TaxRateDeclareDialog, TaxRateDeclareDialogData, TaxRate | null>(TaxRateDeclareDialog, {
        data: { companyId },
        width: '560px',
      })
      .afterClosed()
      .subscribe((declared) => {
        if (!declared) {
          return;
        }
        this.notifications.showSuccess(
          `Tasa de ${declared.taxCode} declarada desde el ${this.dayLabel(declared.effectiveFrom)}`,
        );
        this.ratesResource.reload();
      });
  }

  retry(): void {
    this.ratesResource.reload();
  }

  /**
   * Appends the successor of one row, then re-reads the list.
   *
   * The dialog writes, not this page: it is the only side that knows the day the operator typed and the
   * refusal that came back. `null` is its explicit cancel, so nothing was written and nothing is re-read;
   * any other close is read as a supersession to confirm, because a list that keeps showing the book the
   * operator just wrote is the failure this screen exists to prevent (`S3`).
   */
  supersedeRate(rate: TaxRate): void {
    const companyId = this.companyId();
    if (!companyId) {
      return;
    }

    this.dialog
      .open<TaxRateSupersedeDialog, TaxRateSupersedeDialogData, TaxRate | null>(
        TaxRateSupersedeDialog,
        { data: { companyId, rate }, width: '600px' },
      )
      .afterClosed()
      .subscribe((created) => {
        // `null` is the dialog's explicit cancel, and `undefined` is every other way out — ESC, a backdrop
        // click, the close button. Neither wrote anything, so neither is confirmed: reading `undefined` as a
        // success would toast a supersession that never happened and re-read a book that did not change.
        if (!created) {
          return;
        }
        // The code is not read from the answer: the server inherits it from the row being superseded, so
        // this one is exact even before the response exists.
        this.notifications.showSuccess(`Tasa de ${rate.taxCode} sucedida`);
        this.ratesResource.reload();
      });
  }

  /**
   * Retires or reinstates one row, behind the confirmation that says what the row keeps doing (`S6`).
   *
   * Nothing here says "Eliminar": the row is never deleted, and an operator who reads it as a deletion would
   * believe old documents lose their rate. Only `true` writes; a dismissal writes nothing and re-reads
   * nothing.
   */
  toggleEnabled(rate: TaxRate): void {
    const companyId = this.companyId();
    if (!companyId) {
      return;
    }

    const retiring = rate.enabled;
    const scope = this.scopeLabel(rate);
    const message = retiring
      ? `¿Confirmás que querés retirar la tasa de ${rate.taxCode} de ${scope} vigente desde el ` +
        `${this.dayLabel(rate.effectiveFrom)}? La fila queda como historia y deja de resolver: ningún ` +
        'documento nuevo la recibe, y los documentos con fecha anterior a otra tasa de ese código y alcance ' +
        'la siguen resolviendo.'
      : `¿Confirmás que querés reincorporar la tasa de ${rate.taxCode} de ${scope} vigente desde el ` +
        `${this.dayLabel(rate.effectiveFrom)}? Vuelve a resolver los documentos de esa fecha en adelante ` +
        'que no tengan otra tasa posterior.';

    const data: ConfirmDialogData = {
      title: retiring ? 'Retirar tasa' : 'Reincorporar tasa',
      message,
      confirmLabel: retiring ? 'Retirar' : 'Reincorporar',
      cancelLabel: 'Cancelar',
    };

    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean | undefined>(ConfirmDialog, { data })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed !== true) {
          return;
        }

        this.taxRateService.setTaxRateEnabled(companyId, rate.id, !rate.enabled).subscribe({
          next: () => {
            this.notifications.showSuccess(
              retiring
                ? `Tasa de ${rate.taxCode} retirada: la fila queda en la historia`
                : `Tasa de ${rate.taxCode} reincorporada`,
            );
            this.ratesResource.reload();
          },
          error: (error: unknown) => this.notifications.showError(httpErrorMessage(error)),
        });
      });
  }
}
