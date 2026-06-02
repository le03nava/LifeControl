import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { SupplierService } from '../../data/supplier.service';
import { ApiError } from '@shared/models';
import { Supplier, SupplierControl } from '../../models/supplier.models';
import {
  NonNullableFormBuilder,
  FormGroup,
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

  supplierId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  supplierForm = signal<FormGroup<SupplierControl>>(this.createForm());

  isEditMode = signal(false);
  serverErrors = signal<Record<string, string>>({});
  generalError = signal<string | null>(null);

  ngOnInit(): void {
    const id = this.supplierId();
    if (id) {
      this.isEditMode.set(true);
      this.loadSupplier(id);
    }
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
            street: this.fb.control(supplier.street || ''),
            streetNumber: this.fb.control(supplier.streetNumber || ''),
            neighborhood: this.fb.control(supplier.neighborhood || ''),
            zipCode: this.fb.control(supplier.zipCode || ''),
            city: this.fb.control(supplier.city || ''),
            state: this.fb.control(supplier.state || ''),
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
      street: this.fb.control(''),
      streetNumber: this.fb.control(''),
      neighborhood: this.fb.control(''),
      zipCode: this.fb.control(''),
      city: this.fb.control(''),
      state: this.fb.control(''),
      enabled: this.fb.control(true),
    });
  }

  onSaveSupplier(supplierData: Supplier): void {
    if (supplierData.id === '') {
      const { id, ...createData } = supplierData;
      this.supplierService.createSupplier(createData).subscribe({
        next: (createdSupplier) => {
          this.router.navigate(['/products/suppliers/edit', createdSupplier.id]);
        },
        error: (err: HttpErrorResponse) => {
          this.handleServerError(err);
        },
      });
    } else {
      this.supplierService.updateSupplier(supplierData.id, supplierData).subscribe({
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

  cancelForm(): void {
    this.router.navigate(['/products/suppliers']);
  }

}
