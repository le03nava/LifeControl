/// <reference types="vitest/globals" />
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom, of } from 'rxjs';
import type { ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';
import { unsavedChangesGuard, type UnsavedChangesAware } from './unsaved-changes.guard';

describe('unsavedChangesGuard', () => {
  let dialog: { open: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    dialog = { open: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: MatDialog, useValue: dialog }],
    });
  });

  function run(component: unknown) {
    return TestBed.runInInjectionContext(() =>
      unsavedChangesGuard(
        component as UnsavedChangesAware,
        {} as ActivatedRouteSnapshot,
        {} as RouterStateSnapshot,
        {} as RouterStateSnapshot,
      ),
    );
  }

  it('allows navigation immediately while the form is clean', () => {
    const result = run({ hasUnsavedChanges: () => false });

    expect(result).toBe(true);
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('opens the confirm dialog while the form is dirty', () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });

    run({ hasUnsavedChanges: () => true });

    expect(dialog.open).toHaveBeenCalledTimes(1);
    expect(dialog.open).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        data: expect.objectContaining({ title: 'Cambios sin guardar', destructive: true }),
      }),
    );
  });

  it('allows navigation when the user confirms discarding the changes', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });

    const result = run({ hasUnsavedChanges: () => true });

    expect(await firstValueFrom(result as never)).toBe(true);
  });

  it('blocks navigation when the user keeps editing', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });

    const result = run({ hasUnsavedChanges: () => true });

    expect(await firstValueFrom(result as never)).toBe(false);
  });

  it('allows navigation when the component does not report unsaved changes at all', () => {
    expect(run({})).toBe(true);
    expect(dialog.open).not.toHaveBeenCalled();
  });
});
