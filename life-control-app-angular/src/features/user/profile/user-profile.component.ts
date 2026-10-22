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
import { FormGroup, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { ProfileResponse, ProfileUpdateRequest, AssignedStore } from './data/profile.models';
import { derivedChainLabel } from '@features/hr/models/store-assignment.models';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { ErrorBanner } from '@shared/ui';

import type { Company } from '@features/companies/companies/models/company.models';
import type { CompanyCountry } from '@features/companies/countries/models/country.models';
import type { CompanyRegion } from '@features/companies/regions/models/region.models';
import type { CompanyZone } from '@features/companies/zones/models/zone.models';
import type { CompanyStore } from '@features/companies/stores/models/store.models';

@Component({
  selector: 'app-user-profile',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterModule,
    ErrorBanner,
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

  /**
   * The caller's assigned stores exactly as the response carries them (`T25`/`T29`): `null` is the
   * free mode — no employee row, so no restriction — and an array, **possibly empty**, is the
   * constrained mode. Read from the loaded profile, never inferred from the store catalogue.
   */
  readonly assignedStores = computed<AssignedStore[] | null>(
    () => this.profile()?.assignedStores ?? null,
  );

  /** Whether the store preference is narrowed to the assigned set (`T26`). */
  readonly isConstrained = computed(() => this.assignedStores() !== null);

  /** Reused HR label for an assigned store's chain (`T28`) — one formatter, not a copy. */
  readonly derivedChainLabel = derivedChainLabel;

  /**
   * The location line of the view mode (`T17` of `company-scope-local-fallback`, `S11` of
   * `location-preference`).
   *
   * It used to enumerate the hierarchy from the deepest non-null ancestor — a single word derived from
   * the four scalars the response no longer carries. It reports the preference instead: in the
   * constrained mode the assigned store's own chain through `T28`'s single formatter, and in the free
   * mode the store's own name from the resolved chain (`location.companyStoreName`, `S11`), which is
   * what replaced the `Store assigned` literal: that literal could not say which store, so it said
   * nothing the row's label did not already say.
   *
   * `null` hides the row, exactly as an absent preference did before — and it stays `null` for the
   * legitimate `S2` pair the response can carry (a stored `companyStoreId` whose chain did not
   * resolve), because a row that announced a store it cannot name would be guessing.
   */
  readonly locationLabel = computed<string | null>(() => {
    const profile = this.profile();
    const storeId = profile?.companyStoreId;
    if (!storeId) {
      return null;
    }
    const assigned = this.assignedStores()?.find((store) => store.companyStoreId === storeId);
    if (assigned) {
      return derivedChainLabel(assigned);
    }
    return profile?.location?.companyStoreName ?? null;
  });

  /** True while the initial profile GET is in flight. */
  readonly loading = signal(true);

  /** True while the PUT save is in flight. */
  readonly saving = signal(false);

  /** General error message (load or save failure). */
  readonly error = signal<string | null>(null);

  /** Set to true briefly after a successful save. */
  readonly saveSuccess = signal(false);

  /**
   * Whether the caller touched the store control during this edit session (`T16`).
   *
   * With the four tenancy ancestors gone (T14) a free-mode load cannot reconstruct the cascade, so the
   * store control starts empty even when the row holds a preference. Posting that emptiness would be
   * an invisible clear, so the payload **omits** the location keys unless the caller changed it —
   * including an explicit `— None —`, which is a deliberate choice and posts `clearStorePreference`
   * (`S10`).
   */
  readonly storeTouched = signal(false);

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
    this.isEditMode.set(this.route.snapshot.queryParamMap.get('edit') === 'true');
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

          // Free mode loads the cascade; constrained mode renders one select over the assigned
          // set and never walks the four levels (`T29`).
          if (this.isEditMode() && !this.isConstrained()) {
            this.initializeCascade();
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
      // T14: the four tenancy ancestors are not on the wire any more, so there is nothing to patch into
      // the cascade. `companyStoreId` stays because it is the preference the screen reports and writes.
      companyStoreId: profile.companyStoreId ?? null,
    });
  }

  // ─── Cascading initialization ────────────────────────────────

  /**
   * Loads the companies list so the caller can start walking the cascade, and preselects it from the
   * stored store preference.
   *
   * <p>The preselect used to read the profile's four tenancy ancestors, which `T14` removed from the
   * wire. It now resolves them from the store id through the flat store read (`T20`): the chain has
   * exactly one source that the caller cannot forge — the store row the server resolves and authorizes
   * — and this is the read that replaced the ancestors `G12` had declared out of scope.</p>
   */
  private initializeCascade(): void {
    this.loadingCompanies.set(true);
    this.companyService
      .getCompanies(0, 1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.companies.set(page.content);
          this.loadingCompanies.set(false);
          this.preselectFromStoredStore();
        },
        error: () => this.loadingCompanies.set(false),
      });
  }

  /**
   * Preselects the cascade from the stored store preference, one flat read and no second guess.
   *
   * <p>Best-effort by design: a store the caller can no longer read (or a preference pointing nowhere)
   * leaves the cascade empty and the caller walks it. Nothing here writes, so the stored preference is
   * never touched by a load that failed to display it — the rule `T16` applies to the payload.</p>
   */
  private preselectFromStoredStore(): void {
    const storeId = this.profile()?.companyStoreId;
    if (!storeId) {
      return;
    }
    this.companyStoreService
      .getStoreById(storeId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (store) => {
          this.form().patchValue({ companyStoreId: store.id });
          this.loadCountries(store.companyId, store.companyCountryId, store.regionId, store.zoneId);
        },
        // The cascade stays empty; the free-mode navigator is still walkable from the companies list.
        error: () => undefined,
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
    targetRegionId?: string,
    targetZoneId?: string,
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
            this.loadRegions(companyId, targetCountryId, targetRegionId, targetZoneId);
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
    targetZoneId?: string,
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
            this.loadZones(companyId, countryId, targetRegionId, targetZoneId);
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
  private loadStores(companyId: string, countryId: string, regionId: string, zoneId: string): void {
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
    this.storeTouched.set(true);
    this.form().patchValue({ companyStoreId: storeId });
  }

  /**
   * Called when the user changes the constrained store selector.
   *
   * <p>Choosing a store patches `companyStoreId` and nothing else (`T15` of
   * `company-scope-local-fallback`): the five-field patch existed so the payload would carry the chain
   * of the store the caller chose, and `T14` removed the four fields that patch promised. The `— None —`
   * branch is the same statement now — it clears the store and marks the control touched, so the payload
   * asks for a clear through `clearStorePreference` (`S10`) instead of restoring a tuple the response no
   * longer carries (`D16`'s rule is superseded; the server leaves an omitted key alone).</p>
   */
  onAssignedStoreChange(companyStoreId: string | null): void {
    this.storeTouched.set(true);
    this.form().patchValue({ companyStoreId });
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

    // Build the update payload — only include non-null basic info, and the store preference only when
    // the caller touched it (T16): an omitted key leaves the stored value alone, which is what keeps a
    // free-mode load from clearing a preference it can no longer display.
    const data: ProfileUpdateRequest = {
      firstName: raw.firstName || undefined,
      lastName: raw.lastName || undefined,
      email: raw.email || undefined,
      // T16: an untouched store leaves the KEY out instead of carrying `undefined`. The two are not the
      // same thing — `JSON.stringify` drops the undefined one, but the object the caller and the tests see
      // still has it, and a later spread would resurrect the value the server was never meant to receive.
      //
      // S10: the same rule decides *how* a touched control travels, and it is three wire states rather
      // than three form values. A touched control holding a store posts `companyStoreId`; a touched
      // control that is empty posts `clearStorePreference: true` and NOT `companyStoreId: null` — on a
      // `UUID` the server cannot tell that `null` apart from an omitted key (`L6`), which is the defect
      // the second field closes. The two keys are mutually exclusive by construction: one branch of
      // `storePreferencePayload`, never both, because the server answers the pair with `400` (`S5`).
      ...(this.storeTouched() ? this.storePreferencePayload(raw.companyStoreId) : {}),
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
          this.error.set(err.error?.message ?? 'Failed to update profile. Please try again.');
        },
      });
  }

  /** Cancel editing and return to view mode. */
  cancel(): void {
    this.router.navigate(['/profile']);
  }

  /**
   * The location half of the payload (`S10`) — the single place that decides which of the two keys
   * travels, so the free cascade and the constrained select cannot drift apart.
   *
   * Only called for a touched control: an untouched one contributes no key at all (`T16`). A touched
   * one holding a store posts that store; a touched one that is empty asks the server to clear, because
   * `companyStoreId: null` is indistinguishable from an omitted key on the wire (`L6`).
   */
  private storePreferencePayload(companyStoreId: string | null): ProfileUpdateRequest {
    return companyStoreId ? { companyStoreId } : { clearStorePreference: true };
  }
}
