// UC84: góp ý của khách, chỉ đọc (không trạng thái, không xử lý: ADR-0122). SUPER_MANAGER xem tất cả (lọc được theo chi nhánh),
// BRANCH_MANAGER chỉ chi nhánh mình (BE tự giới hạn, ngầm là chi nhánh của họ nên không có bộ lọc chi nhánh) — content-v1 #20–21,
// BR-DG-02, 03, 05. Giữ nguyên <h1> (route test dựa vào đó).
import { useState } from 'react';
import { AlertTriangle, MessageSquareText } from 'lucide-react';
import { Field, selectClass } from '../../components/admin/form-ui';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useBranches } from '../../shared/hooks/useBranch';
import { useFeedbackList } from '../../shared/hooks/useFeedback';
import { useAccount } from '../../shared/stores/session.store';
import type { PageResponse } from '../../shared/types';
import { FEEDBACK_TOPIC_LABELS, formatFeedbackTime, type Feedback, type FeedbackTopic } from '../../shared/types/feedback';
import { FeedbackDetailModal, RatingStars } from './feedbacks/FeedbackDetailModal';

const PAGE_SIZE = 20;
const TOPICS = Object.keys(FEEDBACK_TOPIC_LABELS) as FeedbackTopic[];
const EXCERPT_LENGTH = 90;

const NO_FILTERS = { topic: '', branchId: '', from: '', to: '' };

/** Một dòng gọn trong bảng; toàn văn nằm ở `title` và ở hộp thoại chi tiết. */
function excerpt(text: string) {
  const letters = [...text.replace(/\s+/g, ' ').trim()];
  return letters.length > EXCERPT_LENGTH ? `${letters.slice(0, EXCERPT_LENGTH).join('').trimEnd()}…` : letters.join('');
}

/** Chỉ SUPER_MANAGER thấy (BRANCH_MANAGER ngầm là chi nhánh mình); danh sách chi nhánh mọi trạng thái. */
function BranchFilter({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const branches = useBranches();
  return (
    <Field label="Chi nhánh">
      {(id) => (
        <select id={id} className={selectClass} value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">Mọi chi nhánh</option>
          {branches.data?.map((b) => (
            <option key={b.branchId} value={b.branchId}>
              {b.name}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}

function Pager({ data, onPage }: { data: PageResponse<Feedback>; onPage: (page: number) => void }) {
  return (
    <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
      <span>
        Trang {data.page + 1}/{data.totalPages} · {data.totalElements} góp ý
      </span>
      <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => onPage(data.page - 1)}>
        Trước
      </Button>
      <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>
        Sau
      </Button>
    </div>
  );
}

export function AdminFeedbacksPage() {
  const chainWide = useAccount()?.role === 'SUPER_MANAGER';
  const [filters, setFilters] = useState(NO_FILTERS);
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);
  const list = useFeedbackList({
    topic: (filters.topic || undefined) as FeedbackTopic | undefined,
    branchId: chainWide && filters.branchId ? Number(filters.branchId) : undefined,
    from: filters.from || undefined,
    to: filters.to || undefined,
    page,
    size: PAGE_SIZE,
  });

  const filtering = Object.values(filters).some(Boolean);
  const setFilter = (key: keyof typeof NO_FILTERS) => (value: string) => {
    setFilters((f) => ({ ...f, [key]: value }));
    setPage(0);
  };
  const clearFilters = () => {
    setFilters(NO_FILTERS);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Góp ý của khách</h1>
        <p className="text-(--text-secondary)">
          {chainWide ? 'Góp ý của khách ở mọi chi nhánh, gồm cả góp ý về website.' : 'Góp ý của khách về chi nhánh của bạn.'} Màn này chỉ để đọc.
        </p>
        <p className="mt-1 text-sm text-(--text-tertiary)">
          Góp ý chỉ lưu trong thời hạn cấu hình (<code>feedback.retention_days</code>, mặc định 30 ngày); quá hạn hệ thống tự xóa, báo cáo
          góp ý chỉ tính trong thời hạn đó.
        </p>
      </div>

      <Card className="card">
        <CardContent className="grid grid-cols-2 items-end gap-3 p-4 sm:grid-cols-3 lg:grid-cols-5">
          <Field label="Chủ đề">
            {(id) => (
              <select id={id} className={selectClass} value={filters.topic} onChange={(e) => setFilter('topic')(e.target.value)}>
                <option value="">Mọi chủ đề</option>
                {TOPICS.map((t) => (
                  <option key={t} value={t}>
                    {FEEDBACK_TOPIC_LABELS[t]}
                  </option>
                ))}
              </select>
            )}
          </Field>
          {chainWide && (
            <div className="col-span-2 sm:col-span-1">
              <BranchFilter value={filters.branchId} onChange={setFilter('branchId')} />
            </div>
          )}
          <Field label="Từ ngày">
            {(id) => <Input id={id} type="date" className="h-10 py-0" max={filters.to || undefined} value={filters.from} onChange={(e) => setFilter('from')(e.target.value)} />}
          </Field>
          <Field label="Đến ngày">
            {(id) => <Input id={id} type="date" className="h-10 py-0" min={filters.from || undefined} value={filters.to} onChange={(e) => setFilter('to')(e.target.value)} />}
          </Field>
          {filtering && (
            <Button variant="outline" className="col-span-2 sm:col-span-1" onClick={clearFilters}>
              Xóa lọc
            </Button>
          )}
        </CardContent>
      </Card>

      <Card className="card">
        <CardContent className="overflow-hidden p-0">
          {list.isPending ? (
            <div role="status" aria-label="Đang tải góp ý" className="divide-y divide-(--border-subtle)">
              {[0, 1, 2, 3, 4].map((i) => (
                <div key={i} className="flex gap-4 px-4 py-4">
                  <div className="h-4 w-28 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" />
                  <div className="h-4 w-32 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" />
                  <div className="h-4 flex-1 rounded bg-(--bg-tertiary) motion-safe:animate-pulse" />
                </div>
              ))}
            </div>
          ) : list.isError ? (
            <div role="alert" className="flex flex-col items-center px-6 py-12 text-center">
              <AlertTriangle className="mb-3 h-8 w-8 text-red-600" aria-hidden />
              <p className="font-semibold text-(--text-primary)">Không tải được danh sách góp ý</p>
              <p className="mt-1 text-sm text-red-700">{plainMessage(toApiError(list.error))}</p>
              <Button className="mt-4" variant="outline" onClick={() => list.refetch()}>
                Thử lại
              </Button>
            </div>
          ) : list.data.content.length === 0 ? (
            <div className="flex flex-col items-center px-6 py-14 text-center">
              <MessageSquareText className="mb-3 h-10 w-10 text-(--text-tertiary)" aria-hidden />
              <p className="font-semibold text-(--text-primary)">{filtering ? 'Không có góp ý nào khớp bộ lọc' : 'Chưa có góp ý nào'}</p>
              <p className="mt-1 max-w-[44ch] text-sm text-(--text-secondary)">
                {filtering ? 'Thử đổi hoặc xóa bộ lọc để xem các góp ý khác.' : 'Góp ý khách gửi sẽ hiện ở đây.'}
              </p>
            </div>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="table [&_td]:px-3! [&_th]:px-3!" aria-label="Danh sách góp ý">
                  <thead>
                    <tr>
                      <th className="whitespace-nowrap">Ngày gửi</th>
                      <th>Khách</th>
                      <th>Chủ đề</th>
                      <th>Chi nhánh</th>
                      <th>Điểm</th>
                      <th>Nội dung</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list.data.content.map((f) => (
                      <tr key={f.feedbackId} onClick={() => setSelected(f.feedbackId)} className="cursor-pointer">
                        <td className="whitespace-nowrap text-xs! text-(--text-secondary)!">{formatFeedbackTime(f.createdAt)}</td>
                        <td className="min-w-28">
                          {/* Phần tử bàn phím vào được; bấm ở đâu trên dòng cũng mở (onClick của <tr>). */}
                          <button
                            type="button"
                            aria-label={`Xem góp ý #${f.feedbackId} của ${f.customerName}`}
                            className="text-left font-medium text-(--text-primary) hover:underline focus-visible:rounded focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                          >
                            {f.customerName}
                          </button>
                        </td>
                        <td className="min-w-28">{FEEDBACK_TOPIC_LABELS[f.topic]}</td>
                        <td className="min-w-28">
                          {f.branchId == null ? (
                            <span className="text-(--text-tertiary)">Không gắn chi nhánh</span>
                          ) : (
                            (f.branchName ?? `Chi nhánh #${f.branchId}`)
                          )}
                        </td>
                        <td className="whitespace-nowrap">
                          {f.rating != null ? <RatingStars rating={f.rating} /> : <span className="text-(--text-tertiary)">Chưa chấm</span>}
                        </td>
                        <td className="min-w-48 max-w-sm text-(--text-secondary)!">
                          <span title={f.content}>{excerpt(f.content)}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {list.data.totalPages > 1 && <Pager data={list.data} onPage={setPage} />}
            </>
          )}
        </CardContent>
      </Card>

      {selected !== null && <FeedbackDetailModal key={selected} feedbackId={selected} onClose={() => setSelected(null)} />}
    </div>
  );
}
