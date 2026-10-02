import { test } from '@playwright/test';
import { GROOMER, MANAGER, VET_AT_DESK, signIn } from './session';

// Visual review only: SCREENS_DIR=<folder> npx playwright test screens
const dir = process.env.SCREENS_DIR;
test.skip(!dir, 'set SCREENS_DIR to capture review screenshots');

const sizes = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'laptop', width: 1100, height: 760 },
  { name: 'phone', width: 390, height: 844 },
];

for (const size of sizes) {
  test(`workspaces at ${size.name}`, async ({ page }) => {
    await page.setViewportSize({ width: size.width, height: size.height });
    await signIn(page, MANAGER);
    for (const ws of ['reception', 'doctor', 'grooming']) {
      await page.goto(`/staff/${ws}`);
      await page.waitForTimeout(900);
      await page.screenshot({ path: `${dir}/${ws}-${size.name}.png` });
    }
  });
}

test('vet view, dark, with a dialog', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('theme', 'dark'));
  await signIn(page, VET_AT_DESK);
  await page.goto('/staff/doctor');
  await page.waitForTimeout(900);
  await page.screenshot({ path: `${dir}/doctor-dark.png` });
  await page.goto('/staff/reception');
  await page.getByRole('button', { name: /Tiếp nhận khách/ }).click();
  await page.getByPlaceholder('Tên khách, số điện thoại hoặc tên thú cưng').fill('trang');
  await page.waitForTimeout(700);
  await page.screenshot({ path: `${dir}/checkin-dark.png` });
});

test('groomer dragging a card', async ({ page }) => {
  await signIn(page, GROOMER);
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
