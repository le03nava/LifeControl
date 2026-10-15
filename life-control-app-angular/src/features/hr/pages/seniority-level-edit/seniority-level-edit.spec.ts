/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Signal, WritableSignal, signal } from '@angular/core';
import { of, throwError } from 'rxjs';
import { SeniorityLevelEdit } from './seniority-level-edit';
import { SeniorityLevelService } from '../../data/seniority-level.service';
import { SeniorityLevel } from '../../models/seniority-level.models';

describe('SeniorityLevelEdit', () => {
  let fixture: ComponentFixture<SeniorityLevelEdit>;
  let component: SeniorityLevelEdit;
  let service: {
    error: Signal<string | null>;
    levels: Signal<SeniorityLevel[]>;
    loading: Signal<boolean>;
    getSeniorityLevel: ReturnType<typeof vi.fn>;
    addSeniorityLevel: ReturnType<typeof vi.fn>;
    updateSeniorityLevel: ReturnType<typeof vi.fn>;
  };
  let router: Router;
  let errorSignal: WritableSignal<string | null>;

  const level = (overrides: Partial<SeniorityLevel> = {}): SeniorityLevel => ({
    id: 'lvl-1',
    levelCode: 'JUN',
    levelName: 'Junior',
    rank: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  interface SetupOptions {
    editId?: string | null;
    loadError?: boolean;
    submitError?: HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    errorSignal = signal<string | null>(null);

    service = {
      error: errorSignal.asReadonly(),
      levels: signal<SeniorityLevel[]>([]).asReadonly(),
      loading: signal(false).asReadonly(),
      getSeniorityLevel: vi.fn(() => {
        if (options.loadError) {
          return throwError(() => new HttpErrorResponse({ status: 404 }));
        }
        return of(level());
      }),
      addSeniorityLevel: vi.fn(() => of(level())),
      updateSeniorityLevel: vi.fn(() => of(level())),
    };

    if (options.submitError) {
      service.addSeniorityLevel.mockReturnValue(throwError(() => options.submitError));
      service.updateSeniorityLevel.mockReturnValue(throwError(() => options.submitError));
    }

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SeniorityLevelEdit, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: SeniorityLevelService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(options.editId ? { id: options.editId } : {}),
              queryParamMap: convertToParamMap({}),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(SeniorityLevelEdit);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate');
  }

  async function settle(): Promise<void> {
    for (let i = 0; i < 5; i += 1) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  const validValue = {
    levelCode: 'LID',
    levelName: 'Líder',
    rank: 3,
    enabled: true,
  };

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should not load a level in create mode and should render no company context', async () => {
    setup();
    await settle();

    expect(component.isEditMode()).toBe(false);
    expect(service.getSeniorityLevel).not.toHaveBeenCalled();
    expect(text()).toContain('Nuevo Nivel de Antigüedad');
    // D1: global reference data; the form has no company to select.
    expect(text()).not.toContain('Empresa');
  });

  it('should load the level into the form in edit mode', async () => {
    setup({ editId: 'lvl-1' });
    await settle();

    expect(service.getSeniorityLevel).toHaveBeenCalledWith('lvl-1');
    expect(component.formGroup.getRawValue()).toEqual({
      levelCode: 'JUN',
      levelName: 'Junior',
      rank: 1,
      enabled: true,
    });
    expect(text()).toContain('Editar Nivel de Antigüedad');
  });

  it('should refuse to submit while the required fields are empty', async () => {
    setup();
    await settle();

    component.onSubmit();
    await settle();

    expect(service.addSeniorityLevel).not.toHaveBeenCalled();
    expect(component.formGroup.controls.levelCode.touched).toBe(true);
    expect(component.formGroup.controls.rank.touched).toBe(true);
    expect(text()).toContain('Este campo es obligatorio.');
  });

  it('should refuse a rank below one instead of sending it', async () => {
    setup();
    await settle();

    component.formGroup.setValue({ ...validValue, rank: 0 });
    component.onSubmit();
    await settle();

    expect(service.addSeniorityLevel).not.toHaveBeenCalled();
    expect(text()).toContain('El valor mínimo es 1.');
  });

  it('should create the level with the trimmed request and return to the list', async () => {
    setup();
    await settle();

    component.formGroup.setValue({ ...validValue, levelCode: '  LID  ', levelName: ' Líder ' });
    component.onSubmit();
    await settle();

    expect(service.addSeniorityLevel).toHaveBeenCalledWith({
      levelCode: 'LID',
      levelName: 'Líder',
      rank: 3,
      enabled: true,
    });
    expect(router.navigate).toHaveBeenCalledWith(['/hr/seniority-levels']);
  });

  it('should update the level in edit mode and return to the list', async () => {
    setup({ editId: 'lvl-1' });
    await settle();

    component.formGroup.controls.rank.setValue(2);
    component.onSubmit();
    await settle();

    expect(service.updateSeniorityLevel).toHaveBeenCalledWith('lvl-1', {
      levelCode: 'JUN',
      levelName: 'Junior',
      rank: 2,
      enabled: true,
    });
    expect(service.addSeniorityLevel).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/hr/seniority-levels']);
  });

  it('should report a load failure instead of rendering an empty form', async () => {
    setup({ editId: 'lvl-1', loadError: true });
    await settle();

    expect(component.loadError()).toBeTruthy();
  });

  it('should surface the duplicate conflict as a banner', async () => {
    setup({
      submitError: new HttpErrorResponse({
        status: 409,
        error: { message: 'Ya existe un nivel de antigüedad con ese código, nombre o rango' },
      }),
    });
    await settle();

    component.formGroup.setValue(validValue);
    component.onSubmit();
    await settle();

    expect(text()).toContain('Ya existe un nivel de antigüedad con ese código, nombre o rango');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('should map a per-field error envelope onto the control', async () => {
    setup({
      submitError: new HttpErrorResponse({
        status: 400,
        error: { errors: { rank: 'El rango ya está en uso por otro nivel' } },
      }),
    });
    await settle();

    component.formGroup.setValue(validValue);
    component.onSubmit();
    await settle();

    expect(component.serverErrors()).toEqual({ rank: 'El rango ya está en uso por otro nivel' });
    expect(text()).toContain('El rango ya está en uso por otro nivel');
  });

  it('should return to the list on cancel', async () => {
    setup();
    await settle();

    component.onCancel();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/seniority-levels']);
  });
});
