import { expect, test, type Locator } from '@playwright/test';
import { MANAGER, signIn } from './session';

/** WCAG contrast of an element's text against the first opaque background behind it, plus that background's luminance. */
function readability(locator: Locator) {
  return locator.evaluate((el) => {
    const ctx = document.createElement('canvas').getContext('2d')!;
    const rgba = (css: string) => {
      ctx.clearRect(0, 0, 1, 1);
      ctx.fillStyle = css;
      ctx.fillRect(0, 0, 1, 1);
      return [...ctx.getImageData(0, 0, 1, 1).data];
    };
    const luminance = ([r, g, b]: number[]) => {
      const lin = (c: number) => (c / 255 <= 0.04045 ? c / 255 / 12.92 : ((c / 255 + 0.055) / 1.055) ** 2.4);
      return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
    };
    let node: Element | null = el;
    let backdrop = [255, 255, 255, 255];
    for (; node; node = node.parentElement) {
      const bg = rgba(getComputedStyle(node).backgroundColor);
      if (bg[3] === 255) {
        backdrop = bg;
        break;
      }
    }
    const text = luminance(rgba(getComputedStyle(el).color));
    const back = luminance(backdrop);
    return { contrast: (Math.max(text, back) + 0.05) / (Math.min(text, back) + 0.05), backdrop: back };
  });
}

// The app's light/dark button decides the theme; the Windows setting only seeds it before the first choice.
for (const [windows, app] of [['dark', 'light'], ['light', 'dark']] as const) {
  test(`the dashboard follows the ${app} app theme on a ${windows} Windows theme`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: windows });
    await page.addInitScript((theme) => localStorage.setItem('theme', theme), app);
    await signIn(page, MANAGER);
    await page.goto('/admin/dashboard');

    const title = page.getByRole('heading', { name: 'Bảng điều khiển' });
    await expect(title).toBeVisible();
    const { contrast, backdrop } = await readability(title);
    expect(contrast).toBeGreaterThanOrEqual(4.5);
    if (app === 'dark') expect(backdrop).toBeLessThan(0.1);
    else expect(backdrop).toBeGreaterThan(0.8);
  });
}
