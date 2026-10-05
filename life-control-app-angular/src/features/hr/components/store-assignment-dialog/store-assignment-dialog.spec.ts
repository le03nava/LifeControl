/// <reference types="vitest/globals" />
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { CompanyCountry } from '@features/companies/countries/models/country.models';
import { CompanyRegion } from '@features/companies/regions/models/region.models';
import { CompanyZone } from '@features/companies/zones/models/zone.models';
import { CompanyStore } from '@features/companies/stores/models/store.models';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { CompanyStoreService } from '@features/companies/stores/data/company-store.service';
import { StoreAssignmentDialog, StoreAssignmentDialogData } from './store-assignment-dialog';
import { StoreAssignmentService } from '../../data/store-assignment.service';
import { StoreAssignment } from '../../models/store-assignment.models';

describe('StoreAssignmentDialog', () => {
  let fixture: ComponentFixture<StoreAssignmentDialog>;
  let component: StoreAssignmentDialog;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let assignmentService: {
    createAssignment: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
  };
  let countryService: { getCountries: ReturnType<typeof vi.fn> };
  let regionService: { getRegions: ReturnType<typeof vi.fn> };
  let zoneService: { getZones: ReturnType<typeof vi.fn> };
  let storeService: { getStores: ReturnType<typeof vi.fn> };

  const companyId = 'company-1';

  const country: CompanyCountry = {
    id: 'company-country-1',
    companyId,
    countryId: 'country-1',
    countryCode: 'MX',
    countryName: 'México',
    localAlias: null,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  /** A second country, so a change of country is a real value change that emits. */
  const otherCountry: CompanyCountry = {
    ...country,
    id: 'company-country-2',
    countryId: 'country-2',
    countryCode: 'ES',
    countryName: 'España',
  };

  const region: CompanyRegion = {
    id: 'region-1',
    companyCountryId: 'company-country-1',
    companyId,
    countryId: 'country-1',
    regionCode: 'CEN',
    regionName: 'Centro',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  /** A second region, so a change of region is a real value change that emits. */
  const otherRegion: CompanyRegion = {
    ...region,
    id: 'region-2',
    regionCode: 'SUR',
    regionName: 'Sur',
  };

  const zone: CompanyZone = {
    id: 'zone-1',
    companyRegionId: 'region-1',
    companyCountryId: 'company-country-1',
    companyId,
    countryId: 'country-1',
    zoneCode: 'ZN',
    zoneName: 'Zona Norte',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const store: CompanyStore = {
    id: 'store-1',
    companyId,
    companyCountryId: 'company-country-1',
    regionId: 'region-1',
    zoneId: 'zone-1',
    storeName: 'Tienda Centro',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    version: 0,
  };

  const created: StoreAssignment = {
    id: 'assignment-1',
    companyStoreId: 'store-1',
    companyStoreName: 'Tienda Centro',
    validFrom: '2026-01-01',
    validTo: null,
    enabled: true,
    derived: {
      companyId,
      companyName: 'Acme Corp',
      companyCountryId: 'company-country-1',
      companyCountryName: 'México',
      companyRegionId: 'region-1',
      companyRegionName: 'Centro',
      companyZoneId: 'zone-1',
      companyZoneName: 'Zona Norte',
    },
  };

  interface SetupOptions {
    countries?: CompanyCountry[];
    countriesError?: HttpErrorResponse;
    regions?: CompanyRegion[];
    regionsError?: HttpErrorResponse;
    zones?: CompanyZone[];
    stores?: CompanyStore[];
    storesError?: HttpErrorResponse;
    createResult?: StoreAssignment;
    createError?: HttpErrorResponse;
    /** What the service's own error signal already holds when createAssignment fails. */
    serviceErrorMessage?: string | null;
  }

  async function setup(options: SetupOptions = {}): Promise<void> {
    dialogRef = { close: vi.fn() };
    assignmentService = {
      createAssignment: vi.fn(() =>
        options.createError
          ? throwError(() => options.createError)
          : of(options.createResult ?? created),
      ),
      error: vi.fn(() => options.serviceErrorMessage ?? null),
    };
    countryService = {
      getCountries: vi.fn(() =>
        options.countriesError
          ? throwError(() => options.countriesError)
          : of(options.countries ?? [country, otherCountry]),
      ),
    };
    regionService = {
      getRegions: vi.fn(() =>
        options.regionsError
          ? throwError(() => options.regionsError)
          : of(options.regions ?? [region, otherRegion]),
      ),
    };
    zoneService = { getZones: vi.fn().mockReturnValue(of(options.zones ?? [zone])) };
    storeService = {
      getStores: vi.fn(() =>
        options.storesError ? throwError(() => options.storesError) : of(options.stores ?? [store]),
      ),
    };

    const data: StoreAssignmentDialogData = { companyId, employeeId: 'emp-1' };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [StoreAssignmentDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: StoreAssignmentService, useValue: assignmentService },
        { provide: CompanyCountryService, useValue: countryService },
        { provide: CompanyRegionService, useValue: regionService },
        { provide: CompanyZoneService, useValue: zoneService },
        { provide: CompanyStoreService, useValue: storeService },
      ],
    });

    fixture = TestBed.createComponent(StoreAssignmentDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function element(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return element().textContent ?? '';
  }

  /** Re-renders after a cascade step or a submit, so the DOM reflects the new state. */
  function settle(): void {
    fixture.detectChanges();
  }

  /** Walks the whole cascade the way the operator does, one level at a time. */
  function chooseCascade(): void {
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.regionId.setValue('region-1');
    component.form.controls.zoneId.setValue('zone-1');
    component.form.controls.companyStoreId.setValue('store-1');
  }

  it('should load the company countries on open, with the company already fixed', async () => {
    await setup();

    expect(countryService.getCountries).toHaveBeenCalledWith(companyId);
    expect(component.countries()).toEqual([country, otherCountry]);
  });

  it('should load the regions of the chosen country from the country join id', async () => {
    await setup();

    component.form.controls.companyCountryId.setValue('company-country-1');

    expect(regionService.getRegions).toHaveBeenCalledWith(companyId, 'company-country-1');
    expect(component.regions()).toEqual([region, otherRegion]);
  });

  it('should load the zones of the chosen region carrying the country join id', async () => {
    await setup();
    component.form.controls.companyCountryId.setValue('company-country-1');

    component.form.controls.regionId.setValue('region-1');

    expect(zoneService.getZones).toHaveBeenCalledWith(companyId, 'company-country-1', 'region-1');
    expect(component.zones()).toEqual([zone]);
  });

  it('should load the stores of the chosen zone (T16)', async () => {
    await setup();
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.regionId.setValue('region-1');

    component.form.controls.zoneId.setValue('zone-1');

    expect(storeService.getStores).toHaveBeenCalledWith(
      companyId,
      'company-country-1',
      'region-1',
      'zone-1',
    );
    expect(component.stores()).toEqual([store]);
  });

  it('should reset every dependent level when the country changes', async () => {
    await setup();
    chooseCascade();
    expect(component.stores()).toEqual([store]);

    component.form.controls.companyCountryId.setValue('company-country-2');

    expect(component.zones()).toEqual([]);
    expect(component.stores()).toEqual([]);
    expect(component.form.controls.regionId.value).toBe('');
    expect(component.form.controls.zoneId.value).toBe('');
    expect(component.form.controls.companyStoreId.value).toBe('');
    // The new country's own regions replace the previous ones.
    expect(regionService.getRegions).toHaveBeenLastCalledWith(companyId, 'company-country-2');
  });

  it('should reset the zone and the store when the region changes', async () => {
    await setup();
    chooseCascade();

    component.form.controls.regionId.setValue('region-2');

    expect(component.stores()).toEqual([]);
    expect(component.form.controls.zoneId.value).toBe('');
    expect(component.form.controls.companyStoreId.value).toBe('');
    expect(component.zones()).toEqual([zone]);
  });

  it('should name the failure when the stores level cannot be read', async () => {
    await setup({ storesError: new HttpErrorResponse({ status: 500 }) });
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.regionId.setValue('region-1');

    component.form.controls.zoneId.setValue('zone-1');
    settle();

    expect(component.stores()).toEqual([]);
    expect(text()).toContain('No se pudieron cargar los niveles de la tienda');
  });

  it('should name the failure when the regions level cannot be read', async () => {
    await setup({ regionsError: new HttpErrorResponse({ status: 500 }) });

    component.form.controls.companyCountryId.setValue('company-country-1');
    settle();

    // Every level funnels its own failure into the same notice, and the form stays usable.
    expect(component.regions()).toEqual([]);
    expect(text()).toContain('No se pudieron cargar los niveles de la tienda');
    expect(component.saving()).toBe(false);
  });

  it('should render the country, region, zone and store controls and a required validFrom date', async () => {
    await setup();

    expect(text()).toContain('País');
    expect(text()).toContain('Región');
    expect(text()).toContain('Zona');
    expect(text()).toContain('Tienda');
    expect(text()).toContain('Vigente desde');
  });

  it('should carry no end-date field at all (T13)', async () => {
    await setup();

    expect(component.form.contains('endDate')).toBe(false);
    expect(element().querySelector('input[formcontrolname="endDate"]')).toBeNull();
  });

  it('should refuse to submit an incomplete form and mark it touched', async () => {
    await setup();

    component.onSubmit();

    expect(assignmentService.createAssignment).not.toHaveBeenCalled();
    expect(component.form.controls.companyStoreId.touched).toBe(true);
    expect(component.form.controls.validFrom.touched).toBe(true);
  });

  it('should POST the store and the first day covered, and close with the created row', async () => {
    await setup();
    chooseCascade();
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();

    expect(assignmentService.createAssignment).toHaveBeenCalledWith(companyId, 'emp-1', {
      companyStoreId: 'store-1',
      validFrom: '2026-01-01',
    });
    expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'created', assignment: created });
  });

  it('should keep the dialog open and show the server envelope message when the write fails', async () => {
    await setup({
      createError: new HttpErrorResponse({
        status: 409,
        error: { message: 'Store assignment is already closed' },
      }),
    });
    chooseCascade();
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();
    settle();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.actionError()).toBe('Store assignment is already closed');
    expect(text()).toContain('Store assignment is already closed');
  });

  it('should fall back to the service message when the failure carries no envelope', async () => {
    await setup({
      createError: new HttpErrorResponse({ status: 409 }),
      serviceErrorMessage: 'Ya existe una asignación en esa tienda que se superpone',
    });
    chooseCascade();
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.actionError()).toBe('Ya existe una asignación en esa tienda que se superpone');
  });

  it('should close without a write when cancelled', async () => {
    await setup();

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
    expect(assignmentService.createAssignment).not.toHaveBeenCalled();
  });

  it('should not submit twice while a write is in flight', async () => {
    await setup();
    // A write that has not come back yet: the guard is only observable while it is pending.
    const pending = new Subject<StoreAssignment>();
    assignmentService.createAssignment.mockReturnValue(pending.asObservable());
    chooseCascade();
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();
    component.onSubmit();

    expect(assignmentService.createAssignment).toHaveBeenCalledTimes(1);
    expect(component.saving()).toBe(true);

    pending.next(created);
    pending.complete();

    expect(component.saving()).toBe(false);
    expect(dialogRef.close).toHaveBeenCalledTimes(1);
  });
});
