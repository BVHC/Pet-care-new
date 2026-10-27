import { useState } from 'react';
import { Edit, Eye, EyeOff, Plus } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { ConfirmModal, FormModal, StatusBadge } from '../../../components/ui/modal-templates';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { contentApi } from '../../../shared/api/content.api';
import { useArticleCategories, useArticleMutation } from '../../../shared/hooks/useArticles';
import type { ArticleCategory } from '../../../shared/types/content';
import { Field, FormError, NoticeBar, type Notice } from '../../../components/admin/form-ui';
import { DeleteButton } from './DeleteButton';
import { SLUG_PATTERN, slugify } from './slug';
import { TableState } from './TableState';

/** UC81 — chuyên mục: tạo, đổi tên, ẩn / hiện; chỉ xóa được chuyên mục không còn bài (BR-BV-04). Slug không đổi sau khi tạo (A1). */
export function ArticleCategoriesTab() {
  const categories = useArticleCategories();
  const [editing, setEditing] = useState<ArticleCategory | 'new' | null>(null);
  const [deleting, setDeleting] = useState<ArticleCategory | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useArticleMutation((c: ArticleCategory) => contentApi.updateArticleCategory(c.categoryId, { isHidden: !c.isHidden }));
  const remove = useArticleMutation((c: ArticleCategory) => contentApi.deleteArticleCategory(c.categoryId));
  const busy = toggle.isPending || remove.isPending;

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
    await run(() => remove.mutateAsync(target), `Đã xóa chuyên mục ${target.name}`);
  };

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Button onClick={() => setEditing('new')}>
          <Plus className="h-4 w-4" />
          Thêm chuyên mục
        </Button>
      </div>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table">
            <thead>
              <tr>
                <th className="px-3! md:px-4!">Chuyên mục</th>
                <th className="hidden sm:table-cell">Số bài</th>
                <th className="hidden sm:table-cell">Trạng thái</th>
                <th className="px-2! text-right sm:px-4!" style={{ textAlign: 'right' }}>
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody>
              {categories.data?.length ? (
                categories.data.map((c) => (
                  <tr key={c.categoryId}>
                    <td className="px-3! md:px-4!">
                      <p className="font-medium text-(--text-primary)">{c.name}</p>
                      <p className="break-all font-mono text-xs text-(--text-tertiary)">{c.slug}</p>
                      {/* Màn hẹp ẩn hai cột phụ: số bài và trạng thái gộp thành một dòng dưới tên */}
                      <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-(--text-secondary) sm:hidden">
                        <StatusBadge status={c.isHidden ? 'Đã ẩn' : 'Đang hiện'} variant={c.isHidden ? 'neutral' : 'success'} />
                        <span>{c.articleCount} bài viết</span>
                      </div>
                    </td>
                    <td className="hidden tabular-nums text-(--text-secondary)! sm:table-cell">{c.articleCount}</td>
                    <td className="hidden whitespace-nowrap sm:table-cell">
                      <StatusBadge status={c.isHidden ? 'Đã ẩn' : 'Đang hiện'} variant={c.isHidden ? 'neutral' : 'success'} />
                    </td>
                    <td className="px-2! sm:px-4!">
                      <div className="flex items-center justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(c)} aria-label={`Đổi tên ${c.name}`} title="Đổi tên">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={() =>
                            run(
                              () => toggle.mutateAsync(c),
                              c.isHidden ? `Đã hiện lại chuyên mục ${c.name}` : `Đã ẩn chuyên mục ${c.name} khỏi bộ lọc tin tức`,
                            )
                          }
                          aria-label={`${c.isHidden ? 'Hiện' : 'Ẩn'} ${c.name}`}
                          title={c.isHidden ? 'Hiện lại ở bộ lọc tin tức' : 'Ẩn khỏi bộ lọc tin tức (bài trong đó vẫn đọc được)'}
                        >
                          {c.isHidden ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
                        </Button>
                        <DeleteButton
                          name={c.name}
                          busy={busy}
                          blockedReason={c.articleCount > 0 ? `Còn ${c.articleCount} bài viết, chỉ ẩn được` : undefined}
                          onClick={() => setDeleting(c)}
                        />
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <TableState
                  colSpan={4}
                  loading={categories.isLoading}
                  error={categories.isError ? plainMessage(toApiError(categories.error)) : undefined}
                  onRetry={() => categories.refetch()}
                >
                  Chưa có chuyên mục nào. Tạo chuyên mục trước khi soạn bài.
                </TableState>
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editing && (
        <CategoryForm
          category={editing === 'new' ? null : editing}
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
        title={`Xóa chuyên mục ${deleting?.name ?? ''}?`}
        description="Chỉ xóa được chuyên mục không còn bài nào ở bất kỳ trạng thái nào (BR-BV-04). Còn bài thì chọn Ẩn."
        confirmText="Xóa"
        onConfirm={confirmDelete}
        loading={remove.isPending}
      />
    </div>
  );
}

function CategoryForm({ category, onClose, onSaved }: { category: ArticleCategory | null; onClose: () => void; onSaved: (text: string) => void }) {
  const [name, setName] = useState(category?.name ?? '');
  const [slug, setSlug] = useState('');
  const [slugTouched, setSlugTouched] = useState(false);
  const [error, setError] = useState('');
  const create = useArticleMutation(contentApi.createArticleCategory);
  const rename = useArticleMutation((vars: { id: number; name: string }) => contentApi.updateArticleCategory(vars.id, { name: vars.name }));

  const submit = async () => {
    const trimmed = name.trim();
    if (!trimmed) return setError('Nhập tên chuyên mục.');
    try {
      if (category) {
        if (trimmed === category.name) return onSaved('Không có thay đổi nào để lưu.');
        const saved = await rename.mutateAsync({ id: category.categoryId, name: trimmed });
        return onSaved(`Đã đổi tên thành ${saved.name}`);
      }
      if (!slug.trim()) return setError('Nhập đường dẫn.');
      if (!SLUG_PATTERN.test(slug.trim())) return setError('Đường dẫn chỉ gồm chữ thường không dấu, số và dấu gạch ngang.');
      const saved = await create.mutateAsync({ name: trimmed, slug: slug.trim() });
      onSaved(`Đã thêm chuyên mục ${saved.name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={category ? 'Đổi tên chuyên mục' : 'Thêm chuyên mục'}
      description={category ? `Đường dẫn ${category.slug} không đổi sau khi tạo.` : undefined}
      onSubmit={submit}
      submitText={category ? 'Lưu thay đổi' : 'Tạo chuyên mục'}
      loading={create.isPending || rename.isPending}
    >
      <Field label="Tên chuyên mục">
        {(id) => (
          <Input
            id={id}
            value={name}
            maxLength={100}
            placeholder="Ví dụ: Dinh dưỡng"
            onChange={(e) => {
              setName(e.target.value);
              if (!slugTouched) setSlug(slugify(e.target.value, 120));
            }}
          />
        )}
      </Field>
      {!category && (
        <Field label="Đường dẫn (slug)" hint="Dùng trong địa chỉ lọc tin /news?category=… và không đổi được sau khi tạo.">
          {(id) => (
            <Input
              id={id}
              className="font-mono"
              value={slug}
              maxLength={120}
              onChange={(e) => {
                setSlugTouched(e.target.value !== '');
                setSlug(e.target.value);
              }}
            />
          )}
        </Field>
      )}
      <FormError message={error} />
    </FormModal>
  );
}
