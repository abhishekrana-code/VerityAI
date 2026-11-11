import { expect, type Page } from '@playwright/test';

export interface TestUser {
  email: string;
  password: string;
  displayName: string;
}

let sequence = 0;

/**
 * Builds a unique account per test. The database is shared across the run, so
 * uniqueness (not cleanup) is what keeps tests independent.
 */
export function uniqueUser(prefix = 'e2e'): TestUser {
  sequence += 1;
  return {
    email: `${prefix}.${Date.now()}.${process.pid}.${sequence}@verityai.test`,
    password: 'E2ePassword123!',
    displayName: 'E2E User',
  };
}

export async function registerUser(page: Page, user: TestUser): Promise<void> {
  await page.goto('/register');
  await page.locator('#email').fill(user.email);
  await page.locator('#displayName').fill(user.displayName);
  await page.locator('#password').fill(user.password);
  await page.locator('#confirmPassword').fill(user.password);
  await page.getByRole('button', { name: 'Create Account' }).click();
  await expect(page).toHaveURL(/\/login\?registered=1$/);
}

/** Fills and submits the login form. Callers assert the resulting URL/state. */
export async function login(page: Page, user: TestUser): Promise<void> {
  await page.goto('/login');
  await page.locator('#email').fill(user.email);
  await page.locator('#password').fill(user.password);
  await page.getByRole('button', { name: 'Sign In' }).click();
}

export async function registerAndLogin(page: Page, user: TestUser): Promise<void> {
  await registerUser(page, user);
  await login(page, user);
  await expect(page).toHaveURL(/\/dashboard$/);
}
