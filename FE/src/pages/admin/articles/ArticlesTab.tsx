import { useState } from 'react';
import { Edit, EyeOff, Plus, Search, Send } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { ConfirmModal, StatusBadge } from '../../../components/ui/modal-templates';
import { cn } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { contentApi } from '../../../shared/api/content.api';
import { useArticleCategories, useArticleList, useArticleMutation } from '../../../shared/hooks/useArticles';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { ARTICLE_STATUS_LABELS, type ArticleStatus, type ArticleSummary } from '../../../shared/types/content';
import { formatPublishedDate } from '../../../shared/utils/public-format';
import { NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { ArticleEditor } from './ArticleEditor';
import { DeleteButton } from './DeleteButton';
import { formatDateTimeVn } from './format';
import { TableState } from './TableState';

const PAGE_SIZE = 20;
const STATUSES = Object.keys(ARTICLE_STATUS_LABELS) as ArticleStatus[];
const STATUS_VARIANT = { DRAFT: 'neutral', PUBLISHED: 'success', HIDDEN: 'warning' } as const;

/** UC81 — bài viết: lọc, soạn, sửa, đăng / ẩn, xóa bài chưa từng xuất bản (BR-BV-01, 02). */
export function ArticlesTab() {
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [status, setStatus] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [page, setPage] = useState(0);
  const list = useArticleList({
    status: (status || undefined) as ArticleStatus | undefined,
    categoryId: categoryId ? Number(categoryId) : undefined,
    q: search || undefined,
    page,
    size: PAGE_SIZE,
  });
  const categories = useArticleCategories();
  const [editing, setEditing] = useState<number | 'new' | null>(null);
  const [deleting, setDeleting] = useState<ArticleSummary | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const publish = useArticleMutation((a: ArticleSummary) => contentApi.publishArticle(a.articleId));
  const hide = useArticleMutation((a: ArticleSummary) => contentApi.hideArticle(a.articleId));
  const remove = useArticleMutation((a: ArticleSummary) => contentApi.deleteArticle(a.articleId));
  const busy = publish.isPending || hide.isPending || remove.isPending;

  const categoryName = (id: number) => categories.data?.find((c) => c.categoryId === id)?.name ?? `#${id}`;
  const filter = (set: (v: string) => void) => (v: string) => {
    set(v);
    setPage(0);
  };

  const run = async (action: () => Promise<unknown>, success: string) => {
    setNotice(null);
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  const confirmDelete = async () => {
    const target = deleting!;
    setDeleting(null);
    await run(() => remove.mutateAsync(target), `Đã xóa bài “${target.title}”`);
  };

  const data = list.data;
  const filtered = !!(status || categoryId || search);

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <div className="relative min-w-[220px] flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" />
            <Input
              aria-label="Tìm theo tiêu đề"
              placeholder="Tìm theo tiêu đề…"
              className="pl-10"
              maxLength={100}
              value={q}
              onChange={(e) => filter(setQ)(e.target.value)}
            />
          </div>
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'sm:w-44')} value={status} onChange={(e) => filter(setStatus)(e.target.value)}>
            <option value="">Tất cả trạng thái</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {ARTICLE_STATUS_LABELS[s]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc chuyên mục" className={cn(selectClass, 'sm:w-56')} value={categoryId} onChange={(e) => filter(setCategoryId)(e.target.value)}>
            <option value="">Mọi chuyên mục</option>
            {categories.data?.map((c) => (
              <option key={c.categoryId} value={c.categoryId}>
                {c.isHidden ? `${c.name} (ẩn)` : c.name}
              </option>
            ))}
          </select>
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Soạn bài mới
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table">
            <thead>
              <tr>
                <th className="px-3!">Tiêu đề</th>
                <th className="hidden px-3! md:table-cell">Chuyên mục</th>
                <th className="hidden px-3! md:table-cell">Trạng thái</th>
                <th className="hidden px-3! md:table-cell">Cập nhật</th>
                <th className="px-2! text-right sm:px-3!" style={{ textAlign: 'right' }}>
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((a) => (
                  <tr key={a.articleId}>
                    <td className="px-3!">
                      <div className="max-w-xs xl:max-w-md">
                        <p className="line-clamp-3 font-medium text-(--text-primary)">{a.title}</p>
                        <p className="mt-0.5 break-all font-mono text-xs text-(--text-tertiary) md:max-w-sm md:truncate md:break-normal">{a.slug}</p>
                        {/* Màn hẹp ẩn các cột phụ: trạng thái, chuyên mục, ngày cập nhật gộp thành một dòng dưới tiêu đề */}
                        <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-(--text-secondary) md:hidden">
                          <StatusBadge status={ARTICLE_STATUS_LABELS[a.status]} variant={STATUS_VARIANT[a.status]} />
                          <span>
                            {categoryName(a.categoryId)} · {formatDateTimeVn(a.updatedAt)}
                          </span>
                        </div>
                      </div>
                    </td>
                    <td className="hidden min-w-32 px-3! text-(--text-secondary)! md:table-cell">{categoryName(a.categoryId)}</td>
                    <td className="hidden whitespace-nowrap px-3! md:table-cell">
                      <StatusBadge status={ARTICLE_STATUS_LABELS[a.status]} variant={STATUS_VARIANT[a.status]} />
                    </td>
                    <td className="hidden whitespace-nowrap px-3! md:table-cell">
                      <p>{formatDateTimeVn(a.updatedAt)}</p>
                      <p className="text-xs text-(--text-tertiary)">
                        {a.firstPublishedAt ? `Đăng lần đầu ${formatPublishedDate(a.firstPublishedAt)}` : 'Chưa xuất bản'}
                      </p>
                    </td>
                    <td className="px-2! sm:px-3!">
                      <div className="flex items-center justify-end gap-1">
                        {a.status === 'PUBLISHED' ? (
                          <Button
                            size="sm"
                            variant="outline"
                            className="sm:min-w-[5.5rem]"
                            disabled={busy}
                            onClick={() => run(() => hide.mutateAsync(a), `Đã ẩn “${a.title}” khỏi trang tin tức`)}
                            aria-label={`Ẩn ${a.title}`}
                            title="Ẩn khỏi trang tin tức"
                          >
                            <EyeOff className="h-3.5 w-3.5" />
                            <span className="hidden sm:inline">Ẩn</span>
                          </Button>
                        ) : (
                          <Button
                            size="sm"
                            variant="outline"
                            className="sm:min-w-[5.5rem]"
                            disabled={busy}
                            onClick={() => run(() => publish.mutateAsync(a), `Đã đăng “${a.title}” lên trang tin tức`)}
                            aria-label={`${a.firstPublishedAt ? 'Đăng lại' : 'Đăng'} ${a.title}`}
                            title="Đăng lên trang tin tức"
                          >
                            <Send className="h-3.5 w-3.5" />
                            <span className="hidden sm:inline">{a.firstPublishedAt ? 'Đăng lại' : 'Đăng'}</span>
                          </Button>
                        )}
                        <Button size="sm" variant="ghost" onClick={() => setEditing(a.articleId)} aria-label={`Sửa ${a.title}`} title="Sửa">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <DeleteButton
                          name={a.title}
                          busy={busy}
                          blockedReason={a.firstPublishedAt ? 'Đã từng xuất bản, chỉ ẩn được' : undefined}
                          onClick={() => setDeleting(a)}
                        />
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <TableState
                  colSpan={6}
                  loading={list.isLoading}
                  error={list.isError ? plainMessage(toApiError(list.error)) : undefined}
                  onRetry={() => list.refetch()}
                >
                  {filtered ? (
                    'Không có bài viết nào khớp bộ lọc.'
                  ) : (
                    <div className="space-y-3">
                      <p>Chưa có bài viết nào. Bài đầu tiên sẽ được lưu thành bản nháp, đăng khi sẵn sàng.</p>
                      <Button size="sm" onClick={() => setEditing('new')}>
                        <Plus className="h-4 w-4" />
                        Soạn bài mới
                      </Button>
                    </div>
                  )}
                </TableState>
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} bài viết
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

      {editing !== null && (
        <ArticleEditor
          articleId={editing}
          categories={categories.data ?? []}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}

      <ConfirmModal
        open={!!deleting}
        onOpenChange={(open) => !open && setDeleting(null)}
        type="danger"
        title={`Xóa bài “${deleting?.title ?? ''}”?`}
        description="Bài chưa từng xuất bản nên xóa được và không khôi phục lại. Bài đã từng xuất bản chỉ ẩn được (BR-BV-02)."
        confirmText="Xóa"
        onConfirm={confirmDelete}
        loading={remove.isPending}
      />
    </div>
  );
}
