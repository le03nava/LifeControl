import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductSupplier } from '../../models/product-supplier.models';
import { of } from 'rxjs';

@Component({
  selector: 'app-product-supplier-list',
  standalone: true,
  imports: [
    CurrencyPipe,
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatCardModule,
  ],
  templateUrl: './product-supplier-list.html',
  styleUrl: './product-supplier-list.scss',
})
export class ProductSupplierList {
  private productSupplierService = inject(ProductSupplierService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly productId = signal<string | null>(
    this.route.snapshot.paramMap.get('id'),
  );

  readonly displayedColumns: string[] = [
    'supplierName',
    'purchaseCost',
    'main',
    'enabled',
    'actions',
  ];

  readonly suppliersResource = rxResource({
    params: () => ({
      productId: this.productId(),
    }),
    stream: ({ params }) => {
      if (!params.productId) {
        return of([] as ProductSupplier[]);
      }
      return this.productSupplierService.getSuppliers(params.productId);
    },
  });

  readonly suppliers = this.suppliersResource.value;
  readonly loading = this.suppliersResource.isLoading;
  readonly error = this.suppliersResource.error;

  constructor() {
    effect(() => {
      const id = this.productId();
      if (!id) {
        this.router.navigate(['/products/list']);
      }
    });
  }

  addSupplier(): void {
    const id = this.productId();
    if (id) {
      this.router.navigate(['/products/edit', id, 'suppliers', 'create']);
    }
  }

  editSupplier(psId: string): void {
    const id = this.productId();
    if (id) {
      this.router.navigate(['/products/edit', id, 'suppliers', 'edit', psId]);
    }
  }

  confirmDelete(psId: string, supplierName: string): void {
    const confirmed = window.confirm(
      `Are you sure you want to remove the assignment for "${supplierName}"? This action cannot be undone.`,
    );
    if (confirmed) {
      const id = this.productId();
      if (id) {
        this.productSupplierService
          .removeSupplier(id, psId)
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => this.suppliersResource.reload(),
          });
      }
    }
  }

  onRetry(): void {
    this.suppliersResource.reload();
  }
}
