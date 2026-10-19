import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
  OnInit,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { SupplierService } from '../../data/supplier.service';
import { ApiError } from '@shared/models';
import { SupplierControl, SupplierFormValue, SupplierRequest } from '../../models/supplier.models';
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
import { ErrorBanner } from '@shared/ui';

@Component({
  selector: 'app-supplier-edit',
  imports: [ReactiveFormsModule, ErrorBanner, SuppliersForm],
  templateUrl: './supplier-edit.html',
  styleUrl: './supplier-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SupplierEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly supplierService = inject(SupplierService);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly router = inject(Router);
  private readonly countryService = inject(CountryService);
  private readonly destroyRef = inject(DestroyRef);

  supplierId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  supplierForm = signal<FormGroup<SupplierControl>>(this.createForm());

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  addressServerErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  // Country catalog for address country selector (error → empty, matching previous behavior)
  countries = toSignal(
    this.countryService.getCountries().pipe(catchError(() => of([] as Country[]))),
    { initialValue: [] },
  );

  ngOnInit(): void {
    const id = this.supplierId();
    if (id) {
      this.isEditMode.set(true);
      this.loadSupplier(id);
    }
  }

  private loadSupplier(id: string): void {
    this.supplierService
      .getSupplierById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (supplier) => {
          this.supplierForm.set(
            this.fb.group({
              id: this.fb.control(supplier.id),
              supplierName: this.fb.control(supplier.supplierName, Validators.required),
              razonSocial: this.fb.control(supplier.razonSocial),
              rfc: this.fb.control(supplier.rfc, [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9]{12,13}$/),
              ]),
              email: this.fb.control(supplier.email, Validators.email),
              phoneNumber: this.fb.control(supplier.phoneNumber),
              internalNumber: new FormControl<string | null>(supplier.internalNumber || null, {
                nonNullable: false,
              }),
              address: new FormGroup<AddressControl>({
                street: new FormControl<string | null>(supplier.address?.street ?? null, {
                  nonNullable: false,
                }),
                streetNumber: new FormControl<string | null>(
                  supplier.address?.streetNumber ?? null,
                  { nonNullable: false },
                ),
                internalNumber: new FormControl<string | null>(
                  supplier.address?.internalNumber ?? null,
                  { nonNullable: false },
                ),
                neighborhood: new FormControl<string | null>(
                  supplier.address?.neighborhood ?? null,
                  { nonNullable: false },
                ),
                zipCode: new FormControl<string | null>(supplier.address?.zipCode ?? null, {
                  nonNullable: false,
                }),
                city: new FormControl<string | null>(supplier.address?.city ?? null, {
                  nonNullable: false,
                }),
                state: new FormControl<string | null>(supplier.address?.state ?? null, {
                  nonNullable: false,
                }),
                countryId: new FormControl<string | null>(supplier.address?.countryId ?? null, {
                  nonNullable: false,
                }),
              }),
              enabled: this.fb.control(supplier.enabled),
            }),
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

  onSaveSupplier(supplierData: SupplierFormValue): void {
    const request = this.buildSupplierRequest(supplierData);
    if (supplierData.id === '') {
      this.supplierService
        .createSupplier(request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (createdSupplier) => {
            this.router.navigate(['/products/suppliers/edit', createdSupplier.id]);
          },
          error: (err: HttpErrorResponse) => {
            this.handleServerError(err);
          },
        });
    } else {
      this.supplierService
        .updateSupplier(supplierData.id, request)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: () => {
            this.router.navigate(['/products/suppliers']);
          },
          error: (err: HttpErrorResponse) => {
            this.handleServerError(err);
          },
        });
    }
  }

  /**
   * Builds the wire body explicitly against `SupplierRequest` (S4).
   *
   * The response model also carries `id`, `createdAt` and `updatedAt`, and the API rejects an
   * undeclared key before validating any field, so the body is written out key by key.
   */
  private buildSupplierRequest(supplier: SupplierFormValue): SupplierRequest {
    const address = supplier.address;
    const hasAddress =
      address &&
      (address.street ||
        address.streetNumber ||
        address.internalNumber ||
        address.neighborhood ||
        address.zipCode ||
        address.city ||
        address.state ||
        address.countryId);
    return {
      supplierName: supplier.supplierName,
      razonSocial: supplier.razonSocial,
      rfc: supplier.rfc,
      email: supplier.email,
      phoneNumber: supplier.phoneNumber,
      internalNumber: supplier.internalNumber,
      enabled: supplier.enabled,
      ...(hasAddress ? { address } : {}),
    };
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
