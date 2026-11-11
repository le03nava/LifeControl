import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  OnInit,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, debounceTime } from 'rxjs';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { CompanyCascadeService } from '../../data/company-cascade.service';
import { requiredFieldError, serverError } from '../../utils/form-error.utils';
import type { PurchaseOrderHeaderControl } from '../../models/purchase-order-control.models';
import type { PurchaseOrder } from '../../models/purchase-order.models';
import type { SelectOption } from '../../models/select-option.models';
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

  /**
   * Whether the page locked the header because the order already holds a line (S7).
   *
   * The store is what every line is stocked against, so it stops moving at the first line. Two things follow from the
   * lock, and neither is a `[disabled]` on the select: the control itself is disabled through the form — that attribute
   * warns and leaves a reactive control enabled, while disabling it makes its validity and the value `getRawValue()`
   * returns follow the lock — and the value it holds survives the cascade, which empties the store on every ancestor
   * change.
   */
  readonly headerLocked = input<boolean>(false);

  /**
   * The store the lock protects, captured when it engaged.
   *
   * Every cascade handler applies `patchStore('')`, which is right while the store is still the operator's to choose and
   * wrong once it is not. Holding the value here is what lets a reset be undone instead of moving the order's store
   * (S7).
   */
  private readonly lockedStoreId = signal<string | null>(null);

  /**
   * The options the store select offers.
   *
   * The cascade's list is what the operator may pick from, and while the header is locked the store it must keep is
   * merged in: an ancestor change empties that list, and a select with no matching option would state the store as
   * blank while the form still holds it — hiding both the locked value and the reason it is locked (S8).
   */
  readonly storeOptions = computed<SelectOption[]>(() => {
    const locked = this.lockedStoreId();
    const stores = this.stores();
    if (!locked || stores.some((store) => store.id === locked)) {
      return stores;
    }
    return [{ id: locked, name: this.loadedOrder()?.companyStoreName ?? locked }, ...stores];
  });

  /**
   * Emits the store the cascade applied to the header form.
   *
   * `CompanyCascadeService` patches `companyStoreId` with `emitEvent: false` —
   * the documented silent-patch contract — so `valueChanges` never sees the
   * profile prefill (create mode), the order reconstruction (edit mode), or any
   * of the company/country/region/zone resets. This signal-backed output is the
   * only channel that can carry those programmatic resolutions to the parent.
   */
  readonly storeResolved = output<string>();

  /**
   * Emits the company the cascade applied to the header form, on the same silent-patch contract as
   * {@link storeResolved} and for the same reason: the company control is never written through an
   * emitting patch, so this output is the only channel that can carry the resolved company to the
   * parent — which scopes the supplier search, the supplier's products and the variant picker to it.
   */
  readonly companyResolved = output<string | null>();

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

    // Mirror the store the cascade applied to the parent. The service patches
    // `companyStoreId` silently (`emitEvent: false`), so `valueChanges` alone
    // can never see the profile prefill or the resets; this channel can.
    effect(() => this.storeResolved.emit(this.cascade.storeId()));

    // Same channel for the company: its control is patched silently too.
    effect(() => this.companyResolved.emit(this.cascade.companyId()));

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

    // The lock follows the lines the page holds: it engages at the first one, with the store the order states by then,
    // and lifts at the last (S7).
    effect(() => {
      const locked = this.headerLocked();
      untracked(() => {
        const control = this.headerForm().controls.companyStoreId;
        if (locked) {
          if (this.lockedStoreId() === null) {
            this.lockedStoreId.set(control.value);
          }
          control.disable({ emitEvent: false });
        } else {
          this.lockedStoreId.set(null);
          control.enable({ emitEvent: false });
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
    this.runCascade(() => this.cascade.onCompanyChange(companyId));
  }

  onCountryChange(countryId: string | null): void {
    this.runCascade(() => this.cascade.onCountryChange(countryId));
  }

  onRegionChange(regionId: string | null): void {
    this.runCascade(() => this.cascade.onRegionChange(regionId));
  }

  onZoneChange(zoneId: string | null): void {
    this.runCascade(() => this.cascade.onZoneChange(zoneId));
  }

  /**
   * Runs a cascade change and puts the locked store back.
   *
   * Every cascade handler empties `companyStoreId`, which is the right answer while the store is the operator's to
   * choose: an ancestor that no longer matches must not leave a stale store selected. Once the order holds a line the
   * store is not the operator's any more, so the reset is undone with the value the lock captured (S7). The guard lives
   * here rather than in the cascade because the cascade is shared with the screens that lock nothing.
   */
  private runCascade(change: () => void): void {
    change();
    const locked = this.lockedStoreId();
    if (locked === null) {
      return;
    }
    const control = this.headerForm().controls.companyStoreId;
    if (control.value !== locked) {
      control.setValue(locked, { emitEvent: false });
    }
  }

  fieldError(field: keyof PurchaseOrderHeaderControl): string | null {
    return requiredFieldError(this.headerForm().controls[field]);
  }

  serverFieldError(field: string): string | null {
    return serverError(this.serverErrors(), field);
  }
}
