import { test, expect } from '@playwright/test';
import { createFakePdf, createPdf } from './support/pdf';
import { registerAndLogin, uniqueUser } from './support/users';

test.describe('document upload', () => {
  test.beforeEach(async ({ page }) => {
    const user = uniqueUser('docs');
    await registerAndLogin(page, user);
    await page.goto('/dashboard/documents');
    await expect(page.getByRole('heading', { name: 'Documents', exact: true })).toBeVisible();
  });

  test('uploads a valid PDF and lists it', async ({ page }) => {
    const filename = `e2e-evidence-${Date.now()}.pdf`;

    await page.locator('input[aria-label="Upload PDF"]').setInputFiles({
      name: filename,
      mimeType: 'application/pdf',
      buffer: createPdf('VerityAI end-to-end upload'),
    });

    await expect(page.getByText('Upload started', { exact: true })).toBeVisible();
    await expect(page.getByText(filename)).toBeVisible();
  });

  test('rejects a non-PDF payload disguised as a .pdf', async ({ page }) => {
    const filename = `e2e-fake-${Date.now()}.pdf`;

    await page.locator('input[aria-label="Upload PDF"]').setInputFiles({
      name: filename,
      mimeType: 'application/pdf',
      buffer: createFakePdf(),
    });

    await expect(page.getByText('Upload failed', { exact: true })).toBeVisible();
    await expect(
      page.getByText(/File is not a valid PDF|Only PDF files are supported/).first(),
    ).toBeVisible();
    await expect(page.getByText(filename)).toHaveCount(0);
  });
});
