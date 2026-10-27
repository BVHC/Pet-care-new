import { Card, CardContent } from '@/components/ui/card';
import type { TypeCapacity } from './capacity';

/** Dải sức chứa theo loại (BR-LT-01: sức chứa = số chuồng không bảo trì). Số liệu tính từ danh sách chưa lọc. */
export function KennelSummary({ rows }: { rows: TypeCapacity[] }) {
  if (!rows.length) return null;
  return (
    <section aria-label="Sức chứa theo loại chuồng" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
      {rows.map((r) => (
        <Card key={r.kennelTypeId} role="group" aria-label={`Sức chứa ${r.name}`}>
          <CardContent className="space-y-3 p-4">
            <p className="font-semibold text-(--text-primary)">{r.name}</p>
            <dl className="grid grid-cols-4 gap-2 text-center">
              <Stat label="Sức chứa" value={r.capacity} strong />
              <Stat label="Đang có thú" value={r.occupied} />
              <Stat label="Trống" value={r.available} />
              <Stat label="Bảo trì" value={r.maintenance} />
            </dl>
            {r.capacity === 0 && <p className="text-xs text-(--text-tertiary)">Chưa có chuồng nào đang dùng được: khách chưa đặt chỗ được loại này.</p>}
          </CardContent>
        </Card>
      ))}
    </section>
  );
}

function Stat({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <div className={strong ? 'rounded-lg bg-(--bg-secondary) py-1.5' : 'py-1.5'}>
      <dt className="text-xs text-(--text-tertiary)">{label}</dt>
      <dd className={strong ? 'text-xl font-semibold text-(--text-primary)' : 'text-lg text-(--text-secondary)'}>{value}</dd>
    </div>
  );
}
