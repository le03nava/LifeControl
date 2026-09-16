import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PO_STATUS_COLORS, PO_STATUS_LABELS } from '../../data/status-config';

/**
 * Small colored pill that shows a purchase order status using the Spanish
 * label and the status color palette. Read-only presentational component.
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
  /** Backend status name, e.g. `Draft`, `In Transit`. */
  readonly statusName = input.required<string>();

  readonly label = computed(() => PO_STATUS_LABELS[this.statusName()] ?? this.statusName());
  readonly color = computed(() => PO_STATUS_COLORS[this.statusName()] ?? '#9e9e9e');
}
