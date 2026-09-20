import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  GOODS_RECEIPT_STATUS_COLORS,
  GOODS_RECEIPT_STATUS_LABELS,
  PO_DETAIL_STATUS_COLORS,
  PO_DETAIL_STATUS_LABELS,
  PO_STATUS_COLORS,
  PO_STATUS_LABELS,
} from '../../data/status-config';

/** Status family the chip resolves its label and colour from. */
export type StatusChipFamily = 'order' | 'detail' | 'receipt';

const STATUS_LABELS: Record<StatusChipFamily, Record<string, string>> = {
  order: PO_STATUS_LABELS,
  detail: PO_DETAIL_STATUS_LABELS,
  receipt: GOODS_RECEIPT_STATUS_LABELS,
};

const STATUS_COLORS: Record<StatusChipFamily, Record<string, string>> = {
  order: PO_STATUS_COLORS,
  detail: PO_DETAIL_STATUS_COLORS,
  receipt: GOODS_RECEIPT_STATUS_COLORS,
};

const UNKNOWN_STATUS_COLOR = '#9e9e9e';

/**
 * Small colored pill that shows a purchase status using the Spanish label and
 * the status color palette. Read-only presentational component.
 *
 * The `family` input selects which status registry resolves the label and the
 * colour; it defaults to the purchase-order family so existing call sites keep
 * their behaviour. Unknown statuses always fall back to the raw English name.
 */
@Component({
  selector: 'app-status-chip',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="status-chip" [style.--chip-color]="color()">
      <span class="status-dot"></span>
      {{ label() }}
    </span>
  `,
  styleUrl: './status-chip.scss',
})
export class StatusChip {
  /** Backend status name, e.g. `Draft`, `In Transit`, `Registered`. */
  readonly statusName = input.required<string>();

  /** Status family the chip renders: purchase order, order detail or goods receipt. */
  readonly family = input<StatusChipFamily>('order');

  readonly label = computed(
    () => STATUS_LABELS[this.family()][this.statusName()] ?? this.statusName(),
  );
  readonly color = computed(
    () => STATUS_COLORS[this.family()][this.statusName()] ?? UNKNOWN_STATUS_COLOR,
  );
}
