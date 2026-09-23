import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { RemoveSupplierDialog } from '../../ui/remove-supplier-dialog/remove-supplier-dialog';
import { httpErrorMessage } from '@shared/data';

@Component({
  selector: 'app-product-supplier-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
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
  /** The product whose supplier assignments are shown. Owned by the workspace shell. */
  readonly productId = input.required<string>();

  /** The loaded supplier count, emitted on every successful read. */
  readonly countChange = output<number>();

  private readonly productSupplierService = inject(ProductSupplierService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly displayedColumns: string[] = [
    'supplierName',
    'purchaseCost',
    'main',
    'enabled',
    'actions',
  ];

  readonly suppliersResource = rxResource({
    params: () => ({ productId: this.productId() }),
    stream: ({ params }) => this.productSupplierService.getSuppliers(params.productId),
  });

  readonly suppliers = computed(() =>
    this.suppliersResource.hasValue() ? this.suppliersResource.value() : undefined,
  );
  readonly loading = this.suppliersResource.isLoading;
  readonly error = this.suppliersResource.error;

  protected readonly httpErrorMessage = httpErrorMessage;

  constructor() {
    effect(() => {
      const suppliers = this.suppliers();
      if (suppliers) {
        this.countChange.emit(suppliers.length);
      }
    });
  }

  addSupplier(): void {
    this.router.navigate(['/products/edit', this.productId(), 'suppliers', 'create']);
  }

  editSupplier(psId: string): void {
    this.router.navigate(['/products/edit', this.productId(), 'suppliers', 'edit', psId]);
  }

  confirmDelete(psId: string, supplierName: string): void {
    const dialogRef = this.dialog.open(RemoveSupplierDialog, {
      data: { supplierName },
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        if (result) {
          this.productSupplierService
            .removeSupplier(this.productId(), psId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
              next: () => this.suppliersResource.reload(),
            });
        }
      });
  }

  onRetry(): void {
    this.suppliersResource.reload();
  }
}
