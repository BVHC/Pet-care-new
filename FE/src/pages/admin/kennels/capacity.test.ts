import { describe, expect, it } from 'vitest';
import type { Kennel, KennelStatus } from '../../../shared/types/boarding';
import { summarizeCapacity } from './capacity';

const kennel = (kennelId: number, kennelTypeId: number, status: KennelStatus, kennelTypeName = `Loại ${kennelTypeId}`): Kennel => ({
  kennelId,
  branchId: 1,
  kennelTypeId,
  kennelTypeName,
  code: `C-${kennelId}`,
  status,
});

describe('summarizeCapacity (BR-LT-01)', () => {
  it('sức chứa = chuồng không bảo trì; đếm riêng đang có thú, trống, bảo trì', () => {
    const [a] = summarizeCapacity(
      [kennel(1, 5, 'OCCUPIED'), kennel(2, 5, 'OCCUPIED'), kennel(3, 5, 'AVAILABLE'), kennel(4, 5, 'MAINTENANCE')],
      [{ kennelTypeId: 5, name: 'Chuồng chó nhỏ' }],
    );
    expect(a).toEqual({ kennelTypeId: 5, name: 'Chuồng chó nhỏ', capacity: 3, occupied: 2, available: 1, maintenance: 1 });
  });

  it('mỗi loại một dòng; loại đã bật nhưng chưa có chuồng nào vẫn hiện với sức chứa 0', () => {
    const rows = summarizeCapacity([kennel(1, 5, 'AVAILABLE')], [{ kennelTypeId: 5, name: 'Chuồng chó nhỏ' }, { kennelTypeId: 6, name: 'Chuồng mèo' }]);
    expect(rows.map((r) => [r.kennelTypeId, r.capacity])).toEqual([[5, 1], [6, 0]]);
  });

  it('loại có chuồng nhưng không còn trong danh sách đã bật vẫn hiện, lấy tên từ chuồng', () => {
    const rows = summarizeCapacity([kennel(1, 9, 'AVAILABLE', 'Chuồng cũ')], []);
    expect(rows).toEqual([{ kennelTypeId: 9, name: 'Chuồng cũ', capacity: 1, occupied: 0, available: 1, maintenance: 0 }]);
  });

  it('không có chuồng, không có loại nào: danh sách rỗng', () => {
    expect(summarizeCapacity([], [])).toEqual([]);
  });
});
