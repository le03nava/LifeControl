import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, Subject, throwError } from 'rxjs';
import { ProductSupplierDialog, ProductSupplierDialogData } from './product-supplier-dialog';
import { ConfirmDialog } from '@shared/ui';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { SupplierService } from '../../suppliers/data/supplier.service';
import { ProductSupplier } from '../../models/product-supplier.models';
import { Page, Supplier } from '../../suppliers/models/supplier.models';

function supplier(id: string, supplierName: string): Supplier {
  return {
    id,
    supplierName,
    razonSocial: `${supplierName} S.A.`,
    rfc: `RFC-${id}`,
    email: `${id}@example.com`,
    phoneNumber: '555-0000',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };
}

function supplierPage(content: Supplier[]): Page<Supplier> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

describe('ProductSupplierDialog', () => {
  let component: ProductSupplierDialog;
  let fixture: ComponentFixture<ProductSupplierDialog>;
  let dialogRef: { close: ReturnType<typeof vi.fn>; disableClose: boolean };
  let dialogMock: { open: ReturnType<typeof vi.fn> };
  let productSupplierServiceMock: {
    getSuppliers: ReturnType<typeof vi.fn>;
    addSupplier: ReturnType<typeof vi.fn>;
    updateSupplier: ReturnType<typeof vi.fn>;
  };
  let supplierServiceMock: { getSuppliers: ReturnType<typeof vi.fn> };

  const productId = 'prod-1';
  const assignment: ProductSupplier = {
    id: 'ps-1',
    productId,
    supplierId: 'sup-1',
    supplierName: 'Proveedor Uno',
    purchaseCost: 42,
    main: true,
    enabled: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  };

  function setup(
    options: {
      data?: ProductSupplierDialogData;
      searchResult?: Supplier[];
      assignments?: ProductSupplier[];
    } = {},
  ): void {
    dialogRef = { close: vi.fn(), disableClose: false };
    dialogMock = { open: vi.fn().mockReturnValue({ afterClosed: () => of(true) }) };
    productSupplierServiceMock = {
      getSuppliers: vi.fn().mockReturnValue(of(options.assignments ?? [])),
      addSupplier: vi.fn().mockReturnValue(of(assignment)),
      updateSupplier: vi.fn().mockReturnValue(of(assignment)),
    };
    supplierServiceMock = {
      getSuppliers: vi.fn().mockReturnValue(of(supplierPage(options.searchResult ?? []))),
    };

    TestBed.configureTestingModule({
      imports: [ProductSupplierDialog, NoopAnimationsModule],
      providers: [
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatDialog, useValue: dialogMock },
        { provide: MAT_DIALOG_DATA, useValue: options.data ?? { productId } },
        { provide: ProductSupplierService, useValue: productSupplierServiceMock },
        { provide: SupplierService, useValue: supplierServiceMock },
      ],
    });

    fixture = TestBed.createComponent(ProductSupplierDialog);
    component = fixture.componentInstance;
  }

  it('should create', () => {
    setup();
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('should be in create mode when no assignment is passed', () => {
    setup();
    fixture.detectChanges();
    expect(component.editMode()).toBe(false);
  });

  it('should be in edit mode when an assignment is passed', () => {
    setup({ data: { productId, assignment } });
    fixture.detectChanges();
    expect(component.editMode()).toBe(true);
  });

  describe('form construction', () => {
    it('should build create-mode defaults', () => {
      setup();
      fixture.detectChanges();

      expect(component.assignmentForm().getRawValue()).toEqual({
        id: '',
        supplierId: '',
        purchaseCost: 0,
        main: false,
        enabled: true,
      });
    });

    it('should build the form from the assignment in edit mode', () => {
      setup({ data: { productId, assignment } });
      fixture.detectChanges();

      expect(component.assignmentForm().getRawValue()).toEqual({
        id: 'ps-1',
        supplierId: 'sup-1',
        purchaseCost: 42,
        main: true,
        enabled: false,
      });
    });
  });

  describe('rendered copy', () => {
    it('should render the create title exactly once', () => {
      setup();
      fixture.detectChanges();

      const headings = fixture.nativeElement.querySelectorAll('h2');
      expect(headings.length).toBe(1);
      expect(headings[0].textContent?.trim()).toBe('Asignar un proveedor al producto');
    });

    it('should render the edit title', () => {
      setup({ data: { productId, assignment } });
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('h2')?.textContent?.trim()).toBe(
        'Editar la asignación del proveedor',
      );
    });
  });

  describe('loading', () => {
    it('should load the product assignments on init', () => {
      setup({ assignments: [assignment] });
      fixture.detectChanges();

      expect(productSupplierServiceMock.getSuppliers).toHaveBeenCalledWith(productId);
      expect(component.assignedSuppliers()).toEqual([assignment]);
    });

    it('should seed the supplier search with one empty-term read', () => {
      setup();
      fixture.detectChanges();

      expect(supplierServiceMock.getSuppliers).toHaveBeenCalledWith(0, 20, undefined);
    });

    it('should surface a load failure in an error banner', () => {
      setup();
      productSupplierServiceMock.getSuppliers.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      supplierServiceMock.getSuppliers.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );
      fixture.detectChanges();

      expect(component.loadError()).toBeTruthy();
      expect(fixture.nativeElement.querySelector('app-error-banner')).toBeTruthy();
    });
  });

  describe('availableSuppliers', () => {
    it('should exclude already-assigned suppliers in create mode', () => {
      setup({
        searchResult: [supplier('sup-1', 'Uno'), supplier('sup-2', 'Dos')],
        assignments: [{ ...assignment, id: 'ps-2', supplierId: 'sup-2' }],
      });
      fixture.detectChanges();

      expect(component.availableSuppliers().map((s) => s.id)).toEqual(['sup-1']);
    });

    it('should keep the currently assigned supplier selectable in edit mode', () => {
      setup({
        data: { productId, assignment },
        searchResult: [supplier('sup-1', 'Uno'), supplier('sup-2', 'Dos')],
        assignments: [assignment, { ...assignment, id: 'ps-2', supplierId: 'sup-2' }],
      });
      fixture.detectChanges();

      expect(component.availableSuppliers().map((s) => s.id)).toEqual(['sup-1']);
    });

    it('should keep the current supplier even when the result page omits it', () => {
      setup({
        data: { productId, assignment },
        searchResult: [supplier('sup-2', 'Dos')],
        assignments: [assignment, { ...assignment, id: 'ps-2', supplierId: 'sup-2' }],
      });
      fixture.detectChanges();

      expect(component.availableSuppliers().map((s) => s.id)).toEqual(['sup-1']);
      expect(component.availableSuppliers()[0].supplierName).toBe('Proveedor Uno');
    });
  });

  describe('debounced server-side search', () => {
    it('should debounce the term and forward it to the service', () => {
      vi.useFakeTimers();
      setup();
      fixture.detectChanges();
      supplierServiceMock.getSuppliers.mockClear();

      component.onSupplierSearch('pro');
      component.onSupplierSearch('prov');
      vi.advanceTimersByTime(300);

      expect(supplierServiceMock.getSuppliers).toHaveBeenCalledTimes(1);
      expect(supplierServiceMock.getSuppliers).toHaveBeenCalledWith(0, 20, 'prov');
      vi.useRealTimers();
    });

    it('should ignore a repeated term (distinctUntilChanged)', () => {
      vi.useFakeTimers();
      setup();
      fixture.detectChanges();
      supplierServiceMock.getSuppliers.mockClear();

      component.onSupplierSearch('pro');
      vi.advanceTimersByTime(300);
      component.onSupplierSearch('pro');
      vi.advanceTimersByTime(300);

      expect(supplierServiceMock.getSuppliers).toHaveBeenCalledTimes(1);
      vi.useRealTimers();
    });

    it('should send an empty term as undefined', () => {
      vi.useFakeTimers();
      setup();
      fixture.detectChanges();
      supplierServiceMock.getSuppliers.mockClear();

      component.onSupplierSearch('   ');
      vi.advanceTimersByTime(300);

      expect(supplierServiceMock.getSuppliers).toHaveBeenCalledWith(0, 20, undefined);
      vi.useRealTimers();
    });

    it('should cancel the earlier request so a slow response cannot overwrite a later one', () => {
      vi.useFakeTimers();
      const responses: Subject<Page<Supplier>>[] = [];
      setup();
      supplierServiceMock.getSuppliers.mockImplementation(() => {
        const response = new Subject<Page<Supplier>>();
        responses.push(response);
        return response;
      });
      fixture.detectChanges();

      // responses[0] is the empty-term seed read.
      responses[0].next(supplierPage([]));
      responses[0].complete();

      component.onSupplierSearch('a');
      vi.advanceTimersByTime(300);
      component.onSupplierSearch('ab');
      vi.advanceTimersByTime(300);

      // The later request answers first; the earlier one answers late.
      responses[2].next(supplierPage([supplier('sup-b', 'B')]));
      responses[2].complete();
      responses[1].next(supplierPage([supplier('sup-a', 'A')]));
      responses[1].complete();

      expect(component.suppliers().map((s) => s.id)).toEqual(['sup-b']);
      vi.useRealTimers();
    });

    it('should not let a late seed response overwrite a later search result', () => {
      vi.useFakeTimers();
      const responses: Subject<Page<Supplier>>[] = [];
      setup();
      supplierServiceMock.getSuppliers.mockImplementation(() => {
        const response = new Subject<Page<Supplier>>();
        responses.push(response);
        return response;
      });
      fixture.detectChanges();

      // responses[0] is the empty-term seed read, still in flight.
      component.onSupplierSearch('b');
      vi.advanceTimersByTime(300);
      // responses[1] is the search read.

      responses[1].next(supplierPage([supplier('sup-b', 'B')]));
      responses[1].complete();
      // The seed answers late; it must not win.
      responses[0].next(supplierPage([supplier('sup-a', 'A')]));
      responses[0].complete();

      expect(component.suppliers().map((s) => s.id)).toEqual(['sup-b']);
      vi.useRealTimers();
    });

    it('should expose the searching flag while a request is in flight', () => {
      const response = new Subject<Page<Supplier>>();
      setup();
      supplierServiceMock.getSuppliers.mockReturnValue(response);
      fixture.detectChanges();

      expect(component.searching()).toBe(true);

      response.next(supplierPage([]));
      response.complete();

      expect(component.searching()).toBe(false);
    });
  });

  describe('saving', () => {
    it('should add the assignment and close with the saved entity in create mode', () => {
      setup({ searchResult: [supplier('sup-1', 'Uno')] });
      fixture.detectChanges();

      component.onSaveAssignment({
        supplierId: 'sup-1',
        purchaseCost: 10,
        main: false,
        enabled: true,
      });

      expect(productSupplierServiceMock.addSupplier).toHaveBeenCalledWith(productId, {
        supplierId: 'sup-1',
        purchaseCost: 10,
        main: false,
        enabled: true,
      });
      expect(productSupplierServiceMock.updateSupplier).not.toHaveBeenCalled();
      expect(dialogRef.close).toHaveBeenCalledWith(assignment);
    });

    it('should update the assignment and close with the saved entity in edit mode', () => {
      setup({ data: { productId, assignment } });
      fixture.detectChanges();

      component.onSaveAssignment({
        supplierId: 'sup-1',
        purchaseCost: 99,
        main: false,
        enabled: true,
      });

      expect(productSupplierServiceMock.updateSupplier).toHaveBeenCalledWith(productId, 'ps-1', {
        supplierId: 'sup-1',
        purchaseCost: 99,
        main: false,
        enabled: true,
      });
      expect(productSupplierServiceMock.addSupplier).not.toHaveBeenCalled();
      expect(dialogRef.close).toHaveBeenCalledWith(assignment);
    });

    it('should keep the dialog open and map a field error map onto the form', () => {
      setup();
      fixture.detectChanges();
      productSupplierServiceMock.addSupplier.mockReturnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 400,
              error: { errors: { purchaseCost: 'No puede ser negativo.' } },
            }),
        ),
      );

      component.onSaveAssignment({
        supplierId: 'sup-1',
        purchaseCost: -1,
        main: false,
        enabled: true,
      });

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.serverErrors()).toEqual({ purchaseCost: 'No puede ser negativo.' });
      expect(component.generalError()).toBeNull();
    });

    it('should keep the dialog open and show the voseo conflict message on a 409', () => {
      setup();
      fixture.detectChanges();
      productSupplierServiceMock.addSupplier.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 409 })),
      );

      component.onSaveAssignment({
        supplierId: 'sup-1',
        purchaseCost: 1,
        main: false,
        enabled: true,
      });

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.generalError()).toBe('Este proveedor ya está asignado al producto.');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-error-banner')).toBeTruthy();
    });

    it('should keep the dialog open and show the voseo generic message on any other error', () => {
      setup();
      fixture.detectChanges();
      productSupplierServiceMock.addSupplier.mockReturnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })),
      );

      component.onSaveAssignment({
        supplierId: 'sup-1',
        purchaseCost: 1,
        main: false,
        enabled: true,
      });

      expect(component.generalError()).toBe(
        'Ocurrió un error inesperado. Intentá de nuevo más tarde.',
      );
    });
  });

  it('should close with null when cancelled', () => {
    setup();
    fixture.detectChanges();

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith(null);
  });

  describe('unsaved input protection', () => {
    it('should start clean when the form is built from the loaded assignment', () => {
      setup({ data: { productId, assignment } });
      fixture.detectChanges();

      expect(component.formDirty()).toBe(false);
    });

    it('should not mark the form dirty when a server error is applied to a control', () => {
      setup();
      fixture.detectChanges();

      component
        .assignmentForm()
        .get('purchaseCost')
        ?.setErrors({ serverError: 'No puede ser negativo.' }, { emitEvent: false });
      fixture.detectChanges();

      expect(component.formDirty()).toBe(false);
    });

    it('should mark the form dirty when the operator edits a control', () => {
      setup();
      fixture.detectChanges();

      component.assignmentForm().get('purchaseCost')?.setValue(99);
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
      component.assignmentForm().get('purchaseCost')?.setValue(99);
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
      component.assignmentForm().get('purchaseCost')?.setValue(99);
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
      component.assignmentForm().get('purchaseCost')?.setValue(99);
      dialogMock.open.mockReturnValue({ afterClosed: () => of(true) });

      component.cancel();

      expect(dialogRef.close).toHaveBeenCalledWith(null);
    });

    it('should keep the dialog open when the operator declines discarding the edits', () => {
      setup();
      fixture.detectChanges();
      component.assignmentForm().get('purchaseCost')?.setValue(99);
      dialogMock.open.mockReturnValue({ afterClosed: () => of(undefined) });

      component.cancel();

      expect(dialogRef.close).not.toHaveBeenCalled();
    });
  });
});
