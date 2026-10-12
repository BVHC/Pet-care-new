import { expect, test } from '@playwright/test';
import { localIsoDate, nextWeekday } from '../src/shared/utils/branch-schedule';
import { CHAIN_MANAGER, MANAGER, MANAGER2, signIn, switchTo } from './session';

// Mock branch: chi nhánh 1 có lịch hẹn thứ Ba tới 09:00 (Mochi) và 18:30 (Lucky); chi nhánh 2 DRAFT chưa có giờ mở cửa.
const tuesday = nextWeekday(localIsoDate(), 2);

test('a branch manager adds a holiday and cancels the appointments of that day (UC14, BR-LH-10)', async ({ page }) => {
  await signIn(page, MANAGER);
  await page.goto('/admin/branches');
  await expect(page).toHaveURL(/\/admin\/branches\/1\/hours$/);

  await page.getByRole('tab', { name: 'Ngày nghỉ' }).click();
  await page.getByRole('button', { name: 'Thêm ngày nghỉ' }).click();
  const form = page.getByRole('dialog');
  await form.getByLabel('Ngày nghỉ').fill(tuesday);
  await form.getByLabel('Lý do').fill('Sửa điện');
  await form.getByRole('button', { name: 'Kiểm tra và thêm' }).click();

  await expect(form.getByText('Mochi')).toBeVisible();
  await expect(form.getByText('Lucky')).toBeVisible();
  await form.getByRole('checkbox', { name: /Hủy hàng loạt các lịch trên/ }).check();
  await form.getByRole('button', { name: 'Thêm và hủy hàng loạt' }).click();

  await expect(page.getByText(/hủy 2 lịch hẹn và 0 đặt chỗ lưu trú/)).toBeVisible();
  await expect(page.getByRole('row', { name: /Sửa điện/ })).toBeVisible();
});

test('a draft branch goes live once its manager has set the opening hours (UC12, UC14)', async ({ page }) => {
  await signIn(page, CHAIN_MANAGER);
  await page.goto('/admin/branches');
  await page.getByRole('button', { name: 'Kích hoạt PetCare Hà Đông' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Kích hoạt' }).click();
  await expect(page.getByRole('alert')).toContainText('chưa cấu hình giờ mở cửa');

  await switchTo(page, MANAGER2, '/admin/branches');
  await expect(page).toHaveURL(/\/admin\/branches\/2\/hours$/);
  await page.getByRole('button', { name: 'Đặt giờ mở cửa mới' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Kiểm tra và lưu' }).click();
  await expect(page.getByText(/Đã lưu giờ mở cửa áp dụng từ/)).toBeVisible();

  await switchTo(page, CHAIN_MANAGER, '/admin/branches');
  await page.getByRole('button', { name: 'Kích hoạt PetCare Hà Đông' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Kích hoạt' }).click();
  await expect(page.getByText('Đã kích hoạt chi nhánh PetCare Hà Đông')).toBeVisible();
  await expect(page.getByRole('row', { name: /PetCare Hà Đông/ })).toContainText('Đang hoạt động');
});
