import { test, expect } from '@playwright/test';
import { createLongPdf } from './support/pdf';
import { registerAndLogin, uniqueUser } from './support/users';

/**
 * Regression: Search used to cache an empty ready-document list for five
 * minutes if it was opened while an upload was still processing, leaving the
 * scope picker empty until a reload. The PDF here is long enough that
 * processing is still running when Search loads.
 */
test.describe('search scope refresh', () => {
  test('lists a document that finishes processing while Search is open', async ({ page }) => {
    const user = uniqueUser('search');
    await registerAndLogin(page, user);

    const filename = `search-refresh-${Date.now()}.pdf`;
    const uploaded = page.waitForResponse(
      (response) =>
        response.url().includes('/api/v1/documents') &&
        response.request().method() === 'POST',
    );
    await page.locator('input[aria-label="Upload PDF"]').setInputFiles({
      name: filename,
      mimeType: 'application/pdf',
      // Long enough (under the 200-page limit) that processing is still running
      // when Search opens, even on a fast machine.
      buffer: createLongPdf(
        190,
        ('The northern cod fishery collapsed in 1992 after decades of overfishing, ' +
          'and the moratorium displaced tens of thousands of workers. ').repeat(40),
      ),
    });
    expect((await uploaded).status()).toBe(200);

    // Open Search straight away, while the backend is still working on the
    // upload, using the sidebar link.
    await page.getByRole('link', { name: 'Search', exact: true }).click();

    const status = await page.evaluate(async () => {
      const response = await fetch('/api/v1/documents?size=1');
      const body = (await response.json()) as { content?: Array<{ status: string }> };
      return body.content?.[0]?.status ?? 'none';
    });
    expect(status, 'PDF finished processing before Search opened').toMatch(/^(UPLOADED|PROCESSING)$/);

    await expect(page.getByRole('heading', { name: 'Search your library' })).toBeVisible();
    await page.getByRole('button', { name: 'All documents' }).click();
    await expect(page.getByText(/No processed documents yet/)).toBeVisible();

    // Only a client-side refresh preserves this marker; a reload would clear it.
    await page.evaluate(() => {
      (window as unknown as { __noReloadMarker?: string }).__noReloadMarker = 'kept';
    });

    await expect(page.locator('label').filter({ hasText: filename })).toBeVisible({
      timeout: 60_000,
    });
    expect(
      await page.evaluate(
        () => (window as unknown as { __noReloadMarker?: string }).__noReloadMarker,
      ),
    ).toBe('kept');
  });
});
