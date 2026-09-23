import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { AbstractControl, FormControl, FormGroup, Validators } from '@angular/forms';
import { MatAutocompleteTrigger } from '@angular/material/autocomplete';
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

  /** The supplier input, distinguished from the numeric purchase-cost input. */
  function supplierInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('input[type="text"]') as HTMLInputElement;
  }

  /** Opens the autocomplete overlay so its projected options are in the DOM. */
  function openPanel(): void {
    fixture.debugElement
      .query(By.directive(MatAutocompleteTrigger))
      .injector.get(MatAutocompleteTrigger)
      .openPanel();
    fixture.detectChanges();
  }

  /** The visible option labels inside the open autocomplete panel. */
  function panelOptionTexts(): string[] {
    return Array.from(
      document.querySelectorAll('.mat-mdc-autocomplete-panel mat-option') as NodeListOf<Element>,
    ).map((option) => option.textContent?.trim() ?? '');
  }

  function panelOptions(): Element[] {
    return Array.from(
      document.querySelectorAll('.mat-mdc-autocomplete-panel mat-option') as NodeListOf<Element>,
    );
  }

  describe('rendered copy', () => {
    it('should render the submit label in voseo', () => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('button[type="submit"]')?.textContent?.trim()).toBe(
        'Asignar proveedor',
      );
    });

    it('should switch the submit label in edit mode', () => {
      fixture.componentRef.setInput('editMode', true);
      fixture.detectChanges();

      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('button[type="submit"]')?.textContent?.trim()).toBe(
        'Actualizar asignación',
      );
    });

    it('should not render its own heading: the hosting dialog owns the title', () => {
      expect(fixture.nativeElement.querySelector('h2')).toBeNull();
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

      // The picker only accepts an id it actually offered, so the option list has to
      // contain the selected supplier for the form to be submittable (D23).
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
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

  describe('supplier picker', () => {
    it('should render an autocomplete input instead of a mat-select', () => {
      expect(supplierInput()).toBeTruthy();
      expect(fixture.nativeElement.querySelector('mat-select')).toBeNull();
    });

    it('should emit supplierSearch with the term typed into the input', () => {
      const spy = vi.fn();
      component.supplierSearch.subscribe(spy);

      const input = supplierInput();
      input.value = 'prove';
      input.dispatchEvent(new Event('input'));

      expect(spy).toHaveBeenCalledWith('prove');
    });

    it('should render the options it is given as a non-empty list', () => {
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
        { id: 'sup-2', supplierName: 'Proveedor Dos' },
      ]);
      fixture.detectChanges();

      openPanel();

      expect(panelOptionTexts()).toEqual(['Proveedor Uno', 'Proveedor Dos']);
    });

    it('should show a non-selectable searching row while searching', () => {
      fixture.componentRef.setInput('searching', true);
      fixture.componentRef.setInput('availableSuppliers', []);
      fixture.detectChanges();

      openPanel();

      expect(panelOptionTexts()).toEqual(['Buscando proveedores…']);
      expect(panelOptions()[0].hasAttribute('disabled')).toBe(true);
    });

    it('should show a non-selectable empty row when not searching and there are no options', () => {
      fixture.componentRef.setInput('searching', false);
      fixture.componentRef.setInput('availableSuppliers', []);
      fixture.detectChanges();

      openPanel();

      expect(panelOptionTexts()).toEqual(['No se encontraron proveedores.']);
      expect(panelOptions()[0].hasAttribute('disabled')).toBe(true);
    });

    it('should display the selected supplier name, never its raw id', async () => {
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
      fixture.detectChanges();

      component.formGroup().controls.supplierId.setValue('sup-1');
      fixture.detectChanges();
      // `MatAutocompleteTrigger.writeValue` assigns the display value on a microtask.
      await fixture.whenStable();
      fixture.detectChanges();

      expect(supplierInput().value).toBe('Proveedor Uno');
    });
  });

  describe('free-text guard (D23)', () => {
    it('should reject a value that is not one of the options and never emit saveSupplier', () => {
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
      fixture.detectChanges();

      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      component.formGroup().patchValue({ supplierId: 'proveedor-inventado', purchaseCost: 10 });
      component.onSave();

      expect(spy).not.toHaveBeenCalled();
      expect(component.formGroup().controls.supplierId.errors?.['invalidSelection']).toBe(true);
    });

    it('should still emit saveSupplier for a value that is one of the options', () => {
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
      fixture.detectChanges();

      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      component.formGroup().patchValue({ supplierId: 'sup-1', purchaseCost: 10 });
      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
    });

    it('should not submit free text typed into the picker', () => {
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
      fixture.detectChanges();

      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      const input = supplierInput();
      input.value = 'Proveedor Inventado';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      // Measured: with `requireSelection`, the trigger is the value accessor and it
      // deliberately does not write while the user is typing, so the control keeps
      // its previous value and `required` fails.
      expect(component.formGroup().controls.supplierId.value).toBe('');

      component.onSave();

      expect(spy).not.toHaveBeenCalled();
    });

    it('should not submit a stale selection when the user types over it (edit mode)', () => {
      fixture.componentRef.setInput('editMode', true);
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
      fixture.detectChanges();

      component.formGroup().patchValue({ supplierId: 'sup-1', purchaseCost: 10 });
      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      // `requireSelection` keeps the pre-typing id in the control while the field
      // shows the typed text, and Enter-submit reaches `onSave` in that window. The
      // membership check alone would pass because `sup-1` is offered, so the
      // visible-text check is what must block the stale submit.
      const input = supplierInput();
      input.value = 'Proveedor Inventado';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();

      expect(component.formGroup().controls.supplierId.value).toBe('sup-1');

      component.onSave();

      expect(spy).not.toHaveBeenCalled();
      expect(component.formGroup().controls.supplierId.errors?.['invalidSelection']).toBe(true);
    });

    it('should submit the newly selected option after typing over a selection (edit mode)', () => {
      fixture.componentRef.setInput('editMode', true);
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
        { id: 'sup-2', supplierName: 'Proveedor Dos' },
      ]);
      fixture.detectChanges();

      component.formGroup().patchValue({ supplierId: 'sup-1', purchaseCost: 10 });
      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      const input = supplierInput();
      input.value = 'Proveedor Dos';
      input.dispatchEvent(new Event('input'));
      // What the template's `(optionSelected)` binding invokes on a real pick.
      component.onSupplierSelected('sup-2');
      fixture.detectChanges();

      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect((spy.mock.calls[0][0] as ProductSupplierRequest).supplierId).toBe('sup-2');
    });

    it('should submit a freshly selected option in create mode', () => {
      fixture.componentRef.setInput('availableSuppliers', [
        { id: 'sup-1', supplierName: 'Proveedor Uno' },
      ]);
      fixture.detectChanges();

      const spy = vi.fn();
      component.saveSupplier.subscribe(spy);

      component.onSupplierSelected('sup-1');
      component.formGroup().patchValue({ purchaseCost: 10 });
      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect((spy.mock.calls[0][0] as ProductSupplierRequest).supplierId).toBe('sup-1');
    });
  });
});
