import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { PageHeader } from '@shared/ui';
import { ProductService } from '../../data/product.service';
import { ProductVariantList } from '../product-variant-list/product-variant-list';

/**
 * Thin route host for the product-scoped variant list (`edit/:id/variants`).
 *
 * D17: this route does **not** redirect into the workspace. It is gated by
 * `VARIANT_ROLES` (`lc-admin` + `lc-sales`) while the workspace `edit/:id` is
 * admin-only, and the variant screens are the sales principal's only entry point.
 * Redirecting would deny `lc-sales` the list, whose only entry point is the
 * `product-variant-stock-search.ts:157` navigation: the variant screens exist for
 * `lc-admin` and `lc-sales` alike, while `edit/:id` is admin-only.
 *
 * Since the list container became tab content and no longer renders its own
 * `app-page-header`, this host supplies the titled header and passes the product id
 * down. It owns no table, no dialogs, no pagination and no store logic: all of that
 * stays in the container.
 */
@Component({
  selector: 'app-product-variant-list-host',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PageHeader, ProductVariantList],
  templateUrl: './product-variant-list-host.html',
  styleUrl: './product-variant-list-host.scss',
})
export class ProductVariantListHost {
  private readonly route = inject(ActivatedRoute);
  private readonly productService = inject(ProductService);

  /** The product whose variants are listed, read from the route param `id`. */
  readonly productId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  /**
   * The owning product, only for the page header.
   *
   * Deliberately the loaded entity and not the live list state: the header identifies
   * which product the screen belongs to, nothing else. It is read exactly once, on
   * mount, because the route param cannot change without a full route change.
   */
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
}
