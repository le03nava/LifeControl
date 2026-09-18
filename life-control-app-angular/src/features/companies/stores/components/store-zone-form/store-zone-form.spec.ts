import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { StoreZoneForm } from './store-zone-form';
import {
  CreateStoreZoneRequest,
  StoreZone,
  UpdateStoreZoneRequest,
} from '../../models/store-zone.models';

describe('StoreZoneForm', () => {
  let component: StoreZoneForm;
  let fixture: ComponentFixture<StoreZoneForm>;

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
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StoreZoneForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreZoneForm);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─── Mode ─────────────────────────────────────────────────
  describe('mode', () => {
    it('should render "Nueva Zona" in create mode', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Nueva Zona');
      expect(component.isEditMode()).toBe(false);
    });

    it('should switch to edit mode when a store zone is provided', () => {
      fixture.componentRef.setInput('storeZone', mockZone);
      fixture.detectChanges();

      expect(component.isEditMode()).toBe(true);
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

    it('should patch the form from the store zone input', () => {
      fixture.componentRef.setInput('storeZone', mockZone);
      fixture.detectChanges();

      expect(component.formGroup.controls.zoneCode.value).toBe('SECO');
      expect(component.formGroup.controls.zoneName.value).toBe('Depósito Seco');
      expect(component.formGroup.controls.description.value).toBe('Zona de almacenamiento seco');
      expect(component.formGroup.controls.displayOrder.value).toBe(2);
    });

    it('should patch nullable fields as null when the store zone has none', () => {
      fixture.componentRef.setInput('storeZone', {
        ...mockZone,
        description: null,
        displayOrder: null,
      });
      fixture.detectChanges();

      expect(component.formGroup.controls.description.value).toBeNull();
      expect(component.formGroup.controls.displayOrder.value).toBeNull();
    });
  });

  // ─── Validation ───────────────────────────────────────────
  describe('validation', () => {
    it('should require zoneCode and zoneName', () => {
      expect(component.formGroup.controls.zoneCode.errors?.['required']).toBeTruthy();
      expect(component.formGroup.controls.zoneName.errors?.['required']).toBeTruthy();
    });

    it('should enforce maxLength 10 on zoneCode', () => {
      const control = component.formGroup.controls.zoneCode;
      control.setValue('A'.repeat(11));
      expect(control.errors?.['maxlength']).toBeTruthy();

      control.setValue('A'.repeat(10));
      expect(control.errors).toBeNull();
    });

    it('should enforce maxLength 100 on zoneName', () => {
      const control = component.formGroup.controls.zoneName;
      control.setValue('A'.repeat(101));
      expect(control.errors?.['maxlength']).toBeTruthy();

      control.setValue('A'.repeat(100));
      expect(control.errors).toBeNull();
    });

    it('should enforce maxLength 255 on description', () => {
      const control = component.formGroup.controls.description;
      control.setValue('A'.repeat(256));
      expect(control.errors?.['maxlength']).toBeTruthy();

      control.setValue('A'.repeat(255));
      expect(control.errors).toBeNull();
    });

    it('should enforce min 0 on displayOrder', () => {
      const control = component.formGroup.controls.displayOrder;
      control.setValue(-1);
      expect(control.errors?.['min']).toBeTruthy();

      control.setValue(0);
      expect(control.errors).toBeNull();
    });

    it('should render the Spanish required message', () => {
      const control = component.formGroup.controls.zoneName;
      control.markAsTouched();
      control.setErrors({ required: true });
      fixture.detectChanges();

      const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error'));
      expect(errors.some((e) => (e as Element).textContent?.includes('obligatorio'))).toBe(true);
    });

    it('should render the Spanish maxlength message', () => {
      const control = component.formGroup.controls.zoneCode;
      control.setValue('A'.repeat(11));
      control.markAsTouched();
      fixture.detectChanges();

      const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error'));
      expect(
        errors.some((e) => (e as Element).textContent?.includes('No puede superar los 10')),
      ).toBe(true);
    });

    it('should render the Spanish min message', () => {
      const control = component.formGroup.controls.displayOrder;
      control.setValue(-5);
      control.markAsTouched();
      fixture.detectChanges();

      const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error'));
      expect(errors.some((e) => (e as Element).textContent?.includes('mínimo'))).toBe(true);
    });
  });

  // ─── Save ─────────────────────────────────────────────────
  describe('onSave', () => {
    it('should NOT emit when the form is invalid and should mark all as touched', () => {
      let emitted = false;
      component.save.subscribe(() => {
        emitted = true;
      });

      component.onSave();

      expect(emitted).toBe(false);
      expect(component.formGroup.controls.zoneCode.touched).toBe(true);
      expect(component.formGroup.controls.zoneName.touched).toBe(true);
    });

    it('should emit trimmed values and omit empty optionals', () => {
      component.formGroup.patchValue({
        zoneCode: '  SECO  ',
        zoneName: '  Depósito  ',
        description: '   ',
        displayOrder: null,
      });

      let emitted: CreateStoreZoneRequest | UpdateStoreZoneRequest | undefined;
      component.save.subscribe((ev) => {
        emitted = ev;
      });

      component.onSave();

      expect(emitted).toEqual({ zoneCode: 'SECO', zoneName: 'Depósito' });
      expect('description' in emitted!).toBe(false);
      expect('displayOrder' in emitted!).toBe(false);
    });

    it('should include description and displayOrder when present', () => {
      component.formGroup.patchValue({
        zoneCode: 'SECO',
        zoneName: 'Depósito',
        description: '  Planta baja  ',
        displayOrder: 0,
      });
      let emitted: CreateStoreZoneRequest | UpdateStoreZoneRequest | undefined;
      component.save.subscribe((ev) => {
        emitted = ev;
      });

      component.onSave();

      // 0 is a valid display order and must not be dropped as "empty".
      expect(emitted).toEqual({
        zoneCode: 'SECO',
        zoneName: 'Depósito',
        description: 'Planta baja',
        displayOrder: 0,
      });
    });
  });

  // ─── Cancel ───────────────────────────────────────────────
  describe('onCancel', () => {
    it('should emit cancelForm', () => {
      let emitted = false;
      component.cancelForm.subscribe(() => {
        emitted = true;
      });

      component.onCancel();
      expect(emitted).toBe(true);
    });
  });

  // ─── Server errors ────────────────────────────────────────
  describe('serverErrors', () => {
    it('should map server errors onto controls and clear them on value change', () => {
      component.formGroup.controls.zoneCode.setValue('SECO');
      component.formGroup.controls.zoneName.setValue('Depósito');

      fixture.componentRef.setInput('serverErrors', { zoneCode: 'Código duplicado' });
      fixture.detectChanges();

      const control = component.formGroup.controls.zoneCode;
      expect(control.errors?.['serverError']).toBe('Código duplicado');

      control.setValue('SECO-2');
      expect(control.errors?.['serverError']).toBeUndefined();
    });

    it('should keep other validation errors when clearing the server error', () => {
      const control = component.formGroup.controls.zoneName;
      fixture.componentRef.setInput('serverErrors', { zoneName: 'Nombre repetido' });
      fixture.detectChanges();

      // Clear the value: `required` stays, `serverError` goes away.
      control.setValue('');
      expect(control.errors?.['required']).toBeTruthy();
      expect(control.errors?.['serverError']).toBeUndefined();
    });

    it('should render the server error message through mat-error', () => {
      component.formGroup.controls.zoneName.setValue('Depósito');
      component.formGroup.controls.zoneName.markAsTouched();
      fixture.componentRef.setInput('serverErrors', { zoneName: 'Nombre repetido' });
      fixture.detectChanges();

      const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error'));
      expect(errors.some((e) => (e as Element).textContent?.includes('Nombre repetido'))).toBe(
        true,
      );
    });

    it('should ignore unknown server error keys', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

      fixture.componentRef.setInput('serverErrors', { unknownField: 'boom' });
      fixture.detectChanges();

      expect(warnSpy).toHaveBeenCalled();
      warnSpy.mockRestore();
    });
  });
});
