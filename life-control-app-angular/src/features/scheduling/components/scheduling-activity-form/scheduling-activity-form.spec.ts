/// <reference types="vitest/globals" />
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { NonNullableFormBuilder, Validators } from '@angular/forms';
import { SchedulingActivityForm } from './scheduling-activity-form';
import {
  SchedulingActivityControl,
  SchedulingActivityFormValue,
} from '../../models/scheduling-activity.models';

@Component({
  selector: 'app-scheduling-activity-form-host',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SchedulingActivityForm],
  template: `
    <app-scheduling-activity-form
      [formGroup]="form"
      [serverErrors]="serverErrors()"
      [isEditMode]="isEditMode()"
      [saving]="saving()"
      [canWrite]="canWrite()"
      [saveBlockedReason]="saveBlockedReason()"
      (save)="saved.set($event)"
      (cancelForm)="cancelled.set(true)"
    />
  `,
})
class HostComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly form = this.fb.group<SchedulingActivityControl>({
    activityName: this.fb.control('', [Validators.required, Validators.maxLength(150)]),
    description: this.fb.control<string | null>(null, [Validators.maxLength(2000)]),
    durationMinutes: this.fb.control(60, [Validators.required, Validators.min(1)]),
    capacityPerSlot: this.fb.control(1, [Validators.required, Validators.min(1)]),
    userId: this.fb.control<string | null>(null),
  });

  readonly serverErrors = signal<Record<string, string>>({});
  readonly isEditMode = signal(false);
  readonly saving = signal(false);
  readonly canWrite = signal(true);
  readonly saveBlockedReason = signal<string | null>(null);

  readonly saved = signal<SchedulingActivityFormValue | null>(null);
  readonly cancelled = signal(false);
}

describe('SchedulingActivityForm', () => {
  let fixture: ComponentFixture<HostComponent>;
  let host: HostComponent;
  let form: SchedulingActivityForm;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent, NoopAnimationsModule] });
    fixture = TestBed.createComponent(HostComponent);
    host = fixture.componentInstance;
    fixture.detectChanges();
    form = fixture.debugElement.query(By.directive(SchedulingActivityForm)).componentInstance;
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function submitButton(): HTMLButtonElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('button[type="submit"]');
  }

  /** Types into a bound control's input, which is the path a real keystroke takes. */
  function typeInto(controlName: string, value: string): void {
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      `input[formcontrolname="${controlName}"]`,
    );
    if (!input) throw new Error(`No input bound to control "${controlName}"`);
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('should render the create copy by default and the edit copy when asked', () => {
    expect(text()).toContain('Nueva actividad');

    host.isEditMode.set(true);
    fixture.detectChanges();

    expect(text()).toContain('Editar actividad');
    expect(text()).toContain('Actualizar');
  });

  it('should emit the raw value on a valid submit', () => {
    host.form.patchValue({ activityName: 'Yoga', durationMinutes: 45, capacityPerSlot: 6 });
    fixture.detectChanges();

    form.onSave();

    expect(host.saved()).toEqual({
      activityName: 'Yoga',
      description: null,
      durationMinutes: 45,
      capacityPerSlot: 6,
      userId: null,
    });
  });

  it('should not emit when the form is invalid', () => {
    // `activityName` is required and still empty.
    form.onSave();

    expect(host.saved()).toBeNull();
  });

  it('should not emit while a save is in flight', () => {
    host.form.patchValue({ activityName: 'Yoga' });
    host.saving.set(true);
    fixture.detectChanges();

    form.onSave();

    expect(host.saved()).toBeNull();
  });

  it('should block a direct handler call while a save-blocked reason is set, and emit once it clears', () => {
    // The form is valid, so only the save-blocked reason can stop the emission.
    host.form.patchValue({ activityName: 'Yoga' });
    host.saveBlockedReason.set('La lectura de la actividad falló.');
    fixture.detectChanges();

    // Invoked directly, bypassing the submit control and its disabled DOM state:
    // the guard must hold at the emission boundary itself, not only at the control.
    form.onSave();

    expect(host.saved()).toBeNull();

    host.saveBlockedReason.set(null);
    fixture.detectChanges();

    form.onSave();

    expect(host.saved()).toEqual({
      activityName: 'Yoga',
      description: null,
      durationMinutes: 60,
      capacityPerSlot: 1,
      userId: null,
    });
  });

  it('should hide the submit control for a read-only user', () => {
    expect(submitButton()).not.toBeNull();

    host.canWrite.set(false);
    fixture.detectChanges();

    expect(submitButton()).toBeNull();
  });

  it('should emit cancel', () => {
    form.onCancel();

    expect(host.cancelled()).toBe(true);
  });

  describe('server errors', () => {
    it('should map a server error onto the control and clear it when the operator edits that field', () => {
      // A valid value first, so the control carries no validator error and the
      // server message is the one the form renders.
      typeInto('activityName', 'otro');
      host.serverErrors.set({ activityName: 'duplicado' });
      fixture.detectChanges();
      // Marks every control touched so the rendered message is exercised too.
      form.onSave();
      fixture.detectChanges();

      expect(host.form.controls.activityName.errors?.['serverError']).toBe('duplicado');
      expect(text()).toContain('duplicado');

      typeInto('activityName', 'otro todavia');
      fixture.detectChanges();

      expect(host.form.controls.activityName.errors?.['serverError']).toBeUndefined();
      expect(text()).not.toContain('duplicado');
    });

    it('should clear only the edited field server error, not the other fields', () => {
      host.serverErrors.set({
        activityName: 'duplicado',
        durationMinutes: 'duración inválida',
      });
      fixture.detectChanges();

      typeInto('activityName', 'otro');
      fixture.detectChanges();

      expect(host.form.controls.activityName.errors?.['serverError']).toBeUndefined();
      expect(host.form.controls.durationMinutes.errors?.['serverError']).toBe('duración inválida');
    });

    it('should warn and not throw for a server error key with no control', () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
      host.serverErrors.set({ unknownField: 'x' });
      fixture.detectChanges();

      expect(warn).toHaveBeenCalled();
      warn.mockRestore();
    });
  });

  describe('validation messages', () => {
    it('should render the required message for an empty required control', () => {
      form.onSave();
      fixture.detectChanges();

      expect(text()).toContain('Este campo es obligatorio.');
    });

    it('should render the min message for a value below the minimum', () => {
      host.form.patchValue({ durationMinutes: 0 });
      form.onSave();
      fixture.detectChanges();

      expect(text()).toContain('El valor mínimo es 1.');
    });

    it('should render the maxlength message for an over-long value', () => {
      host.form.patchValue({ activityName: 'x'.repeat(151) });
      form.onSave();
      fixture.detectChanges();

      expect(text()).toContain('No puede superar los 150 caracteres.');
    });

    it('should fall back to a generic message for an unknown error', () => {
      const control = host.form.controls.activityName;
      control.setErrors({ weird: true });
      control.markAsTouched();
      fixture.detectChanges();

      expect(text()).toContain('Campo inválido.');
    });
  });
});
