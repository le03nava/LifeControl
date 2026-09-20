import type { Page } from '@playwright/test';
import { app, expect } from '../fixtures/app';

/**
 * Reaches the receipts list the way an operator does: through the menu and the
 * dashboard card.
 *
 * Two deliberate choices keep this deterministic:
 *
 * 1. It never deep-links into a guarded route with `page.goto`. Every fresh page
 *    load re-runs the silent SSO, and `keycloakRoleGuard` calls
 *    `keycloak.login()` while `keycloak.authenticated` is still false — a
 *    top-level navigation to the authorization endpoint.
 * 2. Landing on the unguarded Home first and then navigating client-side.
 *    Authentication itself is settled by `ensureAuthenticated`, so this spec does
 *    not race the silent-SSO poll.
 */
async function ensureAuthenticated(page: Page): Promise<void> {
  await page.goto('/');

  const purchasesLink = page.getByRole('link', { name: 'Compras' });
  const loginButton = page.getByRole('button', { name: 'Login' });

  // The app renders a Login button until the session is known. Prefer the silent
  // SSO, but never depend on its timing: the same mocked Keycloak serves an
  // explicit login round trip, which is what a real operator without a live SSO
  // session does anyway.
  await expect(loginButton.or(purchasesLink)).toBeVisible({ timeout: 20_000 });
  if (!(await purchasesLink.isVisible())) {
    await loginButton.click();
  }

  await expect(purchasesLink).toBeVisible({ timeout: 20_000 });
}

async function openReceipts(page: Page): Promise<void> {
  await ensureAuthenticated(page);

  await page.getByRole('link', { name: 'Compras' }).click();
  await expect(page.getByRole('heading', { name: 'Purchases Administration' })).toBeVisible();

  // The action label only renders on an enabled card, so its presence is the
  // assertion that the Receipts card is enabled.
  await page.getByText('Manage Receipts').click();
  await expect(page).toHaveURL(/\/purchases\/receipts$/);
}

async function openOrderPicker(page: Page): Promise<void> {
  await openReceipts(page);
  await page.getByRole('button', { name: 'Nuevo recibo' }).click();

  const pickerHeading = page.getByRole('heading', { name: 'Nuevo recibo' });
  await expect(pickerHeading).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: 'OC-2026-001' })).toBeVisible();
}

app.describe('Purchases — goods receipts', () => {
  app.use({ clientRoles: ['lc-admin'] });

  app('registers a receipt end to end and lists it afterwards', async ({ page }) => {
    await openReceipts(page);
    await expect(page.getByRole('heading', { name: 'No hay recibos registrados' })).toBeVisible();

    await page.getByRole('button', { name: 'Nuevo recibo' }).click();

    const orderRow = (orderNumber: string) =>
      page.getByRole('row').filter({ hasText: orderNumber });
    await expect(orderRow('OC-2026-001')).toBeVisible();
    await expect(orderRow('OC-2026-002')).toBeVisible();

    await orderRow('OC-2026-001').getByRole('button', { name: 'Recibir' }).click();

    // Only the receivable line is offered; the already-received one is dropped.
    const lineRow = page.getByRole('row').filter({ hasText: 'Café Molido 1kg' });
    await expect(lineRow).toBeVisible();
    await expect(lineRow.getByRole('cell').nth(4)).toHaveText('10');
    await expect(page.getByLabel('Cantidad a recibir de Café Molido 1kg')).toHaveValue('10');
    await expect(page.getByText('Azúcar Refinada 1kg')).toHaveCount(0);

    const postReceived = page.waitForRequest(
      (request) => request.method() === 'POST' && request.url().endsWith('/api/goods-receipts'),
    );
    await page.getByRole('button', { name: 'Registrar recepción' }).click();

    expect((await postReceived).postDataJSON()).toEqual({
      purchaseOrderId: 'po-1',
      receivingLocationId: null,
      comments: null,
      lines: [{ purchaseOrderDetailId: 'po-detail-1', quantityReceived: 10 }],
    });

    await expect(page).toHaveURL(/\/purchases\/receipts\/e2e-receipt-\d+/);
    await expect(page.getByRole('heading', { name: 'Recibo GR-OC-2026-001-01' })).toBeVisible();
    await expect(page.getByText('Café Molido 1kg')).toBeVisible();
    await expect(page.getByText('Registrado')).toBeVisible();

    await page.getByRole('button', { name: 'Volver', exact: true }).click();
    await expect(page).toHaveURL(/\/purchases\/receipts$/);
    await expect(page.getByRole('cell', { name: 'GR-OC-2026-001-01' })).toBeVisible();
  });

  app('refuses to receive a non-receivable order', async ({ page }) => {
    await openOrderPicker(page);

    const orderRow = (orderNumber: string) =>
      page.getByRole('row').filter({ hasText: orderNumber });
    await expect(orderRow('OC-2026-001').getByRole('button', { name: 'Recibir' })).toBeEnabled();
    await expect(orderRow('OC-2026-002').getByRole('button', { name: 'Recibir' })).toBeDisabled();
  });
});

app.describe('Purchases — receipts access for a receiving-only user', () => {
  app.use({ clientRoles: ['lc-receiving'] });

  app('opens the receipts area but is denied the orders area', async ({ page }) => {
    // Reach receipts through the deterministic menu/card path (see the file
    // header) instead of a deep-link, so the mocked silent-SSO race cannot
    // decide this assertion.
    await openReceipts(page);
    await expect(page.getByRole('heading', { name: 'Recibos' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Access Denied' })).toHaveCount(0);

    // The orders children re-declare their own admin-only guard, so the same
    // receiving-only user is bounced to /unauthorized.
    await page.goto('/purchases/orders');
    await expect(page.getByRole('heading', { name: 'Access Denied' })).toBeVisible();
  });
});
