// Trình soạn một trang (UC21): thanh công cụ (xem trang, khôi phục mặc định, hoàn tác), form theo khóa, thanh Lưu dính đáy.
// Nháp nằm ở trang cha (theo khóa) nên đổi tab không mất; ở đây chỉ tính "đang đổi gì so với bản đã lưu" và gọi lưu.
import { useState, type ComponentType } from 'react';
import { ExternalLink, RotateCcw, Undo2 } from 'lucide-react';
import { toast } from 'sonner';
import { FormError, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { ConfirmModal } from '../../../components/ui/modal-templates';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { PAGE_CONTENT_MAX_BYTES, PAGE_DEFAULTS, PAGE_LABELS, pageContentBytes } from '../../../shared/constants/page-content';
import { useSavePageContent } from '../../../shared/hooks/usePageContent';
import type { PageKey } from '../../../shared/types/page-content';
import { cn } from '../../../lib/utils';
import { EditorContext, sameContent, updateAt, type Draft } from './draft';
import { AboutForm, FaqForm, HomeBannerForm, PolicyForm } from './forms';
import { validatePage } from './validation';

const FORMS: Record<PageKey, ComponentType> = { HOME_BANNER: HomeBannerForm, ABOUT: AboutForm, POLICY: PolicyForm, FAQ: FaqForm };

/** Trang công khai hiển thị từng khóa (FAQ nằm ở trang chủ). */
const PUBLIC_LINKS: Record<PageKey, { label: string; href: string }[]> = {
  HOME_BANNER: [{ label: 'Xem trang', href: '/' }],
  ABOUT: [{ label: 'Xem trang', href: '/about' }],
  POLICY: [
    { label: 'Xem Điều khoản sử dụng', href: '/terms' },
    { label: 'Xem Chính sách bảo mật', href: '/privacy' },
  ],
  FAQ: [{ label: 'Xem trang', href: '/' }],
};

export function PageEditor({
  pageKey,
  base,
  isDefault,
  draft,
  onChange,
  onDiscard,
}: {
  pageKey: PageKey;
  /** Bản đã lưu đã `safeParse` (hoặc PAGE_DEFAULTS khi `{}` / sai dạng). */
  base: Draft;
  /** Nội dung đã lưu là `{}` hoặc sai dạng: trang công khai đang dùng mặc định trong mã. */
  isDefault: boolean;
  /** Nháp chưa lưu của khóa này, `undefined` nếu chưa có. */
  draft: Draft | undefined;
  onChange: (update: (current: Draft) => Draft) => void;
  /** Bỏ nháp của khóa này (sau khi lưu xong hoặc hoàn tác). */
  onDiscard: () => void;
}) {
  const save = useSavePageContent();
  const [notice, setNotice] = useState<Notice | null>(null);
  const [failure, setFailure] = useState('');
  const [confirm, setConfirm] = useState<'undo' | 'restore' | null>(null);
  const Form = FORMS[pageKey];

  const current = draft ?? base;
  const dirty = !sameContent(current, base);
  const result = validatePage(pageKey, current);
  const bytes = result.ok ? pageContentBytes(result.data) : 0;
  const tooLong = bytes > PAGE_CONTENT_MAX_BYTES;
  const canSave = dirty && result.ok && !tooLong && !save.isPending;
  const errors = result.ok ? {} : result.errors;
  const problems = Object.keys(errors).length;
  const isDefaults = sameContent(current, PAGE_DEFAULTS[pageKey]);

  const change = (path: string, update: (value: unknown) => unknown) => {
    setNotice(null);
    setFailure('');
    onChange((value) => updateAt(value, path, update) as Draft);
  };

  const submit = async () => {
    if (!canSave || !result.ok) return;
    setNotice(null);
    setFailure('');
    try {
      await save.mutateAsync({ key: pageKey, content: result.data });
      onDiscard();
      setNotice({ kind: 'success', text: `Đã lưu “${PAGE_LABELS[pageKey]}”. Trang công khai sẽ hiện nội dung mới.` });
      toast.success(`Đã lưu “${PAGE_LABELS[pageKey]}”`);
    } catch (e) {
      setFailure(plainMessage(toApiError(e)));
    }
  };

  const restore = () => {
    setNotice(null);
    setFailure('');
    onChange(() => PAGE_DEFAULTS[pageKey]);
  };

  const status = tooLong
    ? `Nội dung quá dài (${Math.ceil(bytes / 1024)} KB / ${PAGE_CONTENT_MAX_BYTES / 1024} KB)`
    : problems
      ? `Còn ${problems} trường chưa hợp lệ`
      : dirty
        ? 'Có thay đổi chưa lưu'
        : 'Chưa có thay đổi';

  return (
    <div className="max-w-4xl space-y-4">
      {isDefault && (
        <p role="status" className="rounded-lg bg-amber-50 px-4 py-2.5 text-sm font-medium text-amber-800">
          Trang đang dùng nội dung mặc định trong mã; sửa rồi Lưu để ghi vào hệ thống.
        </p>
      )}

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div className="mr-auto flex flex-wrap gap-x-4 gap-y-1">
          {PUBLIC_LINKS[pageKey].map((link) => (
            <a
              key={link.href}
              href={link.href}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 rounded text-sm font-medium text-(--color-primary) underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary)"
            >
              {link.label}
              <span className="sr-only"> (mở ở tab mới)</span>
              <ExternalLink className="h-3.5 w-3.5" aria-hidden />
            </a>
          ))}
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={save.isPending || isDefaults}
          onClick={() => (draft ? setConfirm('restore') : restore())}
        >
          <RotateCcw className="h-4 w-4" aria-hidden />
          Khôi phục nội dung mặc định
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={save.isPending || !dirty} onClick={() => setConfirm('undo')}>
          <Undo2 className="h-4 w-4" aria-hidden />
          Hoàn tác thay đổi
        </Button>
      </div>

      <p className="text-sm text-(--text-secondary)">Chỉ nhập văn bản thuần: thẻ HTML sẽ hiện thành chữ.</p>

      <EditorContext.Provider value={{ draft: current, errors, busy: save.isPending, change }}>
        <Form />
      </EditorContext.Provider>

      <div className="sticky bottom-2 z-10 space-y-2">
        <NoticeBar notice={notice} />
        <FormError message={failure} />
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-(--border-color) bg-(--bg-primary) px-4 py-3 shadow-lg">
          <p role="status" className={cn('mr-auto text-sm', tooLong || problems ? 'font-medium text-red-600' : 'text-(--text-secondary)')}>
            {status}
          </p>
          <Button type="button" onClick={submit} disabled={!canSave}>
            {save.isPending ? 'Đang lưu…' : 'Lưu thay đổi'}
          </Button>
        </div>
      </div>

      {confirm === 'undo' && (
        <ConfirmModal
          open
          onOpenChange={(open) => !open && setConfirm(null)}
          type="warning"
          title="Hoàn tác thay đổi?"
          description={`Mọi chỉnh sửa chưa lưu của “${PAGE_LABELS[pageKey]}” sẽ bị bỏ, form quay về bản đã lưu.`}
          confirmText="Hoàn tác"
          cancelText="Giữ lại"
          onConfirm={() => {
            setConfirm(null);
            setNotice(null);
            setFailure('');
            onDiscard();
          }}
        />
      )}
      {confirm === 'restore' && (
        <ConfirmModal
          open
          onOpenChange={(open) => !open && setConfirm(null)}
          type="warning"
          title="Khôi phục nội dung mặc định?"
          description="Nội dung đang soạn sẽ bị thay bằng nội dung mặc định. Chưa ghi vào hệ thống cho tới khi bạn bấm Lưu."
          confirmText="Khôi phục"
          cancelText="Giữ lại"
          onConfirm={() => {
            setConfirm(null);
            restore();
          }}
        />
      )}
    </div>
  );
}
