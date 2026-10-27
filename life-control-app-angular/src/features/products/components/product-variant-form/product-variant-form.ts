import { ChangeDetectionStrategy, Component, effect, input, output } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { MeasureUnit, ProductVariantRequest } from '../../models/product-variant.models';
import { ProductAttributeDefinition } from '../../models/product.models';
import { attributePayload } from '../../models/product-attribute-form';
import { ProductAttributeFields } from '../product-attribute-fields/product-attribute-fields';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';

/**
 * Typed control map of the global variant definition form.
 *
 * The three global fields are controls: `enabled` is deliberately absent because the
 * definition request does not carry it and re-enabling is a list-page action, and the
 * owning product travels in the route, never in the body.
 *
 * `measureUnitId` is nullable on purpose: `null` means **no unit declared**, which is
 * the behavior every variant had before the relation existed (S1). It carries no
 * validator, so an empty selection is a valid, deliberate answer rather than an
 * incomplete form.
 */
export interface ProductVariantControl {
  barCode: FormControl<string>;
  variantName: FormControl<string>;
  measureUnitId: FormControl<string | null>;
}

/**
 * Presentational form for the **global definition** of a product variant.
 *
 * It owns no HTTP and no routing: the page owns the `FormGroup` and reacts to
 * `saveVariant`/`saveVariantAndContinue`/`cancelForm`. Presentational because the
 * same component serves create and edit — `editMode` only changes the copy, and
 * the create-only "save and add another" affordance is hidden in edit mode.
 *
 * `measureUnits` is the catalog the host loaded, offered whole: this component never
 * filters it, so a variant can be counted in any enabled unit the catalog holds (S4).
 * The first option clears the selection to `null`, which is why the field is optional.
 *
 * `serverErrors` are keyed by control name; the backend field errors are applied
 * to the matching control exactly as `companies-form` does. The 409 collision is
 * NOT a field error (the backend gives no field attribution), so it never
 * travels through this input: the page renders it as a form-level banner.
 *
 * `attributeForm` and `attributeDefinitions` are the **variant-level** field-set of
 * the product's category profile (S6, S7). Both come from the dialog, which reads the
 * profile: this component stays presentational and the definitions it renders are the
 * ones that belong to a variant — `color` and `acabado`, never a product-level
 * attribute, and never `Talla`, which is `variantName` itself (record L7).
 */
@Component({
  selector: 'app-product-variant-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatSelectModule,
    ProductAttributeFields,
  ],
  templateUrl: './product-variant-form.html',
  styleUrl: './product-variant-form.scss',
})
export class ProductVariantForm {
  formGroup = input.required<FormGroup<ProductVariantControl>>();
  serverErrors = input<Record<string, string>>({});
  /** Only switches the title and submit labels; create and edit share one shape. */
  editMode = input<boolean>(false);
  /** In-flight state owned by the host; both submit handlers early-return while true. */
  saving = input<boolean>(false);
  /**
   * The enabled measure-unit catalog, offered complete. Empty while it loads or when the
   * read failed, which leaves only the "Sin unidad" option — never a broken control.
   */
  measureUnits = input<MeasureUnit[]>([]);

  /**
   * The variant-level attribute field-set, built by the dialog from the category's profile.
   *
   * A separate group on purpose: the definition count is the catalog's, not this form's, so adding
   * `attributes` to `ProductVariantControl` would make every control map depend on a profile that
   * has not arrived yet. An empty group (no profile, or a branch without variant attributes) is
   * valid and writes nothing.
   */
  attributeForm = input<FormGroup>(new FormGroup({}));

  /** The `VARIANT` definitions rendered by `attributeForm`, in the catalog's display order. */
  attributeDefinitions = input<ProductAttributeDefinition[]>([]);

  saveVariant = output<ProductVariantRequest>();
  saveVariantAndContinue = output<ProductVariantRequest>();
  cancelForm = output<void>();

  protected readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No podés superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    serverError: (err) => err as string,
  };

  protected getErrorMessage(control: AbstractControl | null): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const primerErrorKey = Object.keys(control.errors)[0];
    const errorDetalle = control.errors[primerErrorKey];

    if (this.defaultErrorMessages[primerErrorKey]) {
      return this.defaultErrorMessages[primerErrorKey](errorDetalle);
    }

    return 'Campo inválido.';
  }

  constructor() {
    effect((onCleanup) => {
      const serverErrors = this.serverErrors();
      const fg = this.formGroup();

      if (!fg) return;

      // An empty map must also *remove* a previously applied server error: the host
      // clears the input whenever a later failure carries no field attribution, and
      // without this the stale `serverError` key would outlive the attempt that set it
      // and sit under a field the newest failure never named. (`reset()` is not the
      // reason: it already drops the key, because it recomputes `errors` from the
      // validators.)
      if (Object.keys(serverErrors).length === 0) {
        Object.values(fg.controls).forEach((control) => {
          if (control.errors && 'serverError' in control.errors) {
            const { serverError: _, ...otherErrors } = control.errors;
            const remainingKeys = Object.keys(otherErrors);
            control.setErrors(remainingKeys.length > 0 ? otherErrors : null, {
              emitEvent: false,
            });
          }
        });
        return;
      }

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
          console.warn(`[ProductVariantForm] No control found for server error key: "${key}"`);
        }
      });

      onCleanup(() => {
        subscriptions.forEach((sub) => sub.unsubscribe());
      });
    });
  }

  onSave(): void {
    if (this.saving()) return;

    this.formGroup().markAllAsTouched();
    this.attributeForm().markAllAsTouched();

    if (this.formGroup().valid && this.attributeForm().valid) {
      this.saveVariant.emit(this.toRequest());
    }
  }

  onSaveAndContinue(): void {
    if (this.saving()) return;

    this.formGroup().markAllAsTouched();
    this.attributeForm().markAllAsTouched();

    if (this.formGroup().valid && this.attributeForm().valid) {
      this.saveVariantAndContinue.emit(this.toRequest());
    }
  }

  /**
   * The write body, built from both submit paths so they can never drift apart.
   *
   * `measureUnitId` is sent explicitly, `null` included: the field is optional on the
   * wire, and omitting it would leave the stored unit untouched instead of clearing it.
   *
   * `attributes` is the opposite and is omitted when the branch declares no variant-level
   * attribute: an absent set leaves the stored one untouched, while an empty one clears it,
   * and a screen that could not read the profile must not wipe a saved colour.
   */
  private toRequest(): ProductVariantRequest {
    const raw = this.formGroup().getRawValue();
    const definitions = this.attributeDefinitions();
    const request: ProductVariantRequest = {
      barCode: raw.barCode,
      variantName: raw.variantName,
      measureUnitId: raw.measureUnitId ?? null,
    };
    if (definitions.length > 0) {
      request.attributes = attributePayload(definitions, this.attributeForm());
    }
    return request;
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
