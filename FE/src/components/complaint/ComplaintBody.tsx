import { formatVnDateTime } from '@/components/appointments/format';
import { InfoRow, StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import type { Complaint, ComplaintMessage } from '@/shared/types/complaint';
import { COMPLAINT_CLOSED_LABELS, COMPLAINT_RELATED_LABELS, COMPLAINT_STATUS_LABELS, COMPLAINT_STATUS_TONE, COMPLAINT_TOPIC_LABELS } from './labels';

const AUTHOR_FALLBACK = { CUSTOMER: 'Khách hàng', STAFF: 'Nhân viên', SYSTEM: 'Hệ thống' } as const;
const BUBBLE = { CUSTOMER: 'bg-amber-50', STAFF: 'bg-blue-50', SYSTEM: 'bg-gray-100' } as const;

/** Vết trao đổi, cũ nhất trước: dòng chuyển trạng thái (STATUS) là chú thích nhỏ, tin nhắn là khung có tên người gửi. */
export function ComplaintThread({ messages }: { messages: ComplaintMessage[] }) {
  return (
    <ol aria-label="Trao đổi" className="space-y-2">
      {messages.map((m) =>
        m.kind === 'STATUS' ? (
          <li key={m.messageId} className="text-center text-xs text-(--text-tertiary)">
            {m.body} · {formatVnDateTime(m.createdAt)}
          </li>
        ) : (
          <li key={m.messageId} className={cn('rounded-lg px-3 py-2 text-sm text-(--text-primary)', BUBBLE[m.authorType])}>
            <p className="mb-0.5 text-xs font-semibold text-(--text-secondary)">
              {m.authorName ?? AUTHOR_FALLBACK[m.authorType]} · {formatVnDateTime(m.createdAt)}
            </p>
            <p className="whitespace-pre-line">{m.body}</p>
          </li>
        ),
      )}
    </ol>
  );
}

interface Props {
  complaint: Complaint;
  /** Tên chi nhánh đã tra; vắng thì ghi theo mã. */
  branchName?: string;
  /** Phía nhân viên thấy thêm khách và cờ leo thang. */
  staffView?: boolean;
}

/** Thông tin + nội dung + kết luận + vết trao đổi của một khiếu nại; nút thao tác do trang đặt bên dưới. */
export function ComplaintBody({ complaint: c, branchName, staffView }: Props) {
  const branch = c.branchId == null ? 'Không gắn chi nhánh' : (branchName ?? `Chi nhánh #${c.branchId}`);
  return (
    <div className="space-y-4">
      <div>
        <InfoRow label="Trạng thái" value={<StatusBadge status={COMPLAINT_STATUS_LABELS[c.status]} variant={COMPLAINT_STATUS_TONE[c.status]} />} />
        {staffView && c.escalatedAt && <InfoRow label="Quá hạn" value={<StatusBadge status="Đã leo thang lên quản lý chuỗi" variant="danger" />} />}
        <InfoRow label="Chủ đề" value={COMPLAINT_TOPIC_LABELS[c.topic]} />
        <InfoRow label="Chi nhánh" value={branch} />
        {staffView && <InfoRow label="Khách hàng" value={c.createdByStaff ? `${c.customerName} (lễ tân tạo thay)` : c.customerName} />}
        {c.relatedType && c.relatedId != null && <InfoRow label="Liên quan" value={`${COMPLAINT_RELATED_LABELS[c.relatedType]} #${c.relatedId}`} />}
        <InfoRow label="Người xử lý" value={c.handlerName ?? 'Chưa có'} />
        <InfoRow label="Gửi lúc" value={formatVnDateTime(c.createdAt)} />
        {c.closedAt && (
          <InfoRow label="Đóng lúc" value={`${formatVnDateTime(c.closedAt)}${c.closedReason ? ` · ${COMPLAINT_CLOSED_LABELS[c.closedReason]}` : ''}`} />
        )}
        {c.reopenCount > 0 && <InfoRow label="Số lần mở lại" value={c.reopenCount} />}
      </div>
      <section aria-label="Nội dung khiếu nại">
        <h3 className="mb-1 text-sm font-semibold text-(--text-primary)">Nội dung</h3>
        <p className="whitespace-pre-line text-sm text-(--text-primary)">{c.content}</p>
      </section>
      {c.resolution && (
        <section aria-label="Kết luận" className="rounded-lg bg-green-50 px-3 py-2">
          <h3 className="mb-1 text-sm font-semibold text-green-800">Kết luận</h3>
          <p className="whitespace-pre-line text-sm text-green-900">{c.resolution}</p>
        </section>
      )}
      <ComplaintThread messages={c.messages} />
    </div>
  );
}
