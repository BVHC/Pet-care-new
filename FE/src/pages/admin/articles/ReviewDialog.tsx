import { useState } from 'react';
import { Button } from '../../../components/ui/button';
import { InfoRow, Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle, StatusBadge } from '../../../components/ui/modal-templates';
import { Field, FormError } from '../../../components/admin/form-ui';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { articlexApi } from '../../../shared/api/articlex.api';
import { useArticle } from '../../../shared/hooks/useArticles';
import { useArticlexMutation } from '../../../shared/hooks/useArticlex';
import { ARTICLEX_LIMITS } from '../../../shared/types/articlex';
import { ARTICLE_STATUS_LABELS } from '../../../shared/types/content';
import { formatDateTimeVn } from './format';
import { previewDocument } from './preview';

/**
 * UC81 mở rộng: xem một bài chờ duyệt và duyệt (#7) / từ chối kèm lý do (#8). Tác giả lấy từ chi tiết bài của content-v1 (#9):
 * `ArticleSummary` ở danh sách không có tác giả. Nút chỉ hiện khi bài còn PENDING_REVIEW (bài đã đổi trạng thái ở nơi khác thì chỉ xem).
 */
export function ReviewDialog({
  articleId,
  categoryName,
  onClose,
  onDone,
}: {
  articleId: number;
  categoryName: string;
  onClose: () => void;
  onDone: (text: string) => void;
}) {
  const detail = useArticle(articleId);
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState('');
  const [noteError, setNoteError] = useState('');
  const [failure, setFailure] = useState('');
  const approve = useArticlexMutation(() => articlexApi.approve(articleId));
  const reject = useArticlexMutation((reviewNote: string) => articlexApi.reject(articleId, reviewNote));
  const busy = approve.isPending || reject.isPending;
  const article = detail.data;

  const run = async (action: () => Promise<unknown>, done: string) => {
    setFailure('');
    try {
      await action();
      onDone(done);
    } catch (e) {
      setFailure(plainMessage(toApiError(e)));
    }
  };

  const submitReject = () => {
    if (!note.trim()) return setNoteError('Nhập lý do từ chối (tác giả sẽ thấy lý do này).');
    setNoteError('');
    void run(() => reject.mutateAsync(note.trim()), `Đã từ chối bài “${article?.title}”.`);
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-2xl">
      <ModalHeader onClose={onClose}>
        <ModalTitle>Duyệt bài viết</ModalTitle>
      </ModalHeader>
      <ModalBody className="space-y-4">
        {detail.isError ? (
          <div className="space-y-3">
            <FormError message={plainMessage(toApiError(detail.error))} />
            <Button variant="outline" onClick={() => detail.refetch()}>
              Thử lại
            </Button>
          </div>
        ) : !article ? (
          <p className="py-6 text-center text-(--text-tertiary)">Đang tải bài viết…</p>
        ) : (
          <>
            <h3 className="text-lg font-semibold text-(--text-primary)">{article.title}</h3>
            <div>
              <InfoRow label="Tác giả" value={article.authorDisplayName} />
              <InfoRow label="Chuyên mục" value={categoryName} />
              <InfoRow label="Cập nhật" value={formatDateTimeVn(article.updatedAt)} />
              <InfoRow label="Trạng thái" value={<StatusBadge status={ARTICLE_STATUS_LABELS[article.status]} variant={article.status === 'PENDING_REVIEW' ? 'info' : 'neutral'} />} />
            </div>
            {article.summary && <p className="text-sm text-(--text-secondary)">{article.summary}</p>}
            <iframe title="Nội dung bài viết" sandbox="" srcDoc={previewDocument(article.content)} className="h-52 w-full rounded-lg border border-(--border-color) bg-white" />
            {rejecting && (
              <Field label="Lý do từ chối" error={noteError} hint={`${note.length}/${ARTICLEX_LIMITS.reviewNote}`}>
                {(id) => <textarea id={id} className="input" rows={3} maxLength={ARTICLEX_LIMITS.reviewNote} value={note} onChange={(e) => setNote(e.target.value)} />}
              </Field>
            )}
            <FormError message={failure} />
          </>
        )}
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" onClick={onClose} disabled={busy}>
          Đóng
        </Button>
        {article?.status === 'PENDING_REVIEW' &&
          (rejecting ? (
            <>
              <Button variant="outline" onClick={() => setRejecting(false)} disabled={busy}>
                Quay lại
              </Button>
              <Button variant="destructive" onClick={submitReject} disabled={busy}>
                Xác nhận từ chối
              </Button>
            </>
          ) : (
            <>
              <Button variant="outline" onClick={() => setRejecting(true)} disabled={busy}>
                Từ chối
              </Button>
              <Button onClick={() => run(() => approve.mutateAsync(undefined), `Đã duyệt và xuất bản bài “${article.title}”.`)} disabled={busy}>
                Duyệt và xuất bản
              </Button>
            </>
          ))}
      </ModalFooter>
    </Modal>
  );
}
