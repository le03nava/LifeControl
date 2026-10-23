/// <reference types="vitest/globals" />
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { CompanyCountry } from '@features/companies/countries/models/country.models';
import { CompanyRegion } from '@features/companies/regions/models/region.models';
import { CompanyZone } from '@features/companies/zones/models/zone.models';
import { CompanyCountryService } from '@features/companies/countries/data/company-country.service';
import { CompanyRegionService } from '@features/companies/regions/data/company-region.service';
import { CompanyZoneService } from '@features/companies/zones/data/company-zone.service';
import { LC_COMPANY_COUNTRY, LC_COMPANY_ZONE } from '@core/security/roles';
import {
  ScopeAssignmentDialog,
  ScopeAssignmentDialogData,
  orderedLevels,
  suggestedLevelFromRoles,
} from './scope-assignment-dialog';
import { ScopeAssignmentService } from '../../data/scope-assignment.service';
import { ScopeAssignment } from '../../models/scope-assignment.models';

describe('ScopeAssignmentDialog', () => {
  let fixture: ComponentFixture<ScopeAssignmentDialog>;
  let component: ScopeAssignmentDialog;
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let assignmentService: {
    createAssignment: ReturnType<typeof vi.fn>;
    error: ReturnType<typeof vi.fn>;
  };
  let countryService: { getCountries: ReturnType<typeof vi.fn> };
  let regionService: { getRegions: ReturnType<typeof vi.fn> };
  let zoneService: { getZones: ReturnType<typeof vi.fn> };

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

  const otherZone: CompanyZone = {
    ...zone,
    id: 'zone-2',
    companyRegionId: 'region-2',
    zoneCode: 'ZS',
    zoneName: 'Zona Sur',
  };

  const created: ScopeAssignment = {
    id: 'scope-assignment-1',
    level: 'ZONE',
    companyCountryId: 'company-country-1',
    companyRegionId: 'region-1',
    companyZoneId: 'zone-1',
    nodeName: 'Zona Norte',
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
    zones?: CompanyZone[];
    createResult?: ScopeAssignment;
    createError?: HttpErrorResponse;
    /** The employee's own role names, the hint the dialog orders the level choice with. */
    employeeRoles?: string[];
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
      getRegions: vi.fn().mockReturnValue(of(options.regions ?? [region, otherRegion])),
    };
    zoneService = {
      // Keyed by region: the second region's list differs, so a reload for it is distinguishable
      // from the first region's list surviving in place.
      getZones: vi.fn((_companyId: string, _companyCountryId: string, regionId: string) =>
        of(options.zones ?? (regionId === 'region-2' ? [otherZone] : [zone])),
      ),
    };

    const data: ScopeAssignmentDialogData = {
      companyId,
      companyName: 'Acme Corp',
      employeeId: 'emp-1',
      employeeRoles: options.employeeRoles ?? [],
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ScopeAssignmentDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: ScopeAssignmentService, useValue: assignmentService },
        { provide: CompanyCountryService, useValue: countryService },
        { provide: CompanyRegionService, useValue: regionService },
        { provide: CompanyZoneService, useValue: zoneService },
      ],
    });

    fixture = TestBed.createComponent(ScopeAssignmentDialog);
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

  /** Walks the cascade down to a zone, the way the operator does. */
  function chooseZone(): void {
    component.onLevelChange('ZONE');
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.regionId.setValue('region-1');
    component.form.controls.zoneId.setValue('zone-1');
  }

  it('should load the company countries on open, with the company shown and not chosen', async () => {
    await setup();

    expect(countryService.getCountries).toHaveBeenCalledWith(companyId);
    expect(component.countries()).toEqual([country, otherCountry]);
    expect(text()).toContain('Acme Corp');
    // No company control: the employee's own company is fixed by the screen.
    expect(component.form.contains('companyId')).toBe(false);
  });

  it('should offer the three levels, and the company level is not one of them', async () => {
    await setup();

    expect(component.levels).toEqual(['COUNTRY', 'REGION', 'ZONE']);
  });

  it('should preselect and list first the level the employee highest role suggests', async () => {
    await setup({ employeeRoles: [LC_COMPANY_ZONE] });

    expect(component.level()).toBe('ZONE');
    expect(component.levels).toEqual(['ZONE', 'COUNTRY', 'REGION']);
  });

  it('should still offer every level when the roles suggest one', async () => {
    await setup({ employeeRoles: [LC_COMPANY_COUNTRY] });

    expect(component.levels).toEqual(['COUNTRY', 'REGION', 'ZONE']);
    // The suggestion is an affordance, not a gate: the deeper levels are offered anyway.
    component.onLevelChange('ZONE');
    expect(component.level()).toBe('ZONE');
  });

  it('should offer all three levels with no preselection hint when the employee holds no scoped role', async () => {
    await setup({ employeeRoles: [] });

    expect(component.level()).toBe('COUNTRY');
    expect(component.levels).toEqual(['COUNTRY', 'REGION', 'ZONE']);
  });

  it('suggestedLevelFromRoles should pick the highest scoped role and ignore the rest', () => {
    expect(suggestedLevelFromRoles([LC_COMPANY_COUNTRY, LC_COMPANY_ZONE])).toBe('COUNTRY');
    expect(suggestedLevelFromRoles([LC_COMPANY_ZONE])).toBe('ZONE');
    // `lc-company` bounds by the company claim: an assignment is not what it needs.
    expect(suggestedLevelFromRoles(['lc-company', 'lc-admin'])).toBeNull();
    expect(orderedLevels(null)).toEqual(['COUNTRY', 'REGION', 'ZONE']);
  });

  it('should load the regions of the chosen country from the country join id', async () => {
    await setup();
    component.onLevelChange('REGION');

    component.form.controls.companyCountryId.setValue('company-country-1');

    expect(regionService.getRegions).toHaveBeenCalledWith(companyId, 'company-country-1');
    expect(component.regions()).toEqual([region, otherRegion]);
  });

  it('should load the zones of the chosen region carrying the country join id', async () => {
    await setup();
    component.onLevelChange('ZONE');
    component.form.controls.companyCountryId.setValue('company-country-1');

    component.form.controls.regionId.setValue('region-1');

    expect(zoneService.getZones).toHaveBeenCalledWith(companyId, 'company-country-1', 'region-1');
    expect(component.zones()).toEqual([zone]);
  });

  it('should hide the region and zone controls for a country assignment', async () => {
    await setup();
    settle();

    expect(component.form.controls.regionId.hasError('required')).toBe(false);
    expect(element().querySelector('mat-select[formcontrolname="regionId"]')).toBeNull();
    expect(element().querySelector('mat-select[formcontrolname="zoneId"]')).toBeNull();
  });

  it('should require the region but not the zone for a region assignment', async () => {
    await setup();
    component.onLevelChange('REGION');
    settle();

    expect(component.form.controls.regionId.hasError('required')).toBe(true);
    expect(component.form.controls.zoneId.hasError('required')).toBe(false);
    expect(element().querySelector('mat-select[formcontrolname="zoneId"]')).toBeNull();
  });

  it('should require the zone for a zone assignment', async () => {
    await setup();
    component.onLevelChange('ZONE');
    settle();

    expect(component.form.controls.zoneId.hasError('required')).toBe(true);
    expect(element().querySelector('mat-select[formcontrolname="zoneId"]')).not.toBeNull();
  });

  it('should reset every dependent level when the country changes', async () => {
    await setup();
    chooseZone();
    expect(component.zones()).toEqual([zone]);

    component.form.controls.companyCountryId.setValue('company-country-2');

    expect(component.zones()).toEqual([]);
    expect(component.form.controls.regionId.value).toBe('');
    expect(component.form.controls.zoneId.value).toBe('');
    expect(regionService.getRegions).toHaveBeenLastCalledWith(companyId, 'company-country-2');
  });

  it('should reset the zone when the region changes', async () => {
    await setup();
    chooseZone();
    expect(component.zones()).toEqual([zone]);

    component.form.controls.regionId.setValue('region-2');

    // The new region's own zones replace the previous ones, so a zone list that survived in place
    // cannot satisfy this.
    expect(component.zones()).toEqual([otherZone]);
    expect(component.form.controls.zoneId.value).toBe('');
  });

  it('should clear the level and name the failure when the zones level cannot be read', async () => {
    await setup();
    component.onLevelChange('ZONE');
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.regionId.setValue('region-1');
    expect(component.zones()).toEqual([zone]);

    // The same level now fails, so the previous list has a value it must not survive as.
    zoneService.getZones.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    component.form.controls.regionId.setValue('region-2');
    settle();

    expect(component.zones()).toEqual([]);
    expect(text()).toContain('No se pudieron cargar los niveles del alcance');
  });

  it('should refuse to submit an incomplete form and mark it touched', async () => {
    await setup();
    component.onLevelChange('ZONE');

    component.onSubmit();

    expect(assignmentService.createAssignment).not.toHaveBeenCalled();
    expect(component.form.controls.companyCountryId.touched).toBe(true);
    expect(component.form.controls.zoneId.touched).toBe(true);
    expect(component.form.controls.validFrom.touched).toBe(true);
  });

  it('should POST only the country id for a country assignment', async () => {
    await setup();
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();

    expect(assignmentService.createAssignment).toHaveBeenCalledWith(companyId, 'emp-1', {
      companyCountryId: 'company-country-1',
      validFrom: '2026-01-01',
    });
    expect(dialogRef.close).toHaveBeenCalledWith({ outcome: 'created', assignment: created });
  });

  it('should POST only the region id for a region assignment', async () => {
    await setup();
    component.onLevelChange('REGION');
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.regionId.setValue('region-1');
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();

    expect(assignmentService.createAssignment).toHaveBeenCalledWith(companyId, 'emp-1', {
      companyRegionId: 'region-1',
      validFrom: '2026-01-01',
    });
  });

  it('should POST only the zone id for a zone assignment, with no leftover ancestor ids', async () => {
    await setup();
    chooseZone();
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();

    const [, , request] = assignmentService.createAssignment.mock.calls[0] as [
      string,
      string,
      Record<string, unknown>,
    ];
    expect(request).toEqual({ companyZoneId: 'zone-1', validFrom: '2026-01-01' });
    expect(request['companyCountryId']).toBeUndefined();
    expect(request['companyRegionId']).toBeUndefined();
  });

  it('should keep the dialog open and show the server envelope message when the write fails', async () => {
    await setup({
      createError: new HttpErrorResponse({
        status: 409,
        error: { message: 'Scope assignment overlaps an existing one' },
      }),
    });
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();
    settle();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.actionError()).toBe('Scope assignment overlaps an existing one');
    expect(text()).toContain('Scope assignment overlaps an existing one');
  });

  it('should fall back to the service message when the failure carries no envelope', async () => {
    await setup({
      createError: new HttpErrorResponse({ status: 409 }),
      serviceErrorMessage: 'Ya existe una asignación de ese nodo que se superpone',
    });
    component.form.controls.companyCountryId.setValue('company-country-1');
    component.form.controls.validFrom.setValue('2026-01-01');

    component.onSubmit();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(component.actionError()).toBe('Ya existe una asignación de ese nodo que se superpone');
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
    const pending = new Subject<ScopeAssignment>();
    assignmentService.createAssignment.mockReturnValue(pending.asObservable());
    component.form.controls.companyCountryId.setValue('company-country-1');
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

  it('should carry no end-date field at all, because only the close route ends an assignment', async () => {
    await setup();

    expect(component.form.contains('endDate')).toBe(false);
    expect(element().querySelector('input[formcontrolname="endDate"]')).toBeNull();
  });
});
