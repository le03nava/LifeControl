import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CompanyRegion, CompanyRegionRequest } from '../../models/region.models';
import { RegionsCard } from '../regions-card/regions-card';

const REGION_CODE_PATTERN = /^[a-zA-Z0-9-]+$/;

@Component({
  selector: 'app-region-manager',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    RegionsCard,
  ],
  templateUrl: './region-manager.html',
  styleUrl: './region-manager.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegionManager {
  // ─── Inputs ─────────────────────────────────────────────────
  regions = input.required<CompanyRegion[]>();
  companyId = input.required<string>();
  countryId = input.required<string>();
  loading = input(false);
  errorMessage = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  addRegion = output<CompanyRegionRequest>();
  updateRegion = output<{ id: string; data: CompanyRegionRequest }>();
  removeRegion = output<string>();
  enableRegion = output<string>();

  // ─── Internal state ─────────────────────────────────────────
  showDisabled = signal(false);

  /** Add form — Reactive Form with validators */
  newRegionForm = new FormGroup({
    regionCode: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(10),
        Validators.pattern(REGION_CODE_PATTERN),
      ],
    }),
    regionName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
  });

  // ─── Computed ───────────────────────────────────────────────
  filteredRegions = computed(() => {
    const all = this.regions();
    if (this.showDisabled()) return all;
    return all.filter(r => r.enabled);
  });

  // ─── Methods ────────────────────────────────────────────────
  onAdd(): void {
    if (this.newRegionForm.invalid) return;
    const { regionCode, regionName } = this.newRegionForm.getRawValue();
    this.addRegion.emit({ regionCode: regionCode.trim(), regionName: regionName.trim() });
    this.newRegionForm.reset();
  }

  onRemove(id: string): void {
    this.removeRegion.emit(id);
  }

  /**
   * Placeholder for future edit implementation.
   * The card emits the region id; the edit flow is deferred.
   */
  onEditRegion(regionId: string): void {
    // Edit implementation deferred
  }

  /** Fired when the card's delete button is clicked. */
  onDeleteRegion(regionId: string): void {
    this.removeRegion.emit(regionId);
  }

  /**
   * When a user slides the toggle:
   * - ON → OFF → soft-delete (disable)
   * - OFF → ON → re-enable
   */
  onToggleRegion(region: CompanyRegion): void {
    if (region.enabled) {
      this.removeRegion.emit(region.id);
    } else {
      this.enableRegion.emit(region.id);
    }
  }
}
