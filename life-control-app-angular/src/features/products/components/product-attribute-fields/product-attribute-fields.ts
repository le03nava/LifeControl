import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ProductAttributeDefinition } from '../../models/product.models';
import { measurementUnitName, measurementValueName } from '../../models/product-attribute-form';

/**
 * The rendered field-set of one profile level: the attribute inputs the selected category declares.
 *
 * Presentational by contract — it owns no HTTP and no copy of the values. The host builds the
 * `FormGroup` (one control per definition, two for a measurement) and consumes it, which is what lets
 * the same component serve the product form, whose carrier is a JSON string control, and the variant
 * dialog, whose carrier is the request body.
 *
 * One control per `kind`, and the kind is the only thing that decides it (S5): `mat-select` for
 * `ENUM`, `mat-select multiple` for `MULTI_ENUM`, a number plus a unit for `MEASUREMENT`, a
 * `mat-slide-toggle` for `BOOLEAN` and a text input for `TEXT`. Nothing here filters by level: the
 * caller passes the level of *its own* screen, so a `VARIANT` definition can never reach the product
 * form.
 */
@Component({
  selector: 'app-product-attribute-fields',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
  ],
  templateUrl: './product-attribute-fields.html',
  styleUrl: './product-attribute-fields.scss',
})
export class ProductAttributeFields {
  /** The definitions of this level, in the catalog's own display order. */
  readonly definitions = input.required<ProductAttributeDefinition[]>();

  /** The group the host built with `createAttributeForm`; its control names are the definition codes. */
  readonly form = input.required<FormGroup>();

  /** Control names of a measurement's two halves, exposed for the template. */
  protected readonly valueName = measurementValueName;
  protected readonly unitName = measurementUnitName;

  /** The control of an attribute, or `null` while the field-set is being replaced. */
  protected control(name: string): AbstractControl | null {
    return this.form().get(name);
  }

  /**
   * The message of the first error, following the same contract the hosting forms use: an untouched
   * control says nothing, so a required attribute does not open in red.
   */
  protected errorMessage(control: AbstractControl | null): string | null {
    if (!control || !control.errors || !control.touched) {
      return null;
    }

    const [key] = Object.keys(control.errors);
    switch (key) {
      case 'required':
        return 'Este campo es obligatorio.';
      case 'min':
        return 'No puede ser un valor negativo.';
      default:
        return 'Campo inválido.';
    }
  }
}
