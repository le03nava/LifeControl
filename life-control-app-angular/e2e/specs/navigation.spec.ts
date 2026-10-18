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
      'reaches the departments screen from the Recursos Humanos link, through its landing page (E53)',
      async ({ page }) => {
        await page.goto('/');

        // The label navigates, like Compras: it is a link to the landing page that lists
        // the three catalogs, so it carries no caret and opens no menu.
        const hrLabel = page.getByRole('link', { name: 'Recursos Humanos' });
        await expect(hrLabel).toBeVisible();

        await hrLabel.click();

        await expect(page).toHaveURL(/\/hr$/);
        await expect(page.getByText('Gestionar Departamentos')).toBeVisible();

        await page.getByText('Gestionar Departamentos').click();

        await expect(page).toHaveURL(/\/hr\/departments$/);
        await expect(page.getByRole('heading', { name: 'Departamentos' })).toBeVisible();
      },
    );

    app(
      'reaches the positions screen from the Recursos Humanos landing page (D16)',
      async ({ page }) => {
        await page.goto('/');

        const hrEntry = page.getByRole('link', { name: 'Recursos Humanos' });
        await expect(hrEntry).toBeVisible();

        // The card is the rendered control D16 requires to have a destination behind it:
        // the landing page grows one card at a time rather than shipping one that
        // resolves to NotFound.
        await page.getByRole('link', { name: 'Recursos Humanos' }).click();
        await page.getByText('Gestionar Puestos').click();

        await expect(page).toHaveURL(/\/hr\/positions$/);
        await expect(page.getByRole('heading', { name: 'Puestos' })).toBeVisible();
      },
    );

    app('reaches the position settings screen from the row action (W3)', async ({ page }) => {
      await page.goto('/hr/positions?companyId=company-1');

      const row = page.getByRole('row', { name: /OPR/ });
      await expect(row).toBeVisible();

      // D16's rule applied to a row action: the control renders **and** what it
      // opens renders, so the action is never a control with no destination.
      await row.getByRole('button', { name: 'Configuración' }).click();

      await expect(page).toHaveURL(/\/hr\/positions\/position-1\/settings/);
      await expect(page.getByRole('heading', { name: 'Configuración del puesto' })).toBeVisible();
      await expect(page.getByText('JR — Junior')).toBeVisible();
      // The second section is W3b's: its checkboxes come from the allowlist read, and
      // the stored role is the one checked here.
      await expect(page.getByRole('checkbox', { name: 'lc-sales' })).toBeChecked();
      await expect(page.getByRole('checkbox', { name: 'lc-company' })).not.toBeChecked();
    });

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
