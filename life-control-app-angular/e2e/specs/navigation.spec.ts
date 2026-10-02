import { app, expect } from '../fixtures/app';

app.describe('Navigation and role-based menus', () => {
  app.describe('as an admin', () => {
    app.use({ clientRoles: ['lc-admin'] });

    app('shows the full admin menu', async ({ page }) => {
      await page.goto('/');

      await expect(page.getByRole('link', { name: 'Life Control' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Companies' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Products' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Compras' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Users Admin' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Calendario y citas' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Recursos Humanos' })).toBeVisible();
    });

    app(
      'reaches the departments screen from the Recursos Humanos entry (E53)',
      async ({ page }) => {
        await page.goto('/');

        const hrLink = page.getByRole('link', { name: 'Recursos Humanos' });
        await expect(hrLink).toBeVisible();

        await hrLink.click();

        await expect(page).toHaveURL(/\/hr\/departments$/);
        await expect(page.getByRole('heading', { name: 'Departamentos' })).toBeVisible();
      },
    );

    app('loads the companies dashboard and the companies list', async ({ page }) => {
      await page.goto('/companies');
      await expect(page.getByRole('heading', { name: 'Companies Administration' })).toBeVisible();

      await page.goto('/companies/list');
      await expect(page.getByRole('heading', { name: 'Empresas' })).toBeVisible();
      await expect(
        page.locator('mat-card-title').getByText('Acme Corp', { exact: true }),
      ).toBeVisible();
      await expect(
        page.locator('mat-card-title').getByText('Logística Norte', { exact: true }),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: 'Agregar Empresa' })).toBeVisible();
    });
  });

  app.describe('as a receiving operator', () => {
    app.use({ clientRoles: ['lc-receiving'] });

    app('shows only the Compras menu', async ({ page }) => {
      await page.goto('/');

      await expect(page.getByRole('link', { name: 'Compras' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Products' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Users Admin' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Calendario y citas' })).toHaveCount(0);
    });
  });

  app.describe('as a company manager', () => {
    app.use({ clientRoles: ['lc-company'] });

    app('shows only the Companies menu', async ({ page }) => {
      await page.goto('/');

      await expect(page.getByRole('link', { name: 'Companies' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Products' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Compras' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Users Admin' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Calendario y citas' })).toHaveCount(0);
    });

    app('is redirected to unauthorized on admin routes', async ({ page }) => {
      await page.goto('/users-admin');
      await expect(page.getByRole('heading', { name: 'Access Denied' })).toBeVisible();
    });
  });

  app.describe('without company roles', () => {
    app.use({ clientRoles: [] });

    app('shows no protected menu entries', async ({ page }) => {
      await page.goto('/');

      await expect(page.getByRole('link', { name: 'Life Control' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Companies' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Products' })).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Users Admin' })).toHaveCount(0);
    });
  });
});
