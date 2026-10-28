import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import {
  ProductAttributeDefinition,
  ProductAttributeProfile,
  ProductControl,
  ProductFormValue,
} from '../../models/product.models';
import {
  attributeDefinitionsKey,
  attributePayload,
  createAttributeForm,
  parseAttributeJson,
} from '../../models/product-attribute-form';
import { MAX_CATEGORY_DEPTH, ProductCategory } from '../../models/product-category.models';
import { ProductAttributeFields } from '../product-attribute-fields/product-attribute-fields';
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
    ProductAttributeFields,
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
   * The companies the `Empresa` select can name.
   *
   * The host passes the selector read whole so the saved company renders by name. The value itself is the
   * screen's (`?companyId=`), because the product belongs to one company and every write is scoped to it:
   * on **edit** the product already exists, so the select only reports that company and never moves it,
   * and on **create** it is the operator's choice — see {@link companyEditable}.
   */
  companies = input<CompanySelectorOption[]>([]);
  selectedCompanyId = input<string | null>(null);

  /**
   * Whether the `Empresa` select may be used at all.
   *
   * Enabled only on **create**, where the product does not exist yet and choosing the company is part of
   * filling the form (S3). It stays disabled on edit even when a host passes `true`: the product's owner
   * is fixed — the API scopes every write to it and refuses a category of another company — so the field
   * could only restate it or, worse, contradict it. `false` is the default because a host that says
   * nothing about what it is editing must not hand out a choice it cannot honour.
   */
  companyEditable = input<boolean>(false);

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

  /**
   * The deepest selected category, reported so the host can fetch its attribute profile.
   *
   * The host cannot derive this from the `categoryId` control: the form writes it with `emitEvent:
   * false` and the host's own path computed deliberately does not track control values, so the two
   * places that write the classification — a user pick and the host's seeding — report it here.
   */
  categoryChange = output<string | null>();

  /**
   * The attribute profile the selected category resolves to (T4).
   *
   * Three answers, and the difference between them is load-bearing:
   * - `undefined` — the host does not know yet: no category is selected, its read is in flight, or it
   *   failed. The field-set and the carrier JSON are left exactly as they are; clearing them here
   *   would drop the attributes the product already stores before the profile that proves them lands.
   * - `null` — the branch declares no profile (the API's `204`). No inputs render and the carrier is
   *   left exactly as it arrived, because erasing what the product stores without showing it would be a
   *   silent delete. The SAVE projects `{}` (see `attributesForSubmit`) and the discard notice names the
   *   codes that will go, so the product stays saveable without anything disappearing unannounced.
   * - the profile — its `PRODUCT` definitions are the field-set; the `VARIANT` ones belong to the
   *   variant dialog and never render here (S7).
   */
  attributeProfile = input<ProductAttributeProfile | null | undefined>(undefined);

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

  private readonly destroyRef = inject(DestroyRef);

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

  /** The `PRODUCT`-level definitions of the profile: the only ones this screen renders (S7). */
  readonly productAttributeDefinitions = computed<ProductAttributeDefinition[]>(() =>
    (this.attributeProfile()?.productAttributes ?? []).filter(
      (definition) => definition.level === 'PRODUCT',
    ),
  );

  /**
   * The rendered field-set. Its size is the profile's, not the form's, so it is a group of its own
   * rather than a nested group of `ProductControl` — which is what keeps the host's form
   * construction untouched.
   */
  private readonly attributeFormState = signal<FormGroup>(new FormGroup({}));
  readonly attributeForm = this.attributeFormState.asReadonly();

  /** Whether the profile is known at all, i.e. whether `null` means "this branch declares none". */
  readonly attributeProfileKnown = computed(() => this.attributeProfile() !== undefined);

  /**
   * Codes stored on the product that this branch does not declare, so a save will discard them.
   *
   * Tracked in a signal because the carrier is a plain control property that no `computed` can react to;
   * it is written whenever the field-set is rebuilt, which is when the profile or the product changes.
   */
  private readonly undeclaredCodes = signal<string[]>([]);
  readonly undeclaredAttributeCodes = this.undeclaredCodes.asReadonly();

  /**
   * Says out loud what a save is about to discard, or `null` when there is nothing to discard.
   *
   * This is the counterpart of the rule that forbids writing `{}` on arrival: the form does not erase
   * stored attributes behind the operator's back, but it also must not leave the product unsavable. So
   * the save projects the set the branch declares, and this sentence is what makes that visible before
   * it happens instead of after.
   */
  readonly attributeDiscardNotice = computed<string | null>(() => {
    const codes = this.undeclaredCodes();
    if (codes.length === 0) {
      return null;
    }
    return `Estos atributos guardados no pertenecen a la rama de la categoría y se descartarán al guardar: ${codes.join(', ')}.`;
  });

  /**
   * What the screen says when there is nothing to render, or `null` when there is.
   *
   * A `204` is a legitimate answer and not an error, so the copy states the fact rather than warning
   * about it; a branch whose attributes are all variant-level is a different fact and gets its own
   * sentence, because "no attributes" would be false there.
   */
  readonly attributeNotice = computed<string | null>(() => {
    if (!this.attributeProfileKnown()) {
      return null;
    }
    if (this.attributeProfile() === null) {
      return 'La categoría elegida no declara atributos: no hay inputs de atributos para esta rama.';
    }
    if (this.productAttributeDefinitions().length === 0) {
      return 'Los atributos de esta rama son de la variante: se cargan al crear cada variante.';
    }
    return null;
  });

  /** Identity of the rendered field-set; a re-fetch of the same profile must not rebuild it. */
  private readonly attributeFieldKey = computed(() =>
    attributeDefinitionsKey(this.productAttributeDefinitions()),
  );

  private appliedAttributeKey: string | null = null;
  private appliedAttributeForm: FormGroup<ProductControl> | null = null;
  private attributeSubscription: Subscription | null = null;

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
      // The applied path is the classification this form now shows, so it is reported like a pick: a
      // product that lost its classification (a company change, F1's `[]`) must not keep asking for the
      // previous category's attribute profile.
      this.categoryChange.emit(sub2 || sub1 || root || null);
    });

    // S4, S5: the rendered field-set follows the profile and the JSON carrier is rewritten from it. The
    // key is compared instead of the definitions array so a re-fetch of the same profile does not
    // rebuild the controls under the operator's hands, and the writing is untracked because it writes
    // the same carrier it reads.
    effect(() => {
      const fieldKey = this.attributeFieldKey();
      const fg = this.formGroup();
      if (this.attributeProfile() === undefined) return;
      untracked(() => this.applyAttributeDefinitions(fieldKey, fg));
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
    // The field-set is its own group (its size is the profile's), so its required attributes are
    // checked here instead of through the carrier control.
    const attributeForm = this.attributeForm();
    attributeForm.markAllAsTouched();

    // The carrier is projected from the field-set BEFORE the validity check: what this branch declares
    // is the authoritative set, so a stored key the branch does not declare cannot keep a product
    // unsavable forever. When the profile is still unknown nothing is projected and the carrier keeps
    // whatever the host seeded, which is the only set that can be trusted then.
    const projected = this.attributesForSubmit();
    if (projected !== null) {
      this.formGroup().controls.attributes.setValue(projected);
    }

    if (this.formGroup().valid && attributeForm.valid) {
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
    this.categoryChange.emit(deepestId === '' ? null : deepestId);

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
   * Rebuilds the field-set of the current profile and re-seeds the carrier control from it.
   *
   * Seeding reads the carrier's own JSON, which is what preserves the saved values in edit mode and,
   * at the same time, drops whatever the previous category declared: a code the new profile does not
   * list has no control, so it is not part of the payload the API is about to receive (L4).
   */
  private applyAttributeDefinitions(fieldKey: string, fg: FormGroup<ProductControl>): void {
    if (fieldKey === this.appliedAttributeKey && fg === this.appliedAttributeForm) {
      return;
    }
    this.appliedAttributeKey = fieldKey;
    this.appliedAttributeForm = fg;

    const definitions = this.productAttributeDefinitions();
    const stored = parseAttributeJson(fg.controls.attributes.value) ?? {};
    const group = createAttributeForm(definitions, stored);

    // What this branch does not declare, computed from the carrier as it arrived — before any field
    // edit rewrites it. A branch with no profile declares nothing, so every stored key is undeclared.
    const declared = new Set(definitions.map((definition) => definition.code));
    this.undeclaredCodes.set(Object.keys(stored).filter((code) => !declared.has(code)));

    this.attributeSubscription?.unsubscribe();
    this.attributeSubscription = null;
    this.attributeFormState.set(group);

    // A branch that declares no profile is NOT the form's to author. Writing `{}` into the carrier here
    // would erase the attributes a product already stores, silently: they are never rendered, so the
    // operator cannot see what the save is about to delete. The carrier is left exactly as it arrived,
    // and an incompatible stored set is refused by the API with a message naming the code — visible,
    // which is the whole difference between this rule and a wipe.
    if (this.attributeProfile() === null) {
      return;
    }

    this.attributeSubscription = group.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.syncAttributes(definitions, group));

    // Written on arrival as well: the saved JSON may still name codes this branch does not declare.
    this.syncAttributes(definitions, group);
  }

  /**
   * The JSON a save must send, or `null` to leave the carrier untouched.
   *
   * Three cases, and the difference between the first two is the whole point: an unknown profile
   * (`undefined`) is "the host has not answered", so nothing is authored; a branch with no profile
   * (`null`) declares nothing, so the only saveable set is `{}`; a resolved profile sends exactly what
   * its definitions declare, which is what the API validates. Without this projection a product whose
   * stored set the current branch does not accept would be unsavable from this screen forever.
   */
  private attributesForSubmit(): string | null {
    const profile = this.attributeProfile();
    if (profile === undefined) {
      return null;
    }
    if (profile === null) {
      return '{}';
    }
    return JSON.stringify(
      attributePayload(this.productAttributeDefinitions(), this.attributeForm()),
    );
  }

  /** Mirrors the field-set into the carrier control, which is what `onSave` already reads. */
  private syncAttributes(definitions: ProductAttributeDefinition[], group: FormGroup): void {
    const json = JSON.stringify(attributePayload(definitions, group));
    const control = this.formGroup().controls.attributes;
    if (control.value !== json) {
      control.setValue(json);
    }
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
