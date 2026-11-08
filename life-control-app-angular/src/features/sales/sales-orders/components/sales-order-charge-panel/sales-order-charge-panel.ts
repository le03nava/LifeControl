import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  LOCALE_ID,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { formatMoney, MoneyPipe } from '@shared/pipes/money.pipe';
import type { PaymentMethodOption, SalePaymentRequest } from '../../models/sales-order.models';

/** One tender the operator is stating, before it becomes a `SalePaymentRequest`. */
export interface ChargeRow {
  paymentMethodId: string;
  /** What this tender applies to the sale, or `null` while the operator has not typed one. */
  amount: number | null;
  /** What the customer handed over, only for a cash method. `null` when no cash was counted. */
  tendered: number | null;
}

/** The grain the money columns carry (`NUMERIC(19,4)`). */
const SCALE = 4;

/**
 * Trims float noise to the scale the columns and the request hold.
 *
 * `0.1 + 0.2` is `0.30000000000000004` in JavaScript, and `@Digits(fraction = 4)` refuses a fifth decimal: that
 * would arrive as a field violation for a number the operator never typed.
 */
function roundToScale(value: number): number {
  return Math.round(value * 10 ** SCALE) / 10 ** SCALE;
}

/**
 * The charge surface: mixed tender, and the change a cash payment returns.
 *
 * ADR-0002 decision 5 makes a payment a row and a sale many payments, so the single «Cobrar con» button became a
 * list: each row states a method and the amount it applies to the sale, and exactly one row may state a tender —
 * the cash one, because M4 refuses change on any other method.
 *
 * **What it deliberately does not do.** It does not refuse a charge whose payments do not add up to the document's
 * total: M3 is a server invariant and «the form is not an integrity boundary» is the ADR's own reason for keeping
 * the breakdown out of the client (its `## Out of scope, named`). The panel names the difference and lets the
 * server answer.
 */
@Component({
  selector: 'app-sales-order-charge-panel',
  standalone: true,
  imports: [
    MoneyPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './sales-order-charge-panel.html',
  styleUrl: './sales-order-charge-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SalesOrderChargePanel {
  private readonly locale = inject(LOCALE_ID);

  /** The document's total, the amount the payments have to add up to exactly (M3). */
  readonly total = input<number>(0);

  /** The document's currency (`sales_orders.currency`). */
  readonly currency = input<string>('');

  /** The payment methods the catalog offers, with their `isCash` flag. */
  readonly paymentMethods = input<PaymentMethodOption[]>([]);

  /** Whether a charge is already in flight. */
  readonly charging = input(false);

  /** Whether there is nothing to charge — the page disables the panel while the order has no line items. */
  readonly disabled = input(false);

  /** Emits the payments to append, in the order they were entered. */
  readonly charge = output<SalePaymentRequest[]>();

  /** The tenders the operator has stated. `null` means «untouched», so the first row follows the total. */
  private readonly statedRows = signal<ChargeRow[] | null>(null);

  /** The rows on screen: the stated ones, or the default row the document's total proposes. */
  readonly rows = computed<ChargeRow[]>(() => this.statedRows() ?? this.defaultRows());

  /** Whether the method can return change (M4: only cash can). */
  isCash(paymentMethodId: string): boolean {
    return this.paymentMethods().find((method) => method.id === paymentMethodId)?.isCash === true;
  }

  /** The change this row returns, or `null` when it returns none. */
  changeFor(row: ChargeRow): number | null {
    if (!this.isCash(row.paymentMethodId)) return null;
    if (row.tendered === null) return null;

    const amount = row.amount ?? 0;
    if (row.tendered <= amount) return null;

    return roundToScale(row.tendered - amount);
  }

  /** What this row actually covers of the sale. */
  coveredFor(row: ChargeRow): number {
    const amount = row.amount ?? 0;

    // A cash row whose tender is below its amount is not covering that amount: the drawer is short by the
    // difference, and reporting it as covered is how a cashier learns the shortfall from the customer instead of
    // from the screen. The tender pair is simply not stated in that case, so nothing reaches the server.
    if (this.isCash(row.paymentMethodId) && row.tendered !== null) {
      return roundToScale(Math.min(amount, row.tendered));
    }

    return amount;
  }

  /** What the stated payments still leave unpaid. Negative when they exceed the total. */
  readonly outstanding = computed(() => {
    const paid = this.rows().reduce((sum, row) => sum + this.coveredFor(row), 0);
    return roundToScale(this.total() - paid);
  });

  /**
   * The difference between the payments and the total, or `null` when they match.
   *
   * Reported, never enforced: M3 is the server's, and a client copy of it would be the integrity boundary the ADR
   * says the form is not.
   */
  readonly differenceLabel = computed(() => {
    const difference = this.outstanding();
    if (difference === 0) return null;

    const money = formatMoney(Math.abs(difference), this.currency(), this.locale);
    return difference > 0 ? `${money} short of the total` : `${money} over the total`;
  });

  /** Whether the panel can hand its payments to the page. */
  readonly canSubmit = computed(
    () =>
      !this.charging() &&
      !this.disabled() &&
      this.rows().every(
        (row) => row.paymentMethodId !== '' && row.amount !== null && row.amount >= 0.0001,
      ),
  );

  addPayment(): void {
    const outstanding = this.outstanding();
    this.statedRows.set([
      ...this.rows(),
      {
        paymentMethodId: this.paymentMethods()[0]?.id ?? '',
        amount: outstanding > 0 ? outstanding : null,
        tendered: null,
      },
    ]);
  }

  removePayment(index: number): void {
    // A charge with no payments is not a charge: the last row stays.
    if (this.rows().length <= 1) return;
    this.statedRows.set(this.rows().filter((_, i) => i !== index));
  }

  updateMethod(index: number, paymentMethodId: string): void {
    this.patch(index, { paymentMethodId, tendered: null });
  }

  updateAmount(index: number, amount: number | null): void {
    this.patch(index, {
      amount: amount === null || Number.isNaN(amount) ? null : roundToScale(amount),
    });
  }

  updateTendered(index: number, tendered: number | null): void {
    this.patch(index, {
      tendered: tendered === null || Number.isNaN(tendered) ? null : roundToScale(tendered),
    });
  }

  /** Emits the payments, or does nothing when the panel cannot be submitted. */
  submit(): void {
    if (!this.canSubmit()) return;
    this.charge.emit(this.payments());
  }

  /** The request body the page sends: one payment per row, with the tender pair only where it belongs. */
  payments(): SalePaymentRequest[] {
    return this.rows().map((row) => {
      const payment: SalePaymentRequest = {
        paymentMethodId: row.paymentMethodId,
        amount: roundToScale(row.amount ?? 0),
      };

      const change = this.changeFor(row);
      if (change !== null && row.tendered !== null) {
        // Stated together or not at all: `ck_payment_tender` reads both columns, and null is not zero.
        payment.tenderedAmount = roundToScale(row.tendered);
        payment.changeAmount = change;
      }

      return payment;
    });
  }

  /** The row the document's total proposes before the operator states anything. */
  private defaultRows(): ChargeRow[] {
    return [
      {
        paymentMethodId: this.paymentMethods()[0]?.id ?? '',
        amount: this.total(),
        tendered: null,
      },
    ];
  }

  private patch(index: number, values: Partial<ChargeRow>): void {
    this.statedRows.set(this.rows().map((row, i) => (i === index ? { ...row, ...values } : row)));
  }
}
