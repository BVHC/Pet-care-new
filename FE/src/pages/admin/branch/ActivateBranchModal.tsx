import { ConfirmModal } from '../../../components/ui/modal-templates';
import type { Notice } from '../../../components/admin/form-ui';
import { branchApi } from '../../../shared/api/branch.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useBranchMutation } from '../../../shared/hooks/useBranch';
import type { Branch } from '../../../shared/types/branch';

/** UC12 — DRAFT → ACTIVE (Chi nhánh#2). BE từ chối và nêu điều kiện còn thiếu (BR-CN-01, BR-QT-04). */
export function ActivateBranchModal({
  branch,
  onClose,
  onDone,
}: {
  branch: Branch | null;
  onClose: () => void;
  onDone: (notice: Notice) => void;
}) {
  const activate = useBranchMutation((target: Branch) => branchApi.activateBranch(target.branchId));

  const confirm = async () => {
    const target = branch!;
    onClose();
    try {
      await activate.mutateAsync(target);
      onDone({ kind: 'success', text: `Đã kích hoạt chi nhánh ${target.name}` });
    } catch (e) {
      onDone({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <ConfirmModal
      open={!!branch}
      onOpenChange={(open) => !open && onClose()}
      type="info"
      title={`Kích hoạt ${branch?.name ?? ''}?`}
      description="Chi nhánh cần có ít nhất một quản lý chi nhánh và đã cấu hình giờ mở cửa (BR-CN-01, BR-QT-04). Sau khi kích hoạt, chi nhánh hiện trên trang công khai và nhận đặt lịch."
      confirmText="Kích hoạt"
      onConfirm={confirm}
      loading={activate.isPending}
    />
  );
}
