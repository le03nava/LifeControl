import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { ProductsForm } from './products-form';
import { ProductControl } from '../../models/product.models';

describe('ProductsForm', () => {
  let component: ProductsForm;
  let fixture: ComponentFixture<ProductsForm>;

  function createFormGroup(): FormGroup<ProductControl> {
    return new FormGroup<ProductControl>({
      id: new FormControl('', { nonNullable: true }),
      sku: new FormControl('', {
        nonNullable: true,
        validators: Validators.required,
      }),
      name: new FormControl('', {
        nonNullable: true,
        validators: Validators.required,
      }),
      shortName: new FormControl<string | null>(null),
      satCode: new FormControl<string | null>(null),
      productType: new FormControl<string | null>(null),
      attributes: new FormControl<string | null>(null),
      enabled: new FormControl<boolean>(true, { nonNullable: true }),
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProductsForm, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ProductsForm);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', createFormGroup());
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should display the form title for new product', () => {
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Nuevo Producto');
  });

  it('should display the form title for edit mode', () => {
    const editFg = createFormGroup();
    editFg.patchValue({ id: '123' });
    fixture.componentRef.setInput('formGroup', editFg);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Editar Producto');
  });

  it('should emit saveProduct with parsed product data when form is valid', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    const fg = component.formGroup();
    fg.patchValue({
      sku: 'PROD-001',
      name: 'Producto Test',
      attributes: '{"color": "red"}',
      enabled: true,
    });
    fixture.detectChanges();

    component.onSave();

    expect(spy).toHaveBeenCalledTimes(1);
    const emitted = spy.mock.calls[0][0];
    expect(emitted.sku).toBe('PROD-001');
    expect(emitted.name).toBe('Producto Test');
    expect(emitted.attributes).toEqual({ color: 'red' });
    expect(emitted.enabled).toBe(true);
  });

  it('should not emit saveProduct when form is invalid', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    component.onSave();

    expect(spy).not.toHaveBeenCalled();
  });

  it('should emit cancelForm when cancel is clicked', () => {
    const spy = vi.fn();
    component.cancelForm.subscribe(spy);

    component.onCancel();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('should show mat-error for required fields when touched', () => {
    ['sku', 'name'].forEach((key) => {
      const control = component.formGroup().get(key);
      control?.markAsTouched();
      control?.setErrors({ required: true });
    });
    fixture.detectChanges();

    const matErrors = fixture.nativeElement.querySelectorAll('mat-error');
    expect(matErrors.length).toBe(2);
  });

  it('should apply server errors to matching controls', () => {
    fixture.componentRef.setInput('serverErrors', {
      sku: 'SKU ya registrado',
    });
    fixture.detectChanges();

    const skuControl = component.formGroup().controls.sku;
    expect(skuControl.errors?.['serverError']).toBe('SKU ya registrado');
  });

  it('should warn on unmatched server error keys', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    fixture.componentRef.setInput('serverErrors', {
      nonexistent: 'No existe',
    });
    fixture.detectChanges();

    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('nonexistent'),
    );

    warnSpy.mockRestore();
  });

  it('should clear serverError on valueChanges while preserving other validators', () => {
    const skuControl = component.formGroup().controls.sku;

    fixture.componentRef.setInput('serverErrors', {
      sku: 'SKU ya registrado',
    });
    fixture.detectChanges();

    expect(skuControl.errors?.['serverError']).toBe('SKU ya registrado');

    skuControl.setValue('NEW-SKU');
    fixture.detectChanges();

    expect(skuControl.errors?.['serverError']).toBeUndefined();
  });

  it('should parse attributes JSON string on save', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    const fg = component.formGroup();
    fg.patchValue({
      sku: 'PROD-002',
      name: 'Producto JSON',
      attributes: '{"size": "L", "color": "blue"}',
    });
    fixture.detectChanges();

    component.onSave();

    const emitted = spy.mock.calls[0][0];
    expect(emitted.attributes).toEqual({ size: 'L', color: 'blue' });
  });

  it('should set attributes as undefined when JSON is empty', () => {
    const spy = vi.fn();
    component.saveProduct.subscribe(spy);

    const fg = component.formGroup();
    fg.patchValue({
      sku: 'PROD-003',
      name: 'No Attributes',
      attributes: '',
    });
    fixture.detectChanges();

    component.onSave();

    const emitted = spy.mock.calls[0][0];
    expect(emitted.attributes).toBeUndefined();
  });
});
