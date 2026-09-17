import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { StoreAreaForm } from './store-area-form';
import {
  CreateStoreAreaRequest,
  StoreArea,
  UpdateStoreAreaRequest,
} from '../../models/store-area.models';

describe('StoreAreaForm', () => {
  let component: StoreAreaForm;
  let fixture: ComponentFixture<StoreAreaForm>;

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

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StoreAreaForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(StoreAreaForm);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─── Mode ─────────────────────────────────────────────────
  describe('mode', () => {
    it('should render "Nueva Área" in create mode', () => {
      const title = fixture.nativeElement.querySelector('h2');
      expect(title.textContent).toContain('Nueva Área');
      expect(component.isEditMode()).toBe(false);
    });

    it('should switch to edit mode when an area is provided', () => {
      fixture.componentRef.setInput('area', mockArea);
      fixture.detectChanges();

      expect(component.isEditMode()).toBe(true);
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

    it('should patch the form from the area input', () => {
      fixture.componentRef.setInput('area', mockArea);
      fixture.detectChanges();

      expect(component.formGroup.controls.areaCode.value).toBe('ALMACEN');
      expect(component.formGroup.controls.areaName.value).toBe('Almacén Central');
      expect(component.formGroup.controls.description.value).toBe('Depósito principal');
      expect(component.formGroup.controls.displayOrder.value).toBe(2);
    });

    it('should patch nullable fields as null when the area has none', () => {
      fixture.componentRef.setInput('area', {
        ...mockArea,
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
    it('should require areaCode and areaName', () => {
      expect(component.formGroup.controls.areaCode.errors?.['required']).toBeTruthy();
      expect(component.formGroup.controls.areaName.errors?.['required']).toBeTruthy();
    });

    it('should enforce maxLength 10 on areaCode', () => {
      const control = component.formGroup.controls.areaCode;
      control.setValue('A'.repeat(11));
      expect(control.errors?.['maxlength']).toBeTruthy();

      control.setValue('A'.repeat(10));
      expect(control.errors).toBeNull();
    });

    it('should enforce maxLength 100 on areaName', () => {
      const control = component.formGroup.controls.areaName;
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
      const control = component.formGroup.controls.areaName;
      control.markAsTouched();
      control.setErrors({ required: true });
      fixture.detectChanges();

      const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error'));
      expect(errors.some((e) => (e as Element).textContent?.includes('obligatorio'))).toBe(true);
    });

    it('should render the Spanish maxlength message', () => {
      const control = component.formGroup.controls.areaCode;
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
      expect(component.formGroup.controls.areaCode.touched).toBe(true);
      expect(component.formGroup.controls.areaName.touched).toBe(true);
    });

    it('should emit trimmed values and omit empty optionals', () => {
      component.formGroup.patchValue({
        areaCode: '  DEPO  ',
        areaName: '  Depósito  ',
        description: '   ',
        displayOrder: null,
      });

      let emitted: CreateStoreAreaRequest | UpdateStoreAreaRequest | undefined;
      component.save.subscribe((ev) => {
        emitted = ev;
      });

      component.onSave();

      expect(emitted).toEqual({ areaCode: 'DEPO', areaName: 'Depósito' });
      expect('description' in emitted!).toBe(false);
      expect('displayOrder' in emitted!).toBe(false);
    });

    it('should include description and displayOrder when present', () => {
      component.formGroup.patchValue({
        areaCode: 'DEPOSITO',
        areaName: 'Depósito',
        description: '  Planta baja  ',
        displayOrder: 0,
      });
      let emitted: CreateStoreAreaRequest | UpdateStoreAreaRequest | undefined;
      component.save.subscribe((ev) => {
        emitted = ev;
      });

      component.onSave();

      // 0 is a valid display order and must not be dropped as "empty".
      expect(emitted).toEqual({
        areaCode: 'DEPOSITO',
        areaName: 'Depósito',
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
      component.formGroup.controls.areaCode.setValue('ALMACEN');
      component.formGroup.controls.areaName.setValue('Almacén');

      fixture.componentRef.setInput('serverErrors', { areaCode: 'Código duplicado' });
      fixture.detectChanges();

      const control = component.formGroup.controls.areaCode;
      expect(control.errors?.['serverError']).toBe('Código duplicado');

      control.setValue('ALMACEN-2');
      expect(control.errors?.['serverError']).toBeUndefined();
    });

    it('should keep other validation errors when clearing the server error', () => {
      const control = component.formGroup.controls.areaName;
      fixture.componentRef.setInput('serverErrors', { areaName: 'Nombre repetido' });
      fixture.detectChanges();

      // Clear the value: `required` stays, `serverError` goes away.
      control.setValue('');
      expect(control.errors?.['required']).toBeTruthy();
      expect(control.errors?.['serverError']).toBeUndefined();
    });

    it('should render the server error message through mat-error', () => {
      component.formGroup.controls.areaName.setValue('Almacén');
      component.formGroup.controls.areaName.markAsTouched();
      fixture.componentRef.setInput('serverErrors', { areaName: 'Nombre repetido' });
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
