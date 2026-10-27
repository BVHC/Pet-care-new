import { useState } from 'react';
import { Edit, Eye, EyeOff, Plus, Search } from 'lucide-react';
import { Badge } from '../../../components/ui/badge';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { cn, formatCurrency } from '../../../lib/utils';
import { catalogApi } from '../../../shared/api/catalog.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useCatalogMutation, useCategories, useProducts, useVaccineTypes } from '../../../shared/hooks/useCatalog';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import {
  PRODUCT_TYPE_LABELS,
  SPECIES_LABELS,
  type Product,
  type ProductCategory,
  type ProductType,
  type VaccineType,
} from '../../../shared/types/catalog';
import { ActiveBadge, Checkbox, EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';

const PAGE_SIZE = 20;
const TYPES = Object.keys(PRODUCT_TYPE_LABELS) as ProductType[];

/** UC29 — sản phẩm: lọc, tạo, sửa, đổi giá, ngừng / mở bán (BR-SP-01, 05, 07). */
export function ProductsTab() {
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [categoryId, setCategoryId] = useState('');
  const [productType, setProductType] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const products = useProducts({
    q: search || undefined,
    categoryId: categoryId ? Number(categoryId) : undefined,
    productType: (productType || undefined) as ProductType | undefined,
    isActive: status ? status === 'active' : undefined,
    page,
    size: PAGE_SIZE,
  });
  const categories = useCategories();
  const vaccineTypes = useVaccineTypes();
  const [editing, setEditing] = useState<Product | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useCatalogMutation((p: Product) => catalogApi.updateProduct(p.productId, { isActive: !p.isActive }));

  const categoryName = (id: number) => categories.data?.find((c) => c.categoryId === id)?.name ?? `#${id}`;
  const filter = (set: (v: string) => void) => (v: string) => {
    set(v);
    setPage(0);
  };

  const onToggle = async (p: Product) => {
    try {
      await toggle.mutateAsync(p);
      setNotice({ kind: 'success', text: `${p.isActive ? 'Đã ngừng bán' : 'Đã mở bán lại'} ${p.name}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  const data = products.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center gap-3">
          <div className="relative min-w-[220px] flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" />
            <Input
              aria-label="Tìm theo tên hoặc SKU"
              placeholder="Tìm theo tên hoặc SKU…"
              className="pl-10"
              value={q}
              onChange={(e) => filter(setQ)(e.target.value)}
            />
          </div>
          <select aria-label="Lọc danh mục" className={cn(selectClass, 'w-44')} value={categoryId} onChange={(e) => filter(setCategoryId)(e.target.value)}>
            <option value="">Mọi danh mục</option>
            {categories.data?.map((c) => (
              <option key={c.categoryId} value={c.categoryId}>
                {c.name}
              </option>
            ))}
          </select>
          <select aria-label="Lọc loại" className={cn(selectClass, 'w-36')} value={productType} onChange={(e) => filter(setProductType)(e.target.value)}>
            <option value="">Mọi loại</option>
            {TYPES.map((t) => (
              <option key={t} value={t}>
                {PRODUCT_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'w-36')} value={status} onChange={(e) => filter(setStatus)(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            <option value="active">Đang bán</option>
            <option value="inactive">Ngừng bán</option>
          </select>
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Thêm sản phẩm
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Tên sản phẩm</th>
                <th>Danh mục</th>
                <th>Loại</th>
                <th>Đơn vị</th>
                <th className="text-right">Giá</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((p) => (
                  <tr key={p.productId}>
                    <td className="font-mono text-xs text-(--text-secondary)">{p.sku}</td>
                    <td className="font-medium text-(--text-primary)">{p.name}</td>
                    <td className="text-(--text-secondary)">{categoryName(p.categoryId)}</td>
                    <td>
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span>{PRODUCT_TYPE_LABELS[p.productType]}</span>
                        {p.isPrescription && <Badge className="bg-red-100 text-red-700 text-xs">Kê đơn</Badge>}
                        {p.tracksExpiry && <Badge className="bg-amber-100 text-amber-700 text-xs">Theo lô</Badge>}
                      </div>
                    </td>
                    <td className="text-(--text-secondary)">{p.unit}</td>
                    <td className="text-right font-medium">{formatCurrency(p.price)}</td>
                    <td>
                      <ActiveBadge active={p.isActive} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(p)} aria-label={`Sửa ${p.name}`} title="Sửa">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => onToggle(p)}
                          aria-label={`${p.isActive ? 'Ngừng bán' : 'Mở bán'} ${p.name}`}
                          title={p.isActive ? 'Ngừng kinh doanh' : 'Mở bán lại'}
                        >
                          {p.isActive ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={8} loading={products.isLoading} />
              )}
            </tbody>
          </table>
          {data && data.totalPages > 1 && (
            <div className="flex items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
              <span>
                Trang {data.page + 1}/{data.totalPages} · {data.totalElements} sản phẩm
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

      {editing && (
        <ProductForm
          product={editing === 'new' ? null : editing}
          categories={categories.data ?? []}
          vaccineTypes={vaccineTypes.data ?? []}
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

interface ProductDraft {
  categoryId: string;
  sku: string;
  name: string;
  productType: ProductType;
  isPrescription: boolean;
  tracksExpiry: boolean;
  vaccineTypeId: string;
  unit: string;
  price: string;
  description: string;
}

const draftOf = (p: Product | null): ProductDraft => ({
  categoryId: p ? String(p.categoryId) : '',
  sku: p?.sku ?? '',
  name: p?.name ?? '',
  productType: p?.productType ?? 'GOODS',
  isPrescription: p?.isPrescription ?? false,
  tracksExpiry: p?.tracksExpiry ?? false,
  vaccineTypeId: p?.vaccineTypeId ? String(p.vaccineTypeId) : '',
  unit: p?.unit ?? '',
  price: p ? String(p.price) : '',
  description: p?.description ?? '',
});

function ProductForm({
  product,
  categories,
  vaccineTypes,
  onClose,
  onSaved,
}: {
  product: Product | null;
  categories: ProductCategory[];
  vaccineTypes: VaccineType[];
  onClose: () => void;
  onSaved: (notice: string) => void;
}) {
  const [form, setForm] = useState(() => draftOf(product));
  const [error, setError] = useState('');
  const set = (patch: Partial<ProductDraft>) => setForm((f) => ({ ...f, ...patch }));

  const isVaccine = form.productType === 'VACCINE';
  const isDrug = form.productType === 'DRUG';
  // BR-SP-05: thuốc kê đơn và vaccine bắt buộc quản lý hạn dùng.
  const mustTrackExpiry = isVaccine || (isDrug && form.isPrescription);

  const save = useCatalogMutation(async (f: ProductDraft) => {
    // PATCH: BE giữ nguyên khi vắng / null, chỉ xóa khi nhận "" → sửa mà xóa trắng ô mô tả phải gửi "" (T49 nhóm 2, hàng 4).
    // Mô tả không đổi thì không gửi (null cũ không bị ghi thành ""); tạo mới mà để trống thì bỏ trường.
    const description = f.description.trim();
    const body = {
      categoryId: Number(f.categoryId),
      name: f.name.trim(),
      isPrescription: isDrug && f.isPrescription,
      tracksExpiry: f.tracksExpiry || mustTrackExpiry,
      vaccineTypeId: isVaccine ? Number(f.vaccineTypeId) : undefined,
      unit: f.unit.trim(),
      price: Number(f.price),
      description: product ? (description === (product.description ?? '') ? undefined : description) : description || undefined,
    };
    return product
      ? catalogApi.updateProduct(product.productId, body)
      : catalogApi.createProduct({ ...body, sku: f.sku.trim(), productType: f.productType });
  });

  const submit = async () => {
    const invalid =
      (!form.categoryId && 'Chọn danh mục.') ||
      (!product && !form.sku.trim() && 'Nhập SKU.') ||
      (!form.name.trim() && 'Nhập tên sản phẩm.') ||
      (isVaccine && !form.vaccineTypeId && 'Sản phẩm vaccine phải gắn loại vaccine') ||
      (!form.unit.trim() && 'Nhập đơn vị tính.') ||
      ((form.price === '' || Number(form.price) < 0) && 'Giá phải từ 0 đồng trở lên.');
    if (invalid) return setError(invalid);
    try {
      const saved = await save.mutateAsync(form);
      onSaved(product ? `Đã lưu ${saved.name}` : `Đã thêm sản phẩm ${saved.name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={product ? 'Sửa sản phẩm' : 'Thêm sản phẩm'}
      description={product ? `SKU ${product.sku} · loại sản phẩm không đổi được sau khi tạo` : undefined}
      onSubmit={submit}
      submitText={product ? 'Lưu thay đổi' : 'Tạo sản phẩm'}
      loading={save.isPending}
      size="lg"
    >
      <div className="grid grid-cols-2 gap-4">
        <Field label="Danh mục">
          {(id) => (
            <select id={id} className={selectClass} value={form.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
              <option value="">— Chọn danh mục —</option>
              {categories
                .filter((c) => c.isActive || String(c.categoryId) === form.categoryId)
                .map((c) => (
                  <option key={c.categoryId} value={c.categoryId}>
                    {c.name}
                  </option>
                ))}
            </select>
          )}
        </Field>
        <Field label="SKU">
          {(id) => <Input id={id} value={form.sku} maxLength={50} disabled={!!product} onChange={(e) => set({ sku: e.target.value })} />}
        </Field>
      </div>
      <Field label="Tên sản phẩm">
        {(id) => <Input id={id} value={form.name} maxLength={200} onChange={(e) => set({ name: e.target.value })} />}
      </Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Loại sản phẩm">
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={form.productType}
              disabled={!!product}
              onChange={(e) => {
                const type = e.target.value as ProductType;
                // BR-SP-01: chỉ thuốc mới có cờ kê đơn; BR-SP-07: chỉ vaccine mới gắn loại vaccine.
                set({ productType: type, isPrescription: type === 'DRUG' && form.isPrescription, vaccineTypeId: type === 'VACCINE' ? form.vaccineTypeId : '' });
              }}
            >
              {TYPES.map((t) => (
                <option key={t} value={t}>
                  {PRODUCT_TYPE_LABELS[t]}
                </option>
              ))}
            </select>
          )}
        </Field>
        {isVaccine && (
          <Field label="Loại vaccine">
            {(id) => (
              <select id={id} className={selectClass} value={form.vaccineTypeId} onChange={(e) => set({ vaccineTypeId: e.target.value })}>
                <option value="">— Chọn loại vaccine —</option>
                {vaccineTypes
                  .filter((v) => v.isActive || String(v.vaccineTypeId) === form.vaccineTypeId)
                  .map((v) => (
                    <option key={v.vaccineTypeId} value={v.vaccineTypeId}>
                      {`${v.name} · ${SPECIES_LABELS[v.species]}`}
                    </option>
                  ))}
              </select>
            )}
          </Field>
        )}
      </div>
      <div className="flex flex-wrap gap-x-6 gap-y-2">
        <Checkbox
          label="Thuốc kê đơn (chỉ bán theo đơn của bác sĩ)"
          checked={isDrug && form.isPrescription}
          disabled={!isDrug}
          onChange={(checked) => set({ isPrescription: checked })}
        />
        <Checkbox
          label="Quản lý hạn dùng (nhập, xuất theo lô)"
          checked={form.tracksExpiry || mustTrackExpiry}
          disabled={mustTrackExpiry}
          onChange={(checked) => set({ tracksExpiry: checked })}
        />
      </div>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Đơn vị tính">
          {(id) => <Input id={id} value={form.unit} maxLength={20} placeholder="viên, gói, liều…" onChange={(e) => set({ unit: e.target.value })} />}
        </Field>
        <Field label="Giá (đ)">
          {(id) => <Input id={id} type="number" min={0} step={1000} value={form.price} onChange={(e) => set({ price: e.target.value })} />}
        </Field>
      </div>
      <Field label="Mô tả">
        {(id) => (
          <textarea
            id={id}
            rows={2}
            className="w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            value={form.description}
            onChange={(e) => set({ description: e.target.value })}
          />
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
