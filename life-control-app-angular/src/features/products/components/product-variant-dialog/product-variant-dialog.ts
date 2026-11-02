import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { ProductVariantService } from '../../data/product-variant.service';
import { ProductService } from '../../data/product.service';
import { MeasureUnitService } from '../../data/measure-unit.service';
import {
  Product,
  ProductAttributeDefinition,
  ProductVariantMode,
} from '../../models/product.models';
import { productFieldErrors } from '../../models/product-field-errors';
import { createAttributeForm } from '../../models/product-attribute-form';
import {
  MeasureUnit,
  ProductVariant,
  ProductVariantRequest,
} from '../../models/product-variant.models';
import {
  ProductVariantControl,
  ProductVariantForm,
} from '../product-variant-form/product-variant-form';
import { ErrorBanner, ConfirmDialog } from '@shared/ui';

/**
 * The single message for a 409 on the global definition.
 *
 * The backend answers 409 for a duplicate barcode or variant name within the product AND
 * for a refused measure-unit change: once the variant already holds stock or ledger
 * movements the unit is frozen (`D3`), because changing it would silently rewrite the
 * meaning of every stored balance. The server sends no field attribution for either cause,
 * so naming both is the only honest copy: guessing which one happened would present a fact
 * nobody sent, and echoing the server's own message is worse — the API speaks English and
 * this screen speaks Spanish. Kept form-level on purpose — never bound to a single control.
 */
const VARIANT_CONFLICT_MESSAGE =
  'No se pudo guardar: ya tenés una variante con ese código de barras o ese nombre para este ' +
  'producto, o la variante ya tiene stock o movimientos de inventario que no permiten cambiar ' +
  'la unidad de medida';

/** Payload the opener passes to {@link ProductVariantDialog}. */
export interface ProductVariantDialogData {
  /** The company that owns the product: the scope of every request this dialog makes. */
  companyId: string;
  productId: string;
  /** The row the list already holds; present only in edit mode. */
  variant?: ProductVariant;
}

/**
 * Create/edit host for the **global definition** of a product variant.
 *
 * Replaces the routed create/edit screen for the definition: it owns the write and
 * closes with the saved entity (or `null` on cancel), so the list only has to reload
 * on a truthy result (D22). It edits `barCode` + `variantName` + `measureUnitId` and
 * nothing else — the per-store stock and prices panel stays on the store-scoped page
 * (D8/D18).
 *
 * It holds no `ActivatedRoute`, no `Router` and no store handling: the opener passes
 * the owning product and, in edit mode, the row.
 *
 * The measure-unit catalog is read **once per dialog** here, not by the form and not by
 * the picker: the form is presentational, and reading it lower would multiply the
 * request by every place the form is reused. A failed read leaves the catalog empty, so
 * the operator still sees the required unit (decision 3) but cannot pick one — the save is
 * refused with the field error instead of a variant written without a unit.
 *
 * The **product is read for two facts**, not one: its category (for the variant-level
 * attribute profile) and its `variantMode`, which is what decides whether this variant
 * needs a barcode (decision 4: required for `MULTI`, optional for `SIMPLE`). Both come from
 * the same call the dialog already made, so the screen learns the mode without a second
 * request and without duplicating the rule the backend enforces.
 *
 * In create mode it also offers "Guardar y agregar otra": a successful write resets the
 * group in place and keeps the dialog open for the next variant (D32). The dialog
 * remembers the entity of the last successful write and closes with it on every exit
 * path (D34), so an operator who adds several variants and then cancels still hands the
 * list the last one it must reload for; `null` keeps meaning "nothing was persisted".
 *
 * The **variant-level** attributes (S6, S7) are the product's category profile, so this
 * dialog reads it once: the product (for its classification) and then that category's
 * profile. A failed read leaves the field-set empty rather than blocking the write — a
 * barcode and a name are still a valid variant — and the request then omits `attributes`
 * instead of clearing the stored set. `Talla` is not among them on purpose: `variantName`
 * already is the size (record L7).
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
  private readonly productService = inject(ProductService);
  private readonly measureUnitService = inject(MeasureUnitService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<ProductVariantDialogData>(MAT_DIALOG_DATA);

  readonly editMode = computed(() => this.data.variant !== undefined);

  readonly definitionForm = signal<FormGroup<ProductVariantControl>>(
    this.data.variant ? this.buildFormFromVariant(this.data.variant) : this.createForm(),
  );
  /** The enabled unit catalog, offered whole: never narrowed by `unitType` (S4). */
  readonly measureUnits = signal<MeasureUnit[]>([]);

  /**
   * The owning product's mode, or `null` while it is unknown (the read is in flight or failed).
   *
   * It is read from the product this dialog already loads, never from a second source: the mode is a
   * property of the article (decision 1), and asking the variant endpoint for it would make the rule
   * depend on a request that has nothing to do with the variant being edited.
   */
  private readonly productModeState = signal<ProductVariantMode | null>(null);
  readonly productMode = this.productModeState.asReadonly();

  /**
   * Whether the barcode input is required (decision 4), which is `true` only for a known `MULTI`.
   *
   * An **unknown** mode is deliberately not treated as `MULTI`: the dialog cannot ask for a barcode on
   * the strength of a read that failed, so the client-side requirement is dropped and the server
   * decides — it answers `400` with `errors: {barCode: …}` for a `MULTI` product, and that key is
   * attributed to this very control ({@link handleServerError}).
   */
  readonly barcodeRequired = computed(() => this.productModeState() === 'MULTI');

  /** The `VARIANT`-level definitions of the product's category profile; empty when it declares none. */
  private readonly variantDefinitionsState = signal<ProductAttributeDefinition[]>([]);
  readonly variantDefinitions = this.variantDefinitionsState.asReadonly();

  /**
   * Whether the profile behind those definitions resolved, as opposed to still loading or failing.
   *
   * It decides what an empty definition list means for the write: "this branch declares none" sends
   * `{}`, while "nobody knows yet" omits the field and leaves the stored set alone.
   */
  private readonly profileKnownState = signal(false);
  readonly profileKnown = this.profileKnownState.asReadonly();

  /**
   * Codes the edited variant stores that the current branch does not declare, so saving discards them.
   *
   * Stated on screen because the alternative is a dead end: the API validates the stored set, so a
   * variant holding a code its branch no longer declares cannot be saved until it is projected away —
   * and the operator is entitled to see which ones go before agreeing to that.
   */
  readonly discardNotice = computed<string | null>(() => {
    if (!this.profileKnown()) {
      return null;
    }
    const stored = this.data.variant?.attributes ?? {};
    const declared = new Set(this.variantDefinitions().map((definition) => definition.code));
    const undeclared = Object.keys(stored).filter((code) => !declared.has(code));
    if (undeclared.length === 0) {
      return null;
    }
    return `Esta variante guarda atributos que la rama de la categoría no declara y se descartarán al guardar: ${undeclared.join(', ')}.`;
  });

  /** The field-set those definitions render, seeded from the variant being edited. */
  private readonly attributeFormState = signal<FormGroup>(new FormGroup({}));
  readonly attributeForm = this.attributeFormState.asReadonly();

  readonly serverErrors = signal<Record<string, string>>({});

  /**
   * The controls this dialog can put a server-copy on.
   *
   * The write is one variant through `/products/{id}/variants`, so the API can only refuse `barCode`
   * (decision 4) or `measureUnitId` (decision 3) about a field this form renders. Any other key belongs
   * to a screen that owns it; dropping it here is what keeps a field error from becoming a banner in a
   * dialog with no control to mark.
   */
  private static readonly RENDERABLE_FIELDS = ['barCode', 'measureUnitId'];

  /** Write failures shown in the banner; the dialog stays open. */
  readonly generalError = signal<string | null>(null);

  private readonly savingState = signal(false);
  /** True while a write is in flight; disables both submit buttons through the form. */
  readonly saving = this.savingState.asReadonly();

  /**
   * The entity from the most recent successful write. The dialog closes with it on
   * every exit path (D34), so adding several variants and then cancelling still hands
   * the list the last one it must reload for. `null` means nothing was persisted.
   */
  private lastSaved: ProductVariant | null = null;

  /** Mirrors the form's dirty flag as a signal: control state is not reactive. */
  private readonly dirty = signal(false);
  /**
   * Whether the operator has edited the form. Drives the close guard and the
   * cancel confirmation; a form built from the loaded variant starts clean.
   */
  readonly formDirty = this.dirty.asReadonly();

  constructor() {
    // One catalog read per dialog: the picker is offered whatever the catalog holds (S4).
    // A failure leaves the list empty on purpose — the unit is optional, so a catalog
    // outage must not block saving a barcode and a name.
    this.measureUnitService
      .getMeasureUnits()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (units) => this.measureUnits.set(units),
        error: () => this.measureUnits.set([]),
      });

    // The attribute profile belongs to the product's category, and the dialog only receives the
    // product's id: the product is read for its classification, then that category's profile. A failed
    // read leaves the field-set empty instead of blocking a write the operator can still make.
    this.productService
      .getProductById(this.data.companyId, this.data.productId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (product) => {
          // Decision 4 needs the article's mode, and this is the product the dialog was already
          // reading: one call serves the attribute profile and the barcode rule.
          this.productModeState.set(product.variantMode ?? null);
          this.loadVariantDefinitions(product);
        },
        error: () => this.variantDefinitionsState.set([]),
      });

    // The barcode requirement follows the mode (decision 4) and the mode arrives after the form was
    // built, so the validator is (re)applied here: a `MULTI` variant without a barcode cannot be
    // submitted at all, while a `SIMPLE` one is legal without it.
    effect(() => {
      const required = this.barcodeRequired();
      const control = this.definitionForm().controls.barCode;
      control.setValidators(ProductVariantDialog.barcodeValidators(required));
      control.updateValueAndValidity({ emitEvent: false });
    });

    // The field-set is rebuilt when the definitions arrive (once per dialog) and by the add-another
    // reset. Seeding reads the variant only here, so an edit shows the values the row already stores.
    effect(() => {
      const definitions = this.variantDefinitions();
      untracked(() =>
        this.attributeFormState.set(
          createAttributeForm(definitions, this.data.variant?.attributes ?? {}),
        ),
      );
    });

    // Only operator edits reach this: the form is built from the loaded variant and is
    // never patched afterwards, the add-another reset clears it with `emitEvent: false`
    // (which is exactly why it does not re-arm this signal), and the child form applies
    // server errors with `emitEvent: false` too. A freshly loaded or reset form starts
    // clean.
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
    this.submit(request, (saved) => this.dialogRef.close(saved));
  }

  onSaveVariantAndContinue(request: ProductVariantRequest): void {
    this.submit(request, () => this.resetForNextEntry());
  }

  private submit(request: ProductVariantRequest, onSuccess: (saved: ProductVariant) => void): void {
    // A second click while the first request is still in flight must not create a
    // second row; the form disables its buttons from the same state, and this guard
    // covers any other caller.
    if (this.savingState()) return;

    const companyId = this.data.companyId;
    const productId = this.data.productId;
    const variant = this.data.variant;

    const request$ = variant
      ? this.productVariantService.updateVariant(companyId, productId, variant.id, request)
      : this.productVariantService.createVariant(companyId, productId, request);

    this.savingState.set(true);

    request$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (saved) => {
        this.savingState.set(false);
        this.lastSaved = saved;
        onSuccess(saved);
      },
      error: (err: HttpErrorResponse) => {
        this.savingState.set(false);
        this.handleServerError(err);
      },
    });
  }

  private resetForNextEntry(): void {
    // `emitEvent: false` is load-bearing: a plain `reset()` emits `valueChanges`,
    // which would immediately re-arm `dirty` and therefore `disableClose`.
    // `measureUnitId` resets to `null` like the rest: the next variant starts with no
    // unit chosen, and decision 3 makes it required, so the operator picks one before
    // the next write. The barcode requirement of the product's mode is untouched: it is
    // a validator of the control and survives the reset.
    this.definitionForm().reset(
      { barCode: '', variantName: '', measureUnitId: null },
      { emitEvent: false },
    );
    // The attribute field-set starts empty for the next variant, with the profile it already has.
    this.attributeFormState.set(createAttributeForm(this.variantDefinitions(), {}));
    this.dirty.set(false);
    // A stale server error must not survive into the next entry: clear the banner and
    // the field map, and let the form's effect strip the applied `serverError` keys.
    this.serverErrors.set({});
    this.generalError.set(null);
  }

  cancel(): void {
    if (!this.formDirty()) {
      this.dialogRef.close(this.lastSaved);
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
          this.dialogRef.close(this.lastSaved);
        }
      });
  }

  private buildFormFromVariant(variant: ProductVariant): FormGroup<ProductVariantControl> {
    return this.fb.group({
      // The barcode's `required` is applied by the mode effect above; only the size is fixed here,
      // because the size is the column's and the requirement is the product's (decision 4).
      //
      // The `?? ''` is a runtime guard, not a type one: since V56 the column is nullable, and a
      // `SIMPLE` variant legitimately stores no barcode. The read model keeps `barCode: string`
      // because the purchase-order picker (outside this slice) labels its options with it, so this is
      // where the null is absorbed rather than silently rendered as "null".
      barCode: this.fb.control(variant.barCode ?? '', [Validators.maxLength(100)]),
      variantName: this.fb.control(variant.variantName, [
        Validators.required,
        Validators.maxLength(255),
      ]),
      // Decision 3: the unit is required, and an existing row always has one (V55).
      measureUnitId: this.fb.control<string | null>(variant.measureUnitId ?? null, [
        Validators.required,
      ]),
    });
  }

  private createForm(): FormGroup<ProductVariantControl> {
    return this.fb.group({
      barCode: this.fb.control('', [Validators.maxLength(100)]),
      variantName: this.fb.control('', [Validators.required, Validators.maxLength(255)]),
      measureUnitId: this.fb.control<string | null>(null, [Validators.required]),
    });
  }

  private static barcodeValidators(required: boolean) {
    return required
      ? [Validators.required, Validators.maxLength(100)]
      : [Validators.maxLength(100)];
  }

  /**
   * Reads the profile its category resolves to and keeps the variant-level definitions.
   *
   * The company is the explicit one the opener passed, not a field of the product: the dialog lives
   * inside the company segment the screen is standing on, and the product's own `companyId` is the
   * same value — deriving it from the row would be the second source of truth this unit removed (the
   * `categoryCompanyId` field this replaced existed only because the old path carried no company).
   *
   * The split by level is the catalog's own (`variantAttributes`), and it is filtered again here as a
   * guard, not as a second opinion: a product-level attribute rendered on a variant is exactly the
   * confusion the level exists to prevent (S7).
   */
  private loadVariantDefinitions(product: Product): void {
    const companyId = this.data.companyId;
    const categoryId = product.categoryId;
    if (!categoryId) {
      // An unclassified product declares no profile: any attribute would be refused by the API, so the
      // branch IS known to declare none and the request must say `{}` rather than keep a stored set.
      this.variantDefinitionsState.set([]);
      this.profileKnownState.set(true);
      return;
    }

    this.productService
      .getAttributeProfile(companyId, categoryId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (profile) => {
          this.profileKnownState.set(true);
          this.variantDefinitionsState.set(
            (profile?.variantAttributes ?? []).filter(
              (definition) => definition.level === 'VARIANT',
            ),
          );
        },
        error: () => {
          // A failed read is not "no attributes": the request omits the field and keeps the stored set.
          this.profileKnownState.set(false);
          this.variantDefinitionsState.set([]);
        },
      });
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;

    // The catalog serves a field map for its rule refusals now, and the dialog reads that instead of the
    // wording: `barCode` (decision 4) and `measureUnitId` (decision 3) are the only controls this dialog
    // owns, so keys about anything else stay out of a form that could not render them.
    const fieldErrors = Object.fromEntries(
      Object.entries(productFieldErrors(apiError)).filter(([field]) =>
        ProductVariantDialog.RENDERABLE_FIELDS.includes(field),
      ),
    );
    if (Object.keys(fieldErrors).length > 0) {
      this.serverErrors.set(fieldErrors);
      this.generalError.set(null);
      return;
    }

    // The 409 carries no field attribution, so it becomes one form-level banner naming
    // both uniqueness rules the backend enforces and the frozen-unit guard.
    this.serverErrors.set({});
    this.generalError.set(err.status === 409 ? VARIANT_CONFLICT_MESSAGE : httpErrorMessage(err));
  }
}
