import { expect, test } from '@playwright/test';
import { DESK, GROOMER, MANAGER, VET_AT_DESK, signIn } from './session';

test('desk to exam room to cashier: one visit end to end', async ({ page }) => {
  await signIn(page, VET_AT_DESK); // multi-role: a vet who also staffs the desk
  await page.goto('/staff/reception');
  await expect(page.getByRole('heading', { name: 'Quầy lễ tân' })).toBeVisible();

  // Check in Lucky's appointment and assign it to me.
  await page.getByRole('listitem').filter({ hasText: 'Lucky' }).getByRole('button', { name: 'Tiếp nhận' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Người phụ trách').selectOption('5');
  await dialog.getByRole('button', { name: 'Tiếp nhận' }).click();
  await expect(page.getByText('Đã tiếp nhận Lucky, số 9')).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0); // shortcuts stay off while a dialog is open

  // Exam room: the new visit is in my queue.
  await page.keyboard.press('Alt+2');
  await expect(page).toHaveURL(/\/staff\/doctor$/);
  const queue = page.getByRole('region', { name: 'Hàng chờ khám' });
  await queue.getByRole('listitem').filter({ hasText: 'Lucky' }).getByRole('button', { name: 'Gọi vào khám' }).click();
  await expect(page.getByRole('heading', { level: 2, name: 'Lucky', exact: true })).toBeVisible();

  // BR-KB-02: no diagnosis, no completion.
  await page.getByRole('button', { name: /Hoàn tất, chuyển thu ngân/ }).click();
  await expect(page.getByText('Ghi chẩn đoán trước khi hoàn tất lượt khám.')).toBeVisible();
  await page.getByLabel('Chẩn đoán').fill('Viêm khớp háng, theo dõi thêm');
  await page.getByRole('button', { name: /Hoàn tất, chuyển thu ngân/ }).click();
  await expect(page.getByText('Đã chuyển Lucky sang quầy thu ngân')).toBeVisible();

  // Cashier: Lucky's order is waiting; open the shift and collect.
  await page.keyboard.press('Alt+1');
  await page.getByRole('tab', { name: /Chờ thu/ }).click();
  await page.getByRole('listitem').filter({ hasText: 'Lucky' }).getByRole('button', { name: 'Thu tiền' }).click();
  await page.getByRole('button', { name: 'Mở ca thu ngân' }).click();
  await page.getByRole('button', { name: /^Thu 150\.000/ }).click();
  await expect(page.getByRole('dialog')).toContainText('Đã thu 150.000');
});

test('groomer moves cards along the board and is refused going backwards', async ({ page }) => {
  await signIn(page, GROOMER);
  await page.goto('/staff/grooming');
  const waiting = page.getByRole('list', { name: 'Chờ làm' });
  const doing = page.getByRole('list', { name: 'Đang làm' });
  const ready = page.getByRole('list', { name: 'Xong, chờ đón' });

  await waiting.getByRole('article', { name: /Rocky/ }).getByRole('button', { name: 'Bắt đầu' }).click();
  await expect(doing.getByRole('article', { name: /Rocky/ })).toBeVisible();

  // Drag Bơ by its grip into "Xong, chờ đón".
  const drag = async (gripOwner: RegExp, list: typeof ready) => {
    const grip = doing.getByRole('article', { name: gripOwner }).locator('[title="Kéo sang cột khác"]');
    const from = await grip.boundingBox();
    const to = await list.boundingBox();
    if (!from || !to) throw new Error('board not laid out');
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(to.x + to.width / 2, to.y + 30, { steps: 10 });
    await page.mouse.up();
  };

  await drag(/Bơ/, ready);
  await expect(ready.getByRole('article', { name: /Bơ/ })).toBeVisible();

  // Backwards is refused (BR-TN-09) and the card stays put.
  await drag(/Rocky/, waiting);
  await expect(page.locator('[data-sonner-toast]', { hasText: 'Thẻ đã bắt đầu không quay lại cột trước được.' })).toBeVisible();
  await expect(doing.getByRole('article', { name: /Rocky/ })).toBeVisible();
});

test('counter sale with the keyboard only', async ({ page }) => {
  await signIn(page, DESK);
  await page.goto('/staff/reception');
  await page.getByRole('button', { name: 'Mở ca thu ngân' }).click();
  await expect(page.getByText(/Ca mở lúc/)).toBeVisible();

  await page.keyboard.press('F1');
  await page.keyboard.type('pate');
  await page.keyboard.press('Enter');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('region', { name: 'Phiếu thu' })).toContainText('Pate Whiskas 85g');

  await page.keyboard.press('Control+Enter');
  await expect(page.getByRole('dialog')).toContainText('Đã thu 36.000');
});

test('route guard sends staff to a workspace their roles cover', async ({ page }) => {
  await signIn(page, GROOMER);
  await page.goto('/staff/doctor');
  await expect(page).toHaveURL(/\/staff\/grooming$/);
});

test('/staff opens the default workspace of the primary role', async ({ page }) => {
  await signIn(page, MANAGER);
  await page.goto('/staff');
  await expect(page).toHaveURL(/\/admin\/dashboard$/);
  await expect(page.getByText('Lượt tiếp nhận hôm nay')).toBeVisible();
});
