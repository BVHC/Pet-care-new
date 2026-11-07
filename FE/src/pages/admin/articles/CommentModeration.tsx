import { useState } from 'react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { Field, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { articlexApi } from '../../../shared/api/articlex.api';
import { useArticlexMutation } from '../../../shared/hooks/useArticlex';

/**
 * UC81 mở rộng: bỏ ẩn bình luận (#14). Hợp đồng KHÔNG có endpoint liệt kê bình luận đã ẩn, nên chỉ bỏ ẩn được khi biết mã bình luận
 * (hiện cạnh bình luận trên trang bài viết khi đăng nhập SUPER_MANAGER, và trong thông báo lúc ẩn).
 */
export function CommentModeration() {
  const [raw, setRaw] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);
  const unhide = useArticlexMutation((id: number) => articlexApi.unhideComment(id));
  const id = Number(raw);
  const valid = Number.isInteger(id) && id > 0;

  const submit = async () => {
    setNotice(null);
    try {
      const comment = await unhide.mutateAsync(id);
      setNotice({ kind: 'success', text: `Đã bỏ ẩn bình luận #${comment.commentId} (bài #${comment.articleId}).` });
      setRaw('');
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <Card className="card">
      <CardContent className="space-y-3 p-4">
        <div>
          <h2 className="font-semibold text-(--text-primary)">Bỏ ẩn bình luận</h2>
          <p className="text-sm text-(--text-secondary)">
            Chưa có danh sách bình luận đã ẩn: nhập mã bình luận (ghi trong thông báo lúc ẩn, hoặc cạnh bình luận trên trang bài viết) để bỏ ẩn.
          </p>
        </div>
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <div className="w-48">
            <Field label="Mã bình luận">
              {(fieldId) => <Input id={fieldId} inputMode="numeric" value={raw} onChange={(e) => setRaw(e.target.value.trim())} />}
            </Field>
          </div>
          <Button type="submit" variant="outline" disabled={!valid || unhide.isPending}>
            Bỏ ẩn
          </Button>
        </form>
        <NoticeBar notice={notice} />
      </CardContent>
    </Card>
  );
}
