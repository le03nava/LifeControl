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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { map } from 'rxjs/operators';
import { ErrorBanner } from '@shared/ui';
import { ApiError } from '@shared/models';
import { httpErrorMessage } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { DepartmentService } from '../../data/department.service';
import { DepartmentControl, DepartmentRequest } from '../../models/department.models';

const DEPARTMENT_CODE_PATTERN = /^[a-zA-Z0-9-]+$/;

/**
 * Create / edit screen of the department catalog.
 *
 * The company is the same inline `mat-select` the list uses: `CompanyService`
 * companies keyed by `company.id`, seeded from the `companyId` query parameter
 * and written back to the URL on change while creating. In edit mode the
 * selector is disabled, because a department is reached through its company in
 * the URL (`/companies/{companyId}/departments/{id}`) and moving it across
 * companies is not an operation the API offers.
 *
 * The form carries **no version control and no 412 branch** (T2): the catalog
 * has no `version` column.
 */
@Component({
  selector: 'app-department-edit',
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
  templateUrl: './department-edit.html',
  styleUrl: './department-edit.scss',
})
export class DepartmentEdit implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly companyService = inject(CompanyService);
  private readonly departmentService = inject(DepartmentService);
  private readonly destroyRef = inject(DestroyRef);

  readonly companies = toSignal(
    this.companyService.getCompanies(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Route id: present only on the `edit/:id` child. */
  readonly departmentId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly isEditMode = computed(() => !!this.departmentId());
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly submitError = signal<string | null>(null);
  readonly serverErrors = signal<Record<string, string>>({});

  readonly formGroup = new FormGroup<DepartmentControl>({
    departmentCode: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(10),
        Validators.pattern(DEPARTMENT_CODE_PATTERN),
      ],
    }),
    departmentName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [Validators.maxLength(255)],
    }),
    // Optional and nullable, unlike the required strings above: the API accepts
    // an omitted order and the empty input must not become NaN.
    displayOrder: new FormControl<number | null>(null, { validators: [Validators.min(1)] }),
    enabled: new FormControl(true, { nonNullable: true }),
  });

  private readonly defaultErrorMessages: Record<string, (error: unknown) => string> = {
    required: () => 'Este campo es obligatorio.',
    maxlength: (err) =>
      `No puede superar los ${(err as { requiredLength?: number }).requiredLength} caracteres.`,
    pattern: () => 'Solo letras, números y guiones',
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
    const id = this.departmentId();
    if (!id) return;

    const companyId = this.selectedCompanyId();
    if (!companyId) {
      // The nested URL needs the company to build anything; without it the
      // screen can only say why nothing loaded.
      this.loadError.set('No se indicó la empresa del departamento.');
      return;
    }

    this.loading.set(true);
    this.departmentService
      .getDepartment(companyId, id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (department) => {
          this.selectedCompanyId.set(department.companyId);
          this.formGroup.patchValue({
            departmentCode: department.departmentCode,
            departmentName: department.departmentName,
            description: department.description ?? '',
            displayOrder: department.displayOrder,
            enabled: department.enabled,
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
    const id = this.departmentId();
    const save$ = id
      ? this.departmentService.updateDepartment(companyId, id, request)
      : this.departmentService.addDepartment(companyId, request);

    save$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateToList(companyId),
      error: (err: HttpErrorResponse) => this.handleSubmitError(err),
    });
  }

  onCancel(): void {
    this.navigateToList(this.selectedCompanyId());
  }

  private buildRequest(): DepartmentRequest {
    const value = this.formGroup.getRawValue();
    const description = value.description.trim();
    return {
      departmentCode: value.departmentCode.trim(),
      departmentName: value.departmentName.trim(),
      description: description.length > 0 ? description : null,
      displayOrder: value.displayOrder,
      enabled: value.enabled,
    };
  }

  private navigateToList(companyId: string | null): void {
    this.router.navigate(['/hr/departments'], {
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
