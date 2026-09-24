import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import { observeMobileViewport } from '@shared/responsive/mobile-viewport';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { GoodsReceiptService } from '../../data/goods-receipt.service';
import { PurchaseOrderService } from '../../../purchase-orders/data/purchase-order.service';
import { StatusChip } from '../../../purchase-orders/components/status-chip/status-chip';
import type { GoodsReceipt } from '../../models/receipt.models';
import type { PurchaseOrder } from '../../../purchase-orders/models/purchase-order.models';

/**
 * One receipt line joined with its purchase-order detail.
 *
 * `GoodsReceiptLineResponse` only carries `purchaseOrderDetailId`,
 * `productVariantId`, `quantityReceived` and `comments`, so the receipt alone
 * cannot name a product: the order is joined in by `purchaseOrderDetailId`.
 */
export interface ReceiptLineView {
  id: string;
  /** Product name resolved from the order; `null` while the order is unavailable. */
  productName: string | null;
  /** Variant label, or `null` when the order is unavailable. */
  variantName: string | null;
  quantityReceived: number;
  comments: string | null;
}

/**
 * Read-only goods-receipt detail page.
 *
 * The receipt is fetched first and the purchase order second, only once the
 * receipt has resolved, because the order is the only source for line names.
 *
 * The page intentionally does NOT render `receivingLocationId`: it is a bare
 * UUID and the client has no endpoint that resolves a location name from it.
 * The backend denormalizes the order number and the status name on the receipt,
 * but not the receiving-location name.
 */
@Component({
  selector: 'app-receipt-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PageHeader,
    DatePipe,
    StatusChip,
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatCardModule,
  ],
  templateUrl: './receipt-detail.html',
  styleUrl: './receipt-detail.scss',
})
export class ReceiptDetail {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly goodsReceiptService = inject(GoodsReceiptService);
  private readonly purchaseOrderService = inject(PurchaseOrderService);

  /** Route id read from the snapshot; `null` when the route carries no id. */
  readonly id: string | null = this.route.snapshot.paramMap.get('id');

  /** The receipt itself. `undefined` params mean "do not fetch". */
  readonly receiptResource = rxResource({
    params: () => (this.id ? { id: this.id } : undefined),
    stream: ({ params }) => this.goodsReceiptService.getReceipt(params.id),
  });

  /**
   * Reading `value()` on a resource in an error state throws, so the error is
   * projected to `null` through `hasValue()` exactly like the list page does.
   */
  readonly receipt = computed<GoodsReceipt | null>(() =>
    this.receiptResource.hasValue() ? this.receiptResource.value() : null,
  );
  readonly loading = this.receiptResource.isLoading;

  /**
   * The order the receipt belongs to, fetched only once the receipt resolved.
   * `undefined` params keep the resource idle while the receipt is loading.
   */
  readonly orderResource = rxResource({
    params: () => {
      const receipt = this.receipt();
      return receipt ? { orderId: receipt.purchaseOrderId } : undefined;
    },
    stream: ({ params }) => this.purchaseOrderService.getPurchaseOrder(params.orderId),
  });

  readonly order = computed<PurchaseOrder | null>(() =>
    this.orderResource.hasValue() ? this.orderResource.value() : null,
  );

  /** Narrow viewports render one card per line instead of the overflowing table. */
  // One card per line, below the shared mobile max width.
  readonly isMobile = observeMobileViewport();

  readonly headerTitle = computed(() => {
    const receipt = this.receipt();
    return receipt ? `Recibo ${receipt.receiptNumber}` : 'Recibo';
  });

  readonly headerSubtitle = computed<string | undefined>(() => {
    const receipt = this.receipt();
    return receipt ? `Orden ${receipt.orderNumber}` : undefined;
  });

  /**
   * Lines joined with the order's details. When the order is unavailable the
   * names stay `null` instead of falling back to the raw identifiers.
   */
  readonly lines = computed<ReceiptLineView[]>(() => {
    const receipt = this.receipt();
    if (!receipt) {
      return [];
    }

    const detailsById = new Map((this.order()?.details ?? []).map((detail) => [detail.id, detail]));

    return receipt.lines.map((line) => {
      const detail = detailsById.get(line.purchaseOrderDetailId);
      if (!detail) {
        return {
          id: line.id,
          productName: null,
          variantName: null,
          quantityReceived: line.quantityReceived,
          comments: line.comments,
        };
      }
      return {
        id: line.id,
        productName: detail.productName,
        variantName: detail.productVariantName?.trim() || 'Sin variante',
        quantityReceived: line.quantityReceived,
        comments: line.comments,
      };
    });
  });

  /** Error copy that distinguishes the 404, the 403 and everything else. */
  readonly errorMessage = computed<string | null>(() => {
    const error = this.receiptResource.error();
    if (!error) {
      return this.id === null ? 'No se encontró el recibo.' : null;
    }

    const status = unwrapHttpError(error)?.status;
    if (status === 404) {
      return 'No se encontró el recibo.';
    }
    if (status === 403) {
      return 'No tenés permisos para ver este recibo.';
    }
    return httpErrorMessage(error);
  });

  readonly displayedColumns: string[] = ['product', 'variant', 'quantityReceived', 'comments'];

  goBack(): void {
    this.router.navigate(['/purchases/receipts']);
  }
}
