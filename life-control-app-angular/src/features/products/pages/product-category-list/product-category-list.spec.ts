/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { ConfirmDialog } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { Company, Page } from '@features/companies/companies/models/company.models';
import { ProductCategoryList } from './product-category-list';
import { ProductCategoryDialog } from '../../components/product-category-dialog/product-category-dialog';
import { ProductCategoryService } from '../../data/product-category.service';
import { ProductCategory } from '../../models/product-category.models';

describe('ProductCategoryList', () => {
  let fixture: ComponentFixture<ProductCategoryList>;
  let component: ProductCategoryList;
  let categoryService: {
    getCategories: ReturnType<typeof vi.fn>;
    setCategoryEnabled: ReturnType<typeof vi.fn>;
    deleteCategory: ReturnType<typeof vi.fn>;
  };
  let notifications: { showSuccess: ReturnType<typeof vi.fn>; showError: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let router: Router;

  const ADMIN_ROLES = ['lc-admin'];
  const READ_ONLY_ROLES = ['lc-sales'];

  const company = (id: string, name: string): Company => ({
    id,
    companyKey: name.toUpperCase(),
    companyName: name,
    tipoPersonaId: 2,
    razonSocial: `${name} SA`,
    rfc: 'ACM200101010',
    email: 'contacto@acme.example',
    phone: '+525512345678',
    enabled: true,
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01',
  });

  const companiesPage: Page<Company> = {
    content: [company('company-1', 'Acme'), company('company-2', 'Globex')],
    totalElements: 2,
    totalPages: 1,
    size: 1000,
    number: 0,
    first: true,
    last: true,
    empty: false,
  };

  /** Root, child and grandchild: the three levels the tree allows. */
  const tree = (): ProductCategory[] => [
    {
      id: 'cat-1',
      companyId: 'company-1',
      parentCategoryId: null,
      categoryName: 'Bebidas',
      description: 'Todo lo que se toma',
      satCode: '51212000',
      displayOrder: 1,
      depth: 1,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
    {
      id: 'cat-2',
      companyId: 'company-1',
      parentCategoryId: 'cat-1',
      categoryName: 'Gaseosas',
      description: null,
      satCode: null,
      displayOrder: 1,
      depth: 2,
      enabled: false,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
    {
      id: 'cat-3',
      companyId: 'company-1',
      parentCategoryId: 'cat-2',
      categoryName: 'Colas',
      description: null,
      satCode: null,
      displayOrder: 1,
      depth: 3,
      enabled: true,
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  ];

  interface SetupOptions {
    roles?: string[];
    queryCompanyId?: string | null;
    rows?: ProductCategory[];
    loadError?: boolean;
    confirm?: boolean;
    savedCategory?: ProductCategory | null;
    writeError?: HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    const rows = options.rows ?? tree();
    const writeError = options.writeError;

    categoryService = {
      getCategories: vi.fn(() => {
        if (options.loadError) {
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return of(rows);
      }),
      setCategoryEnabled: vi.fn(() => (writeError ? throwError(() => writeError) : of(rows[0]))),
      deleteCategory: vi.fn(() => (writeError ? throwError(() => writeError) : of(undefined))),
    };

    notifications = { showSuccess: vi.fn(), showError: vi.fn() };
    dialog = {
      open: vi.fn((target: unknown) => ({
        afterClosed: () =>
          of(
            target === ConfirmDialog ? (options.confirm ?? true) : (options.savedCategory ?? null),
          ),
      })),
    };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ProductCategoryList],
      providers: [
        provideRouter([]),
        { provide: ProductCategoryService, useValue: categoryService },
        {
          provide: CompanyService,
          useValue: { getCompanySelector: vi.fn().mockReturnValue(of(companiesPage)) },
        },
        { provide: NotificationService, useValue: notifications },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Keycloak,
          useValue: {
            tokenParsed: {
              resource_access: { 'life-control-client': { roles: options.roles ?? ADMIN_ROLES } },
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({}),
              queryParamMap: convertToParamMap(
                options.queryCompanyId ? { companyId: options.queryCompanyId } : {},
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(ProductCategoryList);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function rows(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('tr.mat-mdc-row'));
  }

  function names(): string[] {
    return rows().map((row) => row.querySelector('.category-name')?.textContent?.trim() ?? '');
  }

  function actionButtons(label: string): HTMLElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll(`button[aria-label="${label}"]`),
    );
  }

  describe('company scope (F1)', () => {
    it('should not request the tree before a company is selected', async () => {
      setup({ queryCompanyId: null });
      await settle();

      expect(categoryService.getCategories).not.toHaveBeenCalled();
      expect(text()).toContain('Seleccioná una empresa');
    });

    it('should read the tree of the company named in the URL', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      expect(categoryService.getCategories).toHaveBeenCalledWith('company-1', false);
      expect(component.selectedCompanyId()).toBe('company-1');
    });

    it('should write a changed selection back to the URL so a shared link reproduces it', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onCompanyChange('company-2');
      await settle();

      expect(router.navigate).toHaveBeenCalledWith([], {
        relativeTo: expect.anything(),
        queryParams: { companyId: 'company-2' },
        queryParamsHandling: 'merge',
        replaceUrl: true,
      });
      expect(categoryService.getCategories).toHaveBeenCalledWith('company-2', false);
    });

    it('should forward the include-disabled toggle instead of filtering an already-truncated list', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onIncludeDisabledChange(true);
      await settle();

      expect(categoryService.getCategories).toHaveBeenLastCalledWith('company-1', true);
    });
  });

  describe('tree rendering (F2)', () => {
    it('should render the rows in the order the API returned them', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      expect(names()).toEqual(['Bebidas', 'Gaseosas', 'Colas']);
    });

    it('should indent each row by its depth, so the hierarchy is readable', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      const indents = rows().map(
        (row) => (row.querySelector('.category-name-wrapper') as HTMLElement).style.paddingLeft,
      );

      expect(indents).toEqual(['0px', '24px', '48px']);
    });

    it('should render the state of each row', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      const state = rows().map((row) => row.querySelector('.badge.status')?.textContent?.trim());
      expect(state).toEqual(['Activo', 'Deshabilitado', 'Activo']);
    });

    it('should render the SAT key of each row and an em dash when it has none', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      const keys = rows().map((row) =>
        row.querySelector('td.mat-column-satCode')?.textContent?.trim(),
      );

      // S5: the key is optional, so an absent one is a dash and never a blank cell that could be
      // read as "not loaded".
      expect(keys).toEqual(['51212000', '—', '—']);
    });
  });

  describe('write gating (F7)', () => {
    it('should offer every write control to an lc-admin principal', async () => {
      setup({ queryCompanyId: 'company-1', roles: ADMIN_ROLES });
      await settle();

      expect(actionButtons('Editar')).toHaveLength(3);
      expect(actionButtons('Deshabilitar')).toHaveLength(2);
      expect(actionButtons('Habilitar')).toHaveLength(1);
      expect(actionButtons('Eliminar')).toHaveLength(3);
      expect(text()).toContain('Nueva Categoría');
    });

    it('should render no write control for a principal without lc-admin', async () => {
      setup({ queryCompanyId: 'company-1', roles: READ_ONLY_ROLES });
      await settle();

      // The API answers 403 for these writes, so the screen must not offer them.
      expect(actionButtons('Editar')).toHaveLength(0);
      expect(actionButtons('Deshabilitar')).toHaveLength(0);
      expect(actionButtons('Habilitar')).toHaveLength(0);
      expect(actionButtons('Eliminar')).toHaveLength(0);
      expect(text()).not.toContain('Nueva Categoría');
    });
  });

  describe('enable and disable (S6)', () => {
    it('should confirm a disable, naming the subtree that follows it', async () => {
      setup({ queryCompanyId: 'company-1', confirm: true });
      await settle();

      component.onToggleEnabled(tree()[0]);
      await settle();

      expect(dialog.open).toHaveBeenCalled();
      const [target, config] = dialog.open.mock.calls[0];
      expect(target).toBe(ConfirmDialog);
      expect(config.data.message).toContain('Bebidas');
      expect(config.data.message).toContain('subcategorías');
      expect(config.data.destructive).toBe(true);
      expect(categoryService.setCategoryEnabled).toHaveBeenCalledWith('company-1', 'cat-1', false);
      expect(categoryService.getCategories).toHaveBeenCalledTimes(2);
      expect(notifications.showSuccess).toHaveBeenCalled();
    });

    it('should write nothing when the disable is cancelled', async () => {
      setup({ queryCompanyId: 'company-1', confirm: false });
      await settle();

      component.onToggleEnabled(tree()[0]);
      await settle();

      expect(categoryService.setCategoryEnabled).not.toHaveBeenCalled();
      expect(categoryService.getCategories).toHaveBeenCalledTimes(1);
    });

    it('should enable a disabled node without a confirmation', async () => {
      setup({ queryCompanyId: 'company-1' });
      await settle();

      component.onToggleEnabled(tree()[1]);
      await settle();

      // Enabling is not destructive: the only refusal the API can answer is a
      // disabled ancestor, which it reports as a 409.
      expect(dialog.open).not.toHaveBeenCalled();
      expect(categoryService.setCategoryEnabled).toHaveBeenCalledWith('company-1', 'cat-2', true);
      expect(categoryService.getCategories).toHaveBeenCalledTimes(2);
    });

    it('should show the ancestor rule when a re-enable is refused, not the version copy', async () => {
      setup({
        queryCompanyId: 'company-1',
        writeError: new HttpErrorResponse({ status: 409 }),
      });
      await settle();

      component.onToggleEnabled(tree()[1]);
      await settle();

      // `httpErrorMessage` answers 409 with "the data changed while you were
      // editing"; nothing about a disabled ancestor would change on a reload.
      expect(component.actionError()).toContain('deshabilitada');
      expect(component.actionError()).not.toContain('El dato cambió');
      expect(text()).toContain('deshabilitada');
      expect(notifications.showSuccess).not.toHaveBeenCalled();
    });
  });

  describe('soft delete (S6, F5)', () => {
    it('should confirm the delete and say the row is kept disabled', async () => {
      setup({ queryCompanyId: 'company-1', confirm: true });
      await settle();

      component.onDelete(tree()[0]);
      await settle();

      const [target, config] = dialog.open.mock.calls[0];
      expect(target).toBe(ConfirmDialog);
      expect(config.data.destructive).toBe(true);
      expect(config.data.message).toContain('se conserva');
      expect(categoryService.deleteCategory).toHaveBeenCalledWith('company-1', 'cat-1');
      expect(categoryService.getCategories).toHaveBeenCalledTimes(2);
    });

    it('should delete nothing when the confirmation is cancelled', async () => {
      setup({ queryCompanyId: 'company-1', confirm: false });
      await settle();

      component.onDelete(tree()[0]);
      await settle();

      expect(categoryService.deleteCategory).not.toHaveBeenCalled();
      expect(categoryService.getCategories).toHaveBeenCalledTimes(1);
    });
  });

  describe('dialog wiring (S5)', () => {
    it('should open the form in create mode with the loaded tree for the parent picker', async () => {
      setup({ queryCompanyId: 'company-1', savedCategory: tree()[0] });
      await settle();

      component.onCreate();
      await settle();

      const [target, config] = dialog.open.mock.calls[0];
      expect(target).toBe(ProductCategoryDialog);
      expect(config.data.companyId).toBe('company-1');
      expect(config.data.category).toBeNull();
      expect(config.data.categories.map((row: ProductCategory) => row.id)).toEqual([
        'cat-1',
        'cat-2',
        'cat-3',
      ]);
      // A saved category reloads the tree and confirms the write.
      expect(categoryService.getCategories).toHaveBeenCalledTimes(2);
      expect(notifications.showSuccess).toHaveBeenCalled();
    });

    it('should open the form in edit mode carrying the row', async () => {
      setup({ queryCompanyId: 'company-1', savedCategory: null });
      await settle();

      component.onEdit(tree()[1]);
      await settle();

      const [, config] = dialog.open.mock.calls[0];
      expect(config.data.category).toEqual(tree()[1]);
      // A cancelled dialog writes nothing and reloads nothing.
      expect(categoryService.getCategories).toHaveBeenCalledTimes(1);
    });
  });

  describe('load states', () => {
    it('should render a retry state when the tree read fails', async () => {
      setup({ queryCompanyId: 'company-1', loadError: true });
      await settle();

      expect(component.loadError()).toBe(
        'Ocurrió un error en el servidor. Intentá de nuevo más tarde.',
      );
      expect(text()).toContain('Reintentar');
    });

    it('should re-run the read when the retry is pressed', async () => {
      setup({ queryCompanyId: 'company-1', loadError: true });
      await settle();

      const retry = (fixture.nativeElement as HTMLElement).querySelector('.load-error button');
      (retry as HTMLButtonElement).click();
      await settle();

      expect(categoryService.getCategories).toHaveBeenCalledTimes(2);
    });

    it('should render an empty state when the company has no categories', async () => {
      setup({ queryCompanyId: 'company-1', rows: [] });
      await settle();

      expect(text()).toContain('No hay categorías registradas');
    });
  });

  it('should feed the company selector from the company service', async () => {
    setup({ queryCompanyId: null });
    await settle();

    // The options are the service's page, not a locally invented list: a screen
    // that offered a company the API does not serve would send a request nobody
    // can satisfy. Asserted on the signal, not the DOM: a closed `mat-select`
    // renders no `mat-option`, so a DOM query would pass vacuously.
    expect(component.companies().map((row) => row.companyName)).toEqual(['Acme', 'Globex']);
  });
});
