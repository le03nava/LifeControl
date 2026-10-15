import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { map } from 'rxjs/operators';
import { catchError, of } from 'rxjs';
import { ErrorBanner } from '@shared/ui';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { PositionService } from '../../data/position.service';
import { DepartmentService } from '../../data/department.service';
import { Position, PositionControl, PositionRequest } from '../../models/position.models';
import { Department } from '../../models/department.models';

/**
 * Create / edit screen of the position catalog.
 *
 * The company is the same inline `mat-select` the list uses: `CompanyService`
 * companies keyed by `company.id`, seeded from the `companyId` query parameter
 * and written back to the URL on change while creating. In edit mode it is
 * disabled, because a position is reached through its company in the URL
 * (`/companies/{companyId}/positions/{id}`) and the API offers no way to move
 * one across companies.
 *
 * The **department** stays editable in both modes: the nested path addresses
 * the position, and a position changing department is exactly what the request
 * record allows — the unique code is per department, so the move is meaningful
 * and the API answers 409 when the destination already holds that code.
 *
 * **`reportsTo`** is an optional select over the company's own positions. The
 * screen removes exactly one option, the position itself, because that is the
 * one cycle it can prove invalid before sending anything; the deeper cycles
 * (A before B before A) stay the server's rule to enforce (record T5, G5): a
 * client that tried to reproduce that walk would be a second implementation of
 * an invariant that already has an owner.
 *
 * There is **no version control and no 412 branch** (T2).
 */
@Component({
  selector: 'app-position-edit',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    ErrorBanner,
  ],
  templateUrl: './position-edit.html',
  styleUrl: './position-edit.scss',
})
export class PositionEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly companyService = inject(CompanyService);
  private readonly positionService = inject(PositionService);
  private readonly departmentService = inject(DepartmentService);
  private readonly destroyRef = inject(DestroyRef);

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Route id: present only on the `edit/:id` child. */
  readonly positionId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.positionId());
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly submitError = signal<string | null>(null);
  readonly serverErrors = signal<Record<string, string>>({});

  /** The company's departments: the required parent of every position (T11). */
  readonly departmentsResource = rxResource({
    params: () => this.selectedCompanyId() ?? undefined,
    stream: ({ params: companyId }) =>
      this.departmentService
        .getDepartments(companyId)
        .pipe(catchError(() => of([] as Department[]))),
    defaultValue: [] as Department[],
  });

  readonly departments = computed(() =>
    this.departmentsResource.hasValue() ? this.departmentsResource.value() : [],
  );

  /** The company's positions: the `reportsTo` options, read once per company. */
  readonly positionsResource = rxResource({
    params: () => this.selectedCompanyId() ?? undefined,
    stream: ({ params: companyId }) =>
      this.positionService
        .getPositions(companyId)
        .pipe(catchError(() => of([] as Position[]))),
    defaultValue: [] as Position[],
  });

  /** The self option is removed: the API rejects a self-reporting position (T5). */
  readonly reportsToOptions = computed(() =>
    (this.positionsResource.hasValue() ? this.positionsResource.value() : []).filter(
      (position) => position.id !== this.positionId(),
    ),
  );

  readonly formGroup = new FormGroup<PositionControl>({
    departmentId: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    // The API caps the code at ten characters and declares no character class,
    // so the screen does not invent one: a rule the backend accepts must not be
    // refused here.
    positionCode: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(10)],
    }),
    positionName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(500)],
    }),
    reportsToPositionId: new FormControl<string | null>(null),
    displayOrder: new FormControl<number | null>(null, { validators: [Validators.min(1)] }),
    enabled: new FormControl(true, { nonNullable: true }),
  });

  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    min: (err) => `El valor mínimo es ${(err as { min?: number }).min}.`,
    serverError: (err) => err as string,
  };

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

  ngOnInit(): void {
    const id = this.positionId();
    if (!id) return;

    const companyId = this.selectedCompanyId();
    if (!companyId) {
      // The nested URL needs the company to build anything; without it the
      // screen can only say why nothing loaded.
      this.loadError.set('No se indicó la empresa del puesto.');
      return;
    }

    this.loading.set(true);
    this.positionService
      .getPosition(companyId, id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (position) => {
          this.selectedCompanyId.set(position.companyId);
          this.formGroup.patchValue({
            departmentId: position.departmentId,
            positionCode: position.positionCode,
            positionName: position.positionName,
            description: position.description ?? '',
            reportsToPositionId: position.reportsToPositionId,
            displayOrder: position.displayOrder,
            enabled: position.enabled,
          });
          this.loading.set(false);
        },
        error: (err: HttpErrorResponse) => {
          this.loadError.set(httpErrorMessage(err));
          this.loading.set(false);
        },
      });
  }

  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    // The parent and the reporting position both belong to one company; keeping
    // either one across a company change would submit a cross-company link.
    this.formGroup.controls.departmentId.setValue('');
    this.formGroup.controls.reportsToPositionId.setValue(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  onSubmit(): void {
    this.submitError.set(null);
    this.clearServerErrors();

    if (this.formGroup.invalid) {
      this.formGroup.markAllAsTouched();
      return;
    }

    const companyId = this.selectedCompanyId();
    if (!companyId) return;

    const request = this.buildRequest();
    const id = this.positionId();
    const save$ = id
      ? this.positionService.updatePosition(companyId, id, request)
      : this.positionService.addPosition(companyId, request);

    save$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateToList(companyId),
      error: (err: HttpErrorResponse) => this.handleSubmitError(err),
    });
  }

  onCancel(): void {
    this.navigateToList(this.selectedCompanyId());
  }

  private buildRequest(): PositionRequest {
    const value = this.formGroup.getRawValue();
    const description = value.description.trim();
    return {
      departmentId: value.departmentId,
      positionCode: value.positionCode.trim(),
      positionName: value.positionName.trim(),
      description: description.length > 0 ? description : null,
      reportsToPositionId: value.reportsToPositionId,
      displayOrder: value.displayOrder,
      enabled: value.enabled,
    };
  }

  private navigateToList(companyId: string | null): void {
    this.router.navigate(['/hr/positions'], {
      queryParams: companyId ? { companyId } : {},
    });
  }

  /**
   * Maps the API error envelope: a per-field `errors` map lands on the controls
   * (rendered through `getErrorMessage`'s `serverError` entry), anything else
   * becomes the banner. The 409 duplicate message is a `message`, not a field
   * map, so it takes the banner path.
   */
  private handleSubmitError(err: HttpErrorResponse): void {
    const apiError = err.error as ApiError | undefined;
    if (apiError?.errors && Object.keys(apiError.errors).length > 0) {
      this.serverErrors.set(apiError.errors);
      for (const [field, message] of Object.entries(apiError.errors)) {
        const control = this.formGroup.get(field);
        if (control) {
          control.setErrors({ ...(control.errors ?? {}), serverError: message });
          control.markAsTouched();
        }
      }
      return;
    }
    this.submitError.set(apiError?.message ?? httpErrorMessage(err));
  }

  private clearServerErrors(): void {
    this.serverErrors.set({});
    for (const control of Object.values(this.formGroup.controls)) {
      if (control.errors && 'serverError' in control.errors) {
        const { serverError: _serverError, ...rest } = control.errors;
        control.setErrors(Object.keys(rest).length > 0 ? rest : null);
      }
    }
  }
}
