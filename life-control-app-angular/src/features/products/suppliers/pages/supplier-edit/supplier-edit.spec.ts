import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { ReactiveFormsModule } from '@angular/forms';
import { signal } from '@angular/core';
import { of } from 'rxjs';
import { SupplierEdit } from './supplier-edit';
import { SupplierService } from '../../data/supplier.service';
import { Supplier } from '../../models/supplier.models';
import { CountryService } from '@features/countries/data/country.service';
import { Country } from '@features/companies/countries/models/country.models';
import type { AddressValue } from '@shared/models/address.models';

describe('SupplierEdit', () => {
  let component: SupplierEdit;
  let fixture: ComponentFixture<SupplierEdit>;
  let supplierServiceMock: {
    createSupplier: ReturnType<typeof vi.fn>;
    updateSupplier: ReturnType<typeof vi.fn>;
    getSupplierById: ReturnType<typeof vi.fn>;
  };
  let routerMock: { navigate: ReturnType<typeof vi.fn> };

  /**
   * The company that owns the supplier being created or edited.
   *
   * It arrives in `?companyId=`, exactly as the products form takes it: the catalog is company-scoped
   * on the wire (`/api/companies/{companyId}/suppliers`), so a save without it has no endpoint.
   */
  const companyId = 'company-1';

  const defaultAddress: AddressValue = {
    street: 'Av. Reforma',
    streetNumber: '222',
    internalNumber: 'A-101',
    neighborhood: 'Juárez',
    zipCode: '06600',
    city: 'CDMX',
    state: 'CDMX',
    countryId: 'MX',
  };

  function createSupplierData(overrides: Partial<Supplier> = {}): Supplier {
    return {
      id: '',
      supplierName: 'Proveedor Test',
      razonSocial: 'Proveedor Test SA',
      rfc: 'XAXX010101000',
      email: 'proveedor@test.com',
      phoneNumber: '555-0002',
      internalNumber: 'B-2',
      enabled: true,
      createdAt: '',
      updatedAt: '',
      companyId,
      ...overrides,
    };
  }

  function createSupplierWithAddress(overrides: Partial<Supplier> = {}): Supplier {
    return createSupplierData({ address: { ...defaultAddress }, ...overrides });
  }

  async function setup(
    options: {
      companyId?: string | null;
      supplierId?: string | null;
      supplierById?: Supplier;
    } = {},
  ): Promise<void> {
    const scopedCompanyId = options.companyId === undefined ? companyId : options.companyId;
    const supplierId = options.supplierId ?? null;

    supplierServiceMock = {
      createSupplier: vi.fn(),
      updateSupplier: vi.fn(),
      getSupplierById: options.supplierById
        ? vi.fn().mockReturnValue(of(options.supplierById))
        : vi.fn(),
    };
    routerMock = { navigate: vi.fn() };

    // A test that re-runs `setup()` with another company must get a fresh module: the `beforeEach`
    // already instantiated one.
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [SupplierEdit, ReactiveFormsModule],
      providers: [
        { provide: SupplierService, useValue: supplierServiceMock },
        {
          provide: CountryService,
          useValue: {
            countries: signal<Country[]>([]).asReadonly(),
            loading: signal(false).asReadonly(),
            error: signal<string | null>(null).asReadonly(),
            getCountries: vi.fn().mockReturnValue(of([])),
          },
        },
        { provide: Router, useValue: routerMock },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(supplierId ? { id: supplierId } : {}),
              queryParamMap: convertToParamMap(
                scopedCompanyId ? { companyId: scopedCompanyId } : {},
              ),
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SupplierEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await setup();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('request body', () => {
    /**
     * The API refuses the body *before* it validates any field — it is configured with
     * `spring.jackson.deserialization.fail-on-unknown-properties=true` — and answers
     * `400 Malformed or unreadable request body`. So the body has to carry exactly what
     * `SupplierRequest` declares: this client must not put `id`, `createdAt` or `updatedAt`
     * on the wire.
     */
    const requestFields = {
      supplierName: 'Proveedor Test',
      razonSocial: 'Proveedor Test SA',
      rfc: 'XAXX010101000',
      email: 'proveedor@test.com',
      phoneNumber: '555-0002',
      internalNumber: 'B-2',
      enabled: true,
    };

    it('sends only the fields SupplierRequest declares when creating, address included', () => {
      let captured: unknown;
      supplierServiceMock.createSupplier = vi
        .fn()
        .mockImplementation((_companyId: string, data: unknown) => {
          captured = data;
          return of(createSupplierWithAddress({ id: 'new-id' }));
        });

      component.onSaveSupplier(createSupplierWithAddress({ id: '' }));

      expect(captured).toStrictEqual({
        ...requestFields,
        address: { ...defaultAddress },
      });
    });

    it('sends only the fields SupplierRequest declares when updating, address included', () => {
      let captured: unknown;
      supplierServiceMock.updateSupplier = vi
        .fn()
        .mockImplementation((_companyId: string, _id: string, data: unknown) => {
          captured = data;
          return of(createSupplierWithAddress({ id: 'existing-id' }));
        });

      component.onSaveSupplier(createSupplierWithAddress({ id: 'existing-id' }));

      expect(captured).toStrictEqual({
        ...requestFields,
        address: { ...defaultAddress },
      });
    });

    it('omits the address when every one of its fields is empty', () => {
      let captured: unknown;
      supplierServiceMock.createSupplier = vi
        .fn()
        .mockImplementation((_companyId: string, data: unknown) => {
          captured = data;
          return of(createSupplierData({ id: 'new-id' }));
        });

      component.onSaveSupplier(
        createSupplierWithAddress({
          id: '',
          address: {
            street: null,
            streetNumber: null,
            internalNumber: null,
            neighborhood: null,
            zipCode: null,
            city: null,
            state: null,
            countryId: null,
          },
        }),
      );

      expect(captured).toStrictEqual({ ...requestFields });
    });
  });

  describe('carrying the company on every write (S2)', () => {
    it('should post the new supplier to the selected company’s path', () => {
      supplierServiceMock.createSupplier = vi
        .fn()
        .mockReturnValue(of(createSupplierWithAddress({ id: 'new-id' })));

      component.onSaveSupplier(createSupplierWithAddress({ id: '' }));

      expect(supplierServiceMock.createSupplier).toHaveBeenCalledWith(
        companyId,
        expect.objectContaining({ supplierName: 'Proveedor Test' }),
      );
      expect(supplierServiceMock.updateSupplier).not.toHaveBeenCalled();
    });

    it('should put the edited supplier under the same company', () => {
      supplierServiceMock.updateSupplier = vi
        .fn()
        .mockReturnValue(of(createSupplierWithAddress({ id: 'existing-id' })));

      component.onSaveSupplier(createSupplierWithAddress({ id: 'existing-id' }));

      expect(supplierServiceMock.updateSupplier).toHaveBeenCalledWith(
        companyId,
        'existing-id',
        expect.objectContaining({ supplierName: 'Proveedor Test' }),
      );
      expect(supplierServiceMock.createSupplier).not.toHaveBeenCalled();
    });

    it('should refuse to save when the URL names no company', async () => {
      // Every write is scoped to `/api/companies/{companyId}/suppliers`, so refusing is the only
      // honest answer: there is no endpoint to guess at.
      await setup({ companyId: null });

      component.onSaveSupplier(createSupplierWithAddress({ id: '' }));

      expect(supplierServiceMock.createSupplier).not.toHaveBeenCalled();
      expect(component.generalError()).toBe('Elegí una empresa antes de guardar el proveedor.');
    });

    it('should keep the company on the way back to the list after an update', () => {
      supplierServiceMock.updateSupplier = vi
        .fn()
        .mockReturnValue(of(createSupplierWithAddress({ id: 'existing-id' })));

      component.onSaveSupplier(createSupplierWithAddress({ id: 'existing-id' }));

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/suppliers'], {
        queryParams: { companyId },
      });
    });

    it('should keep the company on the way into the new supplier’s editor', () => {
      supplierServiceMock.createSupplier = vi
        .fn()
        .mockReturnValue(of(createSupplierWithAddress({ id: 'new-id' })));

      component.onSaveSupplier(createSupplierWithAddress({ id: '' }));

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/suppliers/edit', 'new-id'], {
        queryParams: { companyId },
      });
    });

    it('should keep the company on cancel', () => {
      component.cancelForm();

      expect(routerMock.navigate).toHaveBeenCalledWith(['/products/suppliers'], {
        queryParams: { companyId },
      });
    });
  });

  describe('edit mode', () => {
    it('should read the supplier under the URL’s company', async () => {
      await setup({
        supplierId: 'sup-1',
        supplierById: createSupplierWithAddress({ id: 'sup-1' }),
      });

      expect(supplierServiceMock.getSupplierById).toHaveBeenCalledWith(companyId, 'sup-1');
    });

    it('should read nothing when the URL names no company', async () => {
      await setup({ supplierId: 'sup-1', companyId: null });

      // A supplier id alone cannot be addressed on `/api/companies/{companyId}/suppliers`.
      expect(supplierServiceMock.getSupplierById).not.toHaveBeenCalled();
    });
  });
});
