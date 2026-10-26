import { describe, expect, it } from 'vitest';
import { branchNamesByIds, formatPublishedDate, productPriceLabel, servicePriceLabel } from './public-format';

describe('nhãn giá dịch vụ (BR-CK-02)', () => {
  it('khám / tiêm / thẩm mỹ: có priceIsFrom thì "Từ X đ", không thì "X đ"; nghìn ngăn bằng dấu chấm (vi-VN)', () => {
    expect(servicePriceLabel({ group: 'MEDICAL', price: 150_000, priceIsFrom: false })).toBe('150.000 đ');
    expect(servicePriceLabel({ group: 'MEDICAL', price: 50_000, priceIsFrom: true })).toBe('Từ 50.000 đ');
    expect(servicePriceLabel({ group: 'GROOMING', price: 1_250_000, priceIsFrom: true })).toBe('Từ 1.250.000 đ');
    expect(servicePriceLabel({ group: 'GROOMING', price: 120_000, priceIsFrom: false })).toBe('120.000 đ');
  });

  it('loại chuồng (BOARDING): giá theo đêm "X đ / đêm", không có "Từ" kể cả khi priceIsFrom bật', () => {
    expect(servicePriceLabel({ group: 'BOARDING', price: 200_000, priceIsFrom: false })).toBe('200.000 đ / đêm');
    expect(servicePriceLabel({ group: 'BOARDING', price: 200_000, priceIsFrom: true })).toBe('200.000 đ / đêm');
  });

  it('giá 0 vẫn có nhãn, không rơi về chuỗi rỗng', () => {
    expect(servicePriceLabel({ group: 'MEDICAL', price: 0, priceIsFrom: false })).toBe('0 đ');
  });
});

describe('nhãn giá sản phẩm (BR-CK-02)', () => {
  it('giá bán "X đ"', () => {
    expect(productPriceLabel(450_000)).toBe('450.000 đ');
    expect(productPriceLabel(18_000)).toBe('18.000 đ');
    expect(productPriceLabel(999)).toBe('999 đ');
  });
});

describe('ngày xuất bản dd/MM/yyyy theo giờ Việt Nam', () => {
  it('dạng +07:00 giữ đúng ngày', () => {
    expect(formatPublishedDate('2026-10-20T08:30:00+07:00')).toBe('20/10/2026');
  });

  it('dạng Z (UTC) quy về giờ Việt Nam: 18:00Z đã sang ngày hôm sau', () => {
    expect(formatPublishedDate('2026-10-20T18:00:00Z')).toBe('21/10/2026');
    expect(formatPublishedDate('2026-10-20T08:30:00Z')).toBe('20/10/2026');
  });

  it('sát nửa đêm: 23:59 +07:00 vẫn là ngày đó, 00:00 +07:00 là ngày mới; hai dạng cùng một thời điểm ra cùng ngày', () => {
    expect(formatPublishedDate('2026-10-20T23:59:59+07:00')).toBe('20/10/2026');
    expect(formatPublishedDate('2026-10-21T00:00:00+07:00')).toBe('21/10/2026');
    expect(formatPublishedDate('2026-10-20T17:00:00Z')).toBe('21/10/2026');
    expect(formatPublishedDate('2026-10-20T16:59:59Z')).toBe('20/10/2026');
  });

  it('qua tháng, năm và có phần giây lẻ dài như BE trả (micro giây)', () => {
    expect(formatPublishedDate('2026-12-31T20:00:00Z')).toBe('01/01/2027');
    expect(formatPublishedDate('2026-03-05T09:15:30.123456+07:00')).toBe('05/03/2026');
  });

  it('chuỗi không phải ngày giờ: trả chuỗi rỗng thay vì ném lỗi', () => {
    expect(formatPublishedDate('không phải ngày')).toBe('');
    expect(formatPublishedDate('')).toBe('');
  });
});

describe('tên chi nhánh còn hàng (BR-CK-03)', () => {
  const branches = [
    { branchId: 1, name: 'PetCare Cầu Giấy' },
    { branchId: 4, name: 'PetCare Hà Đông' },
  ];

  it('ghép id với danh sách chi nhánh công khai, bỏ id không có trong danh sách (chi nhánh không ACTIVE)', () => {
    expect(branchNamesByIds([1, 99, 4], branches)).toEqual(['PetCare Cầu Giấy', 'PetCare Hà Đông']);
  });

  it('thứ tự theo danh sách chi nhánh (đã sắp theo tên ở BE), không theo thứ tự id; id trùng chỉ hiện một lần', () => {
    expect(branchNamesByIds([4, 1, 4], branches)).toEqual(['PetCare Cầu Giấy', 'PetCare Hà Đông']);
  });

  it('không có id nào khớp hoặc danh sách rỗng: mảng rỗng', () => {
    expect(branchNamesByIds([], branches)).toEqual([]);
    expect(branchNamesByIds([7], branches)).toEqual([]);
    expect(branchNamesByIds([1], [])).toEqual([]);
  });
});
