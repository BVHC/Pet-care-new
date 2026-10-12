import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { ConfirmModal, FormModal, StatusBadge } from '../../../components/ui/modal-templates';
import { EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { cn, formatDate } from '../../../lib/utils';
import { branchApi } from '../../../shared/api/branch.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useBranchMutation, useHolidays } from '../../../shared/hooks/useBranch';
import type { Branch, Holiday, ScheduleImpact } from '../../../shared/types/branch';
import { DAY_LABELS, isEmptyImpact, isoDayOfWeek, localIsoDate } from '../../../shared/utils/branch-schedule';
import { ImpactList } from './ImpactList';

const dayText = (date: string) => `${formatDate(date)} (${DAY_LABELS[isoDayOfWeek(date)]})`;

/** UC14 — ngày nghỉ cả ngày của chi nhánh (BR-CN-03); ngày còn lịch thì phải chọn hủy hàng loạt (BR-LH-10). */
export function HolidaysTab({ branch, canEdit }: { branch: Branch; canEdit: boolean }) {
  const today = localIsoDate();
  const [scope, setScope] = useState<'upcoming' | 'all'>('upcoming');
  const holidays = useHolidays(branch.branchId, scope === 'upcoming' ? today : undefined);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState<Holiday | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const remove = useBranchMutation((h: Holiday) => branchApi.deleteHoliday(branch.branchId, h.holidayId));

  const confirmDelete = async () => {
    const target = deleting!;
    setDeleting(null);
    try {
      await remove.mutateAsync(target);
      setNotice({ kind: 'success', text: `Đã xóa ngày nghỉ ${formatDate(target.holidayDate)}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center justify-between gap-3">
          <select aria-label="Hiển thị" className={cn(selectClass, 'w-44')} value={scope} onChange={(e) => setScope(e.target.value as 'upcoming' | 'all')}>
            <option value="upcoming">Từ hôm nay</option>
            <option value="all">Tất cả</option>
          </select>
          {canEdit && (
            <Button onClick={() => setAdding(true)}>
              <Plus className="h-4 w-4" />
              Thêm ngày nghỉ
            </Button>
          )}
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Ngày</th>
                <th>Lý do</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {holidays.data?.length ? (
                holidays.data.map((h) => {
                  const ahead = h.holidayDate > today;
                  return (
                    <tr key={h.holidayId}>
                      <td className="font-medium text-(--text-primary)">{dayText(h.holidayDate)}</td>
                      <td className="text-(--text-secondary)">{h.reason || '—'}</td>
                      <td>
                        <StatusBadge
                          status={ahead ? 'Sắp tới' : h.holidayDate === today ? 'Hôm nay' : 'Đã qua'}
                          variant={ahead ? 'info' : h.holidayDate === today ? 'warning' : 'neutral'}
                        />
                      </td>
                      <td>
                        <div className="flex justify-end">
                          {canEdit && ahead && (
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => setDeleting(h)}
                              aria-label={`Xóa ngày nghỉ ${formatDate(h.holidayDate)}`}
                              title="Xóa"
                              className="text-red-500 hover:text-red-600"
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              ) : (
                <EmptyRow colSpan={4} loading={holidays.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {adding && (
        <HolidayForm
          branch={branch}
          onClose={() => setAdding(false)}
          onSaved={(text) => {
            setAdding(false);
            setNotice({ kind: 'success', text });
          }}
        />
      )}

      <ConfirmModal
        open={!!deleting}
        onOpenChange={(open) => !open && setDeleting(null)}
        type="danger"
        title={`Xóa ngày nghỉ ${deleting ? formatDate(deleting.holidayDate) : ''}?`}
        description="Lịch đã bị hủy hàng loạt khi thêm ngày nghỉ này không được khôi phục."
        confirmText="Xóa"
        onConfirm={confirmDelete}
        loading={remove.isPending}
      />
    </div>
  );
}

function HolidayForm({ branch, onClose, onSaved }: { branch: Branch; onClose: () => void; onSaved: (notice: string) => void }) {
  const today = localIsoDate();
  const [date, setDate] = useState('');
  const [reason, setReason] = useState('');
  const [impact, setImpact] = useState<ScheduleImpact | null>(null);
  const [cancel, setCancel] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState('');
  const add = useBranchMutation((cancelAffected: boolean) =>
    branchApi.addHoliday(branch.branchId, { holidayDate: date, reason: reason.trim() || undefined, cancelAffected }),
  );

  const submit = async () => {
    const invalid = (!date && 'Chọn ngày nghỉ.') || (date < today && 'Không thêm được ngày nghỉ trong quá khứ.');
    if (invalid) return setError(invalid);
    setError('');
    try {
      let affected = impact;
      if (!affected) {
        setChecking(true);
        affected = await branchApi.previewHoliday(branch.branchId, date).finally(() => setChecking(false));
        if (!isEmptyImpact(affected)) return setImpact(affected);
      } else if (!cancel) {
        return setError('Chọn hủy hàng loạt để thêm ngày nghỉ, hoặc chọn ngày khác.');
      }
      const cancelled = !isEmptyImpact(affected);
      await add.mutateAsync(cancelled);
      onSaved(
        `Đã thêm ngày nghỉ ${formatDate(date)}` +
          (cancelled ? `, hủy ${affected.appointments.length} lịch hẹn và ${affected.boardingBookings.length} đặt chỗ lưu trú` : ''),
      );
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Thêm ngày nghỉ"
      description="Nghỉ cả ngày: không sinh khung đặt lịch, không nhận / trả thú lưu trú (BR-CN-03)."
      onSubmit={submit}
      submitText={impact ? 'Thêm và hủy hàng loạt' : 'Kiểm tra và thêm'}
      loading={checking || add.isPending}
      size="lg"
    >
      <Field label="Ngày nghỉ">
        {(id) => (
          <Input
            id={id}
            type="date"
            min={today}
            value={date}
            onChange={(e) => {
              setDate(e.target.value);
              setImpact(null);
              setCancel(false);
            }}
            className="w-48!"
          />
        )}
      </Field>
      <Field label="Lý do">
        {(id) => <Input id={id} value={reason} maxLength={200} placeholder="Lễ, sự cố, bảo trì…" onChange={(e) => setReason(e.target.value)} />}
      </Field>
      {impact && <ImpactList impact={impact} cancel={cancel} onCancelChange={setCancel} />}
      <FormError message={error} />
    </FormModal>
  );
}
