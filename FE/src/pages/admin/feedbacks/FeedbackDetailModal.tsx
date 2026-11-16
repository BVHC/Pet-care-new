// Chi tiết góp ý (UC84, #21): chỉ đọc. Feedback không có trạng thái, ghi chú hay bước xử lý (ADR-0122, V55).
import type { ReactNode } from 'react';
import { Star } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { DetailModal } from '../../../components/ui/modal-templates';
import { cn } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useFeedbackDetail } from '../../../shared/hooks/useFeedback';
import { FEEDBACK_TOPIC_LABELS, formatFeedbackTime } from '../../../shared/types/feedback';

/** Hàng nhãn / giá trị với đường kẻ nhạt (InfoRow dùng chung kẻ đen đậm trên nền trắng của hộp thoại này). */
function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-(--border-subtle) py-1.5 last:border-b-0">
      <span className="text-sm text-(--text-secondary)">{label}</span>
      <span className="text-right text-sm font-medium text-(--text-primary)">{children}</span>
    </div>
  );
}

export function RatingStars({ rating }: { rating: number }) {
  return (
    <span role="img" aria-label={`Mức hài lòng ${rating} trên 5`} className="inline-flex gap-0.5">
      {[1, 2, 3, 4, 5].map((n) => (
        <Star key={n} aria-hidden className={cn('h-3.5 w-3.5', n <= rating ? 'fill-amber-400 text-amber-600' : 'text-gray-300')} />
      ))}
    </span>
  );
}

export function FeedbackDetailModal({ feedbackId, onClose }: { feedbackId: number; onClose: () => void }) {
  const query = useFeedbackDetail(feedbackId);
  const detail = query.data;

  return (
    <DetailModal open onOpenChange={(open) => !open && onClose()} title={`Góp ý #${feedbackId}`} size="xl">
      {query.isError ? (
        <div role="alert" className="space-y-3 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
          <p className="font-medium">{plainMessage(toApiError(query.error))}</p>
          <Button size="sm" variant="outline" onClick={() => query.refetch()}>
            Thử lại
          </Button>
        </div>
      ) : !detail ? (
        <div role="status" aria-label="Đang mở góp ý" className="space-y-3">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-4 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" style={{ width: `${90 - i * 12}%` }} />
          ))}
        </div>
      ) : (
        <>
          <div>
            <Row label="Khách">{detail.customerName}</Row>
            <Row label="Chủ đề">{FEEDBACK_TOPIC_LABELS[detail.topic]}</Row>
            <Row label="Chi nhánh">
              {detail.branchId == null ? 'Không gắn chi nhánh' : (detail.branchName ?? `Chi nhánh #${detail.branchId}`)}
            </Row>
            <Row label="Mức hài lòng">{detail.rating != null ? <RatingStars rating={detail.rating} /> : 'Chưa chấm'}</Row>
            <Row label="Gửi lúc">{formatFeedbackTime(detail.createdAt)}</Row>
          </div>

          <div className="mt-4">
            <p className="mb-1.5 text-sm font-medium text-(--color-text-primary)">Nội dung</p>
            <p className="whitespace-pre-line break-words rounded-lg bg-(--bg-secondary) p-4 text-sm leading-relaxed text-(--color-text-primary)">
              {detail.content}
            </p>
          </div>
        </>
      )}
    </DetailModal>
  );
}
