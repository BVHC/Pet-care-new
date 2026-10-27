import { useState } from 'react';
import { Eye, Pencil } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { FormModal, Modal, ModalBody, ModalHeader, ModalTitle } from '../../../components/ui/modal-templates';
import { fieldErrorsOf, plainMessage, toApiError, type ApiError } from '../../../shared/api/api-error';
import { contentApi } from '../../../shared/api/content.api';
import { useArticle, useArticleMutation } from '../../../shared/hooks/useArticles';
import { useSessionUser } from '../../../shared/stores/session.store';
import {
  ARTICLE_STATUS_LABELS,
  type Article,
  type ArticleCategory,
  type CreateArticleRequest,
  type UpdateArticleRequest,
} from '../../../shared/types/content';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { previewDocument } from './preview';
import { SLUG_PATTERN, slugify } from './slug';

interface Draft {
  categoryId: string;
  title: string;
  slug: string;
  coverImageUrl: string;
  summary: string;
  authorDisplayName: string;
  content: string;
}
type Errors = Partial<Record<keyof Draft, string>>;

const SLUG_MESSAGE = 'Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang.';
const HTTPS_URL = /^https:\/\/\S+$/;
const ALLOWED_TAGS =
  'a, b, blockquote, br, caption, cite, code, col, colgroup, dd, div, dl, dt, em, h1–h6, i, img, li, ol, p, pre, q, small, span, strike, strong, sub, sup, table, tbody, td, tfoot, th, thead, tr, u, ul';

const draftOf = (a: Article | null, author: string): Draft => ({
  categoryId: a ? String(a.categoryId) : '',
  title: a?.title ?? '',
  slug: a?.slug ?? '',
  coverImageUrl: a?.coverImageUrl ?? '',
  summary: a?.summary ?? '',
  authorDisplayName: a?.authorDisplayName ?? author,
  content: a?.content ?? '',
});

/** Kiểm trước khi gửi (BE vẫn kiểm lại). Ảnh bìa cũ không phải https (bài mẫu) chỉ bị chặn khi người dùng đã sửa nó. */
function validate(d: Draft, original: Article | null, locked: boolean): Errors {
  const errors: Errors = {};
  if (!d.categoryId) errors.categoryId = 'Chọn chuyên mục.';
  if (!d.title.trim()) errors.title = 'Nhập tiêu đề.';
  if (!locked) {
    if (!d.slug.trim()) errors.slug = 'Nhập đường dẫn.';
    else if (!SLUG_PATTERN.test(d.slug.trim())) errors.slug = SLUG_MESSAGE;
  }
  const cover = d.coverImageUrl.trim();
  if (cover && cover !== (original?.coverImageUrl ?? '') && !HTTPS_URL.test(cover)) errors.coverImageUrl = 'Ảnh bìa phải là địa chỉ https.';
  if (!d.content.trim()) errors.content = 'Nhập nội dung bài viết.';
  if (!d.authorDisplayName.trim()) errors.authorDisplayName = 'Nhập tên tác giả hiển thị.';
  return errors;
}

/** Tạo mới: bỏ hẳn ảnh bìa và tóm tắt khi trống (BE từ chối ảnh bìa là chuỗi rỗng). */
function createBody(d: Draft): CreateArticleRequest {
  const cover = d.coverImageUrl.trim();
  const summary = d.summary.trim();
  return {
    categoryId: Number(d.categoryId),
    title: d.title.trim(),
    slug: d.slug.trim(),
    ...(cover ? { coverImageUrl: cover } : {}),
    ...(summary ? { summary } : {}),
    content: d.content,
    authorDisplayName: d.authorDisplayName.trim(),
  };
}

/** Sửa: chỉ trường đã đổi so với bản lúc mở form; `""` xóa ảnh bìa / tóm tắt; slug đã khóa thì không bao giờ gửi. */
function changesOf(a: Article, d: Draft, locked: boolean): UpdateArticleRequest {
  const patch: UpdateArticleRequest = {};
  if (Number(d.categoryId) !== a.categoryId) patch.categoryId = Number(d.categoryId);
  if (d.title.trim() !== a.title) patch.title = d.title.trim();
  if (!locked && d.slug.trim() !== a.slug) patch.slug = d.slug.trim();
  if (d.coverImageUrl.trim() !== (a.coverImageUrl ?? '')) patch.coverImageUrl = d.coverImageUrl.trim();
  if (d.summary.trim() !== (a.summary ?? '')) patch.summary = d.summary.trim();
  if (d.content !== a.content) patch.content = d.content;
  if (d.authorDisplayName.trim() !== a.authorDisplayName) patch.authorDisplayName = d.authorDisplayName.trim();
  return patch;
}

/**
 * Soạn bài mới (`'new'`) hoặc sửa bài `articleId`: sửa lấy chi tiết (có `content`) trước rồi mới mở form, để form luôn khởi tạo từ
 * bản mới nhất. `onSaved` nhận câu báo kết quả; phía gọi đóng form.
 */
export function ArticleEditor({
  articleId,
  categories,
  onClose,
  onSaved,
}: {
  articleId: number | 'new';
  categories: ArticleCategory[];
  onClose: () => void;
  onSaved: (text: string) => void;
}) {
  const detail = useArticle(articleId === 'new' ? null : articleId);
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
              <div className="flex justify-end">
                <Button variant="outline" onClick={() => detail.refetch()}>
                  Thử lại
                </Button>
              </div>
            </>
          ) : (
            <p className="py-6 text-center text-(--text-tertiary)">Đang tải bài viết…</p>
          )}
        </ModalBody>
      </Modal>
    );
  }
  return <ArticleForm article={detail.data ?? null} categories={categories} onClose={onClose} onSaved={onSaved} />;
}

function ArticleForm({
  article,
  categories,
  onClose,
  onSaved,
}: {
  article: Article | null;
  categories: ArticleCategory[];
  onClose: () => void;
  onSaved: (text: string) => void;
}) {
  const [original] = useState(article); // ảnh chụp lúc mở form: "đã đổi" và "đã khóa slug" tính theo nó, không đổi khi danh sách làm mới
  const author = useSessionUser()?.name ?? '';
  const [draft, setDraft] = useState(() => draftOf(original, author));
  const [slugTouched, setSlugTouched] = useState(original !== null);
  const [preview, setPreview] = useState(false);
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const create = useArticleMutation(contentApi.createArticle);
  const update = useArticleMutation((vars: { id: number; patch: UpdateArticleRequest }) => contentApi.updateArticle(vars.id, vars.patch));
  const locked = !!original?.firstPublishedAt; // BR-BV-01: slug khóa sau lần xuất bản đầu tiên

  const set = (change: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...change }));
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([key]) => !(key in change))));
  };

  const submit = async () => {
    setFailure(null);
    const found = validate(draft, original, locked);
    setErrors(found);
    if (Object.keys(found).length) return;
    try {
      if (!original) {
        const saved = await create.mutateAsync(createBody(draft));
        return onSaved(`Đã lưu bản nháp “${saved.title}”. Bấm Đăng để hiện trên trang tin tức.`);
      }
      const patch = changesOf(original, draft, locked);
      if (!Object.keys(patch).length) return onSaved('Không có thay đổi nào để lưu.');
      const saved = await update.mutateAsync({ id: original.articleId, patch });
      onSaved(`Đã lưu “${saved.title}”`);
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
      description={original ? `Bài #${original.articleId} · ${ARTICLE_STATUS_LABELS[original.status]}` : 'Lưu thành bản nháp, chưa hiện trên trang tin tức cho tới khi đăng.'}
      onSubmit={submit}
      submitText={original ? 'Lưu thay đổi' : 'Lưu nháp'}
      loading={create.isPending || update.isPending}
      size="xl"
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Chuyên mục" error={shown.categoryId}>
          {(id) => (
            <select id={id} className={selectClass} value={draft.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
              <option value="">— Chọn chuyên mục —</option>
              {categories.map((c) => (
                <option key={c.categoryId} value={c.categoryId}>
                  {c.isHidden ? `${c.name} (ẩn)` : c.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Tác giả hiển thị" error={shown.authorDisplayName}>
          {(id) => <Input id={id} value={draft.authorDisplayName} maxLength={100} onChange={(e) => set({ authorDisplayName: e.target.value })} />}
        </Field>
      </div>

      <Field label="Tiêu đề" error={shown.title}>
        {(id) => (
          <Input
            id={id}
            value={draft.title}
            maxLength={250}
            onChange={(e) => set({ title: e.target.value, ...(slugTouched || locked ? {} : { slug: slugify(e.target.value) }) })}
          />
        )}
      </Field>

      <Field
        label="Đường dẫn (slug)"
        error={shown.slug}
        hint={
          locked
            ? 'Đã khóa vì bài từng được xuất bản (BR-BV-01): đổi đường dẫn sẽ làm hỏng liên kết đã chia sẻ.'
            : 'Chữ thường không dấu, số và dấu gạch ngang. Tự gợi ý từ tiêu đề cho tới khi bạn sửa tay.'
        }
      >
        {(id) => (
          <Input
            id={id}
            className="font-mono disabled:cursor-not-allowed disabled:bg-(--bg-tertiary)! disabled:text-(--text-secondary)!"
            value={draft.slug}
            maxLength={270}
            disabled={locked}
            onChange={(e) => {
              setSlugTouched(e.target.value !== ''); // xóa trắng thì gợi ý chạy lại khi tiêu đề đổi
              set({ slug: e.target.value });
            }}
          />
        )}
      </Field>

      <Field label="Ảnh bìa (URL https)" error={shown.coverImageUrl} hint="Tùy chọn. Chỉ nhận địa chỉ https, chưa hỗ trợ tải ảnh lên.">
        {(id) => <Input id={id} inputMode="url" value={draft.coverImageUrl} maxLength={500} onChange={(e) => set({ coverImageUrl: e.target.value })} />}
      </Field>

      <Field label="Tóm tắt" error={shown.summary}>
        {(id) => (
          <>
            <textarea id={id} className="input" rows={3} value={draft.summary} maxLength={500} onChange={(e) => set({ summary: e.target.value })} />
            <p className="mt-1 text-right text-xs text-(--text-tertiary)">{draft.summary.length}/500</p>
          </>
        )}
      </Field>

      <Field label="Nội dung (HTML)" error={shown.content}>
        {(id) => (
          <div className="space-y-2">
            <div className="flex items-center justify-between gap-3">
              <p className="text-xs text-(--text-tertiary)">Link và ảnh chỉ nhận https. Thẻ ngoài danh sách, script, style và thuộc tính sự kiện bị bỏ khi lưu.</p>
              <Button type="button" size="sm" variant="outline" onClick={() => setPreview((p) => !p)}>
                {preview ? <Pencil className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                {preview ? 'Tiếp tục soạn' : 'Xem trước'}
              </Button>
            </div>
            <textarea
              id={id}
              hidden={preview}
              className="input min-h-64 font-mono text-sm"
              rows={12}
              spellCheck={false}
              value={draft.content}
              onChange={(e) => set({ content: e.target.value })}
            />
            {preview && (
              <iframe
                title="Xem trước bài viết"
                sandbox=""
                srcDoc={previewDocument(draft.content)}
                className="h-80 w-full rounded-lg border border-(--border-color) bg-white"
              />
            )}
            <details className="text-xs text-(--text-tertiary)">
              <summary className="cursor-pointer">Thẻ HTML được phép</summary>
              <p className="mt-1 font-mono leading-relaxed">{ALLOWED_TAGS}</p>
            </details>
          </div>
        )}
      </Field>

      <FormError message={generic} />
    </FormModal>
  );
}
