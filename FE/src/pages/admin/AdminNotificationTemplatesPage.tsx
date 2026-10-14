// T22 — UC10: Mẫu thông báo. ADMIN chỉ. Sửa nội dung + khôi phục mặc định; biến phải nằm trong allowed.
import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Edit, RotateCcw, Mail, Bell } from 'lucide-react';
import { toast } from 'sonner';
import { FormModal } from '../../components/ui/modal-templates';
import { Field, FormError, NoticeBar, type Notice } from '../../components/admin/form-ui';
import { useNotificationTemplates, useRestoreTemplateDefault, useUpdateTemplate } from '../../shared/hooks/useAdminIdentity';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useAccount } from '../../shared/stores/session.store';
import type { NotificationTemplate } from '../../shared/types/identity';

export function AdminNotificationTemplatesPage() {
  const account = useAccount();
  const { data, isPending } = useNotificationTemplates();
  const update = useUpdateTemplate();
  const restore = useRestoreTemplateDefault();
  const [editing, setEditing] = useState<NotificationTemplate | null>(null);
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [notice, setNotice] = useState<Notice | null>(null);

  if (account?.role !== 'ADMIN') {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold text-(--text-primary)">Mẫu thông báo</h1>
        <NoticeBar notice={{ kind: 'error', text: 'Chỉ quản trị viên mới có thể sửa mẫu thông báo.' }} />
      </div>
    );
  }

  const list = data ?? [];

  const open = (t: NotificationTemplate) => {
    setEditing(t);
    setSubject(t.subject ?? '');
    setBody(t.body);
  };

  const handleError = (e: unknown) => {
    const msg = plainMessage(toApiError(e));
    toast.error(msg);
    setNotice({ kind: 'error', text: msg });
  };

  const handleSave = async () => {
    if (!editing) return;
    try {
      await update.mutateAsync({
        code: editing.code,
        subject: editing.channel === 'EMAIL' ? (subject || null) : null,
        body,
      });
      toast.success(`Đã cập nhật mẫu ${editing.code}`);
      setEditing(null);
    } catch (e) { handleError(e); }
  };

  const handleRestore = async (code: string) => {
    try {
      await restore.mutateAsync(code);
      toast.success(`Đã khôi phục mặc định mẫu ${code}`);
    } catch (e) { handleError(e); }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Mẫu thông báo</h1>
        <p className="text-(--text-secondary)">
          Mẫu email / thông báo in-app. Sửa nội dung và biến <code className="rounded bg-(--bg-tertiary) px-1">{'{varName}'}</code>; thiếu biến bắt buộc hoặc dùng biến lạ → BR-QT-14.
        </p>
      </div>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Mã</th>
                <th>Kênh</th>
                <th>Tiêu đề / Nội dung</th>
                <th>Biến bắt buộc</th>
                <th>Mặc định</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {isPending && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Đang tải…</td></tr>}
              {!isPending && list.length === 0 && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Chưa có mẫu.</td></tr>}
              {list.map((t) => (
                <tr key={t.code}>
                  <td className="font-mono text-xs">{t.code}</td>
                  <td>
                    <span className="inline-flex items-center gap-1 rounded-full bg-(--bg-tertiary) px-2 py-0.5 text-xs">
                      {t.channel === 'EMAIL' ? <Mail className="h-3 w-3" /> : <Bell className="h-3 w-3" />}
                      {t.channel}
                    </span>
                  </td>
                  <td>
                    {t.subject && <p className="text-sm font-medium text-(--text-primary)">{t.subject}</p>}
                    <p className="text-xs text-(--text-secondary) line-clamp-2 whitespace-pre-wrap">{t.body}</p>
                  </td>
                  <td className="text-xs font-mono text-(--text-tertiary)">{t.requiredVars.join(', ')}</td>
                  <td>{t.isDefault ? <span className="text-xs text-green-600">Mặc định</span> : <span className="text-xs text-orange-600">Đã sửa</span>}</td>
                  <td>
                    <div className="flex justify-end gap-1">
                      <Button size="sm" variant="ghost" onClick={() => open(t)} title="Sửa" aria-label={`Sửa ${t.code}`}>
                        <Edit className="h-4 w-4" />
                      </Button>
                      {!t.isDefault && (
                        <Button size="sm" variant="ghost" onClick={() => handleRestore(t.code)} title="Khôi phục mặc định" aria-label={`Khôi phục mặc định ${t.code}`}>
                          <RotateCcw className="h-4 w-4" />
                        </Button>
                      )}
                    </div>
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
        title={`Sửa mẫu ${editing?.code ?? ''}`}
        description={`Biến được phép: ${editing?.allowedVars.map((v) => `{${v}}`).join(', ')}`}
        onSubmit={handleSave}
        submitText="Lưu"
        loading={update.isPending}
        size="md"
      >
        {editing?.channel === 'EMAIL' && (
          <Field label="Tiêu đề">
            {(id) => <Input id={id} value={subject} onChange={(e) => setSubject(e.target.value)} />}
          </Field>
        )}
        <Field label="Nội dung *" hint="Dùng biến như {fullName}, {code}… đã liệt kê ở trên.">
          {(id) => (
            <textarea
              id={id}
              value={body}
              onChange={(e) => setBody(e.target.value)}
              className="input min-h-32 font-mono text-sm"
              rows={8}
              required
            />
          )}
        </Field>
        {update.isError && <FormError message={plainMessage(toApiError(update.error))} />}
      </FormModal>
    </div>
  );
}
