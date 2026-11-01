import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { By } from '@angular/platform-browser';
import { MatSelect } from '@angular/material/select';
import { ProductVariantForm, ProductVariantControl } from './product-variant-form';
import { MeasureUnit, ProductVariantRequest } from '../../models/product-variant.models';

describe('ProductVariantForm', () => {
  let component: ProductVariantForm;
  let fixture: ComponentFixture<ProductVariantForm>;

  const kilogram: MeasureUnit = {
    id: 'mu-kg',
    measureUnitName: 'Kilogramo',
    measureUnitShortName: 'Kg',
    unitType: 'PRODUCT',
    satCode: 'KGM',
    description: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const piece: MeasureUnit = {
    id: 'mu-pza',
    measureUnitName: 'Pieza',
    measureUnitShortName: 'Pza',
    unitType: 'PRODUCT',
    satCode: 'H87',
    description: null,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
  };

  const serviceUnit: MeasureUnit = {
    ...kilogram,
    id: 'mu-service',
    measureUnitName: 'Servicio',
    measureUnitShortName: 'Serv',
    unitType: 'SERVICE',
    satCode: 'E48',
  };

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
      // Mirrors the dialog's group, which is the only place these validators live: the unit is
      // required (ADR-0006 decision 3) and the barcode's requirement is the product's mode's
      // (decision 4), applied by the dialog once the mode arrives — so it is absent here.
      measureUnitId: new FormControl<string | null>(null, Validators.required),
    });
  }

  function unitSelect(): MatSelect {
    return fixture.debugElement.query(By.directive(MatSelect)).componentInstance as MatSelect;
  }

  /** Material renders the options lazily, so the panel is opened before reading them. */
  function renderedOptionLabels(): string[] {
    const select = unitSelect();
    select.open();
    fixture.detectChanges();
    const labels = select.options.map((option) =>
      (option.viewValue ?? '').replace(/\s+/g, ' ').trim(),
    );
    select.close();
    fixture.detectChanges();
    return labels;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductVariantForm],
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

    component.formGroup().patchValue({
      barCode: '7791234567890',
      variantName: 'Talla 38',
      measureUnitId: 'mu-kg',
    });
    fixture.detectChanges();

    component.onSave();

    expect(spy).toHaveBeenCalledTimes(1);
    const emitted = spy.mock.calls[0][0] as ProductVariantRequest;
    expect(emitted).toEqual({
      barCode: '7791234567890',
      variantName: 'Talla 38',
      measureUnitId: 'mu-kg',
    });
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

  it('should say the barcode is optional only when the product does not require it', () => {
    // Decision 4: the copy follows the product's mode, which the host resolved.
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Opcional');

    fixture.componentRef.setInput('barcodeRequired', false);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'Opcional: una variante de un producto simple puede no tener código de barras.',
    );
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

      component.formGroup().patchValue({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-kg',
      });
      fixture.detectChanges();

      component.onSaveAndContinue();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(saveSpy).not.toHaveBeenCalled();
      expect(spy.mock.calls[0][0]).toEqual({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-kg',
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

  describe('measure unit selector', () => {
    it('should render the label of the control', () => {
      expect(fixture.nativeElement.textContent).toContain('Unidad de medida');
    });

    it('should offer no "no unit" option: the unit is required (decision 3)', () => {
      fixture.componentRef.setInput('measureUnits', [kilogram, piece]);
      fixture.detectChanges();

      const select = unitSelect();
      select.open();
      fixture.detectChanges();

      // The catalog and nothing else: the old first option cleared the field to `null`, which the
      // API now refuses, so an empty selection is the placeholder of an unfinished field.
      expect(select.options.length).toBe(2);
      expect(select.options.toArray().map((option) => option.value)).toEqual(['mu-kg', 'mu-pza']);

      select.close();
      fixture.detectChanges();
    });

    it('should render one option per catalog entry with its short and full name', () => {
      fixture.componentRef.setInput('measureUnits', [kilogram, piece]);
      fixture.detectChanges();

      expect(renderedOptionLabels()).toEqual(['Kg — Kilogramo', 'Pza — Pieza']);
    });

    it('should offer every catalog unit, whatever its unitType', () => {
      fixture.componentRef.setInput('measureUnits', [kilogram, serviceUnit]);
      fixture.detectChanges();

      const select = unitSelect();
      select.open();
      fixture.detectChanges();

      expect(select.options.toArray().map((option) => option.value)).toEqual([
        'mu-kg',
        'mu-service',
      ]);

      select.close();
      fixture.detectChanges();
    });

    it('should render no option at all when the catalog is empty', () => {
      expect(renderedOptionLabels()).toEqual([]);
    });

    it('should emit the chosen measureUnitId on save', () => {
      const spy = vi.fn();
      component.saveVariant.subscribe(spy);

      fixture.componentRef.setInput('measureUnits', [kilogram, piece]);
      fixture.detectChanges();
      component.formGroup().patchValue({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-kg',
      });
      fixture.detectChanges();

      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0]).toEqual({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-kg',
      });
    });

    it('should emit the chosen measureUnitId on save and add another', () => {
      const spy = vi.fn();
      component.saveVariantAndContinue.subscribe(spy);

      component.formGroup().patchValue({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-pza',
      });
      fixture.detectChanges();

      component.onSaveAndContinue();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(spy.mock.calls[0][0]).toEqual({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-pza',
      });
    });

    it('should require the unit: an empty selection is not a saveable variant (decision 3)', () => {
      const spy = vi.fn();
      component.saveVariant.subscribe(spy);

      fixture.componentRef.setInput('measureUnits', [kilogram]);
      fixture.detectChanges();
      component.formGroup().patchValue({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: null,
      });
      fixture.detectChanges();

      component.onSave();
      fixture.detectChanges();

      // The refusal the API answers with `400 measureUnitId is required`, refused here first.
      expect(spy).not.toHaveBeenCalled();
      expect(component.formGroup().controls.measureUnitId.valid).toBe(false);
      expect(fixture.nativeElement.querySelectorAll('mat-error').length).toBe(1);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain(
        'Este campo es obligatorio.',
      );
    });

    it('should send the barcode as null when the product does not require one (decision 4)', () => {
      const spy = vi.fn();
      component.saveVariant.subscribe(spy);

      // The host owns the validators, so the optional barcode is stated by a group built without the
      // `required` one — exactly what the dialog builds once it learns the product is `SIMPLE`.
      fixture.componentRef.setInput(
        'formGroup',
        new FormGroup<ProductVariantControl>({
          barCode: new FormControl('', {
            nonNullable: true,
            validators: [Validators.maxLength(100)],
          }),
          variantName: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.maxLength(255)],
          }),
          measureUnitId: new FormControl<string | null>(null, Validators.required),
        }),
      );
      fixture.componentRef.setInput('measureUnits', [kilogram]);
      fixture.componentRef.setInput('barcodeRequired', false);
      fixture.detectChanges();
      component.formGroup().patchValue({
        barCode: '',
        variantName: 'Unica',
        measureUnitId: 'mu-kg',
      });
      fixture.detectChanges();

      component.onSave();

      expect(spy).toHaveBeenCalledTimes(1);
      // `null`, never `''`: the API stores "no barcode" as the absence of one.
      expect(spy.mock.calls[0][0]).toEqual({
        barCode: null,
        variantName: 'Unica',
        measureUnitId: 'mu-kg',
      });
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

    it('should apply a measureUnitId server error to the unit control (decision 3)', () => {
      fixture.componentRef.setInput('serverErrors', {
        measureUnitId: 'Elegí la unidad de medida de la variante.',
      });
      fixture.detectChanges();

      expect(component.formGroup().controls.measureUnitId.errors?.['serverError']).toBe(
        'Elegí la unidad de medida de la variante.',
      );
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
