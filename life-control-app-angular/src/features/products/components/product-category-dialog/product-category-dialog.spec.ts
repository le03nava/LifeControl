/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { ProductCategoryDialog } from './product-category-dialog';
import { ProductCategoryService } from '../../data/product-category.service';
import { ProductCategory } from '../../models/product-category.models';

describe('ProductCategoryDialog', () => {
  let fixture: ComponentFixture<ProductCategoryDialog>;
  let component: ProductCategoryDialog;
  let categoryService: {
    createCategory: ReturnType<typeof vi.fn>;
    updateCategory: ReturnType<typeof vi.fn>;
  };
  let dialogRef: { close: ReturnType<typeof vi.fn> };

  const companyId = 'company-1';

  const category = (overrides: Partial<ProductCategory> = {}): ProductCategory => ({
    id: 'cat-1',
    companyId,
    parentCategoryId: null,
    categoryName: 'Bebidas',
    description: null,
    displayOrder: 1,
    depth: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  const root = category();
  const disabledChild = category({
    id: 'cat-2',
    parentCategoryId: 'cat-1',
    categoryName: 'Gaseosas',
    depth: 2,
    enabled: false,
  });
  const grandchild = category({
    id: 'cat-3',
    parentCategoryId: 'cat-2',
    categoryName: 'Colas',
    depth: 3,
  });
  const disabledRoot = category({
    id: 'cat-4',
    categoryName: 'Lácteos',
    enabled: false,
    displayOrder: 2,
  });

  const tree = [root, disabledChild, grandchild, disabledRoot];

  interface SetupOptions {
    editing?: ProductCategory | null;
    writeError?: HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    const writeError = options.writeError;

    categoryService = {
      createCategory: vi.fn(() =>
        writeError ? throwError(() => writeError) : of(category({ id: 'cat-new' })),
      ),
      updateCategory: vi.fn(() =>
        writeError ? throwError(() => writeError) : of(category({ id: 'cat-1' })),
      ),
    };
    dialogRef = { close: vi.fn() };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductCategoryDialog],
      providers: [
        { provide: ProductCategoryService, useValue: categoryService },
        { provide: MatDialogRef, useValue: dialogRef },
        {
          provide: MAT_DIALOG_DATA,
          useValue: { companyId, category: options.editing ?? null, categories: tree },
        },
      ],
    });

    fixture = TestBed.createComponent(ProductCategoryDialog);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 4; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  describe('create mode', () => {
    it('should start as a root with the defaults the API uses', () => {
      setup();

      expect(component.editMode()).toBe(false);
      expect(component.form().getRawValue()).toEqual({
        categoryName: '',
        parentCategoryId: '',
        description: '',
        displayOrder: 0,
      });
    });

    it('should post a root category as a null parent, enabled', async () => {
      setup();
      component.form().patchValue({ categoryName: 'Bebidas', displayOrder: 3 });

      component.onSave();
      await settle();

      expect(categoryService.createCategory).toHaveBeenCalledWith(companyId, {
        parentCategoryId: null,
        categoryName: 'Bebidas',
        description: null,
        displayOrder: 3,
        enabled: true,
      });
      // The list reloads only on a truthy result, so the dialog ends with the saved row.
      expect(dialogRef.close).toHaveBeenCalledWith(category({ id: 'cat-new' }));
    });

    it('should not issue a write when the name is missing', async () => {
      setup();

      component.onSave();
      await settle();

      expect(categoryService.createCategory).not.toHaveBeenCalled();
      expect(dialogRef.close).not.toHaveBeenCalled();
    });

    it('should post the chosen parent and trim a blank description to null', async () => {
      setup();
      component.form().patchValue({
        categoryName: '  Gaseosas  ',
        parentCategoryId: 'cat-1',
        description: '   ',
      });

      component.onSave();
      await settle();

      expect(categoryService.createCategory).toHaveBeenCalledWith(
        companyId,
        expect.objectContaining({
          parentCategoryId: 'cat-1',
          categoryName: 'Gaseosas',
          description: null,
        }),
      );
    });
  });

  describe('edit mode', () => {
    it('should prefill the form from the row', () => {
      setup({ editing: disabledChild });

      expect(component.editMode()).toBe(true);
      expect(component.form().getRawValue()).toEqual({
        categoryName: 'Gaseosas',
        parentCategoryId: 'cat-1',
        // A text input holds the empty string; the payload maps it back to `null`.
        description: '',
        displayOrder: 1,
      });
    });

    it('should put the saved category’s own enabled flag in the payload', async () => {
      // F4 regression. `ProductCategoryRequest.enabled` defaults to true on the server and a false
      // value runs the disable cascade, so an edit that omitted it would re-enable this disabled
      // category and its subtree. Asserted on the payload, not on the rendered form.
      setup({ editing: disabledChild });
      component.form().patchValue({ categoryName: 'Gaseosas y aguas' });

      component.onSave();
      await settle();

      const [, , request] = categoryService.updateCategory.mock.calls[0];
      expect(request.enabled).toBe(false);
      expect(request.categoryName).toBe('Gaseosas y aguas');
    });

    it('should keep the row enabled when the row was enabled', async () => {
      setup({ editing: root });

      component.onSave();
      await settle();

      const [, , request] = categoryService.updateCategory.mock.calls[0];
      expect(request.enabled).toBe(true);
    });

    it('should update the row by id', async () => {
      setup({ editing: disabledChild });

      component.onSave();
      await settle();

      expect(categoryService.updateCategory).toHaveBeenCalledWith(
        companyId,
        'cat-2',
        expect.anything(),
      );
    });
  });

  describe('parent picker (F3)', () => {
    it('should offer every category of the company when creating', () => {
      setup();

      expect(component.parentOptions().map((option) => option.id)).toEqual([
        'cat-1',
        'cat-2',
        'cat-3',
        'cat-4',
      ]);
    });

    it('should exclude the edited node and its whole subtree', () => {
      setup({ editing: disabledChild });

      // A move into itself or into a descendant is the cycle the API answers 409 for, so the picker
      // must not offer it at all.
      expect(component.parentOptions().map((option) => option.id)).toEqual(['cat-1', 'cat-4']);
    });

    it('should keep the current parent selectable even when it is disabled', () => {
      setup({ editing: grandchild });

      const currentParent = component.parentOptions().find((option) => option.id === 'cat-2');

      // Without this the select would show "root" for a node that is not a root, and a rename would
      // quietly move it.
      expect(currentParent?.selectable).toBe(true);
    });

    it('should refuse a level-3 node as a parent, because its child would be level 4', () => {
      // Create mode: nothing is excluded, so `cat-3` is offered and only the ceiling refuses it.
      setup();

      const deepest = component.parentOptions().find((option) => option.id === 'cat-3');

      expect(deepest?.selectable).toBe(false);
    });

    it('should refuse a disabled node as a parent, because its child could never be enabled', () => {
      setup({ editing: root });

      const disabled = component.parentOptions().find((option) => option.id === 'cat-4');

      expect(disabled?.selectable).toBe(false);
    });

    it('should indent the labels by depth so the picker shows the tree it offers', () => {
      setup();

      expect(component.parentOptions().map((option) => option.label)).toEqual([
        'Bebidas',
        '— Gaseosas',
        '— — Colas',
        'Lácteos',
      ]);
    });
  });

  describe('refusals (F6)', () => {
    it('should keep the dialog open and name the tree rules on a 409', async () => {
      setup({
        editing: disabledChild,
        writeError: new HttpErrorResponse({ status: 409 }),
      });

      component.onSave();
      await settle();

      expect(dialogRef.close).not.toHaveBeenCalled();
      expect(component.formError()).toContain('tres niveles');
      expect(component.formError()).not.toContain('El dato cambió');
      expect(text()).toContain('tres niveles');
    });

    it('should fall back to the shared copy for any other failure', async () => {
      setup({ writeError: new HttpErrorResponse({ status: 500 }) });

      component.form().patchValue({ categoryName: 'Bebidas' });
      component.onSave();
      await settle();

      expect(component.formError()).toBe(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
    });
  });

  describe('cancel', () => {
    it('should close with null and write nothing', async () => {
      setup();

      component.form().patchValue({ categoryName: 'Descartada' });
      component.cancel();
      await settle();

      expect(dialogRef.close).toHaveBeenCalledWith(null);
      expect(categoryService.createCategory).not.toHaveBeenCalled();
    });
  });
});
