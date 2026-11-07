import { Button } from '../../../components/ui/button';
import { InfoRow, Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle, StatusBadge } from '../../../components/ui/modal-templates';
import { FormError } from '../../../components/admin/form-ui';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useMyArticle } from '../../../shared/hooks/useArticlex';
import { ARTICLE_REVIEW_STATUS_LABELS } from '../../../shared/types/articlex';
import { formatDateTimeVn } from './format';
import { previewDocument } from './preview';

export const REVIEW_STATUS_VARIANT = { DRAFT: 'neutral', PENDING_REVIEW: 'info', REJECTED: 'danger', PUBLISHED: 'success', HIDDEN: 'warning' } as const;

/** UC80: chi tiết bài của tôi (#3). Bài bị từ chối hiện lý do của người duyệt (BR-BV-06); sửa được khi DRAFT / REJECTED. */
export function MyArticleDetail({ articleId, onClose, onEdit }: { articleId: number; onClose: () => void; onEdit: () => void }) {
  const detail = useMyArticle(articleId);
  const a = detail.data;
  return (
    <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-2xl">
      <ModalHeader onClose={onClose}>
        <ModalTitle>Bài viết của tôi</ModalTitle>
      </ModalHeader>
      <ModalBody className="space-y-4">
        {detail.isError ? (
          <div className="space-y-3">
            <FormError message={plainMessage(toApiError(detail.error))} />
            <Button variant="outline" onClick={() => detail.refetch()}>
              Thử lại
            </Button>
          </div>
        ) : !a ? (
          <p className="py-6 text-center text-(--text-tertiary)">Đang tải bài viết…</p>
        ) : (
          <>
            <h3 className="text-lg font-semibold text-(--text-primary)">{a.title}</h3>
            {a.status === 'REJECTED' && (
              <div role="note" className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
                <p className="font-semibold">Lý do từ chối</p>
                <p className="mt-1 whitespace-pre-line">{a.reviewNote || 'Người duyệt không ghi lý do.'}</p>
                <p className="mt-2 text-xs text-red-700">Sửa bài rồi gửi duyệt lại (bài sẽ về trạng thái Nháp khi lưu).</p>
              </div>
            )}
            <div>
              <InfoRow label="Trạng thái" value={<StatusBadge status={ARTICLE_REVIEW_STATUS_LABELS[a.status]} variant={REVIEW_STATUS_VARIANT[a.status]} />} />
              {a.submittedAt && <InfoRow label="Gửi duyệt" value={formatDateTimeVn(a.submittedAt)} />}
              {a.publishedAt && <InfoRow label="Xuất bản lần đầu" value={formatDateTimeVn(a.publishedAt)} />}
              <InfoRow label="Cập nhật" value={formatDateTimeVn(a.updatedAt)} />
            </div>
            {a.summary && <p className="text-sm text-(--text-secondary)">{a.summary}</p>}
            <iframe title="Nội dung bài viết" sandbox="" srcDoc={previewDocument(a.content)} className="h-52 w-full rounded-lg border border-(--border-color) bg-white" />
          </>
        )}
      </ModalBody>
      <ModalFooter>
        <Button variant="outline" onClick={onClose}>
          Đóng
        </Button>
        {a && (a.status === 'DRAFT' || a.status === 'REJECTED') && <Button onClick={onEdit}>Sửa bài</Button>}
      </ModalFooter>
    </Modal>
  );
}
