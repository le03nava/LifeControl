import type { Type } from '@angular/core';
import type { Route } from '@angular/router';
import { purchasesRoutes } from './purchases.routes';
import { unsavedChangesGuard } from './purchase-orders/guards/unsaved-changes.guard';

/**
 * The receipts entries live in the children array of the single `purchases`
 * parent route. `receipts` must be declared before `receipts/:id`, otherwise
 * the parameterised entry would capture `create` as an id.
 */
function receiptsChildren(): Route[] {
  return (purchasesRoutes[0].children ?? []).filter((route) => route.path?.startsWith('receipts'));
}

type LoadedComponent = Type<unknown> | { default: Type<unknown> };

async function loadComponentName(route: Route): Promise<string> {
  // The lazy loaders in this routes file are promises, but the `Route` type
  // also admits an observable.
  const loaded = (await route.loadComponent!()) as unknown as LoadedComponent;
  const component = 'default' in loaded ? loaded.default : loaded;
  return component.name;
}

describe('purchasesRoutes', () => {
  it('should declare the three receipts routes in an order that protects the literal path', () => {
    expect(receiptsChildren().map((route) => route.path)).toEqual([
      'receipts',
      'receipts/create',
      'receipts/:id',
    ]);
  });

  it('should lazy-load the receipt list, create and detail pages', async () => {
    const [list, create, detail] = receiptsChildren();

    // The production build prefixes class names (`_ReceiptList`), so match the
    // exported name instead of the exact identifier.
    const names = [
      await loadComponentName(list),
      await loadComponentName(create),
      await loadComponentName(detail),
    ];

    expect(names[0]).toMatch(/ReceiptList$/);
    expect(names[1]).toMatch(/ReceiptCreate$/);
    expect(names[2]).toMatch(/ReceiptDetail$/);
    expect(new Set(names).size).toBe(3);
  });

  it('should guard only the create route with the unsaved-changes guard', () => {
    const [list, create, detail] = receiptsChildren();

    expect(create.canDeactivate).toEqual([unsavedChangesGuard]);
    expect(list.canDeactivate).toBeUndefined();
    expect(detail.canDeactivate).toBeUndefined();
  });

  it('should keep the receipts security surface on the parent route only', () => {
    for (const route of receiptsChildren()) {
      expect(route.canActivate).toBeUndefined();
      expect(route.data).toBeUndefined();
    }

    expect(purchasesRoutes[0].data).toEqual({
      roles: ['lc-admin'],
      clientId: 'life-control-client',
    });
  });
});
