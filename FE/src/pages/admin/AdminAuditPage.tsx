// T22 — UC11: Nhật ký kiểm toán. ADMIN chỉ. Lọc theo actor/action/entity/thời gian, phân trang theo BE (mới nhất trước). Tối đa 500 dòng mock.
import { useState } from 'react';
import { Card, CardContent } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Search, X } from 'lucide-react';
import { NoticeBar, selectClass } from '../../components/admin/form-ui';
import { useAuditLogs } from '../../shared/hooks/useAdminIdentity';
import { useAccount } from '../../shared/stores/session.store';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { Pager } from '../../components/admin/Pager';

/**
 * `datetime-local` (YYYY-MM-DDTHH:mm) → date-time kèm offset giờ nghiệp vụ +07:00 (identity-v1 #36: thiếu offset → 400).
 * Không dùng toISOString(): nó hiểu giá trị theo múi giờ của trình duyệt.
 */
const withVnOffset = (local: string) => (local ? `${local.length === 16 ? `${local}:00` : local}+07:00` : undefined);

const PAGE_SIZE = 50; // BE mặc định 20, kẹp 1–100

/**
 * Loại đối tượng = TÊN BẢNG mà BE ghi vào `audit_logs.entity_type`, so khớp ĐÚNG. Đủ mọi `.entity("<bảng>", id)` của BE
 * (grep ngày 27/10/2026); BE thêm nơi ghi audit mới thì thêm bảng vào đây.
 */
const ENTITY_TYPES: { value: string; label: string }[] = [
  { value: 'accounts', label: 'Tài khoản (accounts)' },
  { value: 'system_configs', label: 'Tham số hệ thống (system_configs)' },
  { value: 'notification_templates', label: 'Mẫu thông báo (notification_templates)' },
  { value: 'customers', label: 'Hồ sơ khách (customers)' },
  { value: 'pets', label: 'Thú cưng (pets)' },
  { value: 'booking_restrictions', label: 'Hạn chế đặt online (booking_restrictions)' },
  { value: 'products', label: 'Sản phẩm (products)' },
  { value: 'services', label: 'Dịch vụ (services)' },
  { value: 'stock_receipts', label: 'Phiếu nhập kho (stock_receipts)' },
  { value: 'stock_adjustments', label: 'Phiếu điều chỉnh kho (stock_adjustments)' },
  { value: 'orders', label: 'Đơn hàng (orders)' },
  { value: 'cashier_shifts', label: 'Ca thu ngân (cashier_shifts)' },
  { value: 'visits', label: 'Lượt tiếp nhận (visits)' },
  { value: 'medical_records', label: 'Bệnh án (medical_records)' },
  { value: 'medical_record_addenda', label: 'Bản bổ sung bệnh án (medical_record_addenda)' },
  { value: 'prescription_items', label: 'Dòng đơn thuốc (prescription_items)' },
];

/**
 * Mã hành động BE đang ghi (`*AuditActions` + `QtAuditActions`, 27/10/2026), chỉ để GỢI Ý: ô nhập vẫn tự do vì BE so khớp ĐÚNG
 * chuỗi, không phải tiền tố, và hiếm khi có mã mới.
 */
const AUDIT_ACTIONS = [
  'STAFF_CREATED', 'STAFF_ROLE_CHANGED', 'STAFF_TRANSFERRED', 'STAFF_DISABLED', 'STAFF_REACTIVATED', 'STAFF_INITIAL_PASSWORD_RESENT',
  'STAFF_EMAIL_CHANGED', 'STAFF_ACTION_DENIED', 'ACCOUNT_LOCKED', 'ACCOUNT_UNLOCKED', 'ACCOUNT_TEMPORARILY_LOCKED', 'LOGIN_SUCCEEDED',
  'LOGIN_FAILED', 'CUSTOMER_PROFILE_LINKED', 'SYSTEM_CONFIG_UPDATED', 'NOTIFICATION_TEMPLATE_UPDATED', 'NOTIFICATION_TEMPLATE_RESTORED',
  'BOOKING_RESTRICTION_LIFTED', 'PRICE_CHANGED', 'PET_OWNER_TRANSFERRED', 'STOCK_RECEIPT_CANCELLED', 'STOCK_ADJUSTED', 'ORDER_PAID',
  'ORDER_CANCELLED_UNPAID', 'CASHIER_SHIFT_RECONCILED', 'VISIT_REASSIGNED', 'MEDICAL_RECORD_UPDATED', 'MEDICAL_RECORD_ADDENDUM_ADDED',
  'PRESCRIPTION_ITEM_ADDED', 'PRESCRIPTION_ITEM_REMOVED',
];

const MAX_ACTION_WINDOW_MS = 31 * 24 * 60 * 60 * 1000; // BE AuditLogFilter.MAX_ACTION_WINDOW

/**
 * BE (AuditLogFilterValidator R3): lọc `action` mà không có người thực hiện (hay loại + mã đối tượng, FE chưa có ô mã đối tượng) thì bắt
 * buộc `from` + `to` cách nhau ≤ 31 ngày, nếu không trả 400. Trả câu hướng dẫn thay vì gửi; null = gửi được.
 */
function auditFilterHint(f: { actor: string; action: string; from: string; to: string }): string | null {
  if (!f.action.trim() || f.actor) return null;
  const from = Date.parse(withVnOffset(f.from) ?? '');
  const to = Date.parse(withVnOffset(f.to) ?? '');
  if (Number.isNaN(from) || Number.isNaN(to)) {
    return 'Lọc theo hành động cần chọn cả "Từ" và "Đến" (cách nhau tối đa 31 ngày), hoặc nhập mã người thực hiện.';
  }
  return to - from > MAX_ACTION_WINDOW_MS ? 'Khi lọc theo hành động, "Từ" và "Đến" chỉ được cách nhau tối đa 31 ngày (hoặc nhập mã người thực hiện).' : null;
}

export function AdminAuditPage() {
  const account = useAccount();
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [entityType, setEntityType] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  // Đổi bộ lọc thì về trang đầu (chỉnh state ngay lúc render: cách React khuyên cho state suy ra từ state khác).
  const filterKey = [actor, action, entityType, from, to].join('|');
  const [paging, setPaging] = useState({ key: filterKey, page: 0 });
  if (paging.key !== filterKey) setPaging({ key: filterKey, page: 0 });
  const page = paging.key === filterKey ? paging.page : 0;
  const hint = auditFilterHint({ actor, action, from, to });

  const params = {
    actorAccountId: actor ? Number(actor) : undefined,
    action: action || undefined,
    entityType: entityType || undefined,
    from: withVnOffset(from),
    to: withVnOffset(to),
    page,
    size: PAGE_SIZE,
  };
  const { data, isPending, error } = useAuditLogs(params, !hint);

  if (account?.role !== 'ADMIN') {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold text-(--text-primary)">Kiểm toán</h1>
        <NoticeBar notice={{ kind: 'error', text: 'Chỉ quản trị viên mới xem được nhật ký kiểm toán.' }} />
      </div>
    );
  }

  // Bộ lọc chưa gửi được thì không hiện dòng cũ của lần lọc trước (keepPreviousData).
  const list = hint ? [] : (data?.content ?? []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Nhật ký kiểm toán</h1>
        <p className="text-(--text-secondary)">Ghi lại mọi thao tác tạo / sửa / khóa / đổi tham số. Mỗi dòng kèm trước-sau (before / after).</p>
      </div>

      {error && !hint && <NoticeBar notice={{ kind: 'error', text: plainMessage(toApiError(error)) }} />}
      {hint && <NoticeBar notice={{ kind: 'error', text: hint }} />}

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
              <Input
                id="action-input"
                list="audit-action-options"
                value={action}
                onChange={(e) => setAction(e.target.value)}
                placeholder="VD: STAFF_CREATED"
                className="pl-10"
              />
              <datalist id="audit-action-options">
                {AUDIT_ACTIONS.map((a) => <option key={a} value={a} />)}
              </datalist>
            </div>
            <p className="mt-1 text-[11px] text-(--text-tertiary)">Nhập đúng mã hành động (không phải tiền tố); chọn từ gợi ý.</p>
          </div>
          <div>
            <label className="text-xs text-(--text-tertiary)" htmlFor="entity-select">Loại đối tượng</label>
            <select id="entity-select" className={selectClass} value={entityType} onChange={(e) => setEntityType(e.target.value)}>
              <option value="">Mọi</option>
              {ENTITY_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
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
              onClick={() => { setActor(''); setAction(''); setEntityType(''); setFrom(''); setTo(''); }}
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
              {hint && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Chưa tải nhật ký: chỉnh bộ lọc theo hướng dẫn phía trên.</td></tr>}
              {!hint && isPending && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Đang tải…</td></tr>}
              {!hint && !isPending && list.length === 0 && <tr><td colSpan={6} className="text-center text-(--text-tertiary) py-8">Không có dòng nhật ký nào.</td></tr>}
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
          {data && !hint && <Pager data={data} unit="dòng" onPage={(p) => setPaging({ key: filterKey, page: p })} />}
        </CardContent>
      </Card>
    </div>
  );
}
