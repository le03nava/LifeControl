import type { Page } from '@playwright/test';
import { app, expect } from '../fixtures/app';

/**
 * Reaches the calendar the way an operator does: through the header entry.
 *
 * Two deliberate choices keep this deterministic:
 *
 * 1. It never deep-links into a guarded route with `page.goto`. Every fresh page
 *    load re-runs the silent SSO, and `keycloakRoleGuard` calls
 *    `keycloak.login()` while `keycloak.authenticated` is still false — a
 *    top-level navigation to the authorization endpoint.
 * 2. Landing on the unguarded Home first and then navigating client-side.
 *    Authentication itself is settled here, so the spec does not race the
 *    silent-SSO poll.
 *
 * The wait keys on the `Calendario y citas` link rather than the `Compras` one the
 * purchases spec uses: a scheduling-only user never sees `Compras`, so keying on it
 * would time out on an authenticated session it cannot detect.
 */
async function ensureAuthenticated(page: Page): Promise<void> {
  await page.goto('/');

  const schedulingLink = page.getByRole('link', { name: 'Calendario y citas' });
  const loginButton = page.getByRole('button', { name: 'Login' });

  // The app renders a Login button until the session is known. Prefer the silent
  // SSO, but never depend on its timing: the same mocked Keycloak serves an
  // explicit login round trip, which is what a real operator without a live SSO
  // session does anyway.
  await expect(loginButton.or(schedulingLink)).toBeVisible({ timeout: 20_000 });
  if (!(await schedulingLink.isVisible())) {
    await loginButton.click();
  }

  await expect(schedulingLink).toBeVisible({ timeout: 20_000 });
}

/**
 * Opens the calendar through the header submenu — the exact control the previous
 * work unit added. The header path is the thing under test, so this spec never
 * lands on `/scheduling/calendar` directly.
 */
async function openCalendar(page: Page): Promise<void> {
  await ensureAuthenticated(page);

  await page.getByRole('button', { name: 'Calendario y citas submenu' }).click();
  await page.getByRole('menuitem', { name: 'Calendario', exact: true }).click();

  await expect(page).toHaveURL(/\/scheduling\/calendar$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Calendario' })).toBeVisible();
}

app.describe('Scheduling — calendar read access', () => {
  app.use({ clientRoles: ['lc-scheduling-read'] });

  app('opens the calendar from the header without any booking affordance', async ({ page }) => {
    await openCalendar(page);

    // The two blocks derive their facts from the requested week, so this assertion
    // never depends on the real current date.
    const cutSlot = page.locator('.slot-block').filter({ hasText: 'Corte de cabello' });
    const beardSlot = page.locator('.slot-block').filter({ hasText: 'Barba' });

    await expect(cutSlot).toContainText('0 / 3');
    await expect(beardSlot).toContainText('2 / 2');

    // `lc-scheduling-read` may reach the page but may not write, so no block offers
    // the booking action — not even the one with room.
    await expect(page.getByRole('button', { name: /Reservar/ })).toHaveCount(0);
  });
});

app.describe('Scheduling — calendar booking', () => {
  app.use({ clientRoles: ['lc-scheduling'] });

  app('books a free slot from the header path and reloads the week', async ({ page }) => {
    await openCalendar(page);

    const cutSlot = page.locator('.slot-block').filter({ hasText: 'Corte de cabello' });
    const beardSlot = page.locator('.slot-block').filter({ hasText: 'Barba' });

    await expect(cutSlot).toContainText('0 / 3');
    await expect(beardSlot).toContainText('2 / 2');

    // Only the slot with a vacancy offers booking; the full one does not.
    await expect(page.getByRole('button', { name: /Reservar/ })).toHaveCount(1);
    await expect(beardSlot.getByRole('button', { name: /Reservar/ })).toHaveCount(0);

    await page.getByRole('button', { name: /Reservar/ }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: 'Reservar un turno' })).toBeVisible();

    // Both fields are optional and are left untouched, so the body must carry
    // `null` for each — never an empty string and never a `customerId`.
    const postBooking = page.waitForRequest(
      (request) =>
        request.method() === 'POST' && request.url().endsWith('/api/scheduling/appointments'),
    );
    await dialog.getByRole('button', { name: 'Reservar', exact: true }).click();

    expect((await postBooking).postDataJSON()).toEqual({
      slotId: 'e2e-slot-1',
      userId: null,
      notes: null,
    });

    await expect(page.getByText('Turno reservado correctamente.')).toBeVisible();

    // The page re-reads the week after a booking; the moved count is what proves
    // the projection was reloaded rather than patched locally.
    await expect(cutSlot).toContainText('1 / 3');
  });
});
