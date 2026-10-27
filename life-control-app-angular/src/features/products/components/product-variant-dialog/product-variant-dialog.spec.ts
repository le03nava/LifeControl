import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { ProductVariantDialog, ProductVariantDialogData } from './product-variant-dialog';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductService } from '../../data/product.service';
import { MeasureUnitService } from '../../data/measure-unit.service';
import {
  MeasureUnit,
  ProductVariant,
  ProductVariantRequest,
} from '../../models/product-variant.models';
import {
  Product,
  ProductAttributeDefinition,
  ProductAttributeProfile,
} from '../../models/product.models';
import { ProductVariantForm } from '../product-variant-form/product-variant-form';
import { ConfirmDialog } from '@shared/ui';

describe('ProductVariantDialog', () => {
  let component: ProductVariantDialog;
  let fixture: ComponentFixture<ProductVariantDialog>;
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let dialogMock: { open: ReturnType<typeof vi.fn> };
  let productVariantServiceMock: {
    getVariantById: ReturnType<typeof vi.fn>;
    createVariant: ReturnType<typeof vi.fn>;
    updateVariant: ReturnType<typeof vi.fn>;
  };
  let measureUnitServiceMock: { getMeasureUnits: ReturnType<typeof vi.fn> };
  let productServiceMock: {
    getProductById: ReturnType<typeof vi.fn>;
    getAttributeProfile: ReturnType<typeof vi.fn>;
  };

  const productId = 'prod-1';
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
    ...kilogram,
    id: 'mu-pza',
    measureUnitName: 'Pieza',
    measureUnitShortName: 'Pza',
    satCode: 'H87',
  };
  const catalog: MeasureUnit[] = [kilogram, piece];

  const variant: ProductVariant = {
    id: 'var-1',
    productId,
    companyStoreId: null,
    barCode: '7791234567890',
    sku: 'SKU-001',
    variantName: 'Talla 38',
    listPrice: null,
    costPrice: null,
    stock: null,
    enabled: true,
    measureUnitId: 'mu-kg',
  };

  /** A product as the dialog reads it: only its classification matters here. */
  function createProduct(overrides: Partial<Product> = {}): Product {
    return {
      id: productId,
      sku: 'SKU-001',
      name: 'Zapato de vestir',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      categoryId: null,
      categoryCompanyId: null,
      ...overrides,
    };
  }

  interface SetupOptions {
    data?: ProductVariantDialogData;
    /** The product the dialog reads for its classification; unclassified by default. */
    product?: Partial<Product>;
    /** The profile its category resolves to; `null` is the API's `204` (no profile). */
    profile?: ProductAttributeProfile | null;
  }

  function setup(options: SetupOptions = {}): void {
    dialogRef = { close: vi.fn(), disableClose: false };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(true) }) };
    productVariantServiceMock = {
      getVariantById: vi.fn().mockReturnValue(of(variant)),
      createVariant: vi.fn().mockReturnValue(of(variant)),
      updateVariant: vi.fn().mockReturnValue(of(variant)),
    };
    measureUnitServiceMock = { getMeasureUnits: vi.fn().mockReturnValue(of(catalog)) };
    // The dialog reads the product for its classification and then that category's profile; the
    // default product is unclassified, so no profile is read and no attribute input renders.
    productServiceMock = {
      getProductById: vi.fn().mockReturnValue(of(createProduct(options.product))),
      getAttributeProfile: vi.fn().mockReturnValue(of(options.profile ?? null)),
    };

    TestBed.configureTestingModule({
      imports: [ProductVariantDialog],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialogMock },
        { provide: MAT_DIALOG_DATA, useValue: options.data ?? { productId } },
        { provide: ProductVariantService, useValue: productVariantServiceMock },
        { provide: ProductService, useValue: productServiceMock },
        { provide: MeasureUnitService, useValue: measureUnitServiceMock },
      ],
    });

    fixture = TestBed.createComponent(ProductVariantDialog);
    component = fixture.componentInstance;
  }

  it('should create', () => {
    setup();
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should be in create mode when no variant is passed', () => {
    setup();
    fixture.detectChanges();
    expect(component.editMode()).toBe(false);
  });

  it('should be in edit mode when a variant is passed', () => {
    setup({ data: { productId, variant } });
    fixture.detectChanges();
    expect(component.editMode()).toBe(true);
  });

  describe('form construction', () => {
    it('should build create-mode defaults', () => {
      setup();
      fixture.detectChanges();

      expect(component.definitionForm().getRawValue()).toEqual({
        barCode: '',
        variantName: '',
        measureUnitId: null,
      });
    });

    it('should build the form from the variant in edit mode', () => {
      setup({ data: { productId, variant } });
      fixture.detectChanges();

      expect(component.definitionForm().getRawValue()).toEqual({
        barCode: '7791234567890',
        variantName: 'Talla 38',
        measureUnitId: 'mu-kg',
      });
    });
  });

  describe('measure unit catalog', () => {
    it('should load the catalog once on open', () => {
      setup();
      fixture.detectChanges();

      expect(measureUnitServiceMock.getMeasureUnits).toHaveBeenCalledTimes(1);
    });

    it('should hand the loaded catalog to the form', () => {
      setup();
      fixture.detectChanges();

      const form = fixture.debugElement.query(By.directive(ProductVariantForm))
        .componentInstance as ProductVariantForm;
      expect(form.measureUnits()).toEqual(catalog);
    });

    it('should keep working with an empty catalog when the read fails', () => {
      setup();
      measureUnitServiceMock.getMeasureUnits.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      fixture = TestBed.createComponent(ProductVariantDialog);
      component = fixture.componentInstance;
      fixture.detectChanges();

      const form = fixture.debugElement.query(By.directive(ProductVariantForm))
        .componentInstance as ProductVariantForm;
      expect(form.measureUnits()).toEqual([]);
    });
  });

  describe('rendered copy', () => {
    it('should render the create title exactly once', () => {
      setup();
      fixture.detectChanges();

      const headings = fixture.nativeElement.querySelectorAll('h2');
      expect(headings.length).toBe(1);
      expect(headings[0].textContent?.trim()).toBe('Agregar una variante al producto');
    });

    it('should render the edit title', () => {
      setup({ data: { productId, variant } });
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('h2')?.textContent?.trim()).toBe(
        'Editar la definición de la variante',
      );
    });
  });

  describe('saving', () => {
    const request: ProductVariantRequest = {
      barCode: '7791234567891',
      variantName: 'Talla 40',
      measureUnitId: 'mu-pza',
    };

    it('should create the variant and close with the saved entity in create mode', () => {
      setup();
      fixture.detectChanges();

      component.onSaveVariant(request);

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(productId, request);
      expect(productVariantServiceMock.updateVariant).not.toHaveBeenCalled();
      expect(dialogRef.close).toHaveBeenCalledWith(variant);
    });

    it('should update the variant and close with the saved entity in edit mode', () => {
      setup({ data: { productId, variant } });
      fixture.detectChanges();

      component.onSaveVariant(request);

      expect(productVariantServiceMock.updateVariant).toHaveBeenCalledWith(
        productId,
        'var-1',
        request,
      );
      expect(productVariantServiceMock.createVariant).not.toHaveBeenCalled();
      expect(dialogRef.close).toHaveBeenCalledWith(variant);
    });

    it('should carry the chosen unit in the create request', () => {
      setup();
      fixture.detectChanges();

      component.onSaveVariant({
        barCode: 'BAR-1',
        variantName: 'Talla 40',
        measureUnitId: 'mu-pza',
      });

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(productId, {
        barCode: 'BAR-1',
        variantName: 'Talla 40',
        measureUnitId: 'mu-pza',
      });
    });

    it('should carry the chosen unit in the update request', () => {
      setup({ data: { productId, variant } });
      fixture.detectChanges();

      component.onSaveVariant({
        barCode: 'BAR-1',
        variantName: 'Talla 40',
        measureUnitId: 'mu-pza',
      });

      expect(productVariantServiceMock.updateVariant).toHaveBeenCalledWith(productId, 'var-1', {
        barCode: 'BAR-1',
        variantName: 'Talla 40',
        measureUnitId: 'mu-pza',
      });
    });

    it('should send a null unit when none was chosen', () => {
      setup();
      fixture.detectChanges();

      component.onSaveVariant({ barCode: 'BAR-1', variantName: 'Talla 40', measureUnitId: null });

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(productId, {
        barCode: 'BAR-1',
        variantName: 'Talla 40',
        measureUnitId: null,
      });
    });

    it('should keep the dialog open and map a field error map onto the form', () => {
      setup();
      fixture.detectChanges();
      productVariantServiceMock.createVariant.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { errors: { barCode: 'Código ya registrado' } },
            }),
        ),
      );

      component.onSaveVariant(request);

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.serverErrors()).toEqual({ barCode: 'Código ya registrado' });
      expect(component.generalError()).toBeNull();
    });

    it('should keep the dialog open and show the voseo conflict message on a 409', () => {
      setup();
      fixture.detectChanges();
      productVariantServiceMock.createVariant.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409 })),
      );

      component.onSaveVariant(request);

      expect(dialogRef.close).not.toHaveBeenCalled();
      // One banner for both causes the backend can report with a 409: the duplicate
      // barcode/name collision and the unit frozen by stored stock or ledger movements
      // (D3/S5). The server sends no attribution, so the copy must name both.
      expect(component.generalError()).toBe(
        'No se pudo guardar: ya tenés una variante con ese código de barras o ese nombre para ' +
          'este producto, o la variante ya tiene stock o movimientos de inventario que no ' +
          'permiten cambiar la unidad de medida',
      );
      expect(component.generalError()).toContain('stock o movimientos de inventario');
      expect(component.generalError()).toContain('código de barras');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-error-banner')).toBeTruthy();
    });

    it('should keep the dialog open and map a generic failure through httpErrorMessage', () => {
      setup();
      fixture.detectChanges();
      productVariantServiceMock.createVariant.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );

      component.onSaveVariant(request);

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.serverErrors()).toEqual({});
      expect(component.generalError()).toContain('Ocurrió un error en el servidor');
    });
  });

  it('should close with null when cancelled', () => {
    setup();
    fixture.detectChanges();

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  describe('save and add another', () => {
    const request: ProductVariantRequest = {
      barCode: '7791234567891',
      variantName: 'Talla 40',
      measureUnitId: 'mu-pza',
    };

    it('should expose saving as false initially', () => {
      setup();
      fixture.detectChanges();

      expect(component.saving()).toBe(false);
    });

    it('should stay open, reset the form and re-enable close on success', () => {
      setup();
      fixture.detectChanges();
      component
        .definitionForm()
        .patchValue({ barCode: request.barCode, variantName: request.variantName });
      fixture.detectChanges();
      expect(component.formDirty()).toBe(true);
      expect(dialogRef.disableClose).toBe(true);

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(productId, request);
      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.definitionForm().getRawValue()).toEqual({
        barCode: '',
        variantName: '',
        measureUnitId: null,
      });
      expect(component.formDirty()).toBe(false);
      expect(dialogRef.disableClose).toBe(false);
    });

    it('should reset the chosen unit to null for the next entry', () => {
      setup();
      fixture.detectChanges();
      component.definitionForm().patchValue({
        barCode: request.barCode,
        variantName: request.variantName,
        measureUnitId: 'mu-kg',
      });
      fixture.detectChanges();
      expect(component.definitionForm().controls.measureUnitId.value).toBe('mu-kg');

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();

      expect(component.definitionForm().controls.measureUnitId.value).toBeNull();
      expect(component.definitionForm().getRawValue()).toEqual({
        barCode: '',
        variantName: '',
        measureUnitId: null,
      });
    });

    it('should reset without emitting valueChanges', () => {
      setup();
      fixture.detectChanges();
      const spy = vi.fn();
      component.definitionForm().valueChanges.subscribe(spy);

      component.onSaveVariantAndContinue(request);

      expect(spy).not.toHaveBeenCalled();
    });

    it('should clear the applied control server error on reset', () => {
      setup();
      fixture.detectChanges();
      productVariantServiceMock.createVariant.mockReturnValueOnce(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { errors: { barCode: 'Código ya registrado' } },
            }),
        ),
      );

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();
      expect(component.serverErrors()).toEqual({ barCode: 'Código ya registrado' });
      expect(component.definitionForm().controls.barCode.errors?.['serverError']).toBe(
        'Código ya registrado',
      );

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();

      expect(component.serverErrors()).toEqual({});
      expect(component.definitionForm().controls.barCode.errors?.['serverError']).toBeUndefined();
    });

    it('should clear the general error banner on reset', () => {
      setup();
      fixture.detectChanges();
      productVariantServiceMock.createVariant.mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 409 })),
      );

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();
      expect(component.generalError()).not.toBeNull();

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();

      expect(component.generalError()).toBeNull();
    });

    it('should issue a second POST with the new payload on a second add-another', () => {
      setup();
      fixture.detectChanges();

      component.onSaveVariantAndContinue(request);
      component.definitionForm().patchValue({
        barCode: '7791234567892',
        variantName: 'Talla 42',
        measureUnitId: null,
      });
      component.onSaveVariantAndContinue({
        barCode: '7791234567892',
        variantName: 'Talla 42',
        measureUnitId: null,
      });

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledTimes(2);
      expect(productVariantServiceMock.createVariant).toHaveBeenNthCalledWith(2, productId, {
        barCode: '7791234567892',
        variantName: 'Talla 42',
        measureUnitId: null,
      });
    });

    it('should not issue a second POST while the first is in flight', () => {
      setup();
      fixture.detectChanges();
      const pending = new Subject<ProductVariant>();
      productVariantServiceMock.createVariant.mockReturnValue(pending.asObservable());

      component.onSaveVariantAndContinue(request);
      component.onSaveVariantAndContinue(request);

      expect(component.saving()).toBe(true);
      expect(productVariantServiceMock.createVariant).toHaveBeenCalledTimes(1);

      pending.next(variant);
      pending.complete();

      expect(component.saving()).toBe(false);
    });

    it('should disable the form buttons while a write is in flight', () => {
      setup();
      fixture.detectChanges();
      const pending = new Subject<ProductVariant>();
      productVariantServiceMock.createVariant.mockReturnValue(pending.asObservable());

      component.onSaveVariantAndContinue(request);
      fixture.detectChanges();

      const addAnother = Array.from(
        (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
      ).find(
        (button) => button.textContent?.trim() === 'Guardar y agregar otra',
      ) as HTMLButtonElement;
      expect(addAnother.disabled).toBe(true);

      pending.next(variant);
      pending.complete();
    });

    it('should clear saving when the request fails', () => {
      setup();
      fixture.detectChanges();
      productVariantServiceMock.createVariant.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );

      component.onSaveVariantAndContinue(request);

      expect(component.saving()).toBe(false);
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('should close with the last saved entity after an add-another save', () => {
      setup();
      fixture.detectChanges();

      component.onSaveVariantAndContinue(request);
      expect(dialogRef.close).not.toHaveBeenCalled();

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(variant);
    });

    it('should close with the last saved entity when the operator confirms discarding later edits', () => {
      setup();
      fixture.detectChanges();

      component.onSaveVariantAndContinue(request);
      component.definitionForm().patchValue({ barCode: '7791234567893', variantName: 'Talla 44' });
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(variant);
    });
  });

  describe('variant attributes (S6, S7)', () => {
    const color: ProductAttributeDefinition = {
      code: 'color',
      label: 'Color',
      level: 'VARIANT',
      kind: 'ENUM',
      unitLabel: null,
      required: true,
      values: [
        { code: 'negro', label: 'Negro Mate' },
        { code: 'rojo', label: 'Charol Rojo' },
      ],
    };

    const acabado: ProductAttributeDefinition = {
      code: 'acabado',
      label: 'Acabado',
      level: 'VARIANT',
      kind: 'ENUM',
      unitLabel: null,
      required: false,
      values: [{ code: 'mate', label: 'Mate' }],
    };

    const shoeProfile: ProductAttributeProfile = {
      profileKey: 'calzado',
      label: 'Calzado',
      resolvedRootName: 'Calzado',
      productAttributes: [
        {
          code: 'marca',
          label: 'Marca',
          level: 'PRODUCT',
          kind: 'TEXT',
          unitLabel: null,
          required: false,
          values: [],
        },
      ],
      variantAttributes: [color, acabado],
    };

    /** A product classified under company-1, which is what makes a profile resolvable at all. */
    const classified: Partial<Product> = {
      categoryId: 'cat-1',
      categoryCompanyId: 'company-1',
    };

    function variantForm(): ProductVariantForm {
      return fixture.debugElement.query(By.directive(ProductVariantForm))
        .componentInstance as ProductVariantForm;
    }

    it('should read the profile of the product category and render only its variant level (S7)', () => {
      setup({ product: classified, profile: shoeProfile });
      fixture.detectChanges();
      fixture.detectChanges();

      expect(productServiceMock.getAttributeProfile).toHaveBeenCalledWith('company-1', 'cat-1');
      expect(component.variantDefinitions()).toEqual([color, acabado]);

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Color');
      expect(text).toContain('Acabado');
      // A product-level attribute belongs to the product form, never to this dialog.
      expect(text).not.toContain('Marca');
    });

    it('should submit the colour as the variant attributes (S6)', () => {
      setup({ product: classified, profile: shoeProfile });
      fixture.detectChanges();
      fixture.detectChanges();

      component.definitionForm().patchValue({ barCode: 'BAR-1', variantName: 'Talla 38' });
      component.attributeForm().get('color')?.setValue('negro');

      variantForm().onSave();

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(productId, {
        barCode: 'BAR-1',
        variantName: 'Talla 38',
        measureUnitId: null,
        attributes: { color: 'negro' },
      });
    });

    it('should seed and submit the values a variant already stores', () => {
      setup({
        data: {
          productId,
          variant: { ...variant, attributes: { color: 'negro', acabado: 'mate' } },
        },
        product: classified,
        profile: shoeProfile,
      });
      fixture.detectChanges();
      fixture.detectChanges();

      variantForm().onSave();

      expect(productVariantServiceMock.updateVariant).toHaveBeenCalledWith(
        productId,
        'var-1',
        expect.objectContaining({ attributes: { color: 'negro', acabado: 'mate' } }),
      );
    });

    it('should leave the stored attributes alone when the category declares no variant attribute', () => {
      // `attributes` is omitted, not sent as `{}`: an empty set would clear what the row already holds.
      setup();
      fixture.detectChanges();
      fixture.detectChanges();

      component.definitionForm().patchValue({ barCode: 'BAR-1', variantName: 'Talla 38' });
      variantForm().onSave();

      expect(productServiceMock.getAttributeProfile).not.toHaveBeenCalled();
      expect(productVariantServiceMock.createVariant).toHaveBeenCalledWith(productId, {
        barCode: 'BAR-1',
        variantName: 'Talla 38',
        measureUnitId: null,
      });
    });
  });

  describe('unsaved input protection', () => {
    it('should start clean when the form is built from the loaded variant', () => {
      setup({ data: { productId, variant } });
      fixture.detectChanges();

      expect(component.formDirty()).toBe(false);
    });

    it('should not mark the form dirty when a server error is applied to a control', () => {
      setup();
      fixture.detectChanges();

      component
        .definitionForm()
        .get('barCode')
        ?.setErrors({ serverError: 'Código ya registrado' }, { emitEvent: false });
      fixture.detectChanges();

      expect(component.formDirty()).toBe(false);
    });

    it('should mark the form dirty when the operator edits a control', () => {
      setup();
      fixture.detectChanges();

      component.definitionForm().get('barCode')?.setValue('7790000000000');
      fixture.detectChanges();

      expect(component.formDirty()).toBe(true);
    });

    it('should keep Esc and backdrop working while the form is clean', () => {
      setup();
      fixture.detectChanges();

      expect(dialogRef.disableClose).toBe(false);
    });

    it('should block Esc and backdrop once the form is dirty', () => {
      setup();
      fixture.detectChanges();
      component.definitionForm().get('barCode')?.setValue('7790000000000');
      fixture.detectChanges();

      expect(dialogRef.disableClose).toBe(true);
    });

    it('should close immediately with null when cancel runs on a clean form', () => {
      setup();
      fixture.detectChanges();

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(null);
      expect(dialogMock.open).not.toHaveBeenCalled();
    });

    it('should ask for confirmation instead of closing when cancel runs on a dirty form', () => {
      setup();
      fixture.detectChanges();
      component.definitionForm().get('barCode')?.setValue('7790000000000');
      dialogMock.open.mockReturnValue({ afterClosed: () => of(undefined) });

      component.cancel();

      expect(dialogMock.open).toHaveBeenCalledWith(
        ConfirmDialog,
        expect.objectContaining({
          data: {
            title: 'Cambios sin guardar',
            message:
              'Tenés cambios sin guardar. Si cerrás ahora, los datos que escribiste se van a perder.',
            confirmLabel: 'Descartar cambios',
            cancelLabel: 'Seguir editando',
            destructive: true,
          },
        }),
      );
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('should close with null when the operator confirms discarding the edits', () => {
      setup();
      fixture.detectChanges();
      component.definitionForm().get('barCode')?.setValue('7790000000000');
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });

    it('should keep the dialog open when the operator declines discarding the edits', () => {
      setup();
      fixture.detectChanges();
      component.definitionForm().get('barCode')?.setValue('7790000000000');
      dialogMock.open.mockReturnValue({ afterClosed: () => of(undefined) });

      component.cancel();

      expect(dialogRef.close).not.toHaveBeenCalled();
    });
  });
});
