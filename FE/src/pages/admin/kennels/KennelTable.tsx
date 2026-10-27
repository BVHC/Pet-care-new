import { Pencil, Trash2, Undo2, Wrench } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { StatusBadge } from '@/components/ui/modal-templates';
import { KENNEL_STATUS_LABELS, type Booking, type Kennel } from '@/shared/types/boarding';
import { KENNEL_STATUS_TONE } from '@/shared/utils/boarding';

const LOCKED_MAINTENANCE = 'Chuồng đang có thú, chưa chuyển bảo trì được (BR-LT-01). Chờ trả thú xong.';
const LOCKED_DELETE = 'Chuồng đang có thú, không xóa được (BR-LT-01). Chờ trả thú xong.';

interface Props {
  kennels: Kennel[];
  /** Đặt chỗ đang lưu trú theo mã, để cột "Đang giữ" hiện mã đặt chỗ + tên thú. */
  stays: Map<number, Booking>;
  /** Có lệnh ghi đang chạy: khóa nút để không bấm đúp. */
  busy: boolean;
  onEdit: (k: Kennel) => void;
  onStartMaintenance: (k: Kennel) => void;
  onEndMaintenance: (k: Kennel) => void;
  onDelete: (k: Kennel) => void;
}

export function KennelTable({ kennels, stays, busy, onEdit, onStartMaintenance, onEndMaintenance, onDelete }: Props) {
  return (
    <Card>
      <CardContent className="overflow-x-auto p-0">
        <table className="table min-w-[44rem]" aria-label="Danh sách chuồng">
          <thead>
            <tr>
              <th>Mã chuồng</th>
              <th>Loại chuồng</th>
              <th>Trạng thái</th>
              <th>Ghi chú</th>
              <th>Đang giữ</th>
              <th className="text-right">Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {kennels.map((k) => {
              const occupied = k.status === 'OCCUPIED';
              return (
                <tr key={k.kennelId}>
                  <td className="whitespace-nowrap font-mono font-semibold">{k.code}</td>
                  <td>{k.kennelTypeName}</td>
                  <td className="whitespace-nowrap">
                    <StatusBadge status={KENNEL_STATUS_LABELS[k.status]} variant={KENNEL_STATUS_TONE[k.status]} />
                  </td>
                  <td className="max-w-64 break-words text-(--text-secondary)">{k.note || '—'}</td>
                  <td>
                    <HeldBy kennel={k} stays={stays} />
                  </td>
                  <td>
                    <div className="flex flex-wrap justify-end gap-1.5">
                      <Button variant="outline" onClick={() => onEdit(k)} aria-label={`Sửa ${k.code}`}>
                        <Pencil className="h-4 w-4" aria-hidden /> Sửa
                      </Button>
                      {k.status === 'MAINTENANCE' ? (
                        <Button variant="outline" disabled={busy} onClick={() => onEndMaintenance(k)} aria-label={`Hết bảo trì ${k.code}`}>
                          <Undo2 className="h-4 w-4" aria-hidden /> Hết bảo trì
                        </Button>
                      ) : (
                        <Button
                          variant="outline"
                          disabled={occupied || busy}
                          title={occupied ? LOCKED_MAINTENANCE : undefined}
                          onClick={() => onStartMaintenance(k)}
                          aria-label={`Bảo trì ${k.code}`}
                        >
                          <Wrench className="h-4 w-4" aria-hidden /> Bảo trì
                        </Button>
                      )}
                      <Button
                        variant="outline"
                        className="border-red-200 text-red-600"
                        disabled={occupied || busy}
                        title={occupied ? LOCKED_DELETE : undefined}
                        onClick={() => onDelete(k)}
                        aria-label={`Xóa ${k.code}`}
                      >
                        <Trash2 className="h-4 w-4" aria-hidden /> Xóa
                      </Button>
                    </div>
                    {occupied && <p className="mt-1 text-right text-xs text-(--text-tertiary)">Chuồng có thú: chưa bảo trì hay xóa được.</p>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </CardContent>
    </Card>
  );
}

function HeldBy({ kennel, stays }: { kennel: Kennel; stays: Map<number, Booking> }) {
  if (kennel.currentBookingId == null) return <span className="text-(--text-tertiary)">—</span>;
  const booking = stays.get(kennel.currentBookingId);
  if (!booking) return <span>Đặt chỗ #{kennel.currentBookingId}</span>;
  return (
    <>
      <span className="whitespace-nowrap font-mono text-xs">{booking.code}</span>
      <span className="block text-xs text-(--text-tertiary)">{booking.petName}</span>
    </>
  );
}
