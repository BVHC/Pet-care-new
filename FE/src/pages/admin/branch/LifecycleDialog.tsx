import { useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Checkbox, Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { branchlifeApi } from '../../../shared/api/branchlife.api';
import { useBranchLifeMutation } from '../../../shared/hooks/useBranchLife';
import { usePublicBranches } from '../../../shared/hooks/usePublicSite';
import type { Branch } from '../../../shared/types/branch';
import { BRANCH_LIFECYCLE_LIMITS, type ClosureImpact } from '../../../shared/types/branchlife';
import { vnDate } from '../../../shared/utils/vn-time';

/**
 * Hộp tạm ngừng (#2) và đóng cửa (#4). Đóng cửa không hoàn tác nên phải gõ lại tên chi nhánh. Điều kiện chặn (BR-CN-08) khóa nút gửi;
 * lịch hẹn / đặt chỗ đang chờ phải chọn hủy hàng loạt (BR-CN-09); Care Task OPEN chuyển sang chi nhánh khác hoặc bị hủy kèm lý do.
 */
export function LifecycleDialog({
  mode,
  branch,
  impact,
  onClose,
  onDone,
}: {
  mode: 'suspend' | 'close';
  branch: Branch;
  impact: ClosureImpact;
  onClose: () => void;
  onDone: (text: string) => void;
}) {
  const closing = mode === 'close';
  const blockers = closing ? impact.closeBlockers : impact.suspendBlockers;
  const others = usePublicBranches();
  const [reason, setReason] = useState('');
  const [resumeOn, setResumeOn] = useState('');
  const [cancelBookings, setCancelBookings] = useState(false);
  const [careTarget, setCareTarget] = useState('');
  const [typedName, setTypedName] = useState('');
  const [error, setError] = useState('');
  const run = useBranchLifeMutation(() => {
    const common = { reason: reason.trim(), cancelBookings, careTaskTargetBranchId: careTarget ? Number(careTarget) : null };
    return closing ? branchlifeApi.close(branch.branchId, common) : branchlifeApi.suspend(branch.branchId, { ...common, expectedResumeOn: resumeOn || null });
  });
  const targets = (others.data ?? []).filter((b) => b.branchId !== branch.branchId);

  const submit = async () => {
    let problem = '';
    if (!reason.trim()) problem = 'Vui lòng nhập lý do';
    else if (impact.bookedAppointments > 0 && !cancelBookings) problem = `Chọn hủy hàng loạt để tiếp tục (còn ${impact.bookedAppointments} lịch hẹn và đặt chỗ đang chờ)`;
    else if (closing && typedName.trim() !== branch.name) problem = 'Gõ đúng tên chi nhánh để xác nhận đóng cửa';
    setError(problem);
    if (problem) return;
    try {
      await run.mutateAsync(undefined);
      onDone(closing ? `Đã đóng cửa vĩnh viễn chi nhánh ${branch.name}` : `Đã tạm ngừng chi nhánh ${branch.name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={closing ? `Đóng cửa vĩnh viễn ${branch.name}` : `Tạm ngừng ${branch.name}`}
      description={closing ? 'Đóng cửa không hoàn tác được và chi nhánh không bao giờ mở lại.' : 'Chi nhánh ngừng nhận lịch mới, mở lại được sau.'}
      onSubmit={submit}
      submitText={closing ? 'Đóng cửa vĩnh viễn' : 'Tạm ngừng chi nhánh'}
      loading={run.isPending}
      submitDisabled={blockers.length > 0}
      size="xl"
    >
      {closing && (
        <p role="note" className="flex gap-2 rounded-lg bg-red-50 px-3 py-2 text-sm font-medium text-red-700">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          Thao tác này không thể hoàn tác. Hồ sơ chi nhánh chỉ còn để tra cứu.
        </p>
      )}
      {blockers.length > 0 && (
        <div role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
          <p className="font-semibold">{closing ? 'Chưa đóng cửa được vì:' : 'Chưa tạm ngừng được vì:'}</p>
          <ul className="list-disc pl-5">
            {blockers.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        </div>
      )}
      <Field label="Lý do" hint={`Tối đa ${BRANCH_LIFECYCLE_LIMITS.reason} ký tự; được ghi nhật ký.`}>
        {(id) => <Input id={id} value={reason} maxLength={BRANCH_LIFECYCLE_LIMITS.reason} onChange={(e) => setReason(e.target.value)} autoFocus />}
      </Field>
      {!closing && (
        <Field label="Ngày dự kiến mở lại" hint="Không bắt buộc.">
          {(id) => <Input id={id} type="date" min={vnDate()} value={resumeOn} onChange={(e) => setResumeOn(e.target.value)} className="w-48" />}
        </Field>
      )}
      {impact.bookedAppointments > 0 && (
        <Checkbox
          label={`Hủy hàng loạt ${impact.bookedAppointments} lịch hẹn và đặt chỗ lưu trú đang chờ (khách được báo)`}
          checked={cancelBookings}
          onChange={setCancelBookings}
        />
      )}
      <Field label="Việc gọi điện đang chờ" hint="Chọn chi nhánh nhận tiếp, hoặc hủy kèm lý do đã ghi ở trên.">
        {(id) => (
          <select id={id} className={selectClass} value={careTarget} onChange={(e) => setCareTarget(e.target.value)}>
            <option value="">Hủy các việc đang chờ</option>
            {targets.map((b) => (
              <option key={b.branchId} value={b.branchId}>
                Chuyển sang {b.name}
              </option>
            ))}
          </select>
        )}
      </Field>
      {closing && (
        <Field label={`Gõ "${branch.name}" để xác nhận`}>{(id) => <Input id={id} value={typedName} onChange={(e) => setTypedName(e.target.value)} autoComplete="off" />}</Field>
      )}
      <FormError message={error} />
    </FormModal>
  );
}
