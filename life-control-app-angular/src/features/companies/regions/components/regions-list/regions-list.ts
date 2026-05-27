import {
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectChange, MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { Company } from '../../../companies/models/company.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../models/region.models';
import { CompanyRegionService } from '../../data/company-region.service';

@Component({
  selector: 'app-regions-list',
  standalone: true,
  imports: [
    MatFormFieldModule,
    MatSelectModule,
    MatTableModule,
    MatButtonModule,
    MatSlideToggleModule,
  ],
  templateUrl: './regions-list.html',
  styleUrl: './regions-list.scss',
})
export class RegionsList {
  // ─── Services ───────────────────────────────────────────────
  private readonly regionService = inject(CompanyRegionService);

  // ─── Inputs ─────────────────────────────────────────────────
  companies = input.required<Company[]>();
  companyCountries = input<CompanyCountry[]>([]);

  // ─── Outputs ────────────────────────────────────────────────
  editRegion = output<CompanyRegion>();
  deleteRegion = output<string>();
  toggleRegion = output<{ id: string; enable: boolean }>();

  // ─── Internal selector state ────────────────────────────────
  selectedCompanyId = signal<string | null>(null);
  selectedCompanyCountryId = signal<string | null>(null);
  showDisabled = signal(false);

  // ─── Service signals (aliases for template) ─────────────────
  readonly regions = this.regionService.regions;
  readonly loading = this.regionService.loading;
  readonly error = this.regionService.error;

  // ─── Computed ───────────────────────────────────────────────
  readonly filteredRegions = computed(() => {
    const all = this.regions();
    if (this.showDisabled()) return all;
    return all.filter((r) => r.enabled);
  });

  // ─── Table config ───────────────────────────────────────────
  displayedColumns = ['regionCode', 'regionName', 'enabled', 'actions'];

  // ─── Methods ────────────────────────────────────────────────
  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCompanyCountryId.set(null);
  }

  onCountryChange(cc: CompanyCountry): void {
    this.selectedCompanyCountryId.set(cc.id);
    const companyId = this.selectedCompanyId();
    if (companyId) {
      this.regionService.getRegions(companyId, cc.id).subscribe();
    }
  }

  onEdit(region: CompanyRegion): void {
    this.editRegion.emit(region);
  }

  onDelete(regionId: string): void {
    this.deleteRegion.emit(regionId);
  }

  /**
   * When user slides the toggle:
   * - Enabled → toggle OFF → emit enable=false (disable)
   * - Disabled → toggle ON → emit enable=true (enable)
   */
  onToggle(region: CompanyRegion): void {
    this.toggleRegion.emit({ id: region.id, enable: !region.enabled });
  }
}
