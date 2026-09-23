import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductVariant, ProductVariantRequest } from '../../models/product-variant.models';
import {
  ProductVariantControl,
  ProductVariantForm,
} from '../product-variant-form/product-variant-form';
import { ErrorBanner, ConfirmDialog } from '@shared/ui';

/**
 * The single message for a 409 on the global definition.
 *
 * The backend answers 409 for a duplicate barcode AND for a duplicate variant
 * name within the product, and it sends no field attribution in either case.
 * Naming both possibilities is the only honest copy: guessing which field
 * collided would present a fact the server never sent. Kept form-level on
 * purpose — never bound to a single control.
 */
const VARIANT_DUPLICATE_MESSAGE =
  'Ya tenés una variante con ese código de barras o ese nombre para este producto';

/** Payload the opener passes to {@link ProductVariantDialog}. */
export interface ProductVariantDialogData {
  productId: string;
  /** The row the list already holds; present only in edit mode. */
  variant?: ProductVariant;
}

/**
 * Create/edit host for the **global definition** of a product variant.
 *
 * Replaces the routed create/edit screen for the definition: it owns the write and
 * closes with the saved entity (or `null` on cancel), so the list only has to reload
 * on a truthy result (D22). It edits `barCode` + `variantName` and nothing else —
 * the per-store stock and prices panel stays on the store-scoped page (D8/D18).
 *
 * It holds no `ActivatedRoute`, no `Router` and no store handling: the opener passes
 * the owning product and, in edit mode, the row.
 */
@Component({
  selector: 'app-product-variant-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatDialogTitle, ErrorBanner, ProductVariantForm],
  templateUrl: './product-variant-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVariantDialog {
  private readonly dialogRef =
    inject<MatDialogRef<ProductVariantDialog, ProductVariant | null>>(MatDialogRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly productVariantService = inject(ProductVariantService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<ProductVariantDialogData>(MAT_DIALOG_DATA);

  readonly editMode = computed(() => this.data.variant !== undefined);

  readonly definitionForm = signal<FormGroup<ProductVariantControl>>(
    this.data.variant ? this.buildFormFromVariant(this.data.variant) : this.createForm(),
  );
  readonly serverErrors = signal<Record<string, string>>({});
  /** Write failures shown in the banner; the dialog stays open. */
  readonly generalError = signal<string | null>(null);

  /** Mirrors the form's dirty flag as a signal: control state is not reactive. */
  private readonly dirty = signal(false);
  /**
   * Whether the operator has edited the form. Drives the close guard and the
   * cancel confirmation; a form built from the loaded variant starts clean.
   */
  readonly formDirty = this.dirty.asReadonly();

  constructor() {
    // Only operator edits reach this: the form is built from the loaded variant and
    // is never patched afterwards, and the child form applies server errors with
    // `emitEvent: false`. A freshly loaded form therefore starts clean.
    this.definitionForm()
      .valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.dirty.set(true));

    // Esc and backdrop must not discard typed input silently. `disableClose` is a
    // mutable `MatDialogRef` property, so it is driven reactively from the signal.
    effect(() => {
      this.dialogRef.disableClose = this.formDirty();
    });
  }

  onSaveVariant(request: ProductVariantRequest): void {
    const productId = this.data.productId;
    const variant = this.data.variant;

    const request$ = variant
      ? this.productVariantService.updateVariant(productId, variant.id, request)
      : this.productVariantService.createVariant(productId, request);

    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => this.dialogRef.close(saved),
      error: (err: HttpErrorResponse) => this.handleServerError(err),
    });
  }

  cancel(): void {
    if (!this.formDirty()) {
      this.dialogRef.close(null);
      return;
    }

    this.dialog
      .open(ConfirmDialog, {
        data: {
          title: 'Cambios sin guardar',
          message:
            'Tenés cambios sin guardar. Si cerrás ahora, los datos que escribiste se van a perder.',
          confirmLabel: 'Descartar cambios',
          cancelLabel: 'Seguir editando',
          destructive: true,
        },
        autoFocus: false,
        restoreFocus: false,
      })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.dialogRef.close(null);
        }
      });
  }

  private buildFormFromVariant(variant: ProductVariant): FormGroup<ProductVariantControl> {
    return this.fb.group({
      barCode: this.fb.control(variant.barCode, [Validators.required, Validators.maxLength(100)]),
      variantName: this.fb.control(variant.variantName, [
        Validators.required,
        Validators.maxLength(255),
      ]),
    });
  }

  private createForm(): FormGroup<ProductVariantControl> {
    return this.fb.group({
      barCode: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
      variantName: this.fb.control('', [Validators.required, Validators.maxLength(255)]),
    });
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
      return;
    }

    // The 409 collision carries no field attribution, so it becomes one
    // form-level banner naming both uniqueness rules the backend enforces.
    this.serverErrors.set({});
    this.generalError.set(err.status === 409 ? VARIANT_DUPLICATE_MESSAGE : httpErrorMessage(err));
  }
}
