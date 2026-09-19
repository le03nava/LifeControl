/// <reference types="vitest/globals" />
import { ComponentFixture } from '@angular/core/testing';
import { StoreAreaForm } from './store-area-form';
import {
  CreateStoreAreaRequest,
  StoreArea,
  UpdateStoreAreaRequest,
} from '../../models/store-area.models';
import {
  createLeafFormFixture,
  LeafFormHarness,
  runLeafFormBehavioralSuite,
} from '../leaf-form-spec-helpers';

type AreaRequest = CreateStoreAreaRequest | UpdateStoreAreaRequest;

describe('StoreAreaForm', () => {
  const mockArea: StoreArea = {
    id: 'area-1',
    companyStoreId: 'store-1',
    companyId: 'company-1',
    companyCountryId: 'cc-1',
    regionId: 'reg-1',
    zoneId: 'zone-1',
    areaCode: 'ALMACEN',
    areaName: 'Almacén Central',
    description: 'Depósito principal',
    displayOrder: 2,
    enabled: true,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-15T00:00:00Z',
  };

  // Shared behavior lives in leaf-form-spec-helpers: one suite, three descriptors.
  runLeafFormBehavioralSuite<AreaRequest>({
    formName: 'StoreAreaForm',
    componentClass: StoreAreaForm,
    entityInput: 'area',
    codeKey: 'areaCode',
    nameKey: 'areaName',
    entity: mockArea,
    entityWithoutOptionals: { ...mockArea, description: null, displayOrder: null },
    expectedPatched: {
      code: mockArea.areaCode,
      name: mockArea.areaName,
      description: mockArea.description,
      displayOrder: mockArea.displayOrder,
    },
    expectedTrimmedRequest: { areaCode: 'DEPO', areaName: 'Depósito' },
    expectedRequest: {
      areaCode: 'DEPOSITO',
      areaName: 'Depósito',
      description: 'Planta baja',
      displayOrder: 0,
    },
  });

  // ─── Component-specific config (keys + copy) ───────────────
  describe('component config', () => {
    let fixture: ComponentFixture<LeafFormHarness<AreaRequest>>;

    beforeEach(async () => {
      ({ fixture } = await createLeafFormFixture<AreaRequest>(StoreAreaForm));
    });

    it('should declare the four area controls in order', () => {
      expect(Object.keys(fixture.componentInstance.formGroup.controls)).toEqual([
        'areaCode',
        'areaName',
        'description',
        'displayOrder',
      ]);
    });

    it('should render "Nueva Área" in create mode', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Nueva Área');
    });

    it('should render "Editar Área" in edit mode', () => {
      fixture.componentRef.setInput('area', mockArea);
      fixture.detectChanges();

      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Editar Área');
    });

    it('should render the submit label per mode', () => {
      let submitBtn = fixture.nativeElement.querySelector('button[type="submit"]');
      expect(submitBtn.textContent?.trim()).toBe('Guardar');

      fixture.componentRef.setInput('area', mockArea);
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
