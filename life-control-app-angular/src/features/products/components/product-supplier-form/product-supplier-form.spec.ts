import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { AbstractControl, FormControl, FormGroup, Validators } from '@angular/forms';
import { ProductSupplierForm } from './product-supplier-form';
import {
  ProductSupplierControl,
  ProductSupplierRequest,
} from '../../models/product-supplier.models';

/** The copy map is protected; the spec reaches it through this narrow cast. */
interface ErrorMessageAccess {
  getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: unknown) => string>,
  ): string | null;
}

describe('ProductSupplierForm', () => {
  let component: ProductSupplierForm;
  let fixture: ComponentFixture<ProductSupplierForm>;

  function createFormGroup(): FormGroup<ProductSupplierControl> {
    return new FormGroup<ProductSupplierControl>({
      id: new FormControl('', { nonNullable: true }),
      supplierId: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      purchaseCost: new FormControl(0, {
        nonNullable: true,
        validators: [Validators.required, Validators.min(0)],
      }),
      main: new FormControl(false, { nonNullable: true }),
      enabled: new FormControl(true, { nonNullable: true }),
    });
  }

  function getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: unknown) => string>,
  ): string | null {
    return (component as unknown as ErrorMessageAccess).getErrorMessage(control, customMessages);
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductSupplierForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductSupplierForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', createFormGroup());
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('rendered copy', () => {
    it('should render the create-mode heading and submit label in voseo', () => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('h2')?.textContent?.trim()).toBe('Asignar un proveedor al producto');
      expect(el.querySelector('button[type="submit"]')?.textContent?.trim()).toBe(
        'Asignar proveedor',
      );
    });

    it('should switch the heading and submit label in edit mode', () => {
      fixture.componentRef.setInput('editMode', true);
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('h2')?.textContent?.trim()).toBe(
        'Editar la asignación del proveedor',
      );
      expect(el.querySelector('button[type="submit"]')?.textContent?.trim()).toBe(
        'Actualizar asignación',
      );
    });

    it('should render the field labels', () => {
      const labels = Array.from(
        fixture.nativeElement.querySelectorAll('mat-label') as NodeListOf<Element>,
      ).map((label) => label.textContent?.trim());

      expect(labels).toEqual(['Proveedor', 'Costo de compra']);
    });

    it('should render the toggle labels', () => {
      const toggles = Array.from(
        fixture.nativeElement.querySelectorAll('mat-slide-toggle') as NodeListOf<Element>,
      ).map((toggle) => toggle.textContent?.trim());

      expect(toggles).toEqual(['Proveedor principal', 'Habilitado']);
    });

    it('should render the cancel button', () => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.form-actions button[type="button"]')?.textContent?.trim()).toBe(
        'Cancelar',
      );
    });
  });

  describe('defaultErrorMessages', () => {
    it('should map required to the shared message', () => {
      expect(component.defaultErrorMessages['required'](true)).toBe('Este campo es obligatorio.');
    });

    it('should map min to a message carrying the bound value', () => {
      expect(component.defaultErrorMessages['min']({ min: 5 })).toBe('El valor mínimo es 5.');
    });

    it('should pass a server error value through unchanged', () => {
      expect(component.defaultErrorMessages['serverError']('No puede ser negativo.')).toBe(
        'No puede ser negativo.',
      );
    });
  });

  describe('getErrorMessage', () => {
    it('should return null for an untouched control', () => {
      const control = component.formGroup().controls.supplierId;
      control.setErrors({ required: true });

      expect(getErrorMessage(control)).toBeNull();
    });

    it('should return null for a control without errors', () => {
      expect(getErrorMessage(component.formGroup().controls.purchaseCost)).toBeNull();
    });

    it('should map a touched required control', () => {
      const control = component.formGroup().controls.supplierId;
      control.setErrors({ required: true });
      control.markAsTouched();

      expect(getErrorMessage(control)).toBe('Este campo es obligatorio.');
    });

    it('should fall back for a touched control with an unmapped error', () => {
      const control = component.formGroup().controls.supplierId;
      control.setErrors({ unmapped: true });
      control.markAsTouched();

      expect(getErrorMessage(control)).toBe('Campo inválido.');
    });

    it('should let custom messages override the default for the same key', () => {
      const control = component.formGroup().controls.supplierId;
      control.setErrors({ required: true });
      control.markAsTouched();

      expect(getErrorMessage(control, { required: () => 'Mensaje propio.' })).toBe(
        'Mensaje propio.',
      );
    });
  });

  describe('serverErrors', () => {
    it('should apply a server error to the matching control', () => {
      fixture.componentRef.setInput('serverErrors', { purchaseCost: 'No puede ser negativo.' });
      fixture.detectChanges();

      expect(component.formGroup().controls.purchaseCost.errors?.['serverError']).toBe(
        'No puede ser negativo.',
      );
    });

    it('should not keep a stale server error once the value changes', () => {
      const control = component.formGroup().controls.purchaseCost;
      control.setValue(-1);

      fixture.componentRef.setInput('serverErrors', { purchaseCost: 'No puede ser negativo.' });
      fixture.detectChanges();

      expect(control.errors?.['serverError']).toBe('No puede ser negativo.');
      expect(control.errors?.['min']).toBeDefined();

      control.setValue(-5);
      fixture.detectChanges();

      // This pins the observable contract, not the mechanism: Angular recomputes
      // `errors` from the validators before it emits `valueChanges`, so the
      // component's cleanup subscription cannot be the thing that removes the
      // entry. What must hold is that the stale server message does not survive
      // an edit, and that the validator error does.
      expect(control.errors?.['serverError']).toBeUndefined();
      expect(control.errors?.['min']).toBeDefined();
    });

    it('should not throw for a key with no matching control', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

      expect(() => {
        fixture.componentRef.setInput('serverErrors', { nonexistent: 'No existe' });
        fixture.detectChanges();
      }).not.toThrow();

      warnSpy.mockRestore();
    });
  });

  describe('onSave', () => {
    it('should emit saveSupplier with the request fields when the form is valid', () => {
      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      component.formGroup().patchValue({
        supplierId: 'sup-1',
        purchaseCost: 12.5,
        main: true,
        enabled: false,
      });
      fixture.detectChanges();

      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      const emitted = spy.mock.calls[0][0] as ProductSupplierRequest;
      expect(emitted).toEqual({
        supplierId: 'sup-1',
        purchaseCost: 12.5,
        main: true,
        enabled: false,
      });
    });

    it('should mark every control as touched and emit nothing when the form is invalid', () => {
      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      component.onSave();

      expect(spy).not.toHaveBeenCalled();
      expect(component.formGroup().controls.supplierId.touched).toBe(true);
      expect(component.formGroup().controls.purchaseCost.touched).toBe(true);
    });
  });

  it('should emit cancelForm when cancelled', () => {
    const spy = vi.fn();
    component.cancelForm.subscribe(spy);

    component.onCancel();

    expect(spy).toHaveBeenCalledTimes(1);
  });
});
