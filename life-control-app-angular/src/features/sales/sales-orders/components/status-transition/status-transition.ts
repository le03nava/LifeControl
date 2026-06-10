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
import { SalesOrderService } from '../../data/sales-order.service';
import { SO_STATUS_TRANSITIONS, SO_STATUS_LABELS, SO_STATUS_COLORS } from '../../data/status-config';
import { NotificationService } from '@shared/data/notification';
import type { SalesOrder } from '../../models/sales-order.models';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
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

interface TransitionOption {
  id: string;
  name: string;
  label: string;
}

/**
 * Status transition component for sales orders.
 *
 * Displays a colored chip for the current status and a dropdown with valid
 * next states per `SO_STATUS_TRANSITIONS`. On selection, calls
 * `PATCH /api/sales-orders/{id}/status`. Terminal states (Completed, Cancelled)
 * show a disabled chip with no dropdown.
 */
@Component({
  selector: 'app-status-transition',
  standalone: true,
  imports: [
    MatFormFieldModule,
    MatSelectModule,
    MatChipsModule,
    MatIconModule,
  ],
  templateUrl: './status-transition.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusTransition implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private configService = inject(ConfigService);
  private http = inject(HttpClient);
  private salesOrderService = inject(SalesOrderService);
  private notificationService = inject(NotificationService);

  /** The current sales order. */
  readonly order = input.required<SalesOrder>();

  /** Emits the statusId of the newly selected status after a successful PATCH. */
  readonly statusChanged = output<string>();

  // ─── Status resolution ─────────────────────────────────
  private readonly allStatuses = signal<StatusEntry[]>([]);
  readonly statusFetchFailed = signal(false);
  readonly changing = signal(false);

  // ─── Status display config ─────────────────────────────
  readonly statusColors = SO_STATUS_COLORS;

  // ─── Computed ───────────────────────────────────────────
  readonly currentStatusName = computed(() => this.order().statusName);
  readonly validTransitionNames = computed(
    () => SO_STATUS_TRANSITIONS[this.currentStatusName()] ?? [],
  );

  readonly isTerminal = computed(() => this.validTransitionNames().length === 0);

  readonly statusMap = computed(() => {
    const map = new Map<string, string>();
    for (const s of this.allStatuses()) {
      map.set(s.name, s.id);
    }
    return map;
  });

  readonly validTransitions = computed<TransitionOption[]>(() => {
    const names = this.validTransitionNames();
    const map = this.statusMap();
    return names
      .filter((name) => map.has(name))
      .map((name) => ({
        id: map.get(name)!,
        name,
        label: SO_STATUS_LABELS[name] ?? name,
      }));
  });

  ngOnInit(): void {
    this.loadStatuses();
  }

  /** Fetch all SALES_ORDER statuses to build a name→UUID lookup map. */
  private loadStatuses(): void {
    const baseUrl = this.configService.apiUrl;

    // Step 1: find the SALES_ORDER status type UUID
    this.http
      .get<PageStatusType>(`${baseUrl}/status-types`, {
        params: { search: 'SALES_ORDER', size: '1' },
      })
      .pipe(
        map((page) => {
          const match = page.content.find(
            (t) => t.statusTypeName.toUpperCase() === 'SALES_ORDER',
          );
          if (!match) {
            throw new Error('SALES_ORDER status type not found');
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

  /** Called when the user selects a new status from the dropdown. */
  onStatusChange(transitionId: string): void {
    const id = this.order().id;
    this.changing.set(true);

    this.salesOrderService
      .updateStatus(id, { statusId: transitionId })
      .subscribe({
        next: () => {
          this.changing.set(false);
          this.notificationService.showSuccess('Status updated successfully.');
          this.statusChanged.emit(transitionId);
        },
        error: (err: HttpErrorResponse) => {
          this.changing.set(false);
          const message =
            err.status === 409
              ? 'Status transition not allowed.'
              : err.status === 404
                ? 'Sales order not found.'
                : 'Error updating status.';
          this.notificationService.showError(message);
        },
      });
  }
}
