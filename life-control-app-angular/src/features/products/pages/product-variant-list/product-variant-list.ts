import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatTableModule } from '@angular/material/table';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { ProductService } from '../../data/product.service';
import { ProductVariantService } from '../../data/product-variant.service';
import { Page } from '../../models/product.models';
import { ProductVariant } from '../../models/product-variant.models';
import { DisableVariantDialogComponent } from '../../ui/disable-variant-dialog/disable-variant-dialog';

/** Page emitted while the route carries no product id, so the template never sees `null`. */
const EMPTY_PAGE: Page<ProductVariant> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 0,
  number: 0,
  first: true,
  last: true,
  empty: true,
};

/**
 * List of a product's **global variant definitions**.
 *
 * The list endpoint is called **without** `storeId`: in that mode the backend
 * answers with the product's global definitions and leaves `companyStoreId`,
 * `listPrice`, `costPrice` and `stock` `null` (per-store stock and prices are a
 * separate slice). That is why the table shows only the identity columns
 * (`barCode`, `variantName`) and the `enabled` state — rendering price or stock
 * would show a column that is always empty here.
 *
 * `DELETE` is a soft delete that flips `enabled` to `false`; `PATCH .../enable`
 * brings the variant back. The user-facing actions are therefore "Deshabilitar"
 * and "Habilitar", never a hard delete.
 */
@Component({
  selector: 'app-product-variant-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatChipsModule,
    MatPaginatorModule,
    MatCardModule,
    PageHeader,
  ],
  templateUrl: './product-variant-list.html',
  styleUrl: './product-variant-list.scss',
})
export class ProductVariantList {
  private readonly productVariantService = inject(ProductVariantService);
  private readonly productService = inject(ProductService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  /** The owning product, only for the page header. */
  readonly productResource = rxResource({
    params: () => ({ productId: this.productId() }),
    stream: ({ params }) => {
      if (!params.productId) {
        return of(null);
      }
      return this.productService.getProductById(params.productId);
    },
  });
  readonly product = computed(() =>
    this.productResource.hasValue() ? this.productResource.value() : undefined,
  );

  readonly variantsResource = rxResource({
    params: () => ({
      productId: this.productId(),
      page: this.pageIndex(),
      size: this.pageSize(),
    }),
    stream: ({ params }) => {
      if (!params.productId) {
        return of(EMPTY_PAGE);
      }
      // No `storeId`: this slice lists global definitions, not per-store rows.
      return this.productVariantService.getVariants(
        params.productId,
        undefined,
        params.page,
        params.size,
      );
    },
  });

  readonly variants = computed(() =>
    this.variantsResource.hasValue() ? this.variantsResource.value() : undefined,
  );
  readonly loading = this.variantsResource.isLoading;
  readonly error = this.variantsResource.error;
  /** Whether the paginator is worth rendering (more than one page of results). */
  readonly hasMultiplePages = computed(() => (this.variants()?.totalPages ?? 0) > 1);

  protected readonly httpErrorMessage = httpErrorMessage;

  readonly displayedColumns: string[] = ['barCode', 'variantName', 'enabled', 'actions'];

  constructor() {
    effect(() => {
      if (!this.productId()) {
        this.router.navigate(['/products/list']);
      }
    });
  }

  addVariant(): void {
    const id = this.productId();
    if (id) {
      // The create page lands in the next work unit; the navigation target is final.
      this.router.navigate(['/products/edit', id, 'variants', 'create']);
    }
  }

  editVariant(variantId: string): void {
    const id = this.productId();
    if (id) {
      // The edit page lands in the next work unit; the navigation target is final.
      this.router.navigate(['/products/edit', id, 'variants', 'edit', variantId]);
    }
  }

  confirmDisable(variant: ProductVariant): void {
    const dialogRef = this.dialog.open(DisableVariantDialogComponent, {
      data: { variant },
    });

    dialogRef
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result: boolean) => {
        const id = this.productId();
        if (result && id) {
          // Soft delete: the backend flips `enabled` to false, it does not remove the row.
          this.productVariantService
            .deleteVariant(id, variant.id)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({ next: () => this.variantsResource.reload() });
        }
      });
  }

  enableVariant(variantId: string): void {
    const id = this.productId();
    if (id) {
      this.productVariantService
        .enableVariant(id, variantId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({ next: () => this.variantsResource.reload() });
    }
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  onRetry(): void {
    this.variantsResource.reload();
  }
}
