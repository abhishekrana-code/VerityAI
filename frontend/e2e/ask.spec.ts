import { test, expect } from '@playwright/test';
import { createPdf } from './support/pdf';
import { registerAndLogin, uniqueUser } from './support/users';

/**
 * The Phase 1 fast path: register lands on Home (no second sign-in), a PDF can
 * be uploaded, and a READY document is one click away from a conversation
 * scoped to it. The answer itself needs a live LLM, which this suite
 * deliberately does not configure, so it stops at the conversation the click
 * creates.
 */
test.describe('ask fast path', () => {
  test('register, upload, and ask about a document in one click', async ({ page }) => {
    const user = uniqueUser('ask');
    await registerAndLogin(page, user);

    // A brand-new account sees the three-step checklist, not "Welcome back".
    await expect(page.getByRole('heading', { name: `Welcome, ${user.displayName}` })).toBeVisible();
    await expect(page.getByText('Your first answer is three steps away')).toBeVisible();

    await page.goto('/dashboard/documents');
    const filename = `ask-${Date.now()}.pdf`;
    await page.locator('input[aria-label="Upload PDF"]').setInputFiles({
      name: filename,
      mimeType: 'application/pdf',
      buffer: createPdf('Marine biologists tracked coral bleaching across the reef system.'),
    });

    const card = page.getByRole('link').filter({ hasText: filename });
    await expect(card).toBeVisible();

    // Processing must finish before the answer path opens.
    const askButton = card.getByRole('button', { name: `Ask about ${filename}` });
    await expect(askButton).toBeVisible({ timeout: 90_000 });
    await askButton.click();

    await expect(page).toHaveURL(/\/dashboard\/workspace\/[0-9a-f-]+$/);
    await expect(page.getByRole('heading', { name: 'Ask your first question' })).toBeVisible();
    // Generic starters work without a summary.
    await expect(
      page.getByRole('button', { name: 'Summarize the main points' }),
    ).toBeVisible();
  });
});
