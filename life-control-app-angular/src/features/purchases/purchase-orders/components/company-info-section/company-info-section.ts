import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  OnInit,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime } from 'rxjs';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { CompanyCascadeService } from '../../data/company-cascade.service';
import { requiredFieldError, serverError } from '../../utils/form-error.utils';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatSelectModule } from '@angular/material/select';

/**
 * Company info section for the purchase order edit form.
 *
 * Thin presentation shell over `CompanyCascadeService`: renders the cascade
 * (Company autocomplete → Country → Region → Zone → Store) plus a read-only
 * company details card, and delegates every load/reconstruction to the service.
 *
 * On create mode the cascade is pre-populated from the authenticated user's
 * profile; on edit mode it is reconstructed from the loaded order's cascade IDs.
 */
@Component({
  selector: 'app-company-info-section',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
    MatSelectModule,
  ],
  templateUrl: './company-info-section.html',
  styleUrl: './company-info-section.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [CompanyCascadeService],
})
export class CompanyInfoSection implements OnInit {
  private readonly cascade = inject(CompanyCascadeService);
  private readonly destroyRef = inject(DestroyRef);

  /** The header form group from the parent component. */
  readonly headerForm = input.required<FormGroup<PurchaseOrderHeaderControl>>();

  /** Whether the form is in edit mode. */
  readonly isEditMode = input<boolean>(false);

  /** The loaded purchase order (edit mode only). */
  readonly loadedOrder = input<PurchaseOrder | null>(null);

  /** Server-side validation errors keyed by field name. */
  readonly serverErrors = input<Record<string, string>>({});

  // ─── Cascade state (owned by the service) ──────────────
  readonly companies = this.cascade.companies;
  readonly countries = this.cascade.countries;
  readonly regions = this.cascade.regions;
  readonly zones = this.cascade.zones;
  readonly stores = this.cascade.stores;
  readonly companyDetail = this.cascade.companyDetail;
  readonly companyDetailLoading = this.cascade.companyDetailLoading;

  private readonly companySearch$ = new Subject<string>();

  constructor() {
    // Bind the cascade service once the required header form input resolves.
    effect(() => this.cascade.bind(this.headerForm()));

    // Reconstruct the cascade from the loaded order (edit mode).
    effect(() => {
      const order = this.loadedOrder();
      const isEditMode = this.isEditMode();
      untracked(() => {
        if (isEditMode && order) {
          this.cascade.reconstructFromOrder(order);
        }
      });
    });

    this.companySearch$
      .pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe((term) => this.cascade.searchCompanies(term.trim()));
  }

  ngOnInit(): void {
    if (!this.isEditMode()) {
      this.cascade.reconstructFromProfile();
    }
  }

  /** Autocomplete display formatter: maps a company UUID to its name. */
  displayCompany = (id: string | null): string =>
    this.companies().find((company) => company.id === id)?.name ?? id ?? '';

  onCompanySearch(event: Event): void {
    this.companySearch$.next((event.target as HTMLInputElement).value);
  }

  onCompanyChange(companyId: string | null): void {
    this.cascade.onCompanyChange(companyId);
  }

  onCountryChange(countryId: string | null): void {
    this.cascade.onCountryChange(countryId);
  }

  onRegionChange(regionId: string | null): void {
    this.cascade.onRegionChange(regionId);
  }

  onZoneChange(zoneId: string | null): void {
    this.cascade.onZoneChange(zoneId);
  }

  fieldError(field: keyof PurchaseOrderHeaderControl): string | null {
    return requiredFieldError(this.headerForm().controls[field]);
  }

  serverFieldError(field: string): string | null {
    return serverError(this.serverErrors(), field);
  }
}
