import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { ConfirmDialog, ErrorBanner, PageHeader } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { hasAnyClientRole, LC_ADMIN } from '@core/security/roles';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { ProductCategoryService } from '../../data/product-category.service';
import { ProductCategory } from '../../models/product-category.models';
import {
  ProductCategoryDialog,
  ProductCategoryDialogData,
} from '../../components/product-category-dialog/product-category-dialog';

/** Indent per level, in pixels: the only thing that turns the flat depth-first list into a tree. */
const INDENT_PER_LEVEL = 24;

/** The endpoints are `lc-admin` only, so the screen offers the writes to the same principal. */
const CATEGORY_WRITE_ROLES = [LC_ADMIN];

/**
 * A 409 on this catalog is a rule refusal — a sibling name collision, a disabled ancestor, a cycle or
 * a fourth level — never a version precondition: the table carries no `version` column, so the shared
 * `httpErrorMessage` copy for that status ("the data changed while you were editing") describes
 * something that cannot have happened here (record F6).
 */
const CATEGORY_CONFLICT_MESSAGE =
  'La operación fue rechazada por una regla del árbol: el nombre no puede repetirse entre hermanas, el ' +
  'padre tiene que estar habilitado (una categoría deshabilitada no admite subcategorías habilitadas) ' +
  'y el árbol admite hasta tres niveles.';

/**
 * The company's product category tree, and the screen S3 asked for.
 *
 * The company is chosen on the screen, exactly as the department catalog does
 * (`DepartmentList`): an inline `mat-select` fed by `CompanyService.getCompanySelector`, seeded from
 * the `companyId` query parameter and written back to it with `replaceUrl` so a shared link
 * reproduces the screen. No request is issued until a company is selected, which is why the resource
 * params return `undefined` in that state.
 *
 * The rows are rendered in the order the API returned them — depth-first, a parent immediately before
 * its children — indented by `depth`. There is no client-side regroup: proving the API's order here
 * would only hide a change in it.
 *
 * Disable and delete both cascade on the server (a node's whole subtree follows it), so both sit
 * behind a confirmation that says so, and both reload the tree instead of patching one row.
 */
@Component({
  selector: 'app-product-category-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatTableModule,
    PageHeader,
    ErrorBanner,
  ],
  templateUrl: './product-category-list.html',
  styleUrl: './product-category-list.scss',
})
export class ProductCategoryList {
  private readonly companyService = inject(CompanyService);
  private readonly categoryService = inject(ProductCategoryService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);

  /** `lc-admin` reaches the write endpoints; every other authenticated caller reads the tree. */
  readonly canWrite = hasAnyClientRole(CATEGORY_WRITE_ROLES);

  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Company selection: seeded from the URL and kept there. */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  /**
   * `includeDisabled` is part of the resource params, so flipping it **re-issues the request**: the
   * API already drops disabled nodes and the subtree below them, so there is nothing to filter
   * client-side.
   */
  readonly includeDisabled = signal(false);

  /** A failed enable / disable / delete, surfaced instead of swallowed. */
  readonly actionError = signal<string | null>(null);

  readonly indentPerLevel = INDENT_PER_LEVEL;
  readonly displayedColumns = [
    'categoryName',
    'description',
    'satCode',
    'displayOrder',
    'enabled',
    'actions',
  ];

  readonly categoriesResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      return companyId ? { companyId, includeDisabled: this.includeDisabled() } : undefined;
    },
    // The error is *not* swallowed: `rxResource` keeps it, and `loadError` renders the retry state.
    // A `catchError(() => of([]))` here would turn a failed read into an empty tree, which is the one
    // message this screen must not print when the API did not answer.
    stream: ({ params }) =>
      this.categoryService.getCategories(params.companyId, params.includeDisabled),
    defaultValue: [] as ProductCategory[],
  });

  readonly categories = computed(() =>
    this.categoriesResource.hasValue() ? this.categoriesResource.value() : [],
  );
  readonly loading = this.categoriesResource.isLoading;

  readonly loadError = computed<string | null>(() => {
    const error = this.categoriesResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  // ─── Event handlers ──────────────────────────────────────────

  /**
   * Keeps the selection in the URL (replace, so mid-page changes do not pollute history) and clears
   * the state that belonged to the previous company.
   */
  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    this.includeDisabled.set(false);
    this.actionError.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  onIncludeDisabledChange(checked: boolean): void {
    this.includeDisabled.set(checked);
  }

  /** Re-runs a tree read that failed. */
  reloadList(): void {
    this.categoriesResource.reload();
  }

  onCreate(): void {
    this.openDialog(null);
  }

  onEdit(category: ProductCategory): void {
    this.openDialog(category);
  }

  /** One control for both directions, so a row never offers an action that cannot apply to it. */
  onToggleEnabled(category: ProductCategory): void {
    if (category.enabled) {
      this.onDisable(category);
      return;
    }

    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.categoryService.setCategoryEnabled(companyId, category.id, true).subscribe({
      next: () => {
        this.actionError.set(null);
        this.notifications.showSuccess(`Categoría "${category.categoryName}" habilitada`);
        this.reloadAfterWrite();
      },
      error: (error: unknown) => this.actionError.set(this.writeErrorMessage(error)),
    });
  }

  /** Disabling a node disables its whole subtree, so it is asked for, not assumed. */
  onDisable(category: ProductCategory): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.dialog
      .open(ConfirmDialog, {
        data: {
          title: 'Deshabilitar categoría',
          message:
            `¿Confirmás que querés deshabilitar "${category.categoryName}"? Su categoría y todas sus ` +
            'subcategorías dejan de estar disponibles para clasificar productos, pero se conservan y ' +
            'podés volver a habilitarlas.',
          confirmLabel: 'Deshabilitar',
          destructive: true,
        },
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) return;

        this.categoryService.setCategoryEnabled(companyId, category.id, false).subscribe({
          next: () => {
            this.actionError.set(null);
            this.notifications.showSuccess(`Categoría "${category.categoryName}" deshabilitada`);
            this.reloadAfterWrite();
          },
          error: (error: unknown) => this.actionError.set(this.writeErrorMessage(error)),
        });
      });
  }

  /** Soft delete: the row is kept, disabled, together with its subtree. */
  onDelete(category: ProductCategory): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    this.dialog
      .open(ConfirmDialog, {
        data: {
          title: 'Eliminar categoría',
          message:
            `¿Confirmás que querés eliminar "${category.categoryName}"? El borrado es lógico: la ` +
            'categoría y sus subcategorías se conservan deshabilitadas, así que podés restaurarlas ' +
            'habilitándolas de nuevo.',
          confirmLabel: 'Eliminar',
          destructive: true,
        },
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) return;

        this.categoryService.deleteCategory(companyId, category.id).subscribe({
          next: () => {
            this.actionError.set(null);
            this.notifications.showSuccess(`Categoría "${category.categoryName}" eliminada`);
            this.reloadAfterWrite();
          },
          error: (error: unknown) => this.actionError.set(this.writeErrorMessage(error)),
        });
      });
  }

  private openDialog(category: ProductCategory | null): void {
    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    const data: ProductCategoryDialogData = {
      companyId,
      category,
      categories: this.categories(),
    };

    this.dialog
      .open<ProductCategoryDialog, ProductCategoryDialogData, ProductCategory | null>(
        ProductCategoryDialog,
        { data, width: '520px' },
      )
      .afterClosed()
      .subscribe((saved) => {
        if (!saved) return;

        this.actionError.set(null);
        this.notifications.showSuccess(
          category
            ? `Categoría "${saved.categoryName}" actualizada`
            : `Categoría "${saved.categoryName}" creada`,
        );
        this.reloadAfterWrite();
      });
  }

  /** Every write can move, add or cascade a node, so the tree is re-read instead of patched. */
  private reloadAfterWrite(): void {
    this.categoriesResource.reload();
  }

  private writeErrorMessage(error: unknown): string {
    const httpError = error instanceof HttpErrorResponse ? error : null;
    return httpError?.status === 409 ? CATEGORY_CONFLICT_MESSAGE : httpErrorMessage(error);
  }
}
