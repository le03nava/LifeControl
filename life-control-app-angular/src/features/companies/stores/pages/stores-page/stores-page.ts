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
import { CompanyZoneService } from '../../../zones/data/company-zone.service';
import { CompanyStoreService } from '../../data/company-store.service';
import { StoresCard } from '../../components/stores-card/stores-card';
import { CompanyStore } from '../../models/store.models';
import { CompanyCountry } from '../../../countries/models/country.models';
import { CompanyRegion } from '../../../regions/models/region.models';
import { CompanyZone } from '../../../zones/models/zone.models';

@Component({
  selector: 'app-stores-page',
  standalone: true,
  imports: [
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    MatFormFieldModule,
    MatSlideToggleModule,
    PageHeader,
    StoresCard,
  ],
  templateUrl: './stores-page.html',
  styleUrl: './stores-page.scss',
})
export class StoresPage implements OnInit {
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private companyService = inject(CompanyService);
  companyCountryService = inject(CompanyCountryService);
  companyRegionService = inject(CompanyRegionService);
  companyZoneService = inject(CompanyZoneService);
  companyStoreService = inject(CompanyStoreService);

  companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  selectedCompanyId = signal<string | null>(null);
  selectedCountry = signal<CompanyCountry | null>(null);
  selectedRegion = signal<CompanyRegion | null>(null);
  selectedZone = signal<CompanyZone | null>(null);

  // ─── Filter state ────────────────────────────────────────────
  showDisabled = signal(false);

  filteredStores = computed(() => {
    const all = this.companyStoreService.stores();
    if (this.showDisabled()) return all;
    return all.filter((s) => s.enabled);
  });

  /** compareWith for mat-select: both sides are CompanyCountry objects */
  protected compareCompanyCountry = (a: CompanyCountry | null, b: CompanyCountry | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyRegion objects */
  protected compareCompanyRegion = (a: CompanyRegion | null, b: CompanyRegion | null): boolean => {
    return a?.id === b?.id;
  };

  /** compareWith for mat-select: both sides are CompanyZone objects */
  protected compareCompanyZone = (a: CompanyZone | null, b: CompanyZone | null): boolean => {
    return a?.id === b?.id;
  };

  // ─── Lifecycle ───────────────────────────────────────────────

  ngOnInit(): void {
    const companyId = this.route.snapshot.queryParamMap.get('companyId');
    const countryId = this.route.snapshot.queryParamMap.get('countryId');
    const regionId = this.route.snapshot.queryParamMap.get('regionId');
    const zoneId = this.route.snapshot.queryParamMap.get('zoneId');

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
                  this.companyZoneService.getZones(cc.companyId, cc.id, region.id).subscribe((zones) => {
                    if (zoneId) {
                      const zone = zones.find((z) => z.id === zoneId);
                      if (zone) {
                        this.selectedZone.set(zone);
                        this.companyStoreService.getStores(cc.companyId, cc.id, region.id, zoneId).subscribe();
                      }
                    }
                  });
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
    this.selectedZone.set(null);
    if (companyId) {
      this.companyCountryService.getCountries(companyId).subscribe();
    }
  }

  onSelectCountry(cc: CompanyCountry): void {
    this.selectedCountry.set(cc);
    this.selectedRegion.set(null);
    this.selectedZone.set(null);
    this.companyRegionService.getRegions(cc.companyId, cc.id).subscribe();
  }

  onSelectRegion(region: CompanyRegion): void {
    this.selectedRegion.set(region);
    this.selectedZone.set(null);
    const cc = this.selectedCountry();
    if (!cc) return;
    this.companyZoneService.getZones(cc.companyId, cc.id, region.id).subscribe();
  }

  onSelectZone(zone: CompanyZone): void {
    this.selectedZone.set(zone);
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    if (!cc || !region) return;
    this.companyStoreService.getStores(cc.companyId, cc.id, region.id, zone.id, this.showDisabled()).subscribe();
  }

  onCreateStore(): void {
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const zone = this.selectedZone();
    if (!cc || !region || !zone) return;
    this.router.navigate(['/companies/stores/create'], {
      queryParams: { companyId: cc.companyId, countryId: cc.id, regionId: region.id, zoneId: zone.id },
    });
  }

  onEditStore(store: CompanyStore): void {
    this.router.navigate(['/companies/stores/edit', store.id], {
      state: { store },
    });
  }

  /** Bridge: the card emits a store ID; look up the full store and delegate. */
  onCardEditStore(storeId: string): void {
    const store = this.companyStoreService.stores().find((s) => s.id === storeId);
    if (store) {
      this.onEditStore(store);
    }
  }

  onToggleStore(storeId: string): void {
    const store = this.companyStoreService.stores().find((s) => s.id === storeId);
    if (!store) return;
    const cc = this.selectedCountry();
    const region = this.selectedRegion();
    const zone = this.selectedZone();
    if (!cc || !region || !zone) return;

    if (store.enabled) {
      this.companyStoreService.removeStore(cc.companyId, cc.id, region.id, zone.id, storeId).subscribe();
    } else {
      this.companyStoreService.enableStore(cc.companyId, cc.id, region.id, zone.id, storeId).subscribe();
    }
  }
}
