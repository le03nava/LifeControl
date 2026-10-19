import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
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
      ...overrides,
    };
  }

  function createSupplierWithAddress(overrides: Partial<Supplier> = {}): Supplier {
    return createSupplierData({ address: { ...defaultAddress }, ...overrides });
  }

  beforeEach(async () => {
    supplierServiceMock = {
      createSupplier: vi.fn(),
      updateSupplier: vi.fn(),
      getSupplierById: vi.fn(),
    };
    routerMock = { navigate: vi.fn() };

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
            snapshot: { paramMap: { get: () => null } },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SupplierEdit);
    component = fixture.componentInstance;
    fixture.detectChanges();
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
      supplierServiceMock.createSupplier = vi.fn().mockImplementation((data: unknown) => {
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
        .mockImplementation((id: string, data: unknown) => {
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
      supplierServiceMock.createSupplier = vi.fn().mockImplementation((data: unknown) => {
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
});
