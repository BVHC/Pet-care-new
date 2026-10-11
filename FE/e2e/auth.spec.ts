import { expect, test, type Page } from '@playwright/test';

// Chạy với mock theo hợp đồng (`npm run dev`): mọi mã OTP là 123456 (MOCK_OTP_CODE).
const OTP = '123456';

async function logIn(page: Page, email: string, password: string) {
  await page.getByPlaceholder('ban@email.com').fill(email);
  await page.getByPlaceholder('Nhập mật khẩu').fill(password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
}

test('a new customer registers, enters the emailed code and logs in (UC01–UC03)', async ({ page }) => {
  await page.goto('/auth/register');
  await page.getByPlaceholder('Nguyễn Văn A').fill('Khách E2E');
  await page.getByPlaceholder('ban@email.com').fill('e2e@petcare.vn');
  await page.getByPlaceholder('Ít nhất 8 ký tự, có chữ và số').fill('MatKhau1');
  await page.getByPlaceholder('Nhập lại mật khẩu').fill('MatKhau1');
  await page.getByRole('checkbox', { name: /đủ 18 tuổi/ }).check();
  await page.getByRole('checkbox', { name: /Điều khoản sử dụng/ }).check();
  await page.getByRole('button', { name: 'Tạo tài khoản' }).click();

  await expect(page).toHaveURL(/\/auth\/verify-otp$/);
  await expect(page.getByRole('button', { name: /Gửi lại sau \d+s/ })).toBeDisabled(); // BR-TK-07
  await page.getByRole('textbox').first().fill(OTP);
  await page.getByRole('button', { name: 'Xác thực' }).click();

  await expect(page).toHaveURL(/\/auth\/login$/);
  await logIn(page, 'e2e@petcare.vn', 'MatKhau1');
  await expect(page).toHaveURL(/localhost:5173\/$/);
});

test('a staff member resets a forgotten password with the emailed code (UC04)', async ({ page }) => {
  await page.goto('/auth/forgot');
  await page.getByPlaceholder('ban@email.com').fill('doctor@store1.vn');
  await page.getByRole('button', { name: 'Gửi mã xác thực' }).click();
  await page.getByPlaceholder('6 chữ số').fill(OTP);
  await page.getByPlaceholder('Ít nhất 8 ký tự, có chữ và số').fill('MatKhauMoi1');
  await page.getByPlaceholder('Nhập lại mật khẩu mới').fill('MatKhauMoi1');
  await page.getByRole('button', { name: 'Đổi mật khẩu' }).click();

  await expect(page).toHaveURL(/\/auth\/login$/);
  await logIn(page, 'doctor@store1.vn', 'MatKhauMoi1');
  await expect(page).toHaveURL(/\/staff\/doctor$/);
});
