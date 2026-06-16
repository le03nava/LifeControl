import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import {
  FormGroup,
  ReactiveFormsModule,
} from '@angular/forms';
import { ConfigService } from '@app/services/config.service';
import { CustomerSelector } from '../customer-selector/customer-selector';
import { SO_STATUS_COLORS, SO_STATUS_LABELS } from '../../data/status-config';
import type { SalesOrderHeaderControl } from '../../models/sales-order-control.models';
import type { SalesOrder, OpenShiftOption, CustomerOption } from '../../models/sales-order.models';
import { DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';

/**
 * Reusable sales order header form component.
 *
 * Manages customer autocomplete (CustomerSelector child), shift selector,
 * comments textarea, read-only store display (from user preferences),
 * and read-only status/order number display in edit mode.
 */
@Component({
  selector: 'app-order-header-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    DatePipe,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    CustomerSelector,
  ],
  templateUrl: './order-header-form.html',
  styleUrl: './order-header-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderHeaderForm implements OnInit {
  private destroyRef = inject(DestroyRef);
  private configService = inject(ConfigService);
  private http = inject(HttpClient);

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<SalesOrderHeaderControl>>();

  /** Whether the form is in edit mode (load existing data). */
  readonly isEditMode = input<boolean>(false);

  /** Whether the order is in Draft status (enables field editing). */
  readonly isDraft = input<boolean>(false);

  /** The loaded sales order (edit mode only). Used for status/order number display. */
  readonly loadedOrder = input<SalesOrder | null>(null);

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  /** Store name from user preferences (create mode) or loaded order (edit mode). Displayed as read-only text. */
  readonly storeName = input<string | null>(null);

  /** Optional customer to pre-select (e.g. PUBLICO EN GENERAL in create mode). */
  readonly initialCustomer = input<CustomerOption | null>(null);

  // ─── Status display ────────────────────────────────────
  readonly statusColor = SO_STATUS_COLORS;
  readonly statusLabel = SO_STATUS_LABELS;

  readonly currentStatusName = computed(
    () => this.loadedOrder()?.statusName ?? null,
  );

  // ─── Shift selector ────────────────────────────────────
  readonly openShifts = signal<OpenShiftOption[]>([]);

  // ─── Customer selector ──────────────────────────────────
  /** Tracks the display name of the currently selected customer. */
  readonly selectedCustomerName = signal<string | null>(null);

  constructor() {
    // Sync selectedCustomerName with loadedOrder in edit mode
    effect(() => {
      const order = this.loadedOrder();
      if (order?.customerName) {
        this.selectedCustomerName.set(order.customerName);
      }
    });
  }

  ngOnInit(): void {
    this.loadOpenShifts();
  }

  // ══════════════════════════════════════════════════════════
  // DATA LOADING
  // ══════════════════════════════════════════════════════════

  private loadOpenShifts(): void {
    this.http
      .get<OpenShiftOption[]>(`${this.configService.apiUrl}/shifts/open`)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => this.openShifts.set(list),
      });
  }

  // ══════════════════════════════════════════════════════════
  // FORM HELPERS
  // ══════════════════════════════════════════════════════════

  /** Called when the user selects a customer from the autocomplete dropdown. */
  onCustomerSelected(customer: CustomerOption): void {
    this.headerForm().controls.customerId.setValue(customer.id);
    this.selectedCustomerName.set(customer.name);
  }

  fieldError(field: keyof SalesOrderHeaderControl): string | null {
    const control = this.headerForm().controls[field];
    if (control && control.invalid && control.touched) {
      if (control.hasError('required')) {
        return 'Este campo es requerido.';
      }
    }
    return null;
  }

  serverFieldError(field: string): string | null {
    return this.serverErrors()[field] ?? null;
  }
}
