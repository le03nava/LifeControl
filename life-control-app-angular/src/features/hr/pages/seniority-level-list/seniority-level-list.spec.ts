/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Signal, WritableSignal, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { ConfirmDialog } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { SeniorityLevelList } from './seniority-level-list';
import { SeniorityLevelService } from '../../data/seniority-level.service';
import { SeniorityLevel } from '../../models/seniority-level.models';

describe('SeniorityLevelList', () => {
  let fixture: ComponentFixture<SeniorityLevelList>;
  let component: SeniorityLevelList;
  let seniorityLevelService: {
    error: Signal<string | null>;
    levels: Signal<SeniorityLevel[]>;
    loading: Signal<boolean>;
    getSeniorityLevels: ReturnType<typeof vi.fn>;
    removeSeniorityLevel: ReturnType<typeof vi.fn>;
    enableSeniorityLevel: ReturnType<typeof vi.fn>;
  };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let router: Router;
  let errorSignal: WritableSignal<string | null>;

  const WRITE_ROLES = ['lc-seniority-level'];
  const READ_ONLY_ROLES = ['lc-sales'];
  /** In the HR menu-visibility union but absent from the seniority-level write set. */
  const OTHER_HR_WRITE_ROLES = ['lc-position'];

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
    roles?: string[];
    rows?: SeniorityLevel[];
    loadError?: boolean;
    dialogConfirmed?: boolean;
  }

  function setup(options: SetupOptions = {}): void {
    const rows = options.rows ?? [
      level(),
      level({ id: 'lvl-2', levelCode: 'SEN', levelName: 'Senior', rank: 2, enabled: false }),
    ];
    errorSignal = signal<string | null>(null);

    seniorityLevelService = {
      error: errorSignal.asReadonly(),
      levels: signal<SeniorityLevel[]>([]).asReadonly(),
      loading: signal(false).asReadonly(),
      getSeniorityLevels: vi.fn(() => {
        if (options.loadError) {
          errorSignal.set('Error al cargar los niveles de antigüedad');
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        errorSignal.set(null);
        return of(rows);
      }),
      removeSeniorityLevel: vi.fn().mockReturnValue(of(undefined)),
      enableSeniorityLevel: vi.fn().mockReturnValue(of(level({ enabled: true }))),
    };

    notifications = { showSuccess: vi.fn() };
    dialog = {
      open: vi.fn().mockReturnValue({ afterClosed: () => of(options.dialogConfirmed ?? false) }),
    };

    const roles = options.roles ?? WRITE_ROLES;

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SeniorityLevelList, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        { provide: SeniorityLevelService, useValue: seniorityLevelService },
        { provide: NotificationService, useValue: notifications },
        { provide: MatDialog, useValue: dialog },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
      ],
    });

    fixture = TestBed.createComponent(SeniorityLevelList);
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

  function headers(): string[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('th.mat-mdc-header-cell'),
    ).map((th) => th.textContent?.trim() ?? '');
  }

  function actionButton(label: string): HTMLButtonElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[aria-label="${label}"]`);
  }

  it('should create', () => {
    setup();
    expect(component).toBeTruthy();
  });

  it('should read the whole catalog on load, with no company in the request', async () => {
    setup();
    await settle();

    expect(seniorityLevelService.getSeniorityLevels).toHaveBeenCalledWith(false);
    expect(headers()).toEqual(['Rango', 'Código', 'Nombre', 'Estado', 'Acciones']);
    expect(text()).toContain('JUN');
    expect(text()).toContain('Senior');
  });

  it('should render no company context, because the catalog is global', async () => {
    setup();
    await settle();

    // D1: seniority levels are global reference data; a company selector here
    // would suggest a scoping the API does not have.
    expect(text()).not.toContain('Empresa');
    expect(text()).not.toContain('Seleccioná una empresa');
  });

  it('should link back to the positions screen it is reached from', async () => {
    setup();
    await settle();

    const back = (fixture.nativeElement as HTMLElement).querySelector('a[href="/hr/positions"]');
    expect(back).not.toBeNull();
  });

  it('should forward the disabled toggle to the request', async () => {
    setup();
    await settle();

    component.onIncludeDisabledChange(true);
    await settle();

    expect(seniorityLevelService.getSeniorityLevels).toHaveBeenLastCalledWith(true);
  });

  it('should narrow the rendered rows without re-issuing the request', async () => {
    setup();
    await settle();
    const callsAfterLoad = seniorityLevelService.getSeniorityLevels.mock.calls.length;

    component.onSearchChange('jun');
    await settle();

    expect(component.filteredLevels().map((l) => l.id)).toEqual(['lvl-1']);
    expect(seniorityLevelService.getSeniorityLevels.mock.calls.length).toBe(callsAfterLoad);
  });

  it('should show the write actions for the seniority-level write role', async () => {
    setup({ roles: WRITE_ROLES });
    await settle();

    expect(component.canWrite).toBe(true);
    expect(actionButton('Editar')).not.toBeNull();
  });

  it('should hide every write action for a caller outside the write set', async () => {
    setup({ roles: READ_ONLY_ROLES });
    await settle();

    expect(component.canWrite).toBe(false);
    expect(actionButton('Editar')).toBeNull();
    expect(actionButton('Deshabilitar')).toBeNull();
  });

  it('should not widen the write gate to another HR write role', async () => {
    setup({ roles: OTHER_HR_WRITE_ROLES });
    await settle();

    expect(component.canWrite).toBe(false);
    expect(actionButton('Editar')).toBeNull();
  });

  it('should navigate to the create screen', async () => {
    setup();
    await settle();

    component.onCreate();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/seniority-levels/create']);
  });

  it('should navigate to the edit screen of the row', async () => {
    setup();
    await settle();

    component.onEdit(level({ id: 'lvl-7' }));

    expect(router.navigate).toHaveBeenCalledWith(['/hr/seniority-levels/edit', 'lvl-7']);
  });

  it('should disable the confirmed row and reload the list', async () => {
    setup({ dialogConfirmed: true });
    await settle();
    const callsBefore = seniorityLevelService.getSeniorityLevels.mock.calls.length;

    component.onDisable(level());
    await settle();

    expect(dialog.open).toHaveBeenCalledWith(ConfirmDialog, expect.anything());
    expect(seniorityLevelService.removeSeniorityLevel).toHaveBeenCalledWith('lvl-1');
    expect(notifications.showSuccess).toHaveBeenCalledWith(
      'Nivel de antigüedad deshabilitado correctamente.',
    );
    expect(seniorityLevelService.getSeniorityLevels.mock.calls.length).toBeGreaterThan(callsBefore);
  });

  it('should leave the row alone when the confirmation is dismissed', async () => {
    setup({ dialogConfirmed: false });
    await settle();

    component.onDisable(level());
    await settle();

    expect(seniorityLevelService.removeSeniorityLevel).not.toHaveBeenCalled();
  });

  it('should re-enable a disabled row', async () => {
    setup();
    await settle();

    component.onEnable(level({ id: 'lvl-2', enabled: false }));
    await settle();

    expect(seniorityLevelService.enableSeniorityLevel).toHaveBeenCalledWith('lvl-2');
    expect(notifications.showSuccess).toHaveBeenCalledWith(
      'Nivel de antigüedad reactivado correctamente.',
    );
  });

  it('should surface a failed disable instead of swallowing it', async () => {
    setup({ dialogConfirmed: true });
    await settle();
    seniorityLevelService.removeSeniorityLevel.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 409 })),
    );

    component.onDisable(level());
    await settle();

    expect(component.actionError()).toBeTruthy();
  });

  it('should show the load failure and re-issue the request on retry', async () => {
    setup({ loadError: true });
    await settle();

    expect(text()).toContain('Error al cargar los niveles de antigüedad');
    const callsBefore = seniorityLevelService.getSeniorityLevels.mock.calls.length;

    const retry = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.trim() === 'Reintentar');
    retry?.click();
    await settle();

    expect(seniorityLevelService.getSeniorityLevels.mock.calls.length).toBeGreaterThan(callsBefore);
  });
});
