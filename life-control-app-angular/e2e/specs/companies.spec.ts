import { app, expect } from '../fixtures/app';

app.describe('Companies CRUD', () => {
  app.use({ clientRoles: ['lc-admin'] });

  app('shows validation errors on an empty form', async ({ page }) => {
    await page.goto('/companies/create');
    await expect(page.getByRole('heading', { name: 'Nueva Compañía' })).toBeVisible();

    await page.getByLabel('Clave de Compañía').clear();
    await page.getByRole('button', { name: 'Guardar' }).click();

    // Clave, Nombre, Razón Social, RFC and Moneda: the five fields the page requires. `Moneda`
    // starts empty on purpose (D1), so an untouched form is one required error short of valid.
    await expect(page.getByText('Este campo es obligatorio.')).toHaveCount(5);
  });

  app('creates a company and lands on the edit page', async ({ page }) => {
    await page.goto('/companies/create');
    await expect(page.getByRole('heading', { name: 'Nueva Compañía' })).toBeVisible();

    await page.getByLabel('Clave de Compañía').fill('E2E-CORP');
    await page.getByLabel('Nombre de la Empresa').fill('Empresa E2E');
    await page.getByLabel('Tipo de Persona').click();
    await page.getByRole('option', { name: 'Persona Moral' }).click();
    await page.getByLabel('Razón Social').fill('Empresa E2E, S.A. de C.V.');
    await page.getByLabel('RFC').fill('E2E202601011');
    await page.getByLabel('Correo Electrónico').fill('empresa@e2e.example');
    await page.getByLabel('Teléfono').fill('+525511223344');
    await page.getByLabel('Moneda').click();
    await page.getByRole('option', { name: 'MXN — $' }).click();

    await page.getByRole('button', { name: 'Guardar' }).click();

    await expect(page).toHaveURL(/\/companies\/edit\/e2e-company-\d+/);
    await expect(page.getByRole('heading', { name: 'Editar Compañía' })).toBeVisible();
    await expect(page.getByLabel('Nombre de la Empresa')).toHaveValue('Empresa E2E');
    await expect(page.getByLabel('RFC')).toHaveValue('E2E202601011');
    // The stored currency comes back on the edit screen, which is what makes create and edit
    // agree about a field the API refuses to default.
    await expect(page.getByLabel('Moneda')).toContainText('MXN');
  });
});
