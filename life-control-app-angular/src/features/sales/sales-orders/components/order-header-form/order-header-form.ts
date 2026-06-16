import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatChipsModule } from '@angular/material/chips';
import { SO_STATUS_COLORS, SO_STATUS_LABELS } from '../../data/status-config';
import type { SalesOrder } from '../../models/sales-order.models';

/**
 * Displays the sales order status chip and order number.
 */
@Component({
  selector: 'app-order-header-form',
  standalone: true,
  imports: [MatChipsModule],
  templateUrl: './order-header-form.html',
  styleUrl: './order-header-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderHeaderForm {
  /** The loaded sales order (edit mode only). Used for status/order number display. */
  readonly loadedOrder = input<SalesOrder | null>(null);

  // ─── Status display ────────────────────────────────────
  readonly statusColor = SO_STATUS_COLORS;
  readonly statusLabel = SO_STATUS_LABELS;

  readonly currentStatusName = computed(
    () => this.loadedOrder()?.statusName ?? null,
  );
}
