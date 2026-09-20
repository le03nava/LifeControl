import { app, expect } from '../fixtures/app';

app.describe('Purchases — goods receipts', () => {
  app.use({ clientRoles: ['lc-admin'] });

  app('registers a receipt end to end and lists it afterwards', async ({ page }) => {
    await page.goto('/purchases/receipts');
    await expect(page.getByRole('heading', { name: 'Recibos', exact: true })).toBeVisible();
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
    await page.goto('/purchases/receipts/create');

    const orderRow = (orderNumber: string) =>
      page.getByRole('row').filter({ hasText: orderNumber });
    await expect(orderRow('OC-2026-001').getByRole('button', { name: 'Recibir' })).toBeEnabled();
    await expect(orderRow('OC-2026-002').getByRole('button', { name: 'Recibir' })).toBeDisabled();
  });
});
