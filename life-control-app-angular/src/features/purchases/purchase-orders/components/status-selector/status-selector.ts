import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { switchMap } from 'rxjs';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { StatusService } from '../../data/status.service';
import { PO_STATUS_FLOW, PO_STATUS_LABELS, PO_STATUS_TRANSITIONS } from '../../data/status-config';
import { NotificationService } from '@shared/data/notification';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import type { SelectOption } from '../../models/select-option.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Standalone component for changing a purchase order's status.
 *
 * Renders the lifecycle as a linear progress stepper and exposes action
 * buttons for the valid next states per `PO_STATUS_TRANSITIONS`. Selecting a
 * transition calls `PATCH /api/purchase-orders/{id}/status`. Terminal states
 * (Closed, Rejected) disable the actions and show a read-only message.
 *
 * Covers spec Requirement 6, scenarios 6.1-6.9.
 */
@Component({
  selector: 'app-status-selector',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './status-selector.html',
  styleUrl: './status-selector.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusSelector implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly statusService = inject(StatusService);
  private readonly purchaseOrderService = inject(PurchaseOrderService);
  private readonly notificationService = inject(NotificationService);

  /** The current purchase order (must include `statusId`, `statusName`, `id`). */
  readonly order = input.required<PurchaseOrder>();

  /** Emits the new status **name** (e.g. `Sent`) after a successful PATCH. */
  readonly statusChanged = output<string>();

  // ─── Status UUID resolution ────────────────────────────
  private readonly allStatuses = signal<SelectOption[]>([]);
  readonly statusFetchFailed = signal(false);
  readonly changing = signal(false);

  // ─── Computed ───────────────────────────────────────────
  readonly currentStatusName = computed(() => this.order().statusName);
  readonly validTransitionNames = computed(
    () => PO_STATUS_TRANSITIONS[this.currentStatusName()] ?? [],
  );

  /** Whether the actions should be hidden (no valid transitions). */
  readonly isTerminal = computed(() => this.validTransitionNames().length === 0);

  /** Linear lifecycle steps with their progress state for the stepper. */
  readonly steps = computed(() => {
    const current = this.currentStatusName();
    const currentIndex = PO_STATUS_FLOW.indexOf(current);
    return PO_STATUS_FLOW.map((name, index) => ({
      name,
      label: PO_STATUS_LABELS[name] ?? name,
      state:
        currentIndex === -1
          ? 'upcoming'
          : index < currentIndex
            ? 'done'
            : index === currentIndex
              ? 'current'
              : 'upcoming',
    }));
  });

  readonly statusMap = computed(() => {
    const map = new Map<string, string>();
    for (const s of this.allStatuses()) {
      map.set(s.name, s.id);
    }
    return map;
  });

  readonly validTransitions = computed(() => {
    const names = this.validTransitionNames();
    const map = this.statusMap();
    return names
      .filter((name) => map.has(name))
      .map((name) => ({
        id: map.get(name)!,
        name,
        label: PO_STATUS_LABELS[name] ?? name,
      }));
  });

  ngOnInit(): void {
    this.loadStatuses();
  }

  /** Resolves the PURCHASE_ORDER status type and loads its statuses. */
  private loadStatuses(): void {
    this.statusService
      .getStatusTypeIdByName('PURCHASE_ORDER')
      .pipe(
        switchMap((statusTypeId) => this.statusService.getStatusesByTypeId(statusTypeId)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (statuses) => this.allStatuses.set(statuses),
        error: () => this.statusFetchFailed.set(true),
      });
  }

  /** Called when the user selects a new status from the dropdown and confirms. */
  onStatusChange(transitionId: string): void {
    const transition = this.validTransitions().find((t) => t.id === transitionId);
    if (!transition) {
      return;
    }

    const id = this.order().id;
    this.changing.set(true);

    this.purchaseOrderService
      .updateStatus(id, { statusId: transitionId })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.changing.set(false);
          this.notificationService.showSuccess('Estado actualizado correctamente.');
          this.statusChanged.emit(transition.name);
        },
        error: (err: HttpErrorResponse) => {
          this.changing.set(false);
          const message =
            err.status === 409
              ? 'Transición de estado no permitida.'
              : err.status === 404
                ? 'Orden de compra no encontrada.'
                : 'Error al actualizar el estado.';
          this.notificationService.showError(message);
        },
      });
  }
}
