import { ChangeDetectionStrategy, Component, effect, input, output, signal } from '@angular/core';
import { AbstractControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  ProductSupplierControl,
  ProductSupplierRequest,
} from '../../models/product-supplier.models';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatIconModule } from '@angular/material/icon';

/**
 * The minimal shape the supplier picker needs. Narrower than `Supplier` on
 * purpose: the dialog may have to surface a supplier that is not in the current
 * server result page (the current selection in edit mode), and fabricating a
 * full `Supplier` for it would mean inventing fields the picker never reads.
 */
export interface SupplierOption {
  id: string;
  supplierName: string;
}

@Component({
  selector: 'app-product-supplier-form',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatSlideToggleModule,
    MatTooltipModule,
    MatIconModule,
  ],
  templateUrl: './product-supplier-form.html',
  styleUrl: './product-supplier-form.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductSupplierForm {
  formGroup = input.required<FormGroup<ProductSupplierControl>>();
  serverErrors = input<Record<string, string>>({});
  editMode = input<boolean>(false);
  availableSuppliers = input<SupplierOption[]>([]);
  /** Whether the host is fetching a new option page; drives the in-panel status row. */
  searching = input<boolean>(false);

  saveSupplier = output<ProductSupplierRequest>();
  cancelForm = output<void>();
  /** Emitted on every keystroke so the host can debounce a server-side search. */
  supplierSearch = output<string>();

  readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    min: (err) => `El valor mínimo es ${(err as { min?: number }).min}.`,
    serverError: (err) => err as string,
    invalidSelection: () => 'Seleccioná un proveedor de la lista.',
  };

  /** Autocomplete display formatter: maps a supplier id to its name. */
  readonly displaySupplier = (id: string | null): string =>
    this.availableSuppliers().find((supplier) => supplier.id === id)?.supplierName ?? id ?? '';

  /**
   * The last text the user typed into the picker; `null` until the first keystroke.
   *
   * `MatAutocomplete.requireSelection` only suppresses the value-accessor write while
   * the user is typing; an Enter-submit with the panel open and no active option runs
   * `onSave` while the field shows the typed text and the control still holds the
   * pre-typing id. This signal is how `onSave` knows what the user can actually see.
   */
  private readonly lastTypedTerm = signal<string | null>(null);

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
          console.warn(`[ProductSupplierForm] No control found for server error key: "${key}"`);
        }
      });

      onCleanup(() => {
        subscriptions.forEach((sub) => sub.unsubscribe());
      });
    });
  }

  onSave(): void {
    const formGroup = this.formGroup();
    formGroup.markAllAsTouched();

    const supplierId = formGroup.controls.supplierId.value;
    // Free text must not be submittable (D23). Two independent checks, because
    // `requireSelection` suppresses the value-accessor write only while the user is
    // typing: on Enter-submit the panel stays open with no active option, `onSave`
    // runs, and in edit mode the control still holds the pre-typing id while the
    // field shows the typed text. The membership check catches an id outside the
    // offered options; the visible-text check catches a stale but offered id that
    // the user can no longer see as the current selection.
    const isOffered =
      !supplierId || this.availableSuppliers().some((supplier) => supplier.id === supplierId);
    const typed = this.lastTypedTerm();
    const matchesVisibleSelection =
      typed === null ||
      typed.trim().toLowerCase() === this.displaySupplier(supplierId).trim().toLowerCase();

    if (!isOffered || !matchesVisibleSelection) {
      formGroup.controls.supplierId.setErrors({
        ...(formGroup.controls.supplierId.errors ?? {}),
        invalidSelection: true,
      });
      return;
    }

    if (formGroup.valid) {
      const raw = formGroup.getRawValue();
      const data: ProductSupplierRequest = {
        supplierId: raw.supplierId,
        purchaseCost: raw.purchaseCost,
        main: raw.main,
        enabled: raw.enabled,
      };
      this.saveSupplier.emit(data);
    }
  }

  onSupplierSearch(event: Event): void {
    const term = (event.target as HTMLInputElement).value;
    this.lastTypedTerm.set(term);
    this.supplierSearch.emit(term);
  }

  onSupplierSelected(supplierId: string): void {
    // Selecting an option is the user confirming the label the trigger is about to
    // write into the field, so the tracked term becomes that label, not the raw id.
    this.lastTypedTerm.set(this.displaySupplier(supplierId));
    this.formGroup().controls.supplierId.setValue(supplierId);
  }

  onCancel(): void {
    this.cancelForm.emit();
  }
}
