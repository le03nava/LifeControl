/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { FormGroup, NonNullableFormBuilder, Validators } from '@angular/forms';
import { SchedulingAvailabilityEditor } from './scheduling-availability-editor';
import {
  SchedulingAvailabilityFormControl,
  SchedulingAvailabilityRowControl,
} from '../../models/scheduling-activity.models';

describe('SchedulingAvailabilityEditor', () => {
  let fixture: ComponentFixture<SchedulingAvailabilityEditor>;
  let component: SchedulingAvailabilityEditor;
  let fb: NonNullableFormBuilder;

  function buildRow(dayOfWeek = 1): FormGroup<SchedulingAvailabilityRowControl> {
    return fb.group<SchedulingAvailabilityRowControl>({
      key: fb.control('local-row'),
      dayOfWeek: fb.control(dayOfWeek, [Validators.required, Validators.min(1), Validators.max(7)]),
      startTime: fb.control('09:00', Validators.required),
      endTime: fb.control('13:00', Validators.required),
      validFrom: fb.control('2026-09-28', Validators.required),
      validTo: fb.control('2027-09-28', Validators.required),
    });
  }

  function buildForm(
    rows: FormGroup<SchedulingAvailabilityRowControl>[],
  ): FormGroup<SchedulingAvailabilityFormControl> {
    return fb.group<SchedulingAvailabilityFormControl>({ windows: fb.array(rows) });
  }

  function setup(
    rows: FormGroup<SchedulingAvailabilityRowControl>[],
    canWrite = true,
    maxWindows = 50,
  ): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SchedulingAvailabilityEditor, NoopAnimationsModule],
    });
    fixture = TestBed.createComponent(SchedulingAvailabilityEditor);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('formGroup', buildForm(rows));
    fixture.componentRef.setInput('canWrite', canWrite);
    fixture.componentRef.setInput('maxWindows', maxWindows);
  }

  beforeAll(() => {
    fb = TestBed.configureTestingModule({}).inject(NonNullableFormBuilder);
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function buttonByText(label: string): HTMLButtonElement | undefined {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === label,
    );
  }

  it('should render one row per window control', () => {
    setup([buildRow(), buildRow(3)]);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.window-row').length).toBe(2);
  });

  it('should show the empty message when there are no rows', () => {
    setup([]);
    fixture.detectChanges();

    expect(text()).toContain('No hay ventanas cargadas');
  });

  it('should emit the add intent while below the cap', () => {
    setup([buildRow()]);
    fixture.detectChanges();
    const spy = vi.fn();
    component.addWindow.subscribe(spy);

    buttonByText('Agregar ventana')!.click();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('should block the add action at the server cap and explain it', () => {
    setup([buildRow()], true, 1);
    fixture.detectChanges();
    const spy = vi.fn();
    component.addWindow.subscribe(spy);

    const addButton = buttonByText('Agregar ventana');
    expect(addButton?.disabled).toBe(true);
    expect(text()).toContain('máximo de 1 ventanas');

    component.onAddWindow();
    expect(spy).not.toHaveBeenCalled();
  });

  it('should emit the removed row index', () => {
    setup([buildRow(), buildRow(3)]);
    fixture.detectChanges();
    const spy = vi.fn();
    component.removeWindow.subscribe(spy);

    const removeButtons = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[aria-label^="Quitar la ventana"]',
    );
    (removeButtons[1] as HTMLButtonElement).click();

    expect(spy).toHaveBeenCalledWith(1);
  });

  it('should emit a save once the form is valid', () => {
    setup([buildRow()]);
    fixture.detectChanges();
    const spy = vi.fn();
    component.save.subscribe(spy);

    buttonByText('Guardar')!.click();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('should block a save while a required field is empty', () => {
    const row = buildRow();
    row.controls.startTime.setValue('');
    setup([row]);
    fixture.detectChanges();
    const spy = vi.fn();
    component.save.subscribe(spy);

    component.onSave();

    expect(spy).not.toHaveBeenCalled();
  });

  it('should not emit a save while a save is already in flight', () => {
    setup([buildRow()]);
    fixture.componentRef.setInput('saving', true);
    fixture.detectChanges();
    const spy = vi.fn();
    component.save.subscribe(spy);

    component.onSave();

    expect(spy).not.toHaveBeenCalled();
  });

  it('should render a field validation message for a touched control', () => {
    const row = buildRow();
    row.controls.startTime.setValue('');
    row.controls.startTime.markAsTouched();
    setup([row]);
    fixture.detectChanges();

    expect(text()).toContain('Este campo es obligatorio.');
  });

  it('should render the cross-field row message', () => {
    const row = fb.group<SchedulingAvailabilityRowControl>(
      {
        key: fb.control('local-row'),
        dayOfWeek: fb.control(1, Validators.required),
        startTime: fb.control('13:00', Validators.required),
        endTime: fb.control('09:00', Validators.required),
        validFrom: fb.control('2026-09-28', Validators.required),
        validTo: fb.control('2027-09-28', Validators.required),
      },
      {
        validators: (group) =>
          (group.get('endTime')?.value as string) <= (group.get('startTime')?.value as string)
            ? { endTimeNotAfterStart: true }
            : null,
      },
    );
    setup([row]);
    fixture.detectChanges();
    row.markAllAsTouched();
    fixture.detectChanges();

    expect(text()).toContain('La hora de fin debe ser posterior a la hora de inicio.');
  });

  it('should render the set-level overlap message', () => {
    const array = fb.array([buildRow()], () => ({ overlappedWindows: true }));
    const form = fb.group<SchedulingAvailabilityFormControl>({ windows: array });
    setup([]);
    fixture.componentRef.setInput('formGroup', form);
    fixture.detectChanges();
    array.markAllAsTouched();
    fixture.detectChanges();

    expect(text()).toContain('Dos ventanas del mismo día no pueden superponerse.');
  });

  it('should render the set-level overlap message while the set is inconsistent, even untouched', () => {
    const array = fb.array([buildRow()], () => ({ overlappedWindows: true }));
    const form = fb.group<SchedulingAvailabilityFormControl>({ windows: array });
    setup([]);
    fixture.componentRef.setInput('formGroup', form);
    fixture.detectChanges();

    // The set error is what makes Guardar unavailable, so the reason is readable
    // without waiting for an interaction that may never touch the array.
    expect(array.touched).toBe(false);
    expect(text()).toContain('Dos ventanas del mismo día no pueden superponerse.');
    expect(buttonByText('Guardar')?.disabled).toBe(true);
  });

  it('should not render the set-level overlap message while the set is consistent', () => {
    const array = fb.array([buildRow()], () => null);
    const form = fb.group<SchedulingAvailabilityFormControl>({ windows: array });
    setup([]);
    fixture.componentRef.setInput('formGroup', form);
    fixture.detectChanges();
    array.markAllAsTouched();
    fixture.detectChanges();

    expect(array.hasError('overlappedWindows')).toBe(false);
    expect(text()).not.toContain('Dos ventanas del mismo día no pueden superponerse.');
    expect(buttonByText('Guardar')?.disabled).toBe(false);
  });

  it('should not render add, remove or save controls for a read-only user', () => {
    setup([buildRow()], false);
    fixture.detectChanges();

    expect(buttonByText('Agregar ventana')).toBeUndefined();
    expect(buttonByText('Guardar')).toBeUndefined();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[aria-label^="Quitar la ventana"]'),
    ).toBeNull();
    // Volver stays reachable so the read-only user can leave the page.
    expect(buttonByText('Volver')).toBeDefined();
  });

  it('should emit the cancel intent', () => {
    setup([], true);
    fixture.detectChanges();
    const spy = vi.fn();
    component.cancelForm.subscribe(spy);

    buttonByText('Volver')!.click();

    expect(spy).toHaveBeenCalledTimes(1);
  });
});
