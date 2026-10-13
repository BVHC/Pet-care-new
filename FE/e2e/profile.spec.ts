import { expect, test } from '@playwright/test';
import { VET, signIn } from './session';

// Mock customer: khachmoi@petcare.vn có SĐT trùng hồ sơ tại quầy #3 "Lê Hoàng Long" (email long.lh@gmail.com)
// và #4 "Lê Thị Hoa" (không có email). Mọi mã OTP của mock là 123456.

test('a new customer is asked at login, links the counter profile and gets the pet pages back (UC07, BR-TK-19)', async ({ page }) => {
  await page.goto('/pets');
  await page.getByLabel('Email', { exact: true }).fill('khachmoi@petcare.vn');
  await page.getByLabel('Mật khẩu', { exact: true }).fill('Petcare123');
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  await expect(page).toHaveURL(/\/account\/link$/);

  await page.getByRole('button', { name: 'Chọn hồ sơ Lê*** H** L*' }).click();
  await expect(page.getByText(/sổ địa chỉ hiện tại \(1 địa chỉ\) sẽ bị xóa/)).toBeVisible();
  await page.getByRole('button', { name: 'Gửi mã xác thực' }).click();
  await expect(page.getByText('lo***@gmail.com')).toBeVisible();
  await page.getByLabel('Mã xác thực').fill('123456');
  await page.getByRole('button', { name: 'Xác nhận liên kết' }).click();
  await expect(page.getByText('Tài khoản đã gắn với hồ sơ tại phòng khám')).toBeVisible();

  await page.getByRole('link', { name: 'Sổ địa chỉ' }).click();
  await expect(page.getByText('0/5')).toBeVisible();
  await page.goto('/pets');
  await expect(page.getByRole('heading', { name: 'Hoàn tất liên kết hồ sơ' })).toHaveCount(0);
});

test('a vet edits the public introduction from the workspace (UC06, BR-TK-20)', async ({ page }) => {
  await signIn(page, VET);
  await page.goto('/staff/doctor');
  await page.getByRole('link', { name: 'Hồ sơ cá nhân' }).click();
  await expect(page).toHaveURL(/\/admin\/profile$/);

  const intro = page.locator('section', { has: page.getByRole('heading', { name: 'Hồ sơ giới thiệu công khai' }) });
  await intro.getByLabel('Chuyên môn').fill('Ngoại khoa');
  await intro.getByLabel('Mô tả ngắn').fill('Phẫu thuật mô mềm cho chó mèo.');
  await expect(intro.getByRole('article', { name: 'Xem trước' })).toContainText('Ngoại khoa');
  await intro.getByRole('button', { name: 'Lưu hồ sơ giới thiệu' }).click();
  await expect(intro.getByRole('status')).toHaveText('Đã lưu hồ sơ giới thiệu');

  await page.reload();
  await expect(page.getByLabel('Chuyên môn')).toHaveValue('Ngoại khoa');
});
