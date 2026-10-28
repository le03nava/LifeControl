import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { rxResource } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PageHeader } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';

/**
 * The products area's front door: pick the company, then everything else is scoped to it.
 *
 * `/products` carries no company because a product belongs to exactly one, and the area cannot
 * guess which one the operator means. Rather than a redirect — which would have to invent a
 * company, or loop back to itself — this is a screen, and the whole of its job is the one answer the
 * rest of the area needs.
 *
 * The list comes from `CompanyService.getCompanySelector`, the same claim-authorized read
 * `ProductCategoryList` and the product form already use: it is the endpoint that paints a
 * company choice for any authenticated caller, so the picker offers exactly the companies the
 * scoped screens can read afterwards.
 *
 * Choosing one navigates to `/products/c/:companyId`, the hub. A card and not a `mat-select`
 * because picking a company here is a navigation with a durable URL, not a filter on this page.
 */
@Component({
  selector: 'app-product-company-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PageHeader, MatCardModule, MatIconModule],
  templateUrl: './product-company-picker.html',
  styleUrl: './product-company-picker.scss',
})
export class ProductCompanyPicker {
  private readonly companyService = inject(CompanyService);

  readonly companiesResource = rxResource({
    params: () => ({}),
    stream: () => this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
  });

  readonly companies = computed(() =>
    this.companiesResource.hasValue() ? this.companiesResource.value() : undefined,
  );
  readonly loading = this.companiesResource.isLoading;
  readonly error = this.companiesResource.error;

  protected readonly httpErrorMessage = httpErrorMessage;

  onRetry(): void {
    this.companiesResource.reload();
  }
}
