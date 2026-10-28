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
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { ProductSupplierDialog } from '../../components/product-supplier-dialog/product-supplier-dialog';
import { ProductSupplier } from '../../models/product-supplier.models';
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

  /**
   * The company that owns the product. The link is company-scoped even though the supplier is global.
   *
   * An input and not a route read, for the same reason as the variant tab: the workspace and the
   * create stepper both render this list, and the shell that knows the company passes it.
   */
  readonly companyId = input.required<string>();

  /** The loaded supplier count, emitted on every successful read. */
  readonly countChange = output<number>();

  private readonly productSupplierService = inject(ProductSupplierService);
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
    params: () => ({ companyId: this.companyId(), productId: this.productId() }),
    stream: ({ params }) =>
      this.productSupplierService.getSuppliers(params.companyId, params.productId),
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
    this.openSupplierDialog();
  }

  editSupplier(row: ProductSupplier): void {
    this.openSupplierDialog(row);
  }

  /**
   * Opens the create/edit dialog and reloads the list only when it closed with a
   * saved entity (D22: `null` on cancel or dismissal). The dialog owns the write
   * and stays open with its own error banner on failure, so no error handling here.
   */
  private openSupplierDialog(assignment?: ProductSupplier): void {
    this.dialog
      .open<
        ProductSupplierDialog,
        { companyId: string; productId: string; assignment?: ProductSupplier },
        ProductSupplier | null
      >(ProductSupplierDialog, {
        data: { companyId: this.companyId(), productId: this.productId(), assignment },
        width: '560px',
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((saved) => {
        if (saved) {
          this.suppliersResource.reload();
        }
      });
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
            .removeSupplier(this.companyId(), this.productId(), psId)
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
