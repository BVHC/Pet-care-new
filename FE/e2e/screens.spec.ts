import { test } from '@playwright/test';
import { CARETAKER, DESK, MANAGER, VET, signIn, switchTo } from './session';

// Visual review only: SCREENS_DIR=<folder> npx playwright test screens
const dir = process.env.SCREENS_DIR;
test.skip(!dir, 'set SCREENS_DIR to capture review screenshots');

const sizes = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'laptop', width: 1100, height: 760 },
  { name: 'phone', width: 390, height: 844 },
];

const PAGES = [
  ['reception', DESK, '/staff/reception'],
  ['doctor', VET, '/staff/doctor'],
  ['grooming', CARETAKER, '/staff/grooming'],
  ['dashboard', MANAGER, '/admin/dashboard'],
  ['branches', MANAGER, '/admin/branches'],
] as const;

for (const size of sizes) {
  for (const theme of ['light', 'dark'] as const) {
    test(`staff pages at ${size.name}, ${theme}`, async ({ page }) => {
      await page.addInitScript((t) => localStorage.setItem('theme', t), theme);
      await page.setViewportSize({ width: size.width, height: size.height });
      for (const [name, owner, path] of PAGES) {
        await switchTo(page, owner, path);
        await page.waitForTimeout(900);
        await page.screenshot({ path: `${dir}/${name}-${size.name}-${theme}.png` });
      }
    });
  }
}

test('vet view, dark, with a dialog', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('theme', 'dark'));
  await signIn(page, VET);
  await page.goto('/staff/doctor');
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${dir}/doctor-dark.png` });
  await switchTo(page, DESK, '/staff/reception');
  await page.getByRole('button', { name: /Tiếp nhận khách/ }).click();
  await page.getByPlaceholder('Tên khách, số điện thoại hoặc tên thú cưng').fill('trang');
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${dir}/checkin-dark.png` });
});

test('groomer dragging a card', async ({ page }) => {
  await signIn(page, CARETAKER);
  await page.goto('/staff/grooming');
  const grip = page.getByRole('list', { name: 'Đang làm' }).getByRole('article', { name: /Bơ/ }).locator('[title="Kéo sang cột khác"]');
  const box = await grip.boundingBox();
  const target = await page.getByRole('list', { name: 'Xong, chờ đón' }).boundingBox();
  if (!box || !target) throw new Error('board not laid out');
  await page.mouse.move(box.x + 5, box.y + 5);
  await page.mouse.down();
  await page.mouse.move(target.x + 60, target.y + 40, { steps: 8 });
  await page.screenshot({ path: `${dir}/grooming-drag.png` });
  await page.mouse.up();
});
