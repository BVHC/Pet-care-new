import { Button } from '../../../components/ui/button';
import { Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle, StatusBadge } from '../../../components/ui/modal-templates';
import type { PendingWorkItem, PendingWorkType } from '../../../shared/types/identity';

const PENDING_WORK_LABELS: Record<PendingWorkType, string> = {
  VISIT_IN_PROGRESS: 'Lượt đang khám hoặc đang làm',
  VISIT_WAITING: 'Lượt đang chờ',
  CASHIER_SHIFT_OPEN: 'Ca thu ngân đang mở',
  APPOINTMENT_BOOKED: 'Lịch hẹn đã đặt',
  BOARDING_ACTIVE: 'Lưu trú đang diễn ra',
  ORDER_UNPAID: 'Order chưa thanh toán',
};

/** BR-QT-11: sau khi khóa, hiện việc dở của người bị khóa; lượt đã gọi mang nhãn "Cần gán lại" (BR-TN-08). BE đã sắp sẵn (lượt cần gán lại lên đầu). */
export function PendingWorkDialog({ email, items, onClose }: { email: string; items: PendingWorkItem[]; onClose: () => void }) {
  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-lg">
      <ModalHeader onClose={onClose}>
        <ModalTitle>Quy trình dở dang của {email}</ModalTitle>
      </ModalHeader>
      <ModalBody className="space-y-3">
        <p className="text-sm text-(--text-secondary)">
          Tài khoản đã bị khóa. Khóa không hủy các việc dưới đây; cần có người nhận xử lý tiếp. Lượt đã gọi phải được gán lại cho người khác (BR-TN-08).
        </p>
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="text-xs text-(--text-tertiary)">
              <th className="py-1 font-medium">Loại</th>
              <th className="py-1 font-medium">Mã</th>
              <th className="py-1 font-medium">Chi nhánh</th>
              <th className="py-1" />
            </tr>
          </thead>
          <tbody>
            {items.map((w) => (
              <tr key={`${w.type}-${w.entityId}`} className="border-t border-(--border)">
                <td className="py-2">{PENDING_WORK_LABELS[w.type] ?? w.type}</td>
                <td className="py-2">#{w.entityId}</td>
                <td className="py-2">Chi nhánh #{w.branchId}</td>
                <td className="py-2 text-right">{w.needsReassign && <StatusBadge status="Cần gán lại" variant="warning" />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" onClick={onClose}>
          Đóng
        </Button>
      </ModalFooter>
    </Modal>
  );
}
