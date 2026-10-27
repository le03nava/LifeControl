import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  output,
  signal,
} from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ProductControl, ProductFormValue } from '../../models/product.models';
import { MAX_CATEGORY_DEPTH, ProductCategory } from '../../models/product-category.models';
import { CompanySelectorOption } from '@features/companies/companies/models/company.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatIconModule } from '@angular/material/icon';

/** One entry of a level's option list: the node plus whether it may be picked. */
interface CategoryOption {
  id: string;
  label: string;
  /** `false` for a node that is disabled: it is listed so a saved path renders, but not selectable. */
  selectable: boolean;
}

@Component({
  selector: 'app-products-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatIconModule,
  ],
  templateUrl: './products-form.html',
  styleUrl: './products-form.scss',
})
export class ProductsForm {
  formGroup = input.required<FormGroup<ProductControl>>();
  serverErrors = input<Record<string, string>>({});

  /**
   * Whether the form presents its edit copy.
   *
   * `null` (the default) keeps the derivation from the `id` control below. A host whose
   * "does this entity exist yet" answer changes **without the form being replaced** must
   * pass it explicitly: `controls.id.value` is a plain property, so a `computed` over it
   * cannot react to a programmatic write-back. That is exactly the create stepper's case
   * (D26): step 1 writes the created id into the control, and the copy has to follow.
   */
  editMode = input<boolean | null>(null);

  /**
   * The companies whose category tree the host can read, for the `Empresa` select.
   *
   * The company is chosen here and not taken from the app's company context: a product is global
   * while its category belongs to a company, so the classification and the tree that renders it have
   * to agree even when the header points somewhere else (S2).
   */
  companies = input<CompanySelectorOption[]>([]);
  selectedCompanyId = input<string | null>(null);

  /** The whole tree of the selected company, disabled nodes included (S8). */
  categoryTree = input<ProductCategory[]>([]);

  /**
   * The saved classification as ids from the root to the deepest node.
   *
   * The host computes it (it owns the product and the tree); the form only applies it. Applying it is
   * deliberately *not* the same path as a user pick: no SAT suggestion and no hint, because the code
   * that belongs on screen is the one the product was saved with (S6).
   *
   * `null` means "the host does not know yet" — its tree has not answered, or its read failed — and is
   * applied by doing nothing. `[]` is a *certain* "no classification" and clears the form. The two must
   * stay distinct: a host that answered `[]` while its tree was in flight would wipe the saved
   * classification before the tree proving it arrived (F1), and the next save would send `null`.
   */
  categoryPath = input<string[] | null>([]);

  saveProduct = output<ProductFormValue>();
  cancelForm = output<void>();
  companyChange = output<string | null>();

  readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    minlength: (err) =>
      `Debe tener al menos ${(err as { requiredLength?: number; actualLength?: number }).requiredLength} caracteres (llevas ${(err as { requiredLength?: number; actualLength?: number }).actualLength}).`,
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number; actualLength?: number }).requiredLength} caracteres.`,
    pattern: () => 'El formato ingresado no es válido.',
    json: () => 'Ingrese un JSON válido.',
    serverError: (err) => err as string,
  };

  /**
   * The three select values, mirrored into a signal.
   *
   * `mat-select` options are rendered from these, and the controls are plain objects a `computed`
   * cannot react to. The mirror is written from two places — a user pick and the host's seeding — so
   * both keep the option lists in step with the controls.
   */
  private readonly selection = signal<{ root: string; sub1: string; sub2: string }>({
    root: '',
    sub1: '',
    sub2: '',
  });

  /** Where the value currently in the SAT input came from, or `null` when it did not come from one. */
  private readonly satCodeSource = signal<string | null>(null);

  /** The SAT code a category last suggested; keeps the hint honest while the field is untouched. */
  private suggestedSatCode: string | null = null;

  /** Whether the hint is still true: it is withdrawn as soon as the operator edits the field (S4). */
  readonly satCodeHint = computed<string | null>(() => {
    const source = this.satCodeSource();
    return source === null ? null : `Código SAT tomado de: ${source}`;
  });

  readonly categoryOptions = computed<CategoryOption[]>(() =>
    this.optionsFor(null, this.categoryTree(), this.selection().root),
  );

  readonly subcategory1Options = computed<CategoryOption[]>(() =>
    this.optionsFor(this.selection().root, this.categoryTree(), this.selection().sub1),
  );

  readonly subcategory2Options = computed<CategoryOption[]>(() =>
    this.optionsFor(this.selection().sub1, this.categoryTree(), this.selection().sub2),
  );

  /** Whether the previous level has a pick, i.e. whether the next one can be chosen at all. */
  readonly hasSubcategory1 = computed(() => this.selection().root !== '');
  readonly hasSubcategory2 = computed(() => this.selection().sub1 !== '');

  protected getErrorMessage(
    control: AbstractControl | null,
    customMessages?: Record<string, (error: unknown) => string>,
  ): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    const allMessages = { ...this.defaultErrorMessages, ...customMessages };
    if (allMessages[primerErrorKey]) {
      return allMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  }

  readonly isEditMode = computed(() => this.editMode() ?? !!this.formGroup()?.controls.id.value);

  constructor() {
    effect((onCleanup) => {
      const serverErrors = this.serverErrors();
      const fg = this.formGroup();

      if (!fg || Object.keys(serverErrors).length === 0) return;

      const subscriptions: Subscription[] = [];

      Object.entries(serverErrors).forEach(([key, message]) => {
        const control = fg.get(key);
        if (control) {
          const currentErrors = control.errors || {};
          control.setErrors({ ...currentErrors, serverError: message }, { emitEvent: false });

          const sub = control.valueChanges.subscribe(() => {
            if (control.errors && 'serverError' in control.errors) {
              const { serverError: _, ...otherErrors } = control.errors;
              const remainingKeys = Object.keys(otherErrors);
              control.setErrors(remainingKeys.length > 0 ? otherErrors : null, { emitEvent: true });
            }
          });
          subscriptions.push(sub);
        } else {
          console.warn(`[ProductsForm] No control found for server error key: "${key}"`);
        }
      });

      onCleanup(() => {
        subscriptions.forEach((sub) => sub.unsubscribe());
      });
    });

    // S6: the saved path and the saved code are applied silently. The write goes through
    // `setValue(..., { emitEvent: false })` so a reload is not a user pick and cannot overwrite the
    // product's own SAT code with the category's.
    effect(() => {
      const path = this.categoryPath();
      const fg = this.formGroup();

      // "Unknown yet": touching the controls here is what F1 was — the saved classification is wiped
      // while the tree that renders it is still in flight.
      if (path === null) return;

      const [root = '', sub1 = '', sub2 = ''] = path;

      fg.controls.categoryRootId.setValue(root, { emitEvent: false });
      fg.controls.subcategory1Id.setValue(sub1, { emitEvent: false });
      fg.controls.subcategory2Id.setValue(sub2, { emitEvent: false });
      fg.controls.categoryId.setValue(sub2 || sub1 || root, { emitEvent: false });
      this.selection.set({ root, sub1, sub2 });
      this.applyLevelAvailability(root, sub1);
      this.withdrawHint();
    });

    // S4: the hint describes the value the form itself wrote. Once the operator types something else
    // it would be a lie, so it goes away.
    effect((onCleanup) => {
      const fg = this.formGroup();
      const sub = fg.controls.satCode.valueChanges.subscribe(() => {
        const source = this.satCodeSource();
        if (source === null) return;
        if (fg.controls.satCode.value !== this.suggestedSatCode) {
          this.withdrawHint();
        }
      });
      onCleanup(() => sub.unsubscribe());
    });
  }

  onCompanyChange(companyId: string | null): void {
    this.companyChange.emit(companyId === '' ? null : companyId);
  }

  /** Applies a level-1 pick: everything below it is cleared (S7). */
  onRootChange(rootId: string | null): void {
    const root = rootId ?? '';
    const fg = this.formGroup();
    fg.controls.categoryRootId.setValue(root);
    fg.controls.subcategory1Id.setValue('');
    fg.controls.subcategory2Id.setValue('');
    this.selection.set({ root, sub1: '', sub2: '' });
    this.applyLevelAvailability(root, '');
    this.commitSelection(root, root);
  }

  /** Applies a level-2 pick: level 3 is cleared (S7). */
  onSubcategory1Change(subcategoryId: string | null): void {
    const sub1 = subcategoryId ?? '';
    const root = this.selection().root;
    const fg = this.formGroup();
    fg.controls.subcategory1Id.setValue(sub1);
    fg.controls.subcategory2Id.setValue('');
    this.selection.set({ root, sub1, sub2: '' });
    this.applyLevelAvailability(root, sub1);
    // Clearing this level leaves the level-1 pick standing, so the product stays classified one
    // level up instead of losing its category (the deepest *still selected* node is what travels).
    this.commitSelection(sub1 || root, sub1);
  }

  /** Applies a level-3 pick. */
  onSubcategory2Change(subcategoryId: string | null): void {
    const sub2 = subcategoryId ?? '';
    const { root, sub1 } = this.selection();
    this.formGroup().controls.subcategory2Id.setValue(sub2);
    this.selection.set({ root, sub1, sub2 });
    this.commitSelection(sub2 || sub1 || root, sub2);
  }

  onSave(): void {
    this.formGroup().markAllAsTouched();

    if (this.formGroup().valid) {
      const raw = this.formGroup().getRawValue();

      let parsedAttributes: Record<string, unknown> | undefined;
      if (raw.attributes && raw.attributes.trim()) {
        try {
          parsedAttributes = JSON.parse(raw.attributes);
        } catch {
          parsedAttributes = undefined;
        }
      }

      const productData: ProductFormValue = {
        id: raw.id,
        sku: raw.sku,
        name: raw.name,
        shortName: raw.shortName ?? undefined,
        satCode: raw.satCode ?? undefined,
        productType: raw.productType ?? undefined,
        attributes: parsedAttributes,
        enabled: raw.enabled ?? true,
        // The persisted classification is the deepest selected node, `null` when none is (S3).
        categoryId: raw.categoryId === '' ? null : raw.categoryId,
      };
      this.saveProduct.emit(productData);
    }
  }

  onCancel(): void {
    this.cancelForm.emit();
  }

  /**
   * Records the deepest selected node as the persisted classification and, when the pick names a
   * category, suggests its SAT code (S3, S4, S5).
   *
   * @param deepestId the deepest node still selected, `''` when nothing is
   * @param pickedId the node the operator just picked, `''` when they cleared a level — a clear
   *     changes the classification but says nothing about the fiscal key, so the input is left alone
   */
  private commitSelection(deepestId: string, pickedId: string): void {
    this.formGroup().controls.categoryId.setValue(deepestId, { emitEvent: false });

    const node =
      pickedId === ''
        ? null
        : (this.categoryTree().find((category) => category.id === pickedId) ?? null);

    if (!node?.satCode) {
      // S5: a node without a key does not touch the input, and the previous suggestion is gone.
      this.withdrawHint();
      return;
    }

    // S4: the key the category was configured with replaces what is in the input, and the form says
    // where it came from. The operator may edit it afterwards; whatever the input holds is saved.
    this.suggestedSatCode = node.satCode;
    this.satCodeSource.set(node.categoryName);
    this.formGroup().controls.satCode.setValue(node.satCode);
  }

  /**
   * Disables the levels that cannot be picked yet, and enables the ones that can (S1).
   *
   * Takes the two values as arguments instead of reading `selection()`: the seeding effect calls it
   * while writing that same signal, and reading it there made every pick invalidate the effect, which
   * then re-applied the saved (usually empty) path on the next flush and wiped the pick.
   *
   * Done through the control and not with a `[disabled]` binding beside `formControlName`: that pairing
   * is the case Angular warns about, and a control is also where the state belongs — `markAllAsTouched`
   * and `valid` already know to skip a disabled control.
   */
  private applyLevelAvailability(root: string, sub1: string): void {
    const fg = this.formGroup();
    if (root === '') {
      fg.controls.subcategory1Id.disable({ emitEvent: false });
    } else {
      fg.controls.subcategory1Id.enable({ emitEvent: false });
    }

    if (sub1 === '') {
      fg.controls.subcategory2Id.disable({ emitEvent: false });
    } else {
      fg.controls.subcategory2Id.enable({ emitEvent: false });
    }
  }

  private withdrawHint(): void {
    this.suggestedSatCode = null;
    this.satCodeSource.set(null);
  }

  /**
   * The selectable children of {@code parentId} — the roots when it is `null` — at the next level.
   *
   * The current value stays selectable even when its node is disabled, the same reasoning
   * `ProductCategoryDialog.parentOptions` records: omitting it would make the picker show "empty" for
   * a level the product really has.
   */
  private optionsFor(
    parentId: string | null,
    tree: ProductCategory[],
    currentId: string,
  ): CategoryOption[] {
    return tree
      .filter((category) => category.parentCategoryId === parentId)
      .filter((category) => category.depth <= MAX_CATEGORY_DEPTH)
      .map((category) => ({
        id: category.id,
        label: category.categoryName,
        selectable: category.enabled || category.id === currentId,
      }));
  }
}
