/// <reference types="vitest/globals" />
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import Keycloak from 'keycloak-js';
import { NotificationService } from '@shared/data/notification';
import { PositionSettings } from './position-settings';
import { PositionService } from '../../data/position.service';
import { SeniorityLevelService } from '../../data/seniority-level.service';
import {
  Position,
  PositionRole,
  PositionRolesRequest,
  PositionSalaryBand,
  PositionSalaryBandsRequest,
} from '../../models/position.models';
import { SeniorityLevel } from '../../models/seniority-level.models';

describe('PositionSettings', () => {
  let fixture: ComponentFixture<PositionSettings>;
  let component: PositionSettings;
  let positionService: {
    getPosition: ReturnType<typeof vi.fn>;
    getSalaryBands: ReturnType<typeof vi.fn>;
    saveSalaryBands: ReturnType<typeof vi.fn>;
    getPositionRoles: ReturnType<typeof vi.fn>;
    getGrantablePositionRoles: ReturnType<typeof vi.fn>;
    savePositionRoles: ReturnType<typeof vi.fn>;
  };
  let seniorityLevelService: { getSeniorityLevels: ReturnType<typeof vi.fn> };
  let notifications: { showSuccess: ReturnType<typeof vi.fn> };
  let router: Router;

  const WRITE_ROLES = ['lc-admin'];

  const level = (overrides: Partial<SeniorityLevel> = {}): SeniorityLevel => ({
    id: 'lvl-1',
    levelCode: 'JR',
    levelName: 'Junior',
    rank: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  const band = (overrides: Partial<PositionSalaryBand> = {}): PositionSalaryBand => ({
    id: 'band-1',
    positionId: 'pos-1',
    seniorityLevelId: 'lvl-1',
    minimumSalary: 1000,
    maximumSalary: 1500,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  const position = (overrides: Partial<Position> = {}): Position => ({
    id: 'pos-1',
    companyId: 'company-1',
    departmentId: 'dep-1',
    positionCode: 'OPR',
    positionName: 'Operario',
    description: null,
    reportsToPositionId: null,
    displayOrder: 1,
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  /**
   * The allowlist the API answers with: deliberately wider than what is stored, so
   * a test can tell "not granted" from "not grantable".
   */
  const GRANTABLE = ['lc-company', 'lc-sales', 'lc-scheduling'];

  const role = (overrides: Partial<PositionRole> = {}): PositionRole => ({
    id: 'role-1',
    positionId: 'pos-1',
    roleName: 'lc-sales',
    enabled: true,
    createdAt: '2026-01-01T00:00:00',
    updatedAt: '2026-01-01T00:00:00',
    ...overrides,
  });

  /** The catalog of the three levels every test starts from, already rank-ordered. */
  const catalog = (): SeniorityLevel[] => [
    level(),
    level({ id: 'lvl-2', levelCode: 'SSR', levelName: 'Semi senior', rank: 2 }),
    level({ id: 'lvl-3', levelCode: 'SR', levelName: 'Senior', rank: 3 }),
  ];

  interface SetupOptions {
    roles?: string[];
    queryCompanyId?: string | null;
    settingsId?: string | null;
    bands?: PositionSalaryBand[];
    bandsError?: boolean;
    levels?: SeniorityLevel[];
    levelsError?: boolean;
    positionError?: boolean;
    savedBands?: PositionSalaryBand[];
    saveError?: HttpErrorResponse;
    storedRoles?: PositionRole[];
    grantable?: string[];
    rolesError?: boolean;
    grantableError?: boolean;
    savedRoles?: PositionRole[];
    roleSaveError?: HttpErrorResponse;
  }

  function setup(options: SetupOptions = {}): void {
    const roles = options.roles ?? WRITE_ROLES;

    positionService = {
      getPosition: vi.fn(() => {
        if (options.positionError) {
          return throwError(() => new HttpErrorResponse({ status: 404 }));
        }
        return of(position());
      }),
      getSalaryBands: vi.fn(() => {
        if (options.bandsError) {
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return of(options.bands ?? [band()]);
      }),
      saveSalaryBands: vi.fn(() => {
        if (options.saveError) {
          return throwError(() => options.saveError);
        }
        return of(options.savedBands ?? [band()]);
      }),
      getPositionRoles: vi.fn(() => {
        if (options.rolesError) {
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return of(options.storedRoles ?? [role()]);
      }),
      getGrantablePositionRoles: vi.fn(() => {
        if (options.grantableError) {
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return of({ roles: options.grantable ?? GRANTABLE });
      }),
      savePositionRoles: vi.fn(() => {
        if (options.roleSaveError) {
          return throwError(() => options.roleSaveError);
        }
        return of(options.savedRoles ?? [role()]);
      }),
    };

    seniorityLevelService = {
      getSeniorityLevels: vi.fn(() => {
        if (options.levelsError) {
          return throwError(() => new HttpErrorResponse({ status: 500 }));
        }
        return of(options.levels ?? catalog());
      }),
    };

    notifications = { showSuccess: vi.fn() };

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [PositionSettings],
      providers: [
        provideRouter([]),
        { provide: PositionService, useValue: positionService },
        { provide: SeniorityLevelService, useValue: seniorityLevelService },
        { provide: NotificationService, useValue: notifications },
        {
          provide: Keycloak,
          useValue: { tokenParsed: { resource_access: { 'life-control-client': { roles } } } },
        },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(
                options.settingsId === null ? {} : { id: options.settingsId ?? 'pos-1' },
              ),
              queryParamMap: convertToParamMap(
                options.queryCompanyId === null
                  ? {}
                  : { companyId: options.queryCompanyId ?? 'company-1' },
              ),
            },
          },
        },
      ],
    });

    fixture = TestBed.createComponent(PositionSettings);
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

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function text(): string {
    return host().textContent ?? '';
  }

  function tableRows(): HTMLElement[] {
    return Array.from(host().querySelectorAll('tbody tr'));
  }

  function amountInputs(control: 'minimumSalary' | 'maximumSalary'): HTMLInputElement[] {
    return Array.from(
      host().querySelectorAll<HTMLInputElement>(`input[formcontrolname="${control}"]`),
    );
  }

  function setAmount(
    index: number,
    control: 'minimumSalary' | 'maximumSalary',
    value: string,
  ): void {
    const input = amountInputs(control)[index];
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function saveButton(): HTMLButtonElement | null {
    return (
      (Array.from(host().querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Guardar',
      ) as HTMLButtonElement | undefined) ?? null
    );
  }

  function lastSaveRequest(): PositionSalaryBandsRequest {
    return positionService.saveSalaryBands.mock.calls.at(-1)?.[2] as PositionSalaryBandsRequest;
  }

  function lastRolesRequest(): PositionRolesRequest {
    return positionService.savePositionRoles.mock.calls.at(-1)?.[2] as PositionRolesRequest;
  }

  function roleCheckboxes(): HTMLInputElement[] {
    return Array.from(
      host().querySelectorAll<HTMLInputElement>('mat-checkbox input[type="checkbox"]'),
    );
  }

  function rolesSaveButton(): HTMLButtonElement | null {
    return (
      (Array.from(host().querySelectorAll('button')).find(
        (button) => button.textContent?.trim() === 'Guardar roles',
      ) as HTMLButtonElement | undefined) ?? null
    );
  }

  it('should read the position, its bands and the global catalog with disabled levels included', async () => {
    setup();
    await settle();

    expect(positionService.getPosition).toHaveBeenCalledWith('company-1', 'pos-1');
    expect(positionService.getSalaryBands).toHaveBeenCalledWith('company-1', 'pos-1');
    // A disabled global level may still hold a stored band, so the catalog read
    // must include disabled levels: hiding one would drop its row and the next
    // save would disable it silently.
    expect(seniorityLevelService.getSeniorityLevels).toHaveBeenCalledWith(true);
  });

  it('should render one row per global level, in rank order', async () => {
    setup();
    await settle();

    expect(tableRows()).toHaveLength(3);
    expect(text()).toContain('JR — Junior');
    expect(text()).toContain('SSR — Semi senior');
    expect(text()).toContain('SR — Senior');
  });

  it('should prefill an enabled stored band with its amounts', async () => {
    setup({
      bands: [band({ seniorityLevelId: 'lvl-2', minimumSalary: 2000, maximumSalary: 2500 })],
    });
    await settle();

    expect(amountInputs('minimumSalary')[1].value).toBe('2000');
    expect(amountInputs('maximumSalary')[1].value).toBe('2500');
    expect(text()).not.toContain('Sin tabulador');
  });

  it('should not prefill a disabled stored band, and should offer its amounts as placeholders', async () => {
    setup({
      bands: [
        band({
          seniorityLevelId: 'lvl-2',
          minimumSalary: 2000,
          maximumSalary: 2500,
          enabled: false,
        }),
      ],
    });
    await settle();

    // Prefilling it would resurrect on the next save exactly the level a previous
    // save omitted (D13), so the values are shown as placeholders and never as
    // values.
    expect(amountInputs('minimumSalary')[1].value).toBe('');
    expect(amountInputs('maximumSalary')[1].value).toBe('');
    expect(amountInputs('minimumSalary')[1].getAttribute('placeholder')).toBe('2000');
    expect(text()).toContain('Sin tabulador');
  });

  it('should send the full set of the configured rows and omit the levels left without a band', async () => {
    setup({
      bands: [band()],
    });
    await settle();

    setAmount(2, 'minimumSalary', '3000');
    setAmount(2, 'maximumSalary', '3500');
    saveButton()!.click();
    await settle();

    expect(positionService.saveSalaryBands).toHaveBeenCalledTimes(1);
    expect(positionService.saveSalaryBands.mock.calls[0][0]).toBe('company-1');
    expect(positionService.saveSalaryBands.mock.calls[0][1]).toBe('pos-1');
    // lvl-2 and lvl-3 have no amounts, so they are omitted — which is how the API
    // is told to disable them — and no `enabled` field travels on the wire.
    expect(lastSaveRequest()).toEqual({
      bands: [
        { seniorityLevelId: 'lvl-1', minimumSalary: 1000, maximumSalary: 1500 },
        { seniorityLevelId: 'lvl-3', minimumSalary: 3000, maximumSalary: 3500 },
      ],
    });
  });

  it('should omit an untouched disabled level, so a plain save cannot resurrect it', async () => {
    setup({
      bands: [
        band(),
        band({
          id: 'band-2',
          seniorityLevelId: 'lvl-2',
          minimumSalary: 2000,
          maximumSalary: 2500,
          enabled: false,
        }),
      ],
    });
    await settle();

    saveButton()!.click();
    await settle();

    expect(lastSaveRequest()).toEqual({
      bands: [{ seniorityLevelId: 'lvl-1', minimumSalary: 1000, maximumSalary: 1500 }],
    });
  });

  it('should refuse a row with only one amount instead of dropping it silently', async () => {
    setup();
    await settle();

    setAmount(2, 'minimumSalary', '3000');
    saveButton()!.click();
    await settle();

    expect(positionService.saveSalaryBands).not.toHaveBeenCalled();
    expect(text()).toContain('Completá el mínimo y el máximo');
  });

  it('should refuse a maximum below the minimum', async () => {
    setup();
    await settle();

    setAmount(2, 'minimumSalary', '4000');
    setAmount(2, 'maximumSalary', '3000');
    saveButton()!.click();
    await settle();

    expect(positionService.saveSalaryBands).not.toHaveBeenCalled();
    expect(text()).toContain('El máximo no puede ser menor que el mínimo');
  });

  it('should refuse a negative amount', async () => {
    setup();
    await settle();

    setAmount(2, 'minimumSalary', '-1');
    setAmount(2, 'maximumSalary', '3000');
    saveButton()!.click();
    await settle();

    expect(positionService.saveSalaryBands).not.toHaveBeenCalled();
    expect(text()).toContain('no pueden ser negativos');
  });

  it('should name a failed band read instead of rendering an empty grid', async () => {
    setup({ bandsError: true });
    await settle();

    // An empty grid would read as "this position has no bands configured", which
    // is a different and false statement; the section keeps its heading so the
    // banner is attributable, and renders no editable table at all.
    expect(tableRows()).toHaveLength(0);
    expect(text()).not.toContain('no tiene niveles de antigüedad');
    expect(component.loadError()).not.toBeNull();
    expect(text()).toContain(component.loadError()!);
  });

  it('should name a stored band whose level is missing from the catalog', async () => {
    setup({ bands: [band({ seniorityLevelId: 'lvl-9' })] });
    await settle();

    // Saving the grid without that row would disable it without anyone asking,
    // so the screen refuses to render an editable set at all.
    expect(tableRows()).toHaveLength(0);
    expect(text()).toContain('completa');
    expect(positionService.saveSalaryBands).not.toHaveBeenCalled();
  });

  it('should seed again from the response of the save, which is the authority on what stayed enabled', async () => {
    setup({
      bands: [band()],
      savedBands: [
        band({ enabled: false }),
        band({ id: 'band-3', seniorityLevelId: 'lvl-3', minimumSalary: 3000, maximumSalary: 3500 }),
      ],
    });
    await settle();

    setAmount(2, 'minimumSalary', '3000');
    setAmount(2, 'maximumSalary', '3500');
    saveButton()!.click();
    await settle();

    // The response says lvl-1 is now disabled and lvl-3 is stored, and the grid
    // follows the response rather than the request it sent.
    expect(amountInputs('minimumSalary')[0].value).toBe('');
    expect(amountInputs('minimumSalary')[0].getAttribute('placeholder')).toBe('1000');
    expect(amountInputs('minimumSalary')[2].value).toBe('3000');
    expect(notifications.showSuccess).toHaveBeenCalledTimes(1);
  });

  it('should surface a failed save instead of leaving the operator believing it worked', async () => {
    setup({ saveError: new HttpErrorResponse({ status: 409 }) });
    await settle();

    saveButton()!.click();
    await settle();

    expect(component.submitError()).not.toBeNull();
    expect(text()).toContain(component.submitError()!);
    expect(notifications.showSuccess).not.toHaveBeenCalled();
  });

  it('should render read-only without the position write role, keeping the values visible', async () => {
    setup({ roles: ['lc-sales'] });
    await settle();

    // The write gate hides the action and never the information: the amounts the
    // API returned stay readable.
    expect(saveButton()).toBeNull();
    expect(tableRows()).toHaveLength(3);
    expect(amountInputs('minimumSalary')[0].value).toBe('1000');
    expect(amountInputs('minimumSalary')[0].readOnly).toBe(true);
  });

  it('should name the missing company instead of issuing a request', async () => {
    setup({ queryCompanyId: null });
    await settle();

    expect(positionService.getPosition).not.toHaveBeenCalled();
    expect(positionService.getSalaryBands).not.toHaveBeenCalled();
    expect(text()).toContain('empresa');
  });

  it('should navigate back to the list carrying the company', async () => {
    setup();
    await settle();

    const backButton = Array.from(host().querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Volver',
    ) as HTMLButtonElement;
    backButton.click();

    expect(router.navigate).toHaveBeenCalledWith(['/hr/positions'], {
      queryParams: { companyId: 'company-1' },
    });
  });

  describe('the roles section', () => {
    it('should read the stored template and the allowlist, and check exactly the granted roles', async () => {
      setup();
      await settle();

      expect(positionService.getPositionRoles).toHaveBeenCalledWith('company-1', 'pos-1');
      expect(positionService.getGrantablePositionRoles).toHaveBeenCalledWith('company-1', 'pos-1');

      // One checkbox per grantable name — never per stored row, which would hide a
      // role that can be granted but is not configured yet (D19).
      const boxes = roleCheckboxes();
      expect(boxes).toHaveLength(3);
      expect(text()).toContain('lc-company');
      expect(text()).toContain('lc-sales');
      expect(text()).toContain('lc-scheduling');
      expect(boxes[0].checked).toBe(false);
      expect(boxes[1].checked).toBe(true);
      expect(boxes[2].checked).toBe(false);
    });

    it('should leave a stored disabled role unchecked', async () => {
      setup({ storedRoles: [role({ enabled: false })] });
      await settle();

      // A disabled stored row is part of the read (T20): it must show as unchecked
      // rather than as a role that was never configured.
      expect(roleCheckboxes()[1].checked).toBe(false);
      expect(text()).toContain('lc-sales');
    });

    it('should save exactly the checked roles and omit the rest', async () => {
      setup();
      await settle();

      roleCheckboxes()[0].click();
      fixture.detectChanges();
      rolesSaveButton()!.click();
      await settle();

      expect(positionService.savePositionRoles).toHaveBeenCalledTimes(1);
      expect(positionService.savePositionRoles.mock.calls[0][0]).toBe('company-1');
      expect(positionService.savePositionRoles.mock.calls[0][1]).toBe('pos-1');
      // An unchecked name is omitted, which is how the API is told to disable it —
      // and no `enabled` field travels, because the wire has none (T20).
      expect(lastRolesRequest()).toEqual({
        roles: [{ roleName: 'lc-company' }, { roleName: 'lc-sales' }],
      });
    });

    it('should re-check the boxes from the response of the save', async () => {
      setup({
        savedRoles: [role({ enabled: false }), role({ id: 'role-2', roleName: 'lc-scheduling' })],
      });
      await settle();

      rolesSaveButton()!.click();
      await settle();

      const boxes = roleCheckboxes();
      expect(boxes[1].checked).toBe(false);
      expect(boxes[2].checked).toBe(true);
      expect(notifications.showSuccess).toHaveBeenCalledTimes(1);
    });

    it('should name a failed allowlist read instead of rendering an empty list', async () => {
      setup({ grantableError: true });
      await settle();

      expect(roleCheckboxes()).toHaveLength(0);
      expect(component.rolesError()).not.toBeNull();
      expect(text()).toContain(component.rolesError()!);
    });

    it('should name a stored role the allowlist no longer carries, instead of clearing it silently', async () => {
      setup({ storedRoles: [role({ roleName: 'lc-country', enabled: true })] });
      await settle();

      // Saving without that name would disable it through the omission rule with
      // nobody asking, so the section refuses to render an editable list.
      expect(roleCheckboxes()).toHaveLength(0);
      expect(component.rolesError()).not.toBeNull();
      expect(positionService.savePositionRoles).not.toHaveBeenCalled();
    });

    it('should surface a failed roles save', async () => {
      setup({ roleSaveError: new HttpErrorResponse({ status: 409 }) });
      await settle();

      rolesSaveButton()!.click();
      await settle();

      expect(component.rolesError()).not.toBeNull();
      expect(text()).toContain(component.rolesError()!);
      expect(notifications.showSuccess).not.toHaveBeenCalled();
    });

    it('should render the roles section read-only without the write role', async () => {
      setup({ roles: ['lc-sales'] });
      await settle();

      expect(rolesSaveButton()).toBeNull();
      expect(roleCheckboxes()).toHaveLength(3);
      expect(roleCheckboxes()[1].checked).toBe(true);
      expect(roleCheckboxes()[1].disabled).toBe(true);
    });

    it('should keep the sections independent: saving the bands never saves the roles', async () => {
      setup();
      await settle();

      saveButton()!.click();
      await settle();

      expect(positionService.saveSalaryBands).toHaveBeenCalledTimes(1);
      expect(positionService.savePositionRoles).not.toHaveBeenCalled();
    });

    it('should still render the roles section when the band read failed', async () => {
      setup({ bandsError: true });
      await settle();

      expect(component.loadError()).not.toBeNull();
      expect(roleCheckboxes()).toHaveLength(3);
    });
  });
});
