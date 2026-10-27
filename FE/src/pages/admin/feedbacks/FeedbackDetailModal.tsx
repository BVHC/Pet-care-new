// Chi tiết góp ý (UC84, #21–22). Mở chi tiết đã ghi dữ liệu NEW → SEEN ở trang (mutation bắn từ thao tác bấm dòng); ở đây chỉ hiện
// kết quả. Chỉ feedback SEEN mới có ô ghi chú và nút "Đánh dấu đã xử lý" (resolve khi NEW hay đã RESOLVED đều 409); RESOLVED chỉ đọc.
import { useState, type FormEvent, type ReactNode } from 'react';
import { Star } from 'lucide-react';
import { Field, FormError } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { DetailModal, StatusBadge } from '../../../components/ui/modal-templates';
import { cn } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useResolveFeedback } from '../../../shared/hooks/useFeedback';
import { useAccount } from '../../../shared/stores/session.store';
import {
  FEEDBACK_STATUS_LABELS,
  FEEDBACK_STATUS_VARIANT,
  FEEDBACK_TOPIC_LABELS,
  RESOLUTION_NOTE_MAX,
  formatFeedbackTime,
  type Feedback,
} from '../../../shared/types/feedback';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';

/** Hàng nhãn / giá trị với đường kẻ nhạt (InfoRow dùng chung kẻ đen đậm trên nền trắng của hộp thoại này). */
function Row({ label, children, rule = 'border-(--border-subtle)' }: { label: string; children: ReactNode; rule?: string }) {
  return (
    <div className={cn('flex items-center justify-between gap-4 border-b py-1.5 last:border-b-0', rule)}>
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

interface Props {
  feedbackId: number;
  /** Kết quả của lần mở (#21); chưa có thì đang chờ. */
  feedback: Feedback | undefined;
  /** Lỗi của lần mở (404 / 403 …). */
  error: unknown;
  onRetry: () => void;
  onClose: () => void;
  onResolved: (resolved: Feedback) => void;
}

export function FeedbackDetailModal({ feedbackId, feedback, error, onRetry, onClose, onResolved }: Props) {
  const viewerId = useAccount()?.id;
  const resolve = useResolveFeedback();
  const [resolved, setResolved] = useState<Feedback | null>(null);
  const [note, setNote] = useState('');
  const [failure, setFailure] = useState('');
  const detail = resolved ?? feedback;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setFailure('');
    try {
      const result = await resolve.mutateAsync({ feedbackId, resolutionNote: note });
      setResolved(result);
      onResolved(result);
    } catch (err) {
      setFailure(plainMessage(toApiError(err)));
    }
  };

  return (
    <DetailModal open onOpenChange={(open) => !open && onClose()} title={`Góp ý #${feedbackId}`} size="xl">
      {error ? (
        <div role="alert" className="space-y-3 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
          <p className="font-medium">{plainMessage(toApiError(error))}</p>
          <Button size="sm" variant="outline" onClick={onRetry}>
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
            <Row label="Trạng thái">
              <StatusBadge status={FEEDBACK_STATUS_LABELS[detail.status]} variant={FEEDBACK_STATUS_VARIANT[detail.status]} />
            </Row>
            {detail.seenAt && <Row label="Xem lần đầu">{formatFeedbackTime(detail.seenAt)}</Row>}
          </div>

          <div className="mt-4">
            <p className="mb-1.5 text-sm font-medium text-(--color-text-primary)">Nội dung</p>
            <p className="whitespace-pre-line break-words rounded-lg bg-(--bg-secondary) p-4 text-sm leading-relaxed text-(--color-text-primary)">
              {detail.content}
            </p>
          </div>

          {detail.status === 'SEEN' && (
            <form onSubmit={onSubmit} noValidate className="mt-5 space-y-3 border-t border-(--color-border-default) pt-5">
              <Field label="Ghi chú xử lý" hint="Ghi chú nội bộ, bắt buộc; khách không thấy.">
                {(id) => (
                  <>
                    <textarea
                      id={id}
                      rows={3}
                      maxLength={RESOLUTION_NOTE_MAX}
                      value={note}
                      onChange={(e) => {
                        setNote(e.target.value);
                        setFailure('');
                      }}
                      className={textareaClass}
                    />
                    <p className="mt-1 text-right text-xs tabular-nums text-(--text-tertiary)">
                      {note.length}/{RESOLUTION_NOTE_MAX}
                    </p>
                  </>
                )}
              </Field>
              <FormError message={failure} />
              <div className="flex justify-end">
                <Button type="submit" disabled={resolve.isPending}>
                  {resolve.isPending ? 'Đang lưu…' : 'Đánh dấu đã xử lý'}
                </Button>
              </div>
            </form>
          )}

          {detail.status === 'RESOLVED' && (
            <section aria-label="Kết quả xử lý" className="mt-5 rounded-lg border border-green-200 bg-green-50 p-4">
              <h3 className="mb-1 text-sm font-semibold text-green-800">Kết quả xử lý</h3>
              {detail.resolvedBy != null && (
                <Row label="Người xử lý" rule="border-green-200">
                  {detail.resolvedBy === viewerId ? 'Bạn' : `Tài khoản #${detail.resolvedBy}`}
                </Row>
              )}
              {detail.resolvedAt && (
                <Row label="Xử lý lúc" rule="border-green-200">
                  {formatFeedbackTime(detail.resolvedAt)}
                </Row>
              )}
              <p className="mt-3 text-xs font-medium text-(--color-text-secondary)">Ghi chú xử lý (nội bộ)</p>
              <p className="mt-1 whitespace-pre-line break-words text-sm leading-relaxed text-(--color-text-primary)">{detail.resolutionNote}</p>
            </section>
          )}
        </>
      )}
    </DetailModal>
  );
}
