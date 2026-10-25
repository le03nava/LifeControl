import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogContent,
  MatDialogRef,
  MatDialogTitle,
} from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs/operators';
import { ErrorBanner } from '@shared/ui';
import { httpErrorMessage } from '@shared/data';
import { ProductCategoryService } from '../../data/product-category.service';
import {
  MAX_CATEGORY_DEPTH,
  ProductCategory,
  ProductCategoryControl,
  ProductCategoryRequest,
} from '../../models/product-category.models';

/** Payload the list page passes to {@link ProductCategoryDialog}. */
export interface ProductCategoryDialogData {
  companyId: string;
  /** The row being edited; `null` in create mode. */
  category: ProductCategory | null;
  /** The company tree as the list loaded it — the only source of parent options. */
  categories: ProductCategory[];
}

/** One entry of the parent picker. */
export interface ParentOption {
  id: string;
  label: string;
  /** False when choosing it would be refused by the API, so the option renders disabled. */
  selectable: boolean;
}

/**
 * A 409 on this catalog is never a version precondition — the table has no `version` column — so the
 * shared copy `httpErrorMessage` returns for that status ("the data changed while you were editing")
 * would send the operator to reload a page where reloading changes nothing. The three refusals the API
 * can answer here are named instead, without pretending to know which one fired: the service reports
 * them all as 409.
 */
const CATEGORY_CONFLICT_MESSAGE =
  'No se pudo guardar la categoría: revisá que el nombre no esté repetido entre sus hermanas, que la ' +
  'categoría padre esté habilitada y que el árbol no supere los tres niveles.';

/**
 * Create/edit form of one node of the company's category tree.
 *
 * It owns the write and closes with the saved category (or `null` on cancel), so the list only has to
 * reload on a truthy result — the same contract `ProductSupplierDialog` uses in this feature.
 *
 * The parent picker is built from the tree the list already loaded, and it refuses what the API would
 * refuse: the node itself, its whole subtree (a move into a descendant is a cycle), a level-3 node (a
 * child there would be level 4) and a disabled node (a child under it could never be enabled). The one
 * exception is the node's current parent, which stays selectable even when disabled — otherwise the
 * select would silently show "root" and a rename would move the node.
 */
@Component({
  selector: 'app-product-category-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatDialogTitle,
    MatDialogContent,
    MatDialogActions,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    ErrorBanner,
  ],
  templateUrl: './product-category-dialog.html',
  styleUrl: './product-category-dialog.scss',
})
export class ProductCategoryDialog {
  private readonly dialogRef =
    inject<MatDialogRef<ProductCategoryDialog, ProductCategory | null>>(MatDialogRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly categoryService = inject(ProductCategoryService);

  readonly data = inject<ProductCategoryDialogData>(MAT_DIALOG_DATA);

  readonly editMode = computed(() => this.data.category !== null);
  readonly form = signal<FormGroup<ProductCategoryControl>>(this.buildForm());
  readonly saving = signal(false);
  /** A refused write, surfaced in the banner while the dialog stays open. */
  readonly formError = signal<string | null>(null);

  readonly parentOptions = computed<ParentOption[]>(() => {
    const excluded = this.excludedIds();
    const currentParentId = this.data.category?.parentCategoryId ?? null;

    return this.data.categories
      .filter((category) => !excluded.has(category.id))
      .map((category) => ({
        id: category.id,
        label: `${'— '.repeat(Math.max(category.depth - 1, 0))}${category.categoryName}`,
        // The current parent stays selectable even when it is disabled or already at the ceiling:
        // omitting it would make the picker show "root" for a node that is not a root.
        selectable:
          category.id === currentParentId ||
          (category.depth < MAX_CATEGORY_DEPTH && category.enabled),
      }));
  });

  private buildForm(): FormGroup<ProductCategoryControl> {
    const category = this.data.category;

    return this.fb.group({
      categoryName: this.fb.control(category?.categoryName ?? '', [
        Validators.required,
        Validators.maxLength(100),
      ]),
      parentCategoryId: this.fb.control(category?.parentCategoryId ?? ''),
      description: this.fb.control(category?.description ?? '', [Validators.maxLength(255)]),
      displayOrder: this.fb.control(category?.displayOrder ?? 0, [
        Validators.min(0),
        Validators.pattern(/^\d+$/),
      ]),
    });
  }

  /** The edited node and every descendant: a move into either would be a cycle or itself. */
  private excludedIds(): Set<string> {
    const excluded = new Set<string>();
    const editing = this.data.category;
    if (!editing) {
      return excluded;
    }

    excluded.add(editing.id);
    let added = true;
    while (added) {
      added = false;
      for (const category of this.data.categories) {
        if (
          category.parentCategoryId !== null &&
          excluded.has(category.parentCategoryId) &&
          !excluded.has(category.id)
        ) {
          excluded.add(category.id);
          added = true;
        }
      }
    }

    return excluded;
  }

  onSave(): void {
    const form = this.form();
    const value = form.getRawValue();
    const parentCategoryId = value.parentCategoryId === '' ? null : value.parentCategoryId;
    const request: ProductCategoryRequest = {
      parentCategoryId,
      categoryName: value.categoryName.trim(),
      description: value.description.trim() === '' ? null : value.description.trim(),
      displayOrder: value.displayOrder,
      // Never omitted: the server defaults an absent flag to `true` and cascades a `false` one, so an
      // edit that dropped it would re-enable a disabled category and its whole subtree (record F4).
      enabled: this.data.category?.enabled ?? true,
    };

    if (form.invalid || request.categoryName === '') {
      form.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.formError.set(null);

    const companyId = this.data.companyId;
    const saved$ = this.data.category
      ? this.categoryService.updateCategory(companyId, this.data.category.id, request)
      : this.categoryService.createCategory(companyId, request);

    saved$.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => this.dialogRef.close(saved),
      error: (error: unknown) => this.formError.set(this.writeErrorMessage(error)),
    });
  }

  cancel(): void {
    this.dialogRef.close(null);
  }

  private writeErrorMessage(error: unknown): string {
    const httpError = error instanceof HttpErrorResponse ? error : null;
    return httpError?.status === 409 ? CATEGORY_CONFLICT_MESSAGE : httpErrorMessage(error);
  }
}
