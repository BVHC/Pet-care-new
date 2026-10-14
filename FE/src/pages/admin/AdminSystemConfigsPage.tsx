// T22 — UC10: Tham số hệ thống. ADMIN chỉ. Sửa inline, validate số ngay trong form.
import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Edit, Save, X, AlertTriangle } from 'lucide-react';
import { toast } from 'sonner';
import { FormModal } from '../../components/ui/modal-templates';
import { Field, FormError, NoticeBar, selectClass, type Notice } from '../../components/admin/form-ui';
import { useSystemConfigs, useUpdateSystemConfig } from '../../shared/hooks/useAdminIdentity';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useAccount } from '../../shared/stores/session.store';
import type { SystemConfig, SystemConfigValueType } from '../../shared/types/identity';

const TYPE_LABEL: Record<SystemConfigValueType, string> = {
  INT: 'Số nguyên',
  DECIMAL: 'Số thập phân',
  BOOL: 'Đúng / Sai',
  TIME: 'Thời gian',
};

export function AdminSystemConfigsPage() {
  const account = useAccount();
  const { data, isPending } = useSystemConfigs();
  const update = useUpdateSystemConfig();
  const [editing, setEditing] = useState<SystemConfig | null>(null);
  const [draft, setDraft] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);

  if (account?.role !== 'ADMIN') {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold text-(--text-primary)">Tham số hệ thống</h1>
        <NoticeBar notice={{ kind: 'error', text: 'Chỉ quản trị viên mới có thể sửa tham số hệ thống.' }} />
      </div>
    );
  }

  const list = data ?? [];

  const open = (c: SystemConfig) => {
    setEditing(c);
    setDraft(c.value);
  };

  const handleError = (e: unknown) => {
    const msg = plainMessage(toApiError(e));
    toast.error(msg);
    setNotice({ kind: 'error', text: msg });
  };

  const handleSave = async () => {
    if (!editing) return;
    try {
      await update.mutateAsync({ key: editing.key, value: draft.trim() });
      toast.success(`Đã cập nhật ${editing.key}`);
      setEditing(null);
    } catch (e) { handleError(e); }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Tham số hệ thống</h1>
        <p className="text-(--text-secondary)">Cấu hình toàn hệ thống — hiệu lực ngay. Lỗi sẽ ghi vào nhật ký kiểm toán (UC11). Số ngoài khoảng → BR-QT-13.</p>
      </div>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Khóa</th>
                <th>Giá trị</th>
                <th>Kiểu</th>
                <th>Khoảng</th>
                <th>Mô tả</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {isPending && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Đang tải…</td></tr>}
              {!isPending && list.length === 0 && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Chưa có tham số.</td></tr>}
              {list.map((c) => (
                <tr key={c.key}>
                  <td className="font-mono text-xs text-(--text-primary)">{c.key}</td>
                  <td className="font-mono text-(--text-secondary)">{c.value}{c.unit ? ` ${c.unit}` : ''}</td>
                  <td><span className="rounded-full bg-(--bg-tertiary) px-2 py-0.5 text-xs">{TYPE_LABEL[c.valueType]}</span></td>
                  <td className="text-xs text-(--text-tertiary)">{c.minValue ?? '—'} → {c.maxValue ?? '—'}</td>
                  <td className="text-xs text-(--text-secondary)">{c.description}</td>
                  <td className="text-right">
                    <Button size="sm" variant="ghost" onClick={() => open(c)} title="Sửa" aria-label={`Sửa ${c.key}`}>
                      <Edit className="h-4 w-4" />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <FormModal
        open={!!editing}
        onOpenChange={(o) => !o && setEditing(null)}
        title={`Sửa ${editing?.key ?? ''}`}
        description={editing?.description}
        onSubmit={handleSave}
        submitText="Lưu thay đổi"
        loading={update.isPending}
        size="sm"
      >
        <Field
          label={`Giá trị (${editing ? TYPE_LABEL[editing.valueType] : ''}) *`}
          hint={editing && (editing.minValue || editing.maxValue) ? `Khoảng cho phép: ${editing.minValue ?? '—'} → ${editing.maxValue ?? '—'}` : undefined}
        >
          {(id) => (
            editing?.valueType === 'BOOL' ? (
              <select id={id} className={selectClass} value={draft} onChange={(e) => setDraft(e.target.value)}>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
            ) : (
              <Input id={id} value={draft} onChange={(e) => setDraft(e.target.value)} required />
            )
          )}
        </Field>
        {update.isError && <FormError message={plainMessage(toApiError(update.error))} />}
      </FormModal>
    </div>
  );
}
