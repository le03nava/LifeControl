import type { Page } from '@playwright/test';
import { app, expect } from '../fixtures/app';

/**
 * The purchase-order screen is a two-step surface: the order first, its lines after.
 *
 * Every fresh page load re-runs the silent SSO and `keycloakRoleGuard` calls `keycloak.login()` while
 * `keycloak.authenticated` is still false, so authentication is settled once on the unguarded Home and the rest of the
 * journey happens client-side, the way an operator does it (the idiom `purchases-receipts.spec.ts` documents).
 */
async function ensureAuthenticated(page: Page): Promise<void> {
  await page.goto('/');

  const purchasesLink = page.getByRole('link', { name: 'Compras' });
  const loginButton = page.getByRole('button', { name: 'Login' });

  await expect(loginButton.or(purchasesLink)).toBeVisible({ timeout: 20_000 });
  if (!(await purchasesLink.isVisible())) {
    await loginButton.click();
  }

  await expect(purchasesLink).toBeVisible({ timeout: 20_000 });
}

async function openOrderList(page: Page): Promise<void> {
  await ensureAuthenticated(page);

  await page.getByRole('link', { name: 'Compras' }).click();
  await expect(page.getByRole('heading', { name: 'Purchases Administration' })).toBeVisible();
  // The card's action label is the only element that navigates; `getByText` would also match the page subtitle and
  // the card description, which share the same words (strict mode).
  await page.locator('.card-action').filter({ hasText: 'Manage Purchase Orders' }).click();
  await expect(page).toHaveURL(/\/purchases\/orders$/);
}

app.describe('purchase-order two-step surface', () => {
  app('S1: the create screen renders the header alone, with no detail area', async ({ page }) => {
    await openOrderList(page);

    await page.getByRole('button', { name: 'Crear Orden' }).click();

    await expect(page).toHaveURL(/\/purchases\/orders\/create$/);
    // The header is there, so the screen rendered rather than failed.
    await expect(page.getByLabel('Proveedor')).toBeVisible({ timeout: 15_000 });
    // A line is addressed by the order's id on the detail sub-resource, so before the order exists there is no area
    // that could hold one.
    await expect(page.locator('app-detail-table')).toHaveCount(0);
  });

  app(
    'S7/S8: an order that already holds lines locks its header and says why',
    async ({ page }) => {
      await ensureAuthenticated(page);
      // `po-1` is the Accepted order the API mock serves with two lines.
      await page.goto('/purchases/orders/po-1');

      await expect(page.locator('app-detail-table')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByText('Café Molido 1kg')).toBeVisible();

      // The supplier cannot move once a line exists, and the reason is stated where the control is.
      await expect(page.getByLabel('Proveedor')).toBeDisabled();
      await expect(page.locator('.supplier-lock-reason')).toBeVisible();
    },
  );

  app(
    'S7/S10: an order with no lines leaves the header editable and offers the add row',
    async ({ page }) => {
      await ensureAuthenticated(page);
      // `po-2` is the Draft order the API mock serves with no lines.
      await page.goto('/purchases/orders/po-2');

      await expect(page.locator('app-detail-table')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByLabel('Proveedor')).toBeEnabled();
      await expect(page.locator('.supplier-lock-reason')).toHaveCount(0);
      await expect(page.getByText('Agregar Producto')).toBeVisible();
    },
  );
});
