import { test, expect } from '@playwright/test';
import { E2E_BASE_URL } from './support/config';
import { login, registerAndLogin, registerUser, uniqueUser } from './support/users';

test.describe('authentication and sessions', () => {
  test('redirects unauthenticated visitors away from the dashboard', async ({ page }) => {
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  });

  test('registers, signs in, redirects public routes, and logs out', async ({ page }) => {
    const user = uniqueUser();
    await registerUser(page, user);
    await expect(
      page.getByText('Account created. Sign in to start researching.'),
    ).toBeVisible();

    await login(page, user);
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(
      page.getByRole('heading', { name: `Welcome back, ${user.displayName}` }),
    ).toBeVisible();

    // An authenticated user must not see the login form again.
    await page.goto('/login');
    await expect(page).toHaveURL(/\/dashboard$/);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);

    await page.goto('/dashboard/documents');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('keeps only one active session per account', async ({ browser }) => {
    const user = uniqueUser();
    const first = await browser.newContext({ baseURL: E2E_BASE_URL });
    const second = await browser.newContext({ baseURL: E2E_BASE_URL });
    const pageA = await first.newPage();
    const pageB = await second.newPage();

    try {
      await registerUser(pageA, user);
      await login(pageA, user);
      await expect(pageA).toHaveURL(/\/dashboard$/);

      await login(pageB, user);
      await expect(pageB).toHaveURL(/\/dashboard$/);

      // Signing in from the second context deleted the first session row.
      await pageA.goto('/dashboard/documents');
      await expect(pageA).toHaveURL(/\/login$/);
    } finally {
      await first.close();
      await second.close();
    }
  });

  test('persists a profile display-name change across reloads', async ({ page }) => {
    const user = uniqueUser();
    await registerAndLogin(page, user);

    await page.goto('/dashboard/settings');
    await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
    await page.locator('#displayName').fill('Renamed E2E');
    await page.getByRole('button', { name: 'Save Changes' }).click();
    await expect(page.getByText('Profile updated', { exact: true })).toBeVisible();

    await page.reload();
    await expect(page.locator('#displayName')).toHaveValue('Renamed E2E');

    // The session principal must reflect the new name after a full reload.
    await page.goto('/dashboard');
    await expect(page.getByRole('heading', { name: 'Welcome back, Renamed E2E' })).toBeVisible();
  });
});
