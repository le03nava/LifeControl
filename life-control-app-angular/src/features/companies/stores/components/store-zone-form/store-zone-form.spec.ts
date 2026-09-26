/// <reference types="vitest/globals" />
import { ComponentFixture } from '@angular/core/testing';
import { StoreZoneForm } from './store-zone-form';
import {
  CreateStoreZoneRequest,
  StoreZone,
  UpdateStoreZoneRequest,
} from '../../models/store-zone.models';
import {
  createLeafFormFixture,
  LeafFormHarness,
  runLeafFormBehavioralSuite,
} from '../leaf-form-spec-helpers';

type ZoneRequest = CreateStoreZoneRequest | UpdateStoreZoneRequest;

describe('StoreZoneForm', () => {
  const mockZone: StoreZone = {
    id: 'store-zone-1',
    storeAreaId: 'area-1',
    companyStoreId: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    zoneCode: 'SECO',
    zoneName: 'Depósito Seco',
    description: 'Zona de almacenamiento seco',
    displayOrder: 2,
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
    version: 1,
  };

  // Shared behavior lives in leaf-form-spec-helpers: one suite, three descriptors.
  runLeafFormBehavioralSuite<ZoneRequest>({
    formName: 'StoreZoneForm',
    componentClass: StoreZoneForm,
    entityInput: 'storeZone',
    codeKey: 'zoneCode',
    nameKey: 'zoneName',
    entity: mockZone,
    entityWithoutOptionals: { ...mockZone, description: null, displayOrder: null },
    expectedPatched: {
      code: mockZone.zoneCode,
      name: mockZone.zoneName,
      description: mockZone.description,
      displayOrder: mockZone.displayOrder,
    },
    expectedTrimmedRequest: { zoneCode: 'SECO', zoneName: 'Depósito' },
    expectedRequest: {
      zoneCode: 'SECO',
      zoneName: 'Depósito',
      description: 'Planta baja',
      displayOrder: 0,
    },
  });

  // ─── Component-specific config (keys + copy) ───────────────
  describe('component config', () => {
    let fixture: ComponentFixture<LeafFormHarness<ZoneRequest>>;

    beforeEach(async () => {
      ({ fixture } = await createLeafFormFixture<ZoneRequest>(StoreZoneForm));
    });

    it('should declare the four zone controls in order', () => {
      expect(Object.keys(fixture.componentInstance.formGroup.controls)).toEqual([
        'zoneCode',
        'zoneName',
        'description',
        'displayOrder',
      ]);
    });

    it('should render "Nueva Zona" in create mode', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Nueva Zona');
    });

    it('should render "Editar Zona" in edit mode', () => {
      fixture.componentRef.setInput('storeZone', mockZone);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar Zona');
    });

    it('should render the submit label per mode', () => {
      let submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn.textContent?.trim()).toBe('Guardar');

      fixture.componentRef.setInput('storeZone', mockZone);
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
