import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CompanyRegion, CompanyRegionRequest } from '../../models/region.models';
import { RegionsCard } from '../regions-card/regions-card';

@Component({
  selector: 'app-region-manager',
  standalone: true,
  imports: [
    MatButtonModule,
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
  editRegion = output<CompanyRegion>();
  createRegion = output<void>();

  // ─── Internal state ─────────────────────────────────────────
  showDisabled = signal(false);

  // ─── Computed ───────────────────────────────────────────────
  filteredRegions = computed(() => {
    const all = this.regions();
    if (this.showDisabled()) return all;
    return all.filter(r => r.enabled);
  });

  // ─── Methods ────────────────────────────────────────────────
  onRemove(id: string): void {
    this.removeRegion.emit(id);
  }

  onEditRegion(regionId: string): void {
    const region = this.regions().find(r => r.id === regionId);
    if (region) {
      this.editRegion.emit(region);
    }
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
