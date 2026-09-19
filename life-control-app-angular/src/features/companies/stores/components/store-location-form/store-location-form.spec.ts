/// <reference types="vitest/globals" />
import { ComponentFixture } from '@angular/core/testing';
import { StoreLocationForm } from './store-location-form';
import {
  CreateStoreLocationRequest,
  StoreLocation,
  UpdateStoreLocationRequest,
} from '../../models/store-location.models';
import {
  createLeafFormFixture,
  LeafFormHarness,
  runLeafFormBehavioralSuite,
} from '../leaf-form-spec-helpers';

type LocationRequest = CreateStoreLocationRequest | UpdateStoreLocationRequest;

describe('StoreLocationForm', () => {
  const mockStoreLocation: StoreLocation = {
    id: 'store-location-1',
    storeZoneId: 'store-zone-1',
    storeAreaId: 'area-1',
    companyStoreId: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    locationCode: 'EST-01',
    locationName: 'Estante 01',
    description: 'Estante de almacenamiento seco',
    displayOrder: 2,
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  // Shared behavior lives in leaf-form-spec-helpers: one suite, three descriptors.
  runLeafFormBehavioralSuite<LocationRequest>({
    formName: 'StoreLocationForm',
    componentClass: StoreLocationForm,
    entityInput: 'storeLocation',
    codeKey: 'locationCode',
    nameKey: 'locationName',
    entity: mockStoreLocation,
    entityWithoutOptionals: { ...mockStoreLocation, description: null, displayOrder: null },
    expectedPatched: {
      code: mockStoreLocation.locationCode,
      name: mockStoreLocation.locationName,
      description: mockStoreLocation.description,
      displayOrder: mockStoreLocation.displayOrder,
    },
    expectedTrimmedRequest: { locationCode: 'EST-01', locationName: 'Estante' },
    expectedRequest: {
      locationCode: 'EST-01',
      locationName: 'Estante',
      description: 'Planta baja',
      displayOrder: 0,
    },
  });

  // ─── Component-specific config (keys + copy) ───────────────
  describe('component config', () => {
    let fixture: ComponentFixture<LeafFormHarness<LocationRequest>>;

    beforeEach(async () => {
      ({ fixture } = await createLeafFormFixture<LocationRequest>(StoreLocationForm));
    });

    it('should declare the four location controls in order', () => {
      expect(Object.keys(fixture.componentInstance.formGroup.controls)).toEqual([
        'locationCode',
        'locationName',
        'description',
        'displayOrder',
      ]);
    });

    it('should render "Nueva Ubicación" in create mode', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Nueva Ubicación');
    });

    it('should render "Editar Ubicación" in edit mode', () => {
      fixture.componentRef.setInput('storeLocation', mockStoreLocation);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar Ubicación');
    });

    it('should render the submit label per mode', () => {
      let submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn.textContent?.trim()).toBe('Guardar');

      fixture.componentRef.setInput('storeLocation', mockStoreLocation);
      fixture.detectChanges();
      submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn.textContent?.trim()).toBe('Actualizar');
    });

    it('should render the unified "Código" / "Nombre" / "Orden" labels', () => {
      const labels = Array.from(
        fixture.nativeElement.querySelectorAll('mat-label') as NodeListOf<Element>,
      ).map((label) => label.textContent?.trim());

      expect(labels).toEqual(['Código', 'Nombre', 'Descripción', 'Orden']);
    });
  });
});
