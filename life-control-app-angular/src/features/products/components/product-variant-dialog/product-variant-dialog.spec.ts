import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { ProductVariantDialog, ProductVariantDialogData } from './product-variant-dialog';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariant, ProductVariantRequest } from '../../models/product-variant.models';
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

  const productId = 'prod-1';
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
  };

  function setup(options: { data?: ProductVariantDialogData } = {}): void {
    dialogRef = { close: vi.fn(), disableClose: false };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(true) }) };
    productVariantServiceMock = {
      getVariantById: vi.fn().mockReturnValue(of(variant)),
      createVariant: vi.fn().mockReturnValue(of(variant)),
      updateVariant: vi.fn().mockReturnValue(of(variant)),
    };

    TestBed.configureTestingModule({
      imports: [ProductVariantDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialogMock },
        { provide: MAT_DIALOG_DATA, useValue: options.data ?? { productId } },
        { provide: ProductVariantService, useValue: productVariantServiceMock },
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
      });
    });

    it('should build the form from the variant in edit mode', () => {
      setup({ data: { productId, variant } });
      fixture.detectChanges();

      expect(component.definitionForm().getRawValue()).toEqual({
        barCode: '7791234567890',
        variantName: 'Talla 38',
      });
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
    const request: ProductVariantRequest = { barCode: '7791234567891', variantName: 'Talla 40' };

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
      expect(component.generalError()).toBe(
        'Ya tenés una variante con ese código de barras o ese nombre para este producto',
      );
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
    const request: ProductVariantRequest = { barCode: '7791234567891', variantName: 'Talla 40' };

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
      expect(component.definitionForm().getRawValue()).toEqual({ barCode: '', variantName: '' });
      expect(component.formDirty()).toBe(false);
      expect(dialogRef.disableClose).toBe(false);
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
      component.definitionForm().patchValue({ barCode: '7791234567892', variantName: 'Talla 42' });
      component.onSaveVariantAndContinue({ barCode: '7791234567892', variantName: 'Talla 42' });

      expect(productVariantServiceMock.createVariant).toHaveBeenCalledTimes(2);
      expect(productVariantServiceMock.createVariant).toHaveBeenNthCalledWith(2, productId, {
        barCode: '7791234567892',
        variantName: 'Talla 42',
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
