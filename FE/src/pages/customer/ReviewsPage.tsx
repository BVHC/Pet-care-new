/**
 * Góp ý của tôi (UC83, A02): gửi góp ý và xem lại góp ý đã gửi — content-v1 #18–19, BR-DG-01, 02, 04.
 * Route nằm sau RequireLinkDecision: hồ sơ còn chờ quyết định liên kết không vào được trang này (BR-TK-19).
 * Góp ý không công khai, không sửa, không xóa; khách chỉ thấy trạng thái, không bao giờ thấy ghi chú nội bộ (BR-DG-04).
 * Mọi luật kiểm ở BE: form gửi nguyên văn rồi hiện câu báo lỗi của BE ngay trên nút gửi, không lặp lại luật ở client.
 */
import { useState, type FormEvent } from 'react';
import { AlertTriangle, MessageSquareText, Star } from 'lucide-react';
import { Field, NoticeBar, type Notice } from '@/components/admin/form-ui';
import { StatusBadge } from '@/components/ui/modal-templates';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { usePublicBranches } from '@/shared/hooks/useAppointments';
import { useMyFeedbacks, useSubmitFeedback } from '@/shared/hooks/useFeedback';
import {
  FEEDBACK_CONTENT_MAX,
  FEEDBACK_CONTENT_MIN,
  FEEDBACK_STATUS_LABELS,
  FEEDBACK_STATUS_VARIANT,
  FEEDBACK_TOPIC_LABELS,
  feedbackContentLength,
  formatFeedbackTime,
  type FeedbackTopic,
  type MyFeedback,
} from '@/shared/types/feedback';

const TOPICS = Object.keys(FEEDBACK_TOPIC_LABELS) as FeedbackTopic[];
const RATING_WORDS = ['Rất kém', 'Kém', 'Trung bình', 'Tốt', 'Xuất sắc'];

/** Cùng ngôn ngữ với form ở trang Tài khoản: bo 10px, viền be 1.5px, nền kem, vòng focus nâu đỏ. */
const inputClass =
  'w-full rounded-[10px] border-[1.5px] border-(--color-border-default) bg-(--color-surface-page) px-3.5 py-2.5 text-sm text-(--color-text-primary) outline-none transition-[border-color,box-shadow] placeholder:text-(--color-text-secondary) focus:border-accent focus:ring-[3px] focus:ring-(--color-accent-soft)';
const cardClass = 'rounded-[18px] bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]';
const legendClass = 'mb-1.5 block text-sm font-medium text-(--text-primary)';

export function ReviewsPage() {
  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Ý kiến khách hàng"
        title="Góp ý của tôi"
        subtitle="Chia sẻ trải nghiệm của bạn. Góp ý không hiển thị công khai, chỉ quản lý được xem."
        breadcrumbs={[{ label: 'Góp ý của tôi' }]}
      />
      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)] lg:items-start">
          <FeedbackForm />
          <FeedbackHistory />
        </div>
      </div>
    </div>
  );
}

function FeedbackForm() {
  const branches = usePublicBranches();
  const submit = useSubmitFeedback();
  const [topic, setTopic] = useState<FeedbackTopic>('BRANCH_SERVICE');
  const [branchId, setBranchId] = useState('');
  const [rating, setRating] = useState(0);
  const [content, setContent] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);
  const length = feedbackContentLength(content);

  /** Sửa gì cũng bỏ thông báo cũ (thành công hay lỗi) để khỏi đứng nhầm chỗ. */
  const edit =
    <T,>(set: (value: T) => void) =>
    (value: T) => {
      set(value);
      setNotice(null);
    };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setNotice(null);
    try {
      await submit.mutateAsync({
        topic,
        content,
        ...(branchId ? { branchId: Number(branchId) } : {}),
        ...(rating ? { rating } : {}),
      });
      setTopic('BRANCH_SERVICE');
      setBranchId('');
      setRating(0);
      setContent('');
      setNotice({ kind: 'success', text: 'Đã gửi góp ý. Cảm ơn bạn đã chia sẻ.' });
    } catch (err) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(err)) });
    }
  };

  return (
    <section aria-labelledby="feedback-form-title" className={cn(cardClass, 'p-5 sm:p-6')}>
      <h2 id="feedback-form-title" className="font-friendly text-[18px] font-extrabold text-(--color-text-primary)">
        Gửi góp ý mới
      </h2>
      <p className="mt-1.5 text-[13px] leading-relaxed text-(--color-text-secondary)">
        Góp ý không hiển thị công khai. Chỉ quản lý PetCare xem được; bạn xem lại được nhưng không sửa hay xóa được.
      </p>

      <form onSubmit={onSubmit} noValidate className="mt-5 space-y-5">
        <fieldset>
          <legend className={legendClass}>Chủ đề</legend>
          <div className="flex flex-wrap gap-2">
            {TOPICS.map((t) => (
              <label key={t} className="cursor-pointer">
                <input
                  type="radio"
                  name="topic"
                  value={t}
                  checked={topic === t}
                  onChange={() => edit(setTopic)(t)}
                  className="peer sr-only"
                />
                <span className="inline-flex min-h-11 items-center rounded-full border-[1.5px] border-(--color-border-default) bg-white px-4 text-[13px] font-bold text-(--color-text-secondary) transition-colors hover:border-accent peer-checked:border-accent peer-checked:bg-(--color-accent-soft) peer-checked:text-accent peer-focus-visible:ring-[3px] peer-focus-visible:ring-(--color-accent-soft)">
                  {FEEDBACK_TOPIC_LABELS[t]}
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        <Field label="Chi nhánh" hint="Bắt buộc khi góp ý về dịch vụ tại chi nhánh.">
          {(id) => (
            <>
              <select id={id} value={branchId} onChange={(e) => edit(setBranchId)(e.target.value)} className={inputClass}>
                <option value="">{branches.isPending ? 'Đang tải chi nhánh…' : 'Chọn chi nhánh'}</option>
                {branches.data?.map((b) => (
                  <option key={b.branchId} value={b.branchId}>
                    {b.name}
                  </option>
                ))}
              </select>
              {branches.isError && (
                <p className="mt-1 text-xs font-medium text-red-600">
                  Không tải được danh sách chi nhánh.{' '}
                  <button type="button" onClick={() => branches.refetch()} className="font-bold underline">
                    Thử lại
                  </button>
                </p>
              )}
            </>
          )}
        </Field>

        <fieldset>
          <legend className={legendClass}>
            Mức hài lòng <span className="font-normal text-(--color-text-secondary)">(không bắt buộc)</span>
          </legend>
          <div className="flex flex-wrap items-center gap-x-1">
            {RATING_WORDS.map((word, i) => (
              <label key={word} className="cursor-pointer p-1.5">
                <input
                  type="radio"
                  name="rating"
                  value={i + 1}
                  checked={rating === i + 1}
                  onChange={() => edit(setRating)(i + 1)}
                  aria-label={`${i + 1} sao`}
                  className="peer sr-only"
                />
                <Star
                  aria-hidden
                  className={cn(
                    'h-7 w-7 transition-colors peer-focus-visible:rounded peer-focus-visible:ring-[3px] peer-focus-visible:ring-(--color-accent-soft)',
                    i < rating ? 'fill-amber-400 text-amber-600' : 'text-(--color-border-strong) hover:text-amber-600',
                  )}
                />
              </label>
            ))}
            {rating > 0 && (
              <>
                <span className="ml-2 text-[13px] font-bold text-amber-700">{RATING_WORDS[rating - 1]}</span>
                <button
                  type="button"
                  onClick={() => edit(setRating)(0)}
                  className="ml-auto text-xs font-semibold text-(--color-text-secondary) underline underline-offset-2 hover:text-accent"
                >
                  Bỏ chọn
                </button>
              </>
            )}
          </div>
        </fieldset>

        <Field label="Nội dung góp ý">
          {(id) => (
            <>
              <textarea
                id={id}
                rows={5}
                value={content}
                onChange={(e) => edit(setContent)(e.target.value)}
                aria-describedby={`${id}-help`}
                placeholder="Bạn hài lòng điều gì, hoặc PetCare cần cải thiện điều gì?"
                className={cn(inputClass, 'min-h-32 resize-y leading-relaxed')}
              />
              <div id={`${id}-help`} className="mt-1.5 flex items-start justify-between gap-3 text-xs text-(--color-text-secondary)">
                <span>Tối thiểu {FEEDBACK_CONTENT_MIN} ký tự.</span>
                <span className={cn('shrink-0 tabular-nums', length > FEEDBACK_CONTENT_MAX && 'font-bold text-red-600')}>
                  {length}/{FEEDBACK_CONTENT_MAX}
                </span>
              </div>
            </>
          )}
        </Field>

        <NoticeBar notice={notice} />

        <button
          type="submit"
          disabled={submit.isPending}
          className="inline-flex w-full items-center justify-center gap-2 rounded-[10px] bg-accent px-6 py-[11px] text-sm font-bold text-white shadow-[0_8px_18px_-10px_rgba(164,51,36,0.7)] transition hover:-translate-y-px hover:bg-(--color-accent-hover) active:scale-[0.985] disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto"
        >
          {submit.isPending ? 'Đang gửi…' : 'Gửi góp ý'}
        </button>
      </form>
    </section>
  );
}

function FeedbackHistory() {
  const feedbacks = useMyFeedbacks();
  const branches = usePublicBranches();
  const branchName = (id?: number | null) => (id == null ? undefined : branches.data?.find((b) => b.branchId === id)?.name);

  return (
    <section aria-labelledby="feedback-history-title">
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 id="feedback-history-title" className="font-friendly text-[18px] font-extrabold text-(--color-text-primary)">
          Góp ý đã gửi
        </h2>
        {feedbacks.data && feedbacks.data.length > 0 && (
          <span className="text-[13px] text-(--color-text-secondary)">{feedbacks.data.length} góp ý</span>
        )}
      </div>

      {feedbacks.isPending ? (
        <div role="status" aria-label="Đang tải góp ý" className={cn(cardClass, 'divide-y divide-(--color-border-default) overflow-hidden')}>
          {[0, 1, 2].map((i) => (
            <div key={i} className="space-y-3 px-5 py-4 sm:px-6">
              <div className="h-4 w-2/5 rounded bg-(--color-surface-2) motion-safe:animate-pulse" />
              <div className="h-3 w-1/4 rounded bg-(--color-surface-2) motion-safe:animate-pulse" />
              <div className="h-3.5 w-full rounded bg-(--color-surface-2) motion-safe:animate-pulse" />
              <div className="h-3.5 w-4/5 rounded bg-(--color-surface-2) motion-safe:animate-pulse" />
            </div>
          ))}
        </div>
      ) : feedbacks.isError ? (
        <div role="alert" className="flex flex-col items-center rounded-[18px] border border-red-200 bg-red-50 px-6 py-12 text-center">
          <AlertTriangle className="mb-3 h-8 w-8 text-red-600" aria-hidden />
          <p className="font-bold text-red-800">Không tải được góp ý</p>
          <p className="mt-1 text-sm text-red-700">{plainMessage(toApiError(feedbacks.error))}</p>
          <button
            type="button"
            onClick={() => feedbacks.refetch()}
            className="mt-4 rounded-[10px] bg-accent px-5 py-2 text-sm font-bold text-white transition hover:bg-(--color-accent-hover) active:scale-[0.985]"
          >
            Thử lại
          </button>
        </div>
      ) : feedbacks.data.length === 0 ? (
        <div className="flex flex-col items-center rounded-[18px] border border-dashed border-(--color-border-default) bg-white px-6 py-14 text-center">
          <MessageSquareText className="mb-3 h-10 w-10 text-(--color-text-secondary) opacity-40" aria-hidden />
          <p className="font-bold text-(--color-text-primary)">Bạn chưa gửi góp ý nào</p>
          <p className="mt-1 max-w-[36ch] text-sm text-(--color-text-secondary)">Góp ý đã gửi sẽ hiện ở đây cùng trạng thái xử lý.</p>
        </div>
      ) : (
        <ul aria-labelledby="feedback-history-title" className={cn(cardClass, 'divide-y divide-(--color-border-default) overflow-hidden')}>
          {feedbacks.data.map((f) => (
            <FeedbackItem key={f.feedbackId} feedback={f} branchName={branchName(f.branchId)} />
          ))}
        </ul>
      )}
    </section>
  );
}

function FeedbackItem({ feedback: f, branchName }: { feedback: MyFeedback; branchName?: string }) {
  return (
    <li className="px-5 py-4 sm:px-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="text-[13px] font-bold text-(--color-text-primary)">{FEEDBACK_TOPIC_LABELS[f.topic]}</span>
        {branchName && <span className="text-[13px] text-(--color-text-secondary)">{branchName}</span>}
        <span className="ml-auto">
          <StatusBadge status={FEEDBACK_STATUS_LABELS[f.status]} variant={FEEDBACK_STATUS_VARIANT[f.status]} />
        </span>
      </div>
      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-(--color-text-secondary)">
        {f.rating != null && (
          <span role="img" aria-label={`Mức hài lòng ${f.rating} trên 5`} className="inline-flex gap-0.5">
            {[1, 2, 3, 4, 5].map((n) => (
              <Star
                key={n}
                aria-hidden
                className={cn('h-3.5 w-3.5', n <= f.rating! ? 'fill-amber-400 text-amber-600' : 'text-(--color-border-strong)')}
              />
            ))}
          </span>
        )}
        <time dateTime={f.createdAt}>{formatFeedbackTime(f.createdAt)}</time>
      </div>
      <p className="mt-2.5 whitespace-pre-line break-words text-sm leading-relaxed text-(--color-text-primary)">{f.content}</p>
    </li>
  );
}
