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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { map, catchError, throwError } from 'rxjs';
import { ConfigService } from '@app/services/config.service';
import { PurchaseOrderService } from '../../data/purchase-order.service';
import { PO_STATUS_FLOW, PO_STATUS_LABELS, PO_STATUS_TRANSITIONS } from '../../data/status-config';
import { NotificationService } from '@shared/data/notification';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

interface StatusEntry {
  id: string;
  name: string;
}

interface StatusTypeEntry {
  id: string;
  statusTypeName: string;
}

interface PageStatusType {
  content: StatusTypeEntry[];
}

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
  private readonly configService = inject(ConfigService);
  private readonly http = inject(HttpClient);
  private readonly purchaseOrderService = inject(PurchaseOrderService);
  private readonly notificationService = inject(NotificationService);

  /** The current purchase order (must include `statusId`, `statusName`, `id`). */
  readonly order = input.required<PurchaseOrder>();

  /** Emits the new status **name** (e.g. `Sent`) after a successful PATCH. */
  readonly statusChanged = output<string>();

  // ─── Status UUID resolution ────────────────────────────
  private readonly allStatuses = signal<StatusEntry[]>([]);
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

  /** Fetch all PURCHASE_ORDER statuses to build a name→UUID lookup map. */
  private loadStatuses(): void {
    const baseUrl = this.configService.apiUrl;

    // Step 1: find the PURCHASE_ORDER status type UUID
    this.http
      .get<PageStatusType>(`${baseUrl}/status-types`, {
        params: { search: 'PURCHASE_ORDER', size: '1' },
      })
      .pipe(
        map((page) => {
          const match = page.content.find(
            (t) => t.statusTypeName.toUpperCase() === 'PURCHASE_ORDER',
          );
          if (!match) {
            throw new Error('PURCHASE_ORDER status type not found');
          }
          return match.id;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (typeId) => this.loadStatusesForType(typeId),
        error: () => {
          this.statusFetchFailed.set(true);
        },
      });
  }

  private loadStatusesForType(statusTypeId: string): void {
    this.http
      .get<StatusEntry[]>(`${this.configService.apiUrl}/statuses`, {
        params: { statusTypeId },
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err) => {
          this.statusFetchFailed.set(true);
          return throwError(() => err);
        }),
      )
      .subscribe({
        next: (statuses) => this.allStatuses.set(statuses),
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
