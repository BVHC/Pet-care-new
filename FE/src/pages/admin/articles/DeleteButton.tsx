import { Trash2 } from 'lucide-react';
import { Button } from '../../../components/ui/button';

const lowerFirst = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);

/**
 * Nút xóa. Có `blockedReason` thì tắt và nói rõ vì sao: nút tắt không nhận chuột (`pointer-events-none`) nên lý do đặt ở `title` của
 * thẻ bọc và ở tên truy cập của nút (đọc được cả khi không rê chuột).
 */
export function DeleteButton({ name, blockedReason, busy, onClick }: { name: string; blockedReason?: string; busy?: boolean; onClick: () => void }) {
  if (blockedReason) {
    return (
      <span title={blockedReason} className="inline-flex">
        <Button size="sm" variant="ghost" disabled aria-label={`Xóa ${name} (${lowerFirst(blockedReason)})`} className="text-red-500">
          <Trash2 className="h-4 w-4" />
        </Button>
      </span>
    );
  }
  return (
    <Button size="sm" variant="ghost" disabled={busy} onClick={onClick} aria-label={`Xóa ${name}`} title="Xóa" className="text-red-500 hover:text-red-600">
      <Trash2 className="h-4 w-4" />
    </Button>
  );
}
