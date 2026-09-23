import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  merge,
  Observable,
  of,
  Subject,
  switchMap,
  tap,
} from 'rxjs';
import { ProductSupplierService } from '../../data/product-supplier.service';
import { SupplierService } from '../../suppliers/data/supplier.service';
import { ApiError } from '@shared/models';
import {
  ProductSupplier,
  ProductSupplierControl,
  ProductSupplierRequest,
} from '../../models/product-supplier.models';
import {
  ProductSupplierForm,
  SupplierOption,
} from '../product-supplier-form/product-supplier-form';
import { ErrorBanner, ConfirmDialog } from '@shared/ui';

/** Number of suppliers fetched per server-side search. */
const SUPPLIER_PAGE_SIZE = 20;

/** Payload the opener passes to {@link ProductSupplierDialog}. */
export interface ProductSupplierDialogData {
  productId: string;
  /** The row the list already holds; present only in edit mode. */
  assignment?: ProductSupplier;
}

/**
 * Create/edit host for a product-supplier assignment.
 *
 * Replaces the former routed create/edit screen: it owns the write and closes with
 * the saved entity (or `null` on cancel), so the list only has to reload on a truthy
 * result (D22). The supplier picker searches the server (`?search=`) instead of
 * loading every supplier (T8/D23).
 */
@Component({
  selector: 'app-product-supplier-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatDialogTitle, ErrorBanner, ProductSupplierForm],
  templateUrl: './product-supplier-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductSupplierDialog implements OnInit {
  private readonly dialogRef =
    inject<MatDialogRef<ProductSupplierDialog, ProductSupplier | null>>(MatDialogRef);
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly productSupplierService = inject(ProductSupplierService);
  private readonly supplierService = inject(SupplierService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  readonly data = inject<ProductSupplierDialogData>(MAT_DIALOG_DATA);

  readonly editMode = computed(() => this.data.assignment !== undefined);

  readonly assignmentForm = signal<FormGroup<ProductSupplierControl>>(
    this.data.assignment ? this.buildFormFromAssignment(this.data.assignment) : this.createForm(),
  );
  readonly serverErrors = signal<Record<string, string>>({});
  /** Write failures shown in the banner; the dialog stays open. */
  readonly generalError = signal<string | null>(null);
  /** Read failures (assignments or supplier search) shown in the banner. */
  readonly loadError = signal<string | null>(null);

  /** Mirrors the form's dirty flag as a signal: control state is not reactive. */
  private readonly dirty = signal(false);
  /**
   * Whether the operator has edited the form. Drives the close guard and the
   * cancel confirmation; a form built from the loaded assignment starts clean.
   */
  readonly formDirty = this.dirty.asReadonly();

  /** The current server result page for the picker. */
  readonly suppliers = signal<SupplierOption[]>([]);
  readonly searching = signal(false);
  readonly assignedSuppliers = signal<ProductSupplier[]>([]);

  private readonly supplierSearch$ = new Subject<string>();

  constructor() {
    // Only operator edits reach this: the form is built from the loaded assignment and
    // is never patched afterwards, and the child form applies server errors with
    // `emitEvent: false`. A freshly loaded form therefore starts clean.
    this.assignmentForm()
      .valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.dirty.set(true));

    // Esc and backdrop must not discard typed input silently. `disableClose` is a
    // mutable `MatDialogRef` property, so it is driven reactively from the signal.
    effect(() => {
      this.dialogRef.disableClose = this.formDirty();
    });
  }

  /** The options the picker may offer: already-assigned suppliers are excluded. */
  readonly availableSuppliers = computed<SupplierOption[]>(() => {
    const currentId = this.assignmentForm().controls.supplierId.value;
    const assigned = this.assignedSuppliers();
    const options = this.suppliers().filter((supplier) => {
      if (this.editMode() && supplier.id === currentId) return true;
      return !assigned.some((row) => row.supplierId === supplier.id);
    });

    // The current supplier must stay selectable even when the current result page
    // omits it: edit mode has to show the assignment it is editing, and a picker
    // that hides the current selection would display a bare id.
    const current = this.data.assignment;
    if (this.editMode() && current && !options.some((option) => option.id === current.supplierId)) {
      options.unshift({ id: current.supplierId, supplierName: current.supplierName });
    }

    return options;
  });

  ngOnInit(): void {
    this.loadAssignedSuppliers();

    // The seed read and every keystroke share one cancellable stream, so a seed
    // that resolves after a search is unsubscribed by `switchMap` and can never
    // overwrite the newer result (F2). Debounce and dedupe apply only to
    // keystrokes: the seed fires immediately, and `searching` flips on as soon as
    // the user types, before the debounce window closes.
    merge(
      of(''),
      this.supplierSearch$.pipe(
        tap(() => this.searching.set(true)),
        debounceTime(300),
        distinctUntilChanged(),
      ),
    )
      .pipe(
        tap(() => this.searching.set(true)),
        // `switchMap`, deliberately: the reference idiom at
        // supplier-info-section.ts:94-125 subscribes per term, so a slow earlier
        // response can overwrite a later one. `switchMap` cancels the in-flight
        // request instead (D23).
        switchMap((term) => this.loadSuppliers(term)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((suppliers) => {
        this.suppliers.set(suppliers);
        this.searching.set(false);
      });
  }

  onSupplierSearch(term: string): void {
    this.supplierSearch$.next(term);
  }

  onSaveAssignment(data: ProductSupplierRequest): void {
    const productId = this.data.productId;
    const assignment = this.data.assignment;

    const request$ = assignment
      ? this.productSupplierService.updateSupplier(productId, assignment.id, data)
      : this.productSupplierService.addSupplier(productId, data);

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

  private loadSuppliers(term: string): Observable<SupplierOption[]> {
    return this.supplierService.getSuppliers(0, SUPPLIER_PAGE_SIZE, term.trim() || undefined).pipe(
      map((page) =>
        page.content.map((supplier) => ({
          id: supplier.id,
          supplierName: supplier.supplierName,
        })),
      ),
      tap(() => this.loadError.set(null)),
      catchError(() => {
        this.loadError.set('No pudimos cargar los proveedores. Intentá de nuevo más tarde.');
        return of<SupplierOption[]>([]);
      }),
    );
  }

  private loadAssignedSuppliers(): void {
    this.productSupplierService
      .getSuppliers(this.data.productId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (assignments) => this.assignedSuppliers.set(assignments),
        error: () =>
          this.loadError.set(
            'No pudimos cargar las asignaciones del producto. Intentá de nuevo más tarde.',
          ),
      });
  }

  private buildFormFromAssignment(assignment: ProductSupplier): FormGroup<ProductSupplierControl> {
    return this.fb.group({
      id: this.fb.control(assignment.id),
      supplierId: this.fb.control(assignment.supplierId, Validators.required),
      purchaseCost: this.fb.control(assignment.purchaseCost, [
        Validators.required,
        Validators.min(0),
      ]),
      main: this.fb.control(assignment.main),
      enabled: this.fb.control(assignment.enabled),
    });
  }

  private createForm(): FormGroup<ProductSupplierControl> {
    return this.fb.group({
      id: this.fb.control(''),
      supplierId: this.fb.control('', Validators.required),
      purchaseCost: this.fb.control(0, [Validators.required, Validators.min(0)]),
      main: this.fb.control(false),
      enabled: this.fb.control(true),
    });
  }

  private handleServerError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      this.generalError.set(null);
    } else if (apiError?.message) {
      this.serverErrors.set({});
      this.generalError.set(apiError.message);
    } else if (err.status === 409) {
      this.serverErrors.set({});
      this.generalError.set('Este proveedor ya está asignado al producto.');
    } else {
      this.serverErrors.set({});
      this.generalError.set('Ocurrió un error inesperado. Intentá de nuevo más tarde.');
    }
  }
}
