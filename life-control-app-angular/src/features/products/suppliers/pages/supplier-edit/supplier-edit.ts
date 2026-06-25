import { ChangeDetectionStrategy, Component, effect, inject, signal, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { SupplierService } from '../../data/supplier.service';
import { ApiError } from '@shared/models';
import { Supplier, SupplierControl } from '../../models/supplier.models';
import { CountryService } from '@features/countries/data/country.service';
import { Country } from '@features/companies/countries/models/country.models';
import type { AddressControl } from '@shared/models/address.models';
import {
  NonNullableFormBuilder,
  FormGroup,
  FormControl,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { SuppliersForm } from '../../components/suppliers-form/suppliers-form';


@Component({
  selector: 'app-supplier-edit',
  imports: [ReactiveFormsModule, SuppliersForm],
  templateUrl: './supplier-edit.html',
  styleUrl: './supplier-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupplierEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private supplierService = inject(SupplierService);
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);
  private countryService = inject(CountryService);

  supplierId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  supplierForm = signal<FormGroup<SupplierControl>>(this.createForm());

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  addressServerErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  countries = signal<Country[]>([]);

  ngOnInit(): void {
    const id = this.supplierId();
    if (id) {
      this.isEditMode.set(true);
      this.loadSupplier(id);
    }

    // Load country catalog for address country selector
    this.countryService.getCountries().subscribe({
      next: (countries) => this.countries.set(countries),
      error: () => this.countries.set([]),
    });
  }

  private loadSupplier(id: string): void {
    this.supplierService.getSupplierById(id).subscribe({
      next: (supplier) => {
        this.supplierForm.set(
          this.fb.group({
            id: this.fb.control(supplier.id),
            supplierName: this.fb.control(supplier.supplierName, Validators.required),
            razonSocial: this.fb.control(supplier.razonSocial),
            rfc: this.fb.control(supplier.rfc, [Validators.required, Validators.pattern(/^[A-Za-z0-9]{12,13}$/)]),
            email: this.fb.control(supplier.email, Validators.email),
            phoneNumber: this.fb.control(supplier.phoneNumber),
            internalNumber: new FormControl<string | null>(supplier.internalNumber || null, { nonNullable: false }),
            address: new FormGroup<AddressControl>({
              street: new FormControl<string | null>(supplier.address?.street ?? null, { nonNullable: false }),
              streetNumber: new FormControl<string | null>(supplier.address?.streetNumber ?? null, { nonNullable: false }),
              internalNumber: new FormControl<string | null>(supplier.address?.internalNumber ?? null, { nonNullable: false }),
              neighborhood: new FormControl<string | null>(supplier.address?.neighborhood ?? null, { nonNullable: false }),
              zipCode: new FormControl<string | null>(supplier.address?.zipCode ?? null, { nonNullable: false }),
              city: new FormControl<string | null>(supplier.address?.city ?? null, { nonNullable: false }),
              state: new FormControl<string | null>(supplier.address?.state ?? null, { nonNullable: false }),
              countryId: new FormControl<string | null>(supplier.address?.countryId ?? null, { nonNullable: false }),
            }),
            enabled: this.fb.control(supplier.enabled),
          })
        );
      },
      error: (err) => {
        console.error('[SupplierEdit] Error loading supplier:', err);
      },
    });
  }

  private createForm(): FormGroup<SupplierControl> {
    return this.fb.group({
      id: this.fb.control(''),
      supplierName: this.fb.control('', Validators.required),
      razonSocial: this.fb.control(''),
      rfc: this.fb.control('', [Validators.required, Validators.pattern(/^[A-Za-z0-9]{12,13}$/)]),
      email: this.fb.control('', Validators.email),
      phoneNumber: this.fb.control(''),
      internalNumber: new FormControl<string | null>(null, { nonNullable: false }),
      address: new FormGroup<AddressControl>({
        street: new FormControl<string | null>(null, { nonNullable: false }),
        streetNumber: new FormControl<string | null>(null, { nonNullable: false }),
        internalNumber: new FormControl<string | null>(null, { nonNullable: false }),
        neighborhood: new FormControl<string | null>(null, { nonNullable: false }),
        zipCode: new FormControl<string | null>(null, { nonNullable: false }),
        city: new FormControl<string | null>(null, { nonNullable: false }),
        state: new FormControl<string | null>(null, { nonNullable: false }),
        countryId: new FormControl<string | null>(null, { nonNullable: false }),
      }),
      enabled: this.fb.control(true),
    });
  }

  onSaveSupplier(supplierData: Supplier): void {
    if (supplierData.id === '') {
      const { id, address, ...rest } = supplierData;
      const hasAddress = address && (
        address.street || address.streetNumber ||
        address.internalNumber || address.neighborhood ||
        address.zipCode || address.city ||
        address.state || address.countryId
      );
      const payload = {
        ...rest,
        ...(hasAddress ? { address } : {}),
      };
      this.supplierService.createSupplier(payload as unknown as Supplier).subscribe({
        next: (createdSupplier) => {
          this.router.navigate(['/products/suppliers/edit', createdSupplier.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    } else {
      const { id, address, ...rest } = supplierData;
      const hasAddress = address && (
        address.street || address.streetNumber ||
        address.internalNumber || address.neighborhood ||
        address.zipCode || address.city ||
        address.state || address.countryId
      );
      const payload = {
        ...rest,
        ...(hasAddress ? { address } : {}),
      };
      this.supplierService.updateSupplier(supplierData.id, payload as unknown as Supplier).subscribe({
        next: () => {
          this.router.navigate(['/products/suppliers']);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    }
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else {
      this.serverErrors.set({});
      this.generalError.set('Error inesperado. Intente de nuevo más tarde.');
    }
  }

  constructor() {
    // Server error mapping: extract address.* keys and strip prefix
    effect(() => {
      const allErrors = this.serverErrors();
      const addrErrors: Record<string, string> = {};
      Object.entries(allErrors).forEach(([key, value]) => {
        if (key.startsWith('address.')) {
          addrErrors[key.slice(8)] = value;
        }
      });
      this.addressServerErrors.set(addrErrors);
    });
  }

  cancelForm(): void {
    this.router.navigate(['/products/suppliers']);
  }

}
