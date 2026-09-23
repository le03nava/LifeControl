import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { ProductVariantForm, ProductVariantControl } from './product-variant-form';
import { ProductVariantRequest } from '../../models/product-variant.models';

describe('ProductVariantForm', () => {
  let component: ProductVariantForm;
  let fixture: ComponentFixture<ProductVariantForm>;

  function createFormGroup(): FormGroup<ProductVariantControl> {
    return new FormGroup<ProductVariantControl>({
      barCode: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(100)],
      }),
      variantName: new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(255)],
      }),
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductVariantForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductVariantForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', createFormGroup());
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should render the create-mode submit label', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('button[type="submit"]')?.textContent?.trim()).toBe('Guardar');
  });

  it('should render the edit-mode submit label', () => {
    fixture.componentRef.setInput('editMode', true);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('button[type="submit"]')?.textContent?.trim()).toBe('Actualizar');
  });

  it('should not render its own heading: the hosting dialog owns the title', () => {
    expect(fixture.nativeElement.querySelector('h2')).toBeNull();
  });

  it('should emit saveVariant with the two global fields when valid', () => {
    const spy = vi.fn();
    component.saveVariant.subscribe(spy);

    component.formGroup().patchValue({ barCode: '7791234567890', variantName: 'Talla 38' });
    fixture.detectChanges();

    component.onSave();

    expect(spy).toHaveBeenCalledTimes(1);
    const emitted = spy.mock.calls[0][0] as ProductVariantRequest;
    expect(emitted).toEqual({ barCode: '7791234567890', variantName: 'Talla 38' });
  });

  it('should not emit saveVariant when the form is invalid', () => {
    const spy = vi.fn();
    component.saveVariant.subscribe(spy);

    component.onSave();

    expect(spy).not.toHaveBeenCalled();
    expect(component.formGroup().controls.barCode.touched).toBe(true);
  });

  it('should render a mat-error for each required field when touched', () => {
    ['barCode', 'variantName'].forEach((key) => {
      const control = component.formGroup().get(key);
      control?.markAsTouched();
      control?.setErrors({ required: true });
    });
    fixture.detectChanges();

    const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
    expect(matErrors.length).toBe(2);
    expect(matErrors[0].textContent).toContain('Este campo es obligatorio.');
  });

  it('should render the maxlength message from the control error', () => {
    const control = component.formGroup().controls.barCode;
    control.setValue('x'.repeat(101));
    control.markAsTouched();
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('No podés superar los 100 caracteres');
  });

  it('should emit cancelForm when cancelled', () => {
    const spy = vi.fn();
    component.cancelForm.subscribe(spy);

    component.onCancel();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  describe('saveVariantAndContinue', () => {
    it('should render the add-another button only in create mode', () => {
      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('Guardar y agregar otra');

      fixture.componentRef.setInput('editMode', true);
      fixture.detectChanges();

      expect(el.textContent).not.toContain('Guardar y agregar otra');
    });

    it('should emit saveVariantAndContinue with the two global fields when valid', () => {
      const spy = vi.fn();
      const saveSpy = vi.fn();
      component.saveVariantAndContinue.subscribe(spy);
      component.saveVariant.subscribe(saveSpy);

      component.formGroup().patchValue({ barCode: '7791234567890', variantName: 'Talla 38' });
      fixture.detectChanges();

      component.onSaveAndContinue();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(saveSpy).not.toHaveBeenCalled();
      expect(spy.mock.calls[0][0]).toEqual({
        barCode: '7791234567890',
        variantName: 'Talla 38',
      });
    });

    it('should not emit saveVariantAndContinue when the form is invalid', () => {
      const spy = vi.fn();
      const saveSpy = vi.fn();
      component.saveVariantAndContinue.subscribe(spy);
      component.saveVariant.subscribe(saveSpy);

      component.onSaveAndContinue();

      expect(spy).not.toHaveBeenCalled();
      expect(saveSpy).not.toHaveBeenCalled();
      expect(component.formGroup().controls.barCode.touched).toBe(true);
    });
  });

  describe('in-flight guard', () => {
    it('should not emit either output while saving', () => {
      const saveSpy = vi.fn();
      const continueSpy = vi.fn();
      component.saveVariant.subscribe(saveSpy);
      component.saveVariantAndContinue.subscribe(continueSpy);
      component.formGroup().patchValue({ barCode: '7791234567890', variantName: 'Talla 38' });
      fixture.componentRef.setInput('saving', true);
      fixture.detectChanges();

      component.onSave();
      component.onSaveAndContinue();

      expect(saveSpy).not.toHaveBeenCalled();
      expect(continueSpy).not.toHaveBeenCalled();
    });

    it('should disable both buttons while saving', () => {
      const el = fixture.nativeElement as HTMLElement;
      fixture.componentRef.setInput('saving', true);
      fixture.detectChanges();

      const submit = el.querySelector('button[type="submit"]') as HTMLButtonElement;
      const addAnother = Array.from(el.querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Guardar y agregar otra',
      ) as HTMLButtonElement;

      expect(submit.disabled).toBe(true);
      expect(addAnother.disabled).toBe(true);
    });
  });

  describe('serverErrors', () => {
    it('should apply server errors to matching controls', () => {
      fixture.componentRef.setInput('serverErrors', {
        barCode: 'Código ya registrado',
      });
      fixture.detectChanges();

      expect(component.formGroup().controls.barCode.errors?.['serverError']).toBe(
        'Código ya registrado',
      );
    });

    it('should clear an applied serverError when the input becomes empty', () => {
      const control = component.formGroup().controls.barCode;

      fixture.componentRef.setInput('serverErrors', { barCode: 'Código ya registrado' });
      fixture.detectChanges();
      expect(control.errors?.['serverError']).toBe('Código ya registrado');

      fixture.componentRef.setInput('serverErrors', {});
      fixture.detectChanges();
      expect(control.errors?.['serverError']).toBeUndefined();
    });

    it('should warn on unmatched server error keys', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

      fixture.componentRef.setInput('serverErrors', {
        nonexistent: 'No existe',
      });
      fixture.detectChanges();

      expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('nonexistent'));

      warnSpy.mockRestore();
    });

    it('should clear serverError on valueChanges while preserving other validators', () => {
      const control = component.formGroup().controls.variantName;

      fixture.componentRef.setInput('serverErrors', {
        variantName: 'Nombre ya registrado',
      });
      fixture.detectChanges();

      expect(control.errors?.['serverError']).toBe('Nombre ya registrado');
      expect(control.errors?.['required']).toBe(true);

      control.setValue('');
      fixture.detectChanges();

      expect(control.errors?.['serverError']).toBeUndefined();
      expect(control.errors?.['required']).toBe(true);
    });
  });
});
