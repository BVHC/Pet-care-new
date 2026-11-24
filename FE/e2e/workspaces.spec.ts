import { expect, test } from '@playwright/test';
import { CARETAKER, DESK, MANAGER, VET, pinClock, signIn, switchTo } from './session';

test('desk to exam room to cashier: one visit end to end', async ({ page }) => {
  await signIn(page, DESK);
  await pinClock(page);
  await page.goto('/staff/reception');
  await expect(page.getByRole('heading', { name: 'Quầy lễ tân' })).toBeVisible();

  // Check in Mochi's appointment; the visit joins the queue unassigned.
  await page.getByRole('listitem').filter({ hasText: 'Mochi' }).getByRole('button', { name: 'Tiếp nhận' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Tiếp nhận' }).click();
  await expect(page.getByText(/Đã tiếp nhận Mochi, mã TN-\d{4}-\d{5}/)).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0); // shortcuts stay off while a dialog is open

  // Assign it to the vet from the queue tab.
  await page.getByRole('tab', { name: /Hàng chờ/ }).click();
  await page.getByRole('listitem').filter({ hasText: 'Mochi' }).filter({ hasText: 'Chưa gán' }).getByRole('button', { name: 'Gán người' }).click();
  const assign = page.getByRole('dialog');
  await assign.getByLabel(/Người phụ trách/).selectOption('5');
  await assign.getByRole('button', { name: 'Gán', exact: true }).click();
  await expect(page.getByText(/Đã gán Mochi .* cho BS\. Minh Anh/)).toBeVisible();

  // Exam room: the vet finds the visit in their queue and calls it in.
  await switchTo(page, VET, '/staff/doctor');
  const queue = page.getByRole('region', { name: 'Hàng chờ khám' });
  await queue.getByRole('listitem').filter({ hasText: 'Mochi' }).getByRole('button', { name: 'Gọi lượt' }).click();
  await expect(page.getByRole('heading', { level: 2, name: 'Mochi', exact: true })).toBeVisible();

  // BR-KB-02: no diagnosis, no completion.
  await page.getByRole('button', { name: /Hoàn tất, chuyển thu ngân/ }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Lượt có dịch vụ khám phải có chẩn đoán trước khi hoàn tất' })).toBeVisible();
  await page.getByLabel('Chẩn đoán').fill('Viêm khớp háng, theo dõi thêm');
  await page.getByRole('button', { name: /Hoàn tất, chuyển thu ngân/ }).click();
  await expect(page.getByText('Đã chuyển Mochi sang quầy thu ngân')).toBeVisible();

  // Cashier: back at the desk, the visit's order is waiting; open the shift and collect.
  await switchTo(page, DESK, '/staff/reception');
  await page.getByRole('tab', { name: /Chờ thu/ }).click();
  await page.getByRole('listitem').filter({ hasText: 'Lượt tiếp nhận' }).filter({ hasText: '150.000' }).getByRole('button', { name: 'Thu tiền' }).click();
  await page.getByRole('button', { name: 'Mở ca thường' }).click();
  await page.getByRole('button', { name: /^Thu 150\.000/ }).click();
  await expect(page.getByRole('dialog')).toContainText('Đã thu 150.000');
});

test('groomer moves a card along the board and is refused going backwards', async ({ page }) => {
  await signIn(page, CARETAKER);
  await page.goto('/staff/grooming');
  const waiting = page.getByRole('list', { name: 'Chờ làm' });
  const doing = page.getByRole('list', { name: 'Đang làm' });
  const ready = page.getByRole('list', { name: 'Xong hôm nay' });

  await waiting.getByRole('article', { name: /Bim/ }).getByRole('button', { name: 'Bắt đầu' }).click();
  await expect(doing.getByRole('article', { name: /Bim/ })).toBeVisible();
  // "Xong" is disabled while the start call is in flight; a drag begun before it settles is lost.
  await expect(doing.getByRole('article', { name: /Bim/ }).getByRole('button', { name: 'Xong' })).toBeEnabled();

  const drag = async (to: typeof ready) => {
    const grip = doing.getByRole('article', { name: /Bim/ }).locator('[title="Kéo sang cột khác"]');
    const from = await grip.boundingBox();
    const target = await to.boundingBox();
    if (!from || !target) throw new Error('board not laid out');
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
    await page.mouse.down();
    await page.mouse.move(target.x + target.width / 2, target.y + 30, { steps: 10 });
    await page.mouse.up();
  };

  // Backwards is refused (BR-TN-09) and the card stays put.
  await drag(waiting);
  await expect(page.locator('[data-sonner-toast]', { hasText: 'Thẻ đã bắt đầu không quay lại cột trước được.' })).toBeVisible();
  await expect(doing.getByRole('article', { name: /Bim/ })).toBeVisible();

  // Forwards works: drag the card by its grip into "Xong hôm nay".
  await drag(ready);
  await expect(ready.getByRole('article', { name: /Bim/ })).toBeVisible();
});

test('counter sale with the keyboard', async ({ page }) => {
  await signIn(page, DESK);
  await pinClock(page);
  await page.goto('/staff/reception');
  await page.getByRole('button', { name: 'Mở ca thường' }).click();
  await expect(page.getByText(/Ca mở lúc/)).toBeVisible();

  // A retail order belongs to a customer profile (UC66): F1 finds the customer, a click picks one.
  await page.keyboard.press('F1');
  await page.keyboard.type('Trang');
  await page.getByRole('list', { name: 'Hồ sơ khách' }).getByRole('button', { name: /Nguyễn Thu Trang/ }).click();
  await page.getByRole('button', { name: 'Tạo Order bán lẻ' }).click();

  // Wait for the editor and for the filtered list before Enter: Enter ignores results that still belong to an older keyword.
  await expect(page.getByRole('searchbox', { name: /Tìm sản phẩm/ })).toBeVisible();
  await page.keyboard.press('F1');
  await page.keyboard.type('pate');
  await expect(page.getByRole('button', { name: /^Thêm Hạt Royal Canin/ })).toHaveCount(0);
  await page.keyboard.press('Enter');
  await expect(page.getByText('18.000 ₫ × 1')).toBeVisible();
  await page.getByRole('button', { name: 'Chốt Order' }).click();

  // The closed order waits at the cashier tab; take it onto the receipt and pay with Ctrl+Enter.
  await page.getByRole('tab', { name: /Chờ thu/ }).click();
  await page.getByRole('listitem').filter({ hasText: 'Bán lẻ' }).filter({ hasText: '18.000' }).getByRole('button', { name: 'Thu tiền' }).click();
  await expect(page.getByRole('region', { name: 'Phiếu thu' })).toContainText('Bán lẻ');
  await page.keyboard.press('Control+Enter');
  await expect(page.getByRole('dialog')).toContainText('Đã thu 18.000');
});

test('a caretaker gets 403 in the exam room and a way back to grooming', async ({ page }) => {
  await signIn(page, CARETAKER);
  await page.goto('/staff/doctor');
  await expect(page.getByRole('heading', { name: 'Bạn không có quyền vào trang này' })).toBeVisible();
  await page.getByRole('link', { name: 'Về trang của tôi' }).click();
  await expect(page).toHaveURL(/\/staff\/grooming$/);
});

test('a visitor signs in on the demo login and lands back on the workspace', async ({ page }) => {
  await page.goto('/staff/reception');
  await expect(page).toHaveURL(/\/admin\/login$/);
  await page.getByRole('button', { name: /Lan Chi/ }).click();
  await page.getByRole('button', { name: 'Đăng nhập với vai trò Lễ tân' }).click();
  await expect(page).toHaveURL(/\/staff\/reception$/);
  await expect(page.getByRole('heading', { name: 'Quầy lễ tân' })).toBeVisible();
});

test('a new staff member changes the temporary password before working (BR-TK-17)', async ({ page }) => {
  await page.goto('/admin/login');
  await page.getByRole('button', { name: /Lễ tân mới/ }).click();
  await page.getByRole('button', { name: 'Đăng nhập với vai trò Lễ tân' }).click();
  await expect(page).toHaveURL(/\/auth\/change-password$/);
  await page.getByPlaceholder('Nhập mật khẩu hiện tại').fill('Petcare123');
  await page.getByPlaceholder('Ít nhất 8 ký tự, có chữ và số').fill('MatKhauMoi1');
  await page.getByPlaceholder('Nhập lại mật khẩu mới').fill('MatKhauMoi1');
  await page.getByRole('button', { name: 'Đổi mật khẩu' }).click();
  await expect(page).toHaveURL(/\/staff\/reception$/);
});

test('/staff opens the home of the role', async ({ page }) => {
  await signIn(page, MANAGER);
  await page.goto('/staff');
  await expect(page).toHaveURL(/\/admin\/dashboard$/);
  await expect(page.getByText('Lượt tiếp nhận hôm nay')).toBeVisible();
});
