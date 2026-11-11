import { test, expect } from '@playwright/test';
import { clearMailbox, extractResetToken, waitForMessage } from './support/mail';
import { login, registerAndLogin, registerUser, uniqueUser } from './support/users';

test.describe('password reset', () => {
  test.beforeEach(async () => {
    await clearMailbox();
  });

  test('delivers a reset link and changes the password', async ({ page }) => {
    const user = uniqueUser('reset');
    const newPassword = 'E2ePassword456!';

    await registerUser(page, user);
    await page.goto('/forgot-password');
    await page.locator('#email').fill(user.email);
    await page.getByRole('button', { name: 'Send reset link' }).click();
    await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible();

    const message = await waitForMessage(user.email, 'Reset your VerityAI password');
    const token = extractResetToken(message);

    await page.goto(`/reset-password?token=${encodeURIComponent(token)}`);
    await page.locator('#password').fill(newPassword);
    await page.locator('#confirmPassword').fill(newPassword);
    await page.getByRole('button', { name: 'Update password' }).click();
    await expect(page.getByRole('heading', { name: 'Password updated' })).toBeVisible();

    // The old password must no longer work.
    await login(page, user);
    await expect(page.getByRole('alert')).toContainText('Invalid credentials');

    await login(page, { ...user, password: newPassword });
    await expect(page).toHaveURL(/\/dashboard$/);
  });

  test('shows the same response for an unknown address', async ({ page }) => {
    const unknown = uniqueUser('unknown');

    await page.goto('/forgot-password');
    await page.locator('#email').fill(unknown.email);
    await page.getByRole('button', { name: 'Send reset link' }).click();

    await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible();
  });

  test('a signed-in user can open a reset link instead of being sent to the dashboard', async ({ page }) => {
    // Regression: the reset route used to be wrapped in PublicRoute, so any
    // browser with a live session was redirected to /dashboard and the emailed
    // link looked broken.
    await registerAndLogin(page, uniqueUser('reset-in'));

    await page.goto('/reset-password?token=not-a-real-token');
    await expect(page).toHaveURL(/\/reset-password\?token=not-a-real-token$/);
    await expect(page.getByRole('heading', { name: 'Choose a new password' })).toBeVisible();

    // The form is real: the API rejects the bogus token.
    await page.locator('#password').fill('E2ePassword789!');
    await page.locator('#confirmPassword').fill('E2ePassword789!');
    await page.getByRole('button', { name: 'Update password' }).click();
    await expect(page.getByRole('alert')).toContainText('Invalid or expired');
  });
});
