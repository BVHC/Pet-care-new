import { expect, test } from '@playwright/test';
import { CHAIN_MANAGER, signIn } from './session';

test('the chain manager adds a kennel type and a vaccine product (UC29, UC30)', async ({ page }) => {
  await signIn(page, CHAIN_MANAGER);
  await page.goto('/admin/catalog');
  await expect(page).toHaveURL(/\/admin\/catalog\/products$/);

  await page.getByRole('tab', { name: 'Dịch vụ & loại chuồng' }).click();
  await page.getByRole('button', { name: 'Thêm dịch vụ' }).click();
  const service = page.getByRole('dialog');
  await service.getByLabel('Tên dịch vụ', { exact: true }).fill('Chuồng chó lớn');
  await service.getByLabel('Nhóm dịch vụ', { exact: true }).selectOption({ label: 'Lưu trú (loại chuồng)' });
  await service.getByLabel('Loài', { exact: true }).selectOption({ label: 'Chó' });
  await service.getByLabel('Cân nặng tối đa (kg)').fill('40');
  await service.getByLabel('Giá theo đêm (đ)').fill('300000');
  await service.getByRole('button', { name: 'Tạo dịch vụ' }).click();
  await expect(page.getByRole('row', { name: /Chuồng chó lớn/ })).toContainText('Chó · tối đa 40 kg');

  await page.getByRole('tab', { name: 'Sản phẩm', exact: true }).click();
  await page.getByRole('button', { name: 'Thêm sản phẩm' }).click();
  const product = page.getByRole('dialog');
  await product.getByLabel('Danh mục', { exact: true }).selectOption({ label: 'Vaccine' });
  await product.getByLabel('SKU', { exact: true }).fill('VAC-HOCUI');
  await product.getByLabel('Tên sản phẩm', { exact: true }).fill('Vaccine ho cũi Nobivac');
  await product.getByLabel('Loại sản phẩm', { exact: true }).selectOption({ label: 'Vaccine' });
  await expect(product.getByRole('checkbox', { name: /Quản lý hạn dùng/ })).toBeChecked(); // BR-SP-05
  await product.getByLabel('Loại vaccine', { exact: true }).selectOption({ label: 'Ho cũi · Chó' });
  await product.getByLabel('Đơn vị tính').fill('liều');
  await product.getByLabel('Giá (đ)').fill('220000');
  await product.getByRole('button', { name: 'Tạo sản phẩm' }).click();
  await expect(page.getByRole('row', { name: /Vaccine ho cũi Nobivac/ })).toContainText('Theo lô');
});
