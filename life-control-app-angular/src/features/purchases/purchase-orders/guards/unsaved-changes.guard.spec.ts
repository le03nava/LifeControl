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

  function run(component: UnsavedChangesAware) {
    return TestBed.runInInjectionContext(() =>
      unsavedChangesGuard(
        component,
        {} as ActivatedRouteSnapshot,
        {} as RouterStateSnapshot,
        {} as RouterStateSnapshot,
      ),
    );
  }

  it('allows navigation immediately when there are no unsaved changes', () => {
    const result = run({ hasUnsavedChanges: () => false });
    expect(result).toBe(true);
    expect(dialog.open).not.toHaveBeenCalled();
  });

  it('opens the confirm dialog when there are unsaved changes', () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    run({ hasUnsavedChanges: () => true });
    expect(dialog.open).toHaveBeenCalledTimes(1);
  });

  it('allows navigation when the user confirms', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    const result = run({ hasUnsavedChanges: () => true });
    expect(await firstValueFrom(result as never)).toBe(true);
  });

  it('blocks navigation when the user cancels', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    const result = run({ hasUnsavedChanges: () => true });
    expect(await firstValueFrom(result as never)).toBe(false);
  });
});
