import { useState } from 'react';
import { Edit, Plus, Search, Trash2 } from 'lucide-react';
import { EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { ConfirmModal, FormModal, StatusBadge } from '../../../components/ui/modal-templates';
import { cn } from '../../../lib/utils';
import { inventoryApi } from '../../../shared/api/inventory.api';
import { fieldErrorsOf, plainMessage, toApiError } from '../../../shared/api/api-error';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { useInventoryMutation, useSuppliers } from '../../../shared/hooks/useInventory';
import type { Supplier } from '../../../shared/types/inventory';

const PHONE = /^0[0-9]{9,10}$/;

/** UC73 — nhà cung cấp dùng chung toàn chuỗi; đã có phiếu nhập thì chỉ ngừng hợp tác, không xóa (BR-KO-02). */
export function SuppliersTab() {
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [status, setStatus] = useState('');
  const suppliers = useSuppliers({ q: search || undefined, isActive: status ? status === 'active' : undefined });
  const [editing, setEditing] = useState<Supplier | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Supplier | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useInventoryMutation((s: Supplier) => inventoryApi.updateSupplier(s.supplierId, { isActive: !s.isActive }));
  const remove = useInventoryMutation((s: Supplier) => inventoryApi.deleteSupplier(s.supplierId));

  const run = async (action: () => Promise<unknown>, success: string) => {
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center gap-3">
          <div className="relative min-w-[220px] flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" />
            <Input aria-label="Tìm nhà cung cấp" placeholder="Tìm theo tên hoặc SĐT…" className="pl-10" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <select aria-label="Lọc trạng thái" className={cn(selectClass, 'w-44')} value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Mọi trạng thái</option>
            <option value="active">Đang hợp tác</option>
            <option value="inactive">Ngừng hợp tác</option>
          </select>
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Thêm nhà cung cấp
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Tên</th>
                <th>Số điện thoại</th>
                <th>Địa chỉ</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {suppliers.data?.length ? (
                suppliers.data.map((s) => (
                  <tr key={s.supplierId}>
                    <td className="font-medium text-(--text-primary)">{s.name}</td>
                    <td className="tabular-nums">{s.phone}</td>
                    <td className="text-(--text-secondary)">{s.address ?? '—'}</td>
                    <td>
                      <StatusBadge status={s.isActive ? 'Đang hợp tác' : 'Ngừng hợp tác'} variant={s.isActive ? 'success' : 'neutral'} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(s)} aria-label={`Sửa ${s.name}`} title="Sửa">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => run(() => toggle.mutateAsync(s), `${s.isActive ? 'Đã ngừng hợp tác với' : 'Đã hợp tác lại với'} ${s.name}`)}
                        >
                          {s.isActive ? 'Ngừng hợp tác' : 'Hợp tác lại'}
                        </Button>
                        <Button size="sm" variant="ghost" className="text-red-600" onClick={() => setDeleting(s)} aria-label={`Xóa ${s.name}`}>
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={5} loading={suppliers.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editing && (
        <SupplierForm
          supplier={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
      {deleting && (
        <ConfirmModal
          open
          onOpenChange={(open) => !open && setDeleting(null)}
          type="danger"
          title={`Xóa ${deleting.name}?`}
          description="Chỉ xóa được nhà cung cấp chưa có phiếu nhập; đã có phiếu thì chuyển ngừng hợp tác."
          confirmText="Xóa"
          loading={remove.isPending}
          onConfirm={() => {
            const target = deleting;
            setDeleting(null);
            void run(() => remove.mutateAsync(target), `Đã xóa ${target.name}`);
          }}
        />
      )}
    </div>
  );
}

function SupplierForm({ supplier, onClose, onSaved }: { supplier: Supplier | null; onClose: () => void; onSaved: (text: string) => void }) {
  const [name, setName] = useState(supplier?.name ?? '');
  const [phone, setPhone] = useState(supplier?.phone ?? '');
  const [address, setAddress] = useState(supplier?.address ?? '');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState('');
  const save = useInventoryMutation(() =>
    supplier
      ? inventoryApi.updateSupplier(supplier.supplierId, { name: name.trim(), phone: phone.trim(), address: address.trim() })
      : inventoryApi.createSupplier({ name: name.trim(), phone: phone.trim(), address: address.trim() || undefined }),
  );

  const submit = async () => {
    const found: Record<string, string> = {};
    if (!name.trim()) found.name = 'Vui lòng nhập tên nhà cung cấp';
    else if (name.trim().length > 200) found.name = 'Tên tối đa 200 ký tự';
    if (!PHONE.test(phone.trim())) found.phone = 'Số điện thoại bắt đầu bằng 0, gồm 10–11 chữ số';
    if (address.trim().length > 300) found.address = 'Địa chỉ tối đa 300 ký tự';
    setErrors(found);
    setFormError('');
    if (Object.keys(found).length) return;
    try {
      await save.mutateAsync(undefined);
      onSaved(supplier ? `Đã cập nhật ${name.trim()}` : `Đã thêm ${name.trim()}`);
    } catch (e) {
      const error = toApiError(e);
      const fields = fieldErrorsOf(error);
      if (Object.keys(fields).length) setErrors(fields);
      else setFormError(plainMessage(error));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={supplier ? 'Sửa nhà cung cấp' : 'Thêm nhà cung cấp'}
      description="Dùng chung toàn chuỗi."
      onSubmit={submit}
      loading={save.isPending}
    >
      <Field label="Tên nhà cung cấp" error={errors.name}>
        {(id) => <Input id={id} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} autoFocus />}
      </Field>
      <Field label="Số điện thoại" error={errors.phone}>
        {(id) => <Input id={id} value={phone} inputMode="tel" maxLength={11} onChange={(e) => setPhone(e.target.value)} />}
      </Field>
      <Field label="Địa chỉ" error={errors.address}>
        {(id) => <Input id={id} value={address} maxLength={300} onChange={(e) => setAddress(e.target.value)} />}
      </Field>
      <FormError message={formError} />
    </FormModal>
  );
}
