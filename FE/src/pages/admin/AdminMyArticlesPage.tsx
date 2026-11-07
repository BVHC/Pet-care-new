import { useState } from 'react';
import { Edit, Eye, Plus, Send, Undo2 } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { StatusBadge } from '../../components/ui/modal-templates';
import { NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { cn } from '../../lib/utils';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { articlexApi } from '../../shared/api/articlex.api';
import { useArticlexMutation, useMyArticles } from '../../shared/hooks/useArticlex';
import { usePublicArticleCategories } from '../../shared/hooks/usePublicSite';
import { ARTICLE_REVIEW_STATUS_LABELS, type ArticleReviewStatus, type MyArticleSummary } from '../../shared/types/articlex';
import { formatDateTimeVn } from './articles/format';
import { MyArticleDetail, REVIEW_STATUS_VARIANT } from './articles/MyArticleDetail';
import { MyArticleEditor } from './articles/MyArticleEditor';
import { TableState } from './articles/TableState';

const PAGE_SIZE = 20;
const STATUSES = Object.keys(ARTICLE_REVIEW_STATUS_LABELS) as ArticleReviewStatus[];
/** BR-BV-07: chỉ DRAFT / REJECTED sửa được; PUBLISHED / HIDDEN muốn đổi thì nhờ quản lý ẩn và soạn bài mới. */
const EDITABLE: ArticleReviewStatus[] = ['DRAFT', 'REJECTED'];

/** UC80 — bài viết của tôi (VET): soạn nháp, sửa, gửi duyệt, rút về nháp; bài bị từ chối hiện lý do. */
export function AdminMyArticlesPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const list = useMyArticles({ status: (status || undefined) as ArticleReviewStatus | undefined, page, size: PAGE_SIZE });
  // VET không đọc được /article-categories của content-v1 (chỉ SUPER_MANAGER): dùng danh sách chuyên mục công khai (chỉ chuyên mục đang hiện).
  const categories = usePublicArticleCategories();
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [viewing, setViewing] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const submit = useArticlexMutation((a: MyArticleSummary) => articlexApi.submit(a.articleId));
  const withdraw = useArticlexMutation((a: MyArticleSummary) => articlexApi.withdraw(a.articleId));
  const busy = submit.isPending || withdraw.isPending;
  const data = list.data;

  const run = async (action: () => Promise<unknown>, success: string) => {
    setNotice(null);
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Bài viết của tôi</h1>
        <p className="text-(--text-secondary)">Soạn bài, gửi quản lý duyệt rồi mới hiện trên trang tin tức. Bài đang chờ duyệt hoặc đã xuất bản không sửa được.</p>
      </div>

      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <select
            aria-label="Lọc trạng thái"
            className={cn(selectClass, 'sm:w-48')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Tất cả trạng thái</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {ARTICLE_REVIEW_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <Button className="ml-auto" onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Soạn bài mới
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table" aria-label="Bài viết của tôi">
            <thead>
              <tr>
                <th className="px-3!">Tiêu đề</th>
                <th className="px-3!">Trạng thái</th>
                <th className="px-3!">Cập nhật</th>
                <th className="px-3!" style={{ textAlign: 'right' }}>
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((a) => (
                  <tr key={a.articleId}>
                    <td className="max-w-md px-3! font-medium text-(--text-primary)">{a.title}</td>
                    <td className="whitespace-nowrap px-3!">
                      <StatusBadge status={ARTICLE_REVIEW_STATUS_LABELS[a.status]} variant={REVIEW_STATUS_VARIANT[a.status]} />
                    </td>
                    <td className="whitespace-nowrap px-3!">
                      <p>{formatDateTimeVn(a.updatedAt)}</p>
                      {a.status === 'PENDING_REVIEW' && a.submittedAt && <p className="text-xs text-(--text-tertiary)">Gửi duyệt {formatDateTimeVn(a.submittedAt)}</p>}
                    </td>
                    <td className="px-3!">
                      <div className="flex items-center justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setViewing(a.articleId)} aria-label={`${a.status === 'REJECTED' ? 'Xem lý do từ chối' : 'Xem'} ${a.title}`} title={a.status === 'REJECTED' ? 'Xem lý do từ chối' : 'Xem'}>
                          <Eye className="h-4 w-4" />
                        </Button>
                        {EDITABLE.includes(a.status) && (
                          <Button size="sm" variant="ghost" onClick={() => setEditing(a.articleId)} aria-label={`Sửa ${a.title}`} title="Sửa">
                            <Edit className="h-4 w-4" />
                          </Button>
                        )}
                        {a.status === 'DRAFT' && (
                          <Button size="sm" variant="outline" disabled={busy} aria-label={`Gửi duyệt ${a.title}`} onClick={() => run(() => submit.mutateAsync(a), `Đã gửi duyệt “${a.title}”.`)}>
                            <Send className="h-3.5 w-3.5" />
                            Gửi duyệt
                          </Button>
                        )}
                        {a.status === 'PENDING_REVIEW' && (
                          <Button size="sm" variant="outline" disabled={busy} aria-label={`Rút ${a.title}`} onClick={() => run(() => withdraw.mutateAsync(a), `Đã rút “${a.title}” về nháp.`)}>
                            <Undo2 className="h-3.5 w-3.5" />
                            Rút về nháp
                          </Button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <TableState colSpan={4} loading={list.isLoading} error={list.isError ? plainMessage(toApiError(list.error)) : undefined} onRetry={() => list.refetch()}>
                  {status ? 'Không có bài nào ở trạng thái này.' : 'Bạn chưa có bài viết nào. Bài đầu tiên sẽ được lưu thành bản nháp.'}
                </TableState>
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} bài
              </span>
              <Button size="sm" variant="outline" disabled={data.page === 0} onClick={() => setPage(data.page - 1)}>
                Trước
              </Button>
              <Button size="sm" variant="outline" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
                Sau
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {viewing !== null && (
        <MyArticleDetail
          articleId={viewing}
          onClose={() => setViewing(null)}
          onEdit={() => {
            setEditing(viewing);
            setViewing(null);
          }}
        />
      )}
      {editing !== null && (
        <MyArticleEditor
          articleId={editing}
          categories={categories.data ?? []}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}
