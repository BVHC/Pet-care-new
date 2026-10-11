import { useState } from 'react';
import { Edit, EyeOff, Eye, Plus } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { catalogApi } from '../../../shared/api/catalog.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useCatalogMutation, useCategories } from '../../../shared/hooks/useCatalog';
import type { ProductCategory } from '../../../shared/types/catalog';
import { ActiveBadge, EmptyRow, Field, FormError, NoticeBar, type Notice } from './ui';

/** UC28 — danh mục sản phẩm: tạo, đổi tên, ẩn / hiện (không xóa, catalog-v1 A2). */
export function CategoriesTab() {
  const categories = useCategories();
  const [editing, setEditing] = useState<ProductCategory | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useCatalogMutation((c: ProductCategory) => catalogApi.updateCategory(c.categoryId, { isActive: !c.isActive }));

  const onToggle = async (c: ProductCategory) => {
    try {
      await toggle.mutateAsync(c);
      setNotice({ kind: 'success', text: `${c.isActive ? 'Đã ẩn' : 'Đã hiện lại'} danh mục ${c.name}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Button onClick={() => setEditing('new')}>
          <Plus className="h-4 w-4" />
          Thêm danh mục
        </Button>
      </div>
      <NoticeBar notice={notice} />
      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Tên danh mục</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {categories.data?.length ? (
                categories.data.map((c) => (
                  <tr key={c.categoryId}>
                    <td className="font-medium text-(--text-primary)">{c.name}</td>
                    <td>
                      <ActiveBadge active={c.isActive} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(c)} aria-label={`Sửa ${c.name}`} title="Đổi tên">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => onToggle(c)}
                          aria-label={`${c.isActive ? 'Ẩn' : 'Hiện'} ${c.name}`}
                          title={c.isActive ? 'Ẩn danh mục' : 'Hiện lại'}
                        >
                          {c.isActive ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={3} loading={categories.isLoading} />
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
    </div>
  );
}

function CategoryForm({
  category,
  onClose,
  onSaved,
}: {
  category: ProductCategory | null;
  onClose: () => void;
  onSaved: (notice: string) => void;
}) {
  const [name, setName] = useState(category?.name ?? '');
  const [error, setError] = useState('');
  const save = useCatalogMutation((value: string) =>
    category ? catalogApi.updateCategory(category.categoryId, { name: value }) : catalogApi.createCategory({ name: value }),
  );

  const submit = async () => {
    if (!name.trim()) return setError('Nhập tên danh mục.');
    try {
      const saved = await save.mutateAsync(name.trim());
      onSaved(category ? `Đã đổi tên thành ${saved.name}` : `Đã thêm danh mục ${saved.name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={category ? 'Đổi tên danh mục' : 'Thêm danh mục'}
      onSubmit={submit}
      submitText={category ? 'Lưu thay đổi' : 'Tạo danh mục'}
      loading={save.isPending}
    >
      <Field label="Tên danh mục">
        {(id) => <Input id={id} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} placeholder="Ví dụ: Thức ăn" />}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
