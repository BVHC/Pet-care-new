import { useState } from 'react';
import { Eye, Pencil } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { FormModal, Modal, ModalBody, ModalHeader, ModalTitle } from '../../../components/ui/modal-templates';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { fieldErrorsOf, plainMessage, toApiError, type ApiError } from '../../../shared/api/api-error';
import { articlexApi } from '../../../shared/api/articlex.api';
import { useArticlexMutation, useMyArticle } from '../../../shared/hooks/useArticlex';
import { ARTICLEX_LIMITS, type CreateMyArticleRequest, type MyArticle, type UpdateMyArticleRequest } from '../../../shared/types/articlex';
import { previewDocument } from './preview';

interface Draft {
  categoryId: string;
  title: string;
  coverImageUrl: string;
  summary: string;
  content: string;
}
type Errors = Partial<Record<keyof Draft, string>>;
export interface CategoryOption {
  categoryId: number;
  name: string;
}

const HTTPS_URL = /^https:\/\/\S+$/;
const draftOf = (a: MyArticle | null): Draft => ({
  categoryId: a?.categoryId != null ? String(a.categoryId) : '',
  title: a?.title ?? '',
  coverImageUrl: a?.coverImageUrl ?? '',
  summary: a?.summary ?? '',
  content: a?.content ?? '',
});

/** Kiểm trước khi gửi (BE kiểm lại). Chuyên mục chưa bắt buộc khi lưu nháp; thiếu thì BE chặn lúc gửi duyệt (BR-BV-01). */
function validate(d: Draft, original: MyArticle | null): Errors {
  const errors: Errors = {};
  if (!d.title.trim()) errors.title = 'Nhập tiêu đề.';
  if (!d.content.trim()) errors.content = 'Nhập nội dung bài viết.';
  const cover = d.coverImageUrl.trim();
  if (cover && cover !== (original?.coverImageUrl ?? '') && !HTTPS_URL.test(cover)) errors.coverImageUrl = 'Ảnh bìa phải là địa chỉ https.';
  return errors;
}

/** Tạo mới: bỏ hẳn trường trống (BE từ chối ảnh bìa là chuỗi rỗng). */
function createBody(d: Draft): CreateMyArticleRequest {
  const cover = d.coverImageUrl.trim();
  const summary = d.summary.trim();
  return {
    title: d.title.trim(),
    content: d.content,
    ...(d.categoryId ? { categoryId: Number(d.categoryId) } : {}),
    ...(cover ? { coverImageUrl: cover } : {}),
    ...(summary ? { summary } : {}),
  };
}

/** Sửa (PATCH): chỉ trường đã đổi; ảnh bìa không xóa được (yaml chỉ nhận https), nên ô trống thì giữ nguyên. */
function changesOf(a: MyArticle, d: Draft): UpdateMyArticleRequest {
  const patch: UpdateMyArticleRequest = {};
  if (d.title.trim() !== a.title) patch.title = d.title.trim();
  if (d.categoryId && Number(d.categoryId) !== a.categoryId) patch.categoryId = Number(d.categoryId);
  if (d.summary.trim() !== (a.summary ?? '')) patch.summary = d.summary.trim();
  if (d.content !== a.content) patch.content = d.content;
  const cover = d.coverImageUrl.trim();
  if (cover && cover !== (a.coverImageUrl ?? '')) patch.coverImageUrl = cover;
  return patch;
}

/** UC80: soạn bài mới (`'new'`) hoặc sửa bài `articleId` (chỉ DRAFT / REJECTED, BR-BV-07). Sửa lấy chi tiết (có `content`) trước rồi mới mở form. */
export function MyArticleEditor({
  articleId,
  categories,
  onClose,
  onSaved,
}: {
  articleId: number | 'new';
  categories: CategoryOption[];
  onClose: () => void;
  onSaved: (text: string) => void;
}) {
  const detail = useMyArticle(articleId === 'new' ? null : articleId);
  if (articleId !== 'new' && !detail.data) {
    return (
      <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-md">
        <ModalHeader onClose={onClose}>
          <ModalTitle>Sửa bài viết</ModalTitle>
        </ModalHeader>
        <ModalBody className="space-y-4">
          {detail.isError ? (
            <>
              <FormError message={plainMessage(toApiError(detail.error))} />
              <Button variant="outline" onClick={() => detail.refetch()}>
                Thử lại
              </Button>
            </>
          ) : (
            <p className="py-6 text-center text-(--text-tertiary)">Đang tải bài viết…</p>
          )}
        </ModalBody>
      </Modal>
    );
  }
  return <MyArticleForm article={detail.data ?? null} categories={categories} onClose={onClose} onSaved={onSaved} />;
}

function MyArticleForm({ article, categories, onClose, onSaved }: { article: MyArticle | null; categories: CategoryOption[]; onClose: () => void; onSaved: (text: string) => void }) {
  const [original] = useState(article); // ảnh chụp lúc mở form: "đã đổi" tính theo nó
  const [draft, setDraft] = useState(() => draftOf(original));
  const [preview, setPreview] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const create = useArticlexMutation(articlexApi.createMy);
  const update = useArticlexMutation((v: { id: number; patch: UpdateMyArticleRequest }) => articlexApi.updateMy(v.id, v.patch));
  const set = (change: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...change }));
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([key]) => !(key in change))));
  };
  // Chuyên mục hiện tại có thể đã ẩn (danh sách công khai chỉ có chuyên mục hiện): vẫn giữ lựa chọn để không đổi ngầm.
  const options = original?.categoryId != null && !categories.some((c) => c.categoryId === original.categoryId) ? [...categories, { categoryId: original.categoryId, name: `Chuyên mục #${original.categoryId}` }] : categories;

  const submit = async () => {
    setFailure(null);
    const found = validate(draft, original);
    setErrors(found);
    if (Object.keys(found).length) return;
    try {
      if (!original) {
        const saved = await create.mutateAsync(createBody(draft));
        return onSaved(`Đã lưu bản nháp “${saved.title}”. Chọn chuyên mục rồi bấm Gửi duyệt khi sẵn sàng.`);
      }
      const patch = changesOf(original, draft);
      if (!Object.keys(patch).length && original.status !== 'REJECTED') return onSaved('Không có thay đổi nào để lưu.');
      const saved = await update.mutateAsync({ id: original.articleId, patch });
      onSaved(original.status === 'REJECTED' ? `Đã lưu “${saved.title}”: bài về trạng thái Nháp, bấm Gửi duyệt để gửi lại.` : `Đã lưu “${saved.title}”`);
    } catch (e) {
      setFailure(toApiError(e));
    }
  };

  const remote = failure ? fieldErrorsOf(failure) : {};
  const shown: Errors = { ...errors, ...remote };
  const generic = failure && Object.keys(remote).length === 0 ? plainMessage(failure) : '';

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={original ? 'Sửa bài viết' : 'Soạn bài mới'}
      description={original ? `Bài #${original.articleId}` : 'Lưu thành bản nháp; chỉ hiện trên trang tin tức sau khi quản lý duyệt.'}
      onSubmit={submit}
      submitText={original ? 'Lưu thay đổi' : 'Lưu nháp'}
      loading={create.isPending || update.isPending}
      size="xl"
    >
      <Field label="Tiêu đề" error={shown.title} hint={`${draft.title.length}/${ARTICLEX_LIMITS.title}`}>
        {(id) => <Input id={id} value={draft.title} maxLength={ARTICLEX_LIMITS.title} onChange={(e) => set({ title: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Chuyên mục" error={shown.categoryId} hint="Bắt buộc khi gửi duyệt.">
          {(id) => (
            <select id={id} className={selectClass} value={draft.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
              <option value="">— Chọn chuyên mục —</option>
              {options.map((c) => (
                <option key={c.categoryId} value={c.categoryId}>
                  {c.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Ảnh bìa (URL https)" error={shown.coverImageUrl} hint="Tùy chọn.">
          {(id) => <Input id={id} inputMode="url" value={draft.coverImageUrl} maxLength={ARTICLEX_LIMITS.coverImageUrl} onChange={(e) => set({ coverImageUrl: e.target.value })} />}
        </Field>
      </div>
      <Field label="Tóm tắt" error={shown.summary} hint={`${draft.summary.length}/${ARTICLEX_LIMITS.summary}`}>
        {(id) => <textarea id={id} className="input" rows={3} value={draft.summary} maxLength={ARTICLEX_LIMITS.summary} onChange={(e) => set({ summary: e.target.value })} />}
      </Field>
      <Field label="Nội dung (HTML)" error={shown.content}>
        {(id) => (
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-3">
              <p className="text-xs text-(--text-tertiary)">Link và ảnh chỉ nhận https. Thẻ ngoài danh sách cho phép, script và thuộc tính sự kiện bị bỏ khi lưu.</p>
              <Button type="button" size="sm" variant="outline" onClick={() => setPreview((p) => !p)}>
                {preview ? <Pencil className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                {preview ? 'Tiếp tục soạn' : 'Xem trước'}
              </Button>
            </div>
            <textarea id={id} hidden={preview} className="input min-h-64 font-mono text-sm" rows={12} spellCheck={false} value={draft.content} onChange={(e) => set({ content: e.target.value })} />
            {preview && <iframe title="Xem trước bài viết" sandbox="" srcDoc={previewDocument(draft.content)} className="h-80 w-full rounded-lg border border-(--border-color) bg-white" />}
          </div>
        )}
      </Field>
      <FormError message={generic} />
    </FormModal>
  );
}
