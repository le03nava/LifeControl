import { Component, computed, inject, signal, OnInit } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { PageHeader } from '@shared/ui';
import { map } from 'rxjs/operators';
import { CompanyService } from '../../../companies/data/company.service';
import { CompanyCountryService } from '../../../countries/data/company-country.service';
import { CompanyRegionService } from '../../../regions/data/company-region.service';
import { CompanyZoneService } from '../../data/company-zone.service';
import { ZonesCard } from '../../components/zones-card/zones-card';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../models/zone.models';

@Component({
  selector: 'app-zones-page',
  standalone: true,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
    ZonesCard,
  ],
  templateUrl: './zones-page.html',
  styleUrl: './zones-page.scss',
})
export class ZonesPage implements OnInit {
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private companyService = inject(CompanyService);
  companyCountryService = inject(CompanyCountryService);
  companyRegionService = inject(CompanyRegionService);
  companyZoneService = inject(CompanyZoneService);

  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  selectedCompanyId = signal<string | null>(null);
  selectedCountry = signal<CompanyCountry | null>(null);
  selectedRegion = signal<CompanyRegion | null>(null);

  // ─── Filter state ────────────────────────────────────────────
  showDisabled = signal(false);

  filteredZones = computed(() => {
    const all = this.companyZoneService.zones();
    if (this.showDisabled()) return all;
    return all.filter((z) => z.enabled);
  });

  /** compareWith for mat-select: both sides are CompanyCountry objects */
  protected compareCompanyCountry = (a: CompanyCountry | null, b: CompanyCountry | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyRegion objects */
  protected compareCompanyRegion = (a: CompanyRegion | null, b: CompanyRegion | null): boolean => {
    return a?.id === b?.id;
  };

  // ─── Lifecycle ───────────────────────────────────────────────

  ngOnInit(): void {
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');

    if (companyId) {
      this.selectedCompanyId.set(companyId);
      this.companyCountryService.getCountries(companyId).subscribe((countries) => {
        if (countryId) {
          const cc = countries.find((c) => c.id === countryId);
          if (cc) {
            this.selectedCountry.set(cc);
            this.companyRegionService.getRegions(cc.companyId, cc.id).subscribe((regions) => {
              if (regionId) {
                const region = regions.find((r) => r.id === regionId);
                if (region) {
                  this.selectedRegion.set(region);
                  this.companyZoneService.getZones(cc.companyId, cc.id, region.id).subscribe();
                }
              }
            });
          }
        }
      });
    }
  }

  // ─── Event handlers ──────────────────────────────────────────

  onCompanyChange(companyId: string): void {
    this.selectedCompanyId.set(companyId);
    this.selectedCountry.set(null);
    this.selectedRegion.set(null);
    if (companyId) {
      this.companyCountryService.getCountries(companyId).subscribe();
    }
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
    this.companyRegionService.getRegions(cc.companyId, cc.id).subscribe();
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    const cc = this.selectedCountry();
    if (!cc) return;
    this.companyZoneService.getZones(cc.companyId, cc.id, region.id).subscribe();
  }

  onCreateZone(): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.router.navigate(['/companies/zones/create'], {
      queryParams: { companyId: cc.companyId, countryId: cc.id, regionId: region.id },
    });
  }

  onEditZone(zone: CompanyZone): void {
    this.router.navigate(['/companies/zones/edit', zone.id], {
      state: { zone },
    });
  }

  /** Bridge: the card emits a zone ID; look up the full zone and delegate. */
  onCardEditZone(zoneId: string): void {
    const zone = this.companyZoneService
      .zones()
      .find((z) => z.id === zoneId);
    if (zone) {
      this.onEditZone(zone);
    }
  }

  onRemoveZone(zoneId: string): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.companyZoneService
      .removeZone(cc.companyId, cc.id, region.id, zoneId)
      .subscribe();
  }

  onEnableZone(zoneId: string): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.companyZoneService
      .enableZone(cc.companyId, cc.id, region.id, zoneId)
      .subscribe();
  }

  /** Bridge: card emits { id, enable } where enable is the desired new state. */
  onCardToggleZone(event: { id: string; enable: boolean }): void {
    if (event.enable) {
      this.onEnableZone(event.id);
    } else {
      this.onRemoveZone(event.id);
    }
  }

  /**
   * When a user slides the toggle:
   * - ON → OFF → soft-delete (disable)
   * - OFF → ON → re-enable
   */
  onToggleZone(zone: CompanyZone): void {
    if (zone.enabled) {
      this.onRemoveZone(zone.id);
    } else {
      this.onEnableZone(zone.id);
    }
  }
}
