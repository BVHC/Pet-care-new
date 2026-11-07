import { useState } from 'react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useArticleCategories, useArticleList } from '../../../shared/hooks/useArticles';
import { CommentModeration } from './CommentModeration';
import { formatDateTimeVn } from './format';
import { ReviewDialog } from './ReviewDialog';
import { TableState } from './TableState';

const PAGE_SIZE = 20;

/** UC81 mở rộng: hàng chờ duyệt = `GET /articles?status=PENDING_REVIEW` của content-v1 (articlex-v1 không có endpoint riêng), kèm bỏ ẩn bình luận. */
export function ReviewTab() {
  const [page, setPage] = useState(0);
  const list = useArticleList({ status: 'PENDING_REVIEW', page, size: PAGE_SIZE });
  const categories = useArticleCategories();
  const [reviewing, setReviewing] = useState<number | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const categoryName = (id: number) => categories.data?.find((c) => c.categoryId === id)?.name ?? `#${id}`;
  const data = list.data;

  return (
    <div className="space-y-4">
      <NoticeBar notice={notice} />
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table" aria-label="Bài chờ duyệt">
            <thead>
              <tr>
                <th className="px-3!">Tiêu đề</th>
                <th className="px-3!">Chuyên mục</th>
                <th className="px-3!">Cập nhật</th>
                <th className="px-3! text-right" style={{ textAlign: 'right' }}>
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((a) => (
                  <tr key={a.articleId}>
                    <td className="px-3! font-medium text-(--text-primary)">{a.title}</td>
                    <td className="px-3!">{categoryName(a.categoryId)}</td>
                    <td className="whitespace-nowrap px-3!">{formatDateTimeVn(a.updatedAt)}</td>
                    <td className="px-3! text-right">
                      <Button size="sm" variant="outline" aria-label={`Xem và duyệt ${a.title}`} onClick={() => setReviewing(a.articleId)}>
                        Xem và duyệt
                      </Button>
                    </td>
                  </tr>
                ))
              ) : (
                <TableState colSpan={4} loading={list.isLoading} error={list.isError ? plainMessage(toApiError(list.error)) : undefined} onRetry={() => list.refetch()}>
                  Không có bài nào đang chờ duyệt.
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

      <CommentModeration />

      {reviewing !== null && (
        <ReviewDialog
          articleId={reviewing}
          categoryName={categoryName(data?.content.find((a) => a.articleId === reviewing)?.categoryId ?? 0)}
          onClose={() => setReviewing(null)}
          onDone={(text) => {
            setReviewing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}
