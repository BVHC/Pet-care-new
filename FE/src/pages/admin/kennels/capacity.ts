import type { Kennel } from '../../../shared/types/boarding';

export interface KennelTypeRef {
  kennelTypeId: number;
  name: string;
}

export interface TypeCapacity extends KennelTypeRef {
  /** BR-LT-01: số chuồng của loại không ở `MAINTENANCE`. */
  capacity: number;
  occupied: number;
  available: number;
  maintenance: number;
}

/**
 * Sức chứa theo loại chuồng của chi nhánh. Mỗi loại đang bật có một dòng (kể cả khi chưa có chuồng: sức chứa 0, khách chưa đặt được);
 * loại có chuồng nhưng đã bị tắt vẫn hiện, lấy tên từ chuồng. Phải tính từ danh sách KHÔNG lọc.
 */
export function summarizeCapacity(kennels: Kennel[], types: KennelTypeRef[]): TypeCapacity[] {
  const rows = new Map<number, TypeCapacity>();
  const rowOf = (kennelTypeId: number, name: string) => {
    let row = rows.get(kennelTypeId);
    if (!row) rows.set(kennelTypeId, (row = { kennelTypeId, name, capacity: 0, occupied: 0, available: 0, maintenance: 0 }));
    return row;
  };
  types.forEach((t) => rowOf(t.kennelTypeId, t.name));
  for (const k of kennels) {
    const row = rowOf(k.kennelTypeId, k.kennelTypeName);
    if (k.status === 'MAINTENANCE') row.maintenance++;
    else {
      row.capacity++;
      if (k.status === 'OCCUPIED') row.occupied++;
      else row.available++;
    }
  }
  return [...rows.values()];
}
