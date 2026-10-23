// T22 — UC11: Nhật ký kiểm toán. ADMIN chỉ. Lọc theo actor/action/entity/thời gian. Tối đa 500 dòng mock.
import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Search, X } from 'lucide-react';
import { NoticeBar, selectClass } from '../../components/admin/form-ui';
import { useAuditLogs } from '../../shared/hooks/useAdminIdentity';
import { useAccount } from '../../shared/stores/session.store';
import { plainMessage, toApiError } from '../../shared/api/api-error';

/**
 * `datetime-local` (YYYY-MM-DDTHH:mm) → date-time kèm offset giờ nghiệp vụ +07:00 (identity-v1 #36: thiếu offset → 400).
 * Không dùng toISOString(): nó hiểu giá trị theo múi giờ của trình duyệt.
 */
const withVnOffset = (local: string) => (local ? `${local.length === 16 ? `${local}:00` : local}+07:00` : undefined);

export function AdminAuditPage() {
  const account = useAccount();
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [entityType, setEntityType] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);

  const params = {
    actorAccountId: actor ? Number(actor) : undefined,
    action: action || undefined,
    entityType: entityType || undefined,
    from: withVnOffset(from),
    to: withVnOffset(to),
    page,
    size: 50,
  };
  const { data, isPending, error } = useAuditLogs(params);

  if (account?.role !== 'ADMIN') {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold text-(--text-primary)">Kiểm toán</h1>
        <NoticeBar notice={{ kind: 'error', text: 'Chỉ quản trị viên mới xem được nhật ký kiểm toán.' }} />
      </div>
    );
  }

  const list = data?.content ?? [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Nhật ký kiểm toán</h1>
        <p className="text-(--text-secondary)">Ghi lại mọi thao tác tạo / sửa / khóa / đổi tham số. Mỗi dòng kèm trước-sau (before / after).</p>
      </div>

      {error && <NoticeBar notice={{ kind: 'error', text: plainMessage(toApiError(error)) }} />}

      <Card className="card">
        <CardContent className="p-4 grid grid-cols-1 md:grid-cols-6 gap-3">
          <div>
            <label className="text-xs text-(--text-tertiary)" htmlFor="actor-input">Mã người thực hiện</label>
            <Input id="actor-input" type="number" value={actor} onChange={(e) => setActor(e.target.value)} placeholder="VD: 2" />
          </div>
          <div>
            <label className="text-xs text-(--text-tertiary)" htmlFor="action-input">Hành động</label>
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-(--text-tertiary)" />
              <Input id="action-input" value={action} onChange={(e) => setAction(e.target.value)} placeholder="VD: STAFF_" className="pl-10" />
            </div>
          </div>
          <div>
            <label className="text-xs text-(--text-tertiary)" htmlFor="entity-select">Loại đối tượng</label>
            <select id="entity-select" className={selectClass} value={entityType} onChange={(e) => setEntityType(e.target.value)}>
              <option value="">Mọi</option>
              <option value="Staff">Staff</option>
              <option value="Account">Account</option>
              <option value="SystemConfig">SystemConfig</option>
              <option value="NotificationTemplate">NotificationTemplate</option>
            </select>
          </div>
          <div>
            <label className="text-xs text-(--text-tertiary)" htmlFor="from-input">Từ</label>
            <Input id="from-input" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
          <div>
            <label className="text-xs text-(--text-tertiary)" htmlFor="to-input">Đến</label>
            <Input id="to-input" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div className="flex items-end">
            <Button
              variant="outline"
              onClick={() => { setActor(''); setAction(''); setEntityType(''); setFrom(''); setTo(''); setPage(0); }}
            >
              <X className="mr-2 h-4 w-4" /> Xóa lọc
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Thời điểm</th>
                <th>Người thực hiện</th>
                <th>Hành động</th>
                <th>Đối tượng</th>
                <th>Lý do</th>
                <th>Sự thay đổi</th>
              </tr>
            </thead>
            <tbody>
              {isPending && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Đang tải…</td></tr>}
              {!isPending && list.length === 0 && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Không có dòng nhật ký nào.</td></tr>}
              {list.map((a) => (
                <tr key={a.id}>
                  <td className="text-xs text-(--text-tertiary) whitespace-nowrap">{new Date(a.createdAt).toLocaleString('vi-VN')}</td>
                  <td className="text-xs">
                    {a.actorAccountId ? (
                      <span>#{a.actorAccountId} <span className="text-(--text-tertiary)">{a.actorEmail ?? ''}</span></span>
                    ) : (
                      <span className="text-(--text-tertiary)">hệ thống</span>
                    )}
                  </td>
                  <td><span className="rounded-full bg-(--bg-tertiary) px-2 py-0.5 text-xs font-mono">{a.action}</span></td>
                  <td className="text-xs">{a.entityType}{a.entityId ? ` #${a.entityId}` : ''}</td>
                  <td className="text-xs text-(--text-secondary)">{a.reason ?? '—'}</td>
                  <td className="text-xs font-mono text-(--text-tertiary) max-w-md truncate" title={JSON.stringify({ before: a.beforeData, after: a.afterData })}>
                    {a.beforeData && a.afterData ? (
                      <span>
                        <span className="text-red-500">{Object.entries(a.beforeData).map(([k, v]) => `${k}: ${v}`).join(', ')}</span>
                        {' → '}
                        <span className="text-green-600">{Object.entries(a.afterData).map(([k, v]) => `${k}: ${v}`).join(', ')}</span>
                      </span>
                    ) : a.afterData ? (
                      <span className="text-green-600">+ {Object.entries(a.afterData).map(([k, v]) => `${k}: ${v}`).join(', ')}</span>
                    ) : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  );
}
