import { app, expect } from '../fixtures/app';

app.describe('Authentication', () => {
  app('opens the app authenticated via silent SSO', async ({ page }) => {
    await page.goto('/');

    await expect(page.getByText('E2E User')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Login' })).toHaveCount(0);
  });

  app.describe('starting unauthenticated', () => {
    app.use({ startUnauthenticated: true, clientRoles: ['lc-admin'] });

    app('signs in through the Keycloak login flow', async ({ page }) => {
      await page.goto('/');
      await expect(page.getByRole('button', { name: 'Login' })).toBeVisible();

      await page.getByRole('button', { name: 'Login' }).click();

      await expect(page.getByText('E2E User')).toBeVisible();
      await expect(page.getByRole('link', { name: 'Life Control' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Companies' })).toBeVisible();
    });

    app('supports logout and login again', async ({ page }) => {
      await page.goto('/');
      await page.getByRole('button', { name: 'Login' }).click();
      await expect(page.getByText('E2E User')).toBeVisible();

      await page.getByText('E2E User').click();
      await page.getByRole('menuitem', { name: 'Logout' }).click();

      await expect(page.getByRole('button', { name: 'Login' })).toBeVisible();
    });
  });
});
