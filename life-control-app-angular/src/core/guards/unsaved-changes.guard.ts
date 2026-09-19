import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { map } from 'rxjs';
import { ConfirmDialog } from '@shared/ui';

/** Contract for components that can report pending unsaved changes. */
export interface UnsavedChangesAware {
  hasUnsavedChanges(): boolean;
}

/**
 * Blocks navigation away from a route when the component reports unsaved
 * changes and asks the user to confirm discarding them.
 *
 * Wired from the route definition (`canDeactivate: [unsavedChangesGuard]`), so it
 * also covers programmatic navigation: a page that saves successfully must mark its
 * form pristine before navigating away.
 *
 * @remarks
 * `features/purchases/purchase-orders/guards/unsaved-changes.guard.ts` holds an
 * equivalent implementation, reached only from `purchases.routes.ts`. Consolidating
 * both into this shared guard is a follow-up: it touches a feature outside this
 * slice's authorized edit surfaces.
 */
export const unsavedChangesGuard: CanDeactivateFn<UnsavedChangesAware> = (component) => {
  if (!component?.hasUnsavedChanges?.()) {
    return true;
  }

  return inject(MatDialog)
    .open(ConfirmDialog, {
      data: {
        title: 'Cambios sin guardar',
        message: 'Tenés cambios sin guardar. Si salís ahora, se van a perder.',
        confirmLabel: 'Salir sin guardar',
        cancelLabel: 'Seguir editando',
        destructive: true,
      },
      autoFocus: false,
      restoreFocus: false,
    })
    .afterClosed()
    .pipe(map((confirmed) => confirmed === true));
};
