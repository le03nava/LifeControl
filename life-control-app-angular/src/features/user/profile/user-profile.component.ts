import {
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  ChangeDetectionStrategy,
  OnInit,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import {
  FormGroup,
  FormControl,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, ActivatedRoute, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatOptionModule } from '@angular/material/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { ProfileService } from './data/profile.service';
import { ProfileResponse, ProfileUpdateRequest } from './data/profile.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';

import type { Company } from '@features/companies/companies/models/company.models';
import type { CompanyCountry } from '@features/companies/countries/models/country.models';
import type { CompanyRegion } from '@features/companies/regions/models/region.models';
import type { CompanyZone } from '@features/companies/zones/models/zone.models';
import type { CompanyStore } from '@features/companies/stores/models/store.models';

@Component({
  selector: 'app-user-profile',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterModule,
    MatCardModule,
    MatIconModule,
    MatSelectModule,
    MatOptionModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.scss',
})
export class UserProfileComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly profileService = inject(ProfileService);
  private readonly companyService = inject(CompanyService);
  private readonly companyCountryService = inject(CompanyCountryService);
  private readonly companyRegionService = inject(CompanyRegionService);
  private readonly companyZoneService = inject(CompanyZoneService);
  private readonly companyStoreService = inject(CompanyStoreService);

  // ─── View/Edit mode ───────────────────────────────────────────

  /** Whether the profile is in edit mode (set via `?edit=true` query param). */
  readonly isEditMode = signal(false);

  // ─── Profile data ─────────────────────────────────────────────

  /** The full profile loaded from the backend. */
  readonly profile = signal<ProfileResponse | null>(null);

  /** True while the initial profile GET is in flight. */
  readonly loading = signal(true);

  /** True while the PUT save is in flight. */
  readonly saving = signal(false);

  /** General error message (load or save failure). */
  readonly error = signal<string | null>(null);

  /** Set to true briefly after a successful save. */
  readonly saveSuccess = signal(false);

  // ─── Cascading location lists ─────────────────────────────────

  readonly companies = signal<Company[]>([]);
  readonly companyCountries = signal<CompanyCountry[]>([]);
  readonly regions = signal<CompanyRegion[]>([]);
  readonly zones = signal<CompanyZone[]>([]);
  readonly stores = signal<CompanyStore[]>([]);

  readonly loadingCompanies = signal(false);
  readonly loadingCountries = signal(false);
  readonly loadingRegions = signal(false);
  readonly loadingZones = signal(false);
  readonly loadingStores = signal(false);

  // ─── Form ──────────────────────────────────────────────────────

  readonly form = signal<FormGroup>(this.buildForm());

  /** Computed helper: the companyId form control. */
  private readonly companyControl = computed(
    () => this.form().get('companyId') as FormControl<string | null>,
  );

  // ─── Lifecycle ─────────────────────────────────────────────────

  constructor() {
    // Read edit mode from query params on construction
    this.isEditMode.set(
      this.route.snapshot.queryParamMap.get('edit') === 'true',
    );
  }

  ngOnInit(): void {
    this.loadProfile();
  }

  // ─── Form building ────────────────────────────────────────────

  private buildForm(): FormGroup {
    return new FormGroup({
      firstName: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      lastName: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      email: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.email],
      }),
      companyId: new FormControl<string | null>(null),
      companyCountryId: new FormControl<string | null>(null),
      companyRegionId: new FormControl<string | null>(null),
      companyZoneId: new FormControl<string | null>(null),
      companyStoreId: new FormControl<string | null>(null),
    });
  }

  // ─── Profile load ────────────────────────────────────────────

  private loadProfile(): void {
    this.loading.set(true);
    this.error.set(null);
    this.profileService
      .getProfile()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          this.profile.set(profile);
          this.populateForm(profile);
          this.loading.set(false);

          // If editing, load the companies list and cascade
          if (this.isEditMode()) {
            this.initializeCascade(profile);
          }
        },
        error: () => {
          this.error.set('Failed to load profile. Please try again.');
          this.loading.set(false);
        },
      });
  }

  /**
   * Populate the form controls from the profile response.
   * Called once after profile is loaded.
   */
  private populateForm(profile: ProfileResponse): void {
    this.form().patchValue({
      firstName: profile.firstName ?? '',
      lastName: profile.lastName ?? '',
      email: profile.email ?? '',
      companyId: profile.companyId ?? null,
      companyCountryId: profile.companyCountryId ?? null,
      companyRegionId: profile.companyRegionId ?? null,
      companyZoneId: profile.companyZoneId ?? null,
      companyStoreId: profile.companyStoreId ?? null,
    });
  }

  // ─── Cascading initialization ────────────────────────────────

  /**
   * Load the full location cascade chain based on existing profile values.
   * This is called once on init to pre-populate all location selects.
   */
  private initializeCascade(profile: ProfileResponse): void {
    // Load companies
    this.loadingCompanies.set(true);
    this.companyService
      .getCompanies(0, 1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.companies.set(page.content);
          this.loadingCompanies.set(false);

          // If a company is already selected, load its countries
          if (profile.companyId) {
            this.loadCountries(profile.companyId, profile.companyCountryId ?? undefined);
          }
        },
        error: () => this.loadingCompanies.set(false),
      });
  }

  // ─── Cascading load helpers ──────────────────────────────────

  /**
   * Load countries for the given company, then optionally cascade to regions
   * if a specific country is already selected.
   */
  private loadCountries(
    companyId: string,
    targetCountryId?: string,
  ): void {
    this.loadingCountries.set(true);
    this.companyCountryService
      .getCountries(companyId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (countries) => {
          this.companyCountries.set(countries);
          this.loadingCountries.set(false);

          // Cascade: if a country is already selected, load its regions
          if (targetCountryId) {
            this.loadRegions(companyId, targetCountryId);
          }
        },
        error: () => this.loadingCountries.set(false),
      });
  }

  /**
   * Load regions for the given company+country, then optionally cascade
   * to zones if a specific region is already selected.
   */
  private loadRegions(
    companyId: string,
    countryId: string,
    targetRegionId?: string,
  ): void {
    this.loadingRegions.set(true);
    this.companyRegionService
      .getRegions(companyId, countryId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (regions) => {
          this.regions.set(regions);
          this.loadingRegions.set(false);

          // Cascade: if a region is already selected, load its zones
          if (targetRegionId) {
            this.loadZones(companyId, countryId, targetRegionId);
          }
        },
        error: () => this.loadingRegions.set(false),
      });
  }

  /**
   * Load zones for the given company+country+region, then optionally cascade
   * to stores if a specific zone is already selected.
   */
  private loadZones(
    companyId: string,
    countryId: string,
    regionId: string,
    targetZoneId?: string,
  ): void {
    this.loadingZones.set(true);
    this.companyZoneService
      .getZones(companyId, countryId, regionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (zones) => {
          this.zones.set(zones);
          this.loadingZones.set(false);

          // Cascade: if a zone is already selected, load its stores
          if (targetZoneId) {
            this.loadStores(companyId, countryId, regionId, targetZoneId);
          }
        },
        error: () => this.loadingZones.set(false),
      });
  }

  /**
   * Load stores for the given company+country+region+zone.
   */
  private loadStores(
    companyId: string,
    countryId: string,
    regionId: string,
    zoneId: string,
  ): void {
    this.loadingStores.set(true);
    this.companyStoreService
      .getStores(companyId, countryId, regionId, zoneId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stores) => {
          this.stores.set(stores);
          this.loadingStores.set(false);
        },
        error: () => this.loadingStores.set(false),
      });
  }

  // ─── Cascading selection handlers ────────────────────────────

  /** Called when the user changes the company selector. */
  onCompanyChange(companyId: string | null): void {
    // Update the form control
    this.form().patchValue({
      companyId,
      companyCountryId: null,
      companyRegionId: null,
      companyZoneId: null,
      companyStoreId: null,
    });

    // Reset downstream lists
    this.companyCountries.set([]);
    this.regions.set([]);
    this.zones.set([]);
    this.stores.set([]);

    // Load new countries if a company is selected
    if (companyId) {
      this.loadCountries(companyId);
    }
  }

  /** Called when the user changes the country selector. */
  onCountryChange(companyCountryId: string | null): void {
    const form = this.form();
    form.patchValue({
      companyCountryId,
      companyRegionId: null,
      companyZoneId: null,
      companyStoreId: null,
    });

    this.regions.set([]);
    this.zones.set([]);
    this.stores.set([]);

    const companyId = form.get('companyId')?.value;
    // The companyCountryId here IS the ID understood by the backend regions endpoint's {countryId}
    if (companyId && companyCountryId) {
      this.loadRegions(companyId, companyCountryId);
    }
  }

  /** Called when the user changes the region selector. */
  onRegionChange(regionId: string | null): void {
    const form = this.form();
    form.patchValue({
      companyRegionId: regionId,
      companyZoneId: null,
      companyStoreId: null,
    });

    this.zones.set([]);
    this.stores.set([]);

    const companyId = form.get('companyId')?.value;
    const companyCountryId = form.get('companyCountryId')?.value;
    if (companyId && companyCountryId && regionId) {
      this.loadZones(companyId, companyCountryId, regionId);
    }
  }

  /** Called when the user changes the zone selector. */
  onZoneChange(zoneId: string | null): void {
    const form = this.form();
    form.patchValue({
      companyZoneId: zoneId,
      companyStoreId: null,
    });

    this.stores.set([]);

    const companyId = form.get('companyId')?.value;
    const companyCountryId = form.get('companyCountryId')?.value;
    const regionId = form.get('companyRegionId')?.value;
    if (companyId && companyCountryId && regionId && zoneId) {
      this.loadStores(companyId, companyCountryId, regionId, zoneId);
    }
  }

  /** Called when the user changes the store selector (terminal level, no further cascade). */
  onStoreChange(storeId: string | null): void {
    this.form().patchValue({ companyStoreId: storeId });
  }

  // ─── Save / Cancel ────────────────────────────────────────────

  /** Save the profile changes. */
  save(): void {
    if (this.form().invalid) {
      // Touch all controls to show validation errors
      Object.values(this.form().controls).forEach((c) => c.markAsTouched());
      return;
    }

    this.saving.set(true);
    this.error.set(null);
    this.saveSuccess.set(false);

    const raw = this.form().getRawValue();

    // Build the update payload — only include non-null basic info
    const data: ProfileUpdateRequest = {
      firstName: raw.firstName || undefined,
      lastName: raw.lastName || undefined,
      email: raw.email || undefined,
      // Location fields: explicitly null when no selection, undefined when unchanged
      companyId: raw.companyId ?? null,
      companyCountryId: raw.companyCountryId ?? null,
      companyRegionId: raw.companyRegionId ?? null,
      companyZoneId: raw.companyZoneId ?? null,
      companyStoreId: raw.companyStoreId ?? null,
    };

    this.profileService
      .updateProfile(data)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.profile.set(updated);
          this.saving.set(false);
          this.saveSuccess.set(true);
          // Navigate back to view mode
          this.router.navigate(['/profile']);
        },
        error: (err) => {
          this.saving.set(false);
          this.error.set(
            err.error?.message ?? 'Failed to update profile. Please try again.',
          );
        },
      });
  }

  /** Cancel editing and return to view mode. */
  cancel(): void {
    this.router.navigate(['/profile']);
  }
}
