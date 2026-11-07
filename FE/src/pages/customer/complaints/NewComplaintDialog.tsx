import { useState } from 'react';
import { FormError } from '@/components/admin/form-ui';
import { EMPTY_COMPLAINT_DRAFT, complaintRequestOf, type ComplaintDraft } from '@/components/complaint/draft';
import { ComplaintFields } from '@/components/complaint/ComplaintFields';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { complaintApi } from '@/shared/api/complaint.api';
import { useComplaintMutation } from '@/shared/hooks/useComplaints';
import type { PublicBranch } from '@/shared/types/branch';
import type { Complaint } from '@/shared/types/complaint';

interface Props {
  branches: PublicBranch[];
  onClose: () => void;
  onCreated: (complaint: Complaint) => void;
}

/** Gửi khiếu nại mới (complaint-v1 #1). Lỗi BR-DG-05 (nội dung 10–2 000, thiếu chi nhánh, đối tượng không phải của bạn, quá 10 khiếu nại đang mở) hiện ngay trong hộp. */
export function NewComplaintDialog({ branches, onClose, onCreated }: Props) {
  const [draft, setDraft] = useState<ComplaintDraft>(EMPTY_COMPLAINT_DRAFT);
  const [error, setError] = useState('');
  const create = useComplaintMutation(complaintApi.createMyComplaint);

  const submit = () => {
    const body = complaintRequestOf(draft, draft.branchId ? Number(draft.branchId) : null);
    if (typeof body === 'string') return setError(body);
    create.mutate(body, { onSuccess: onCreated, onError: (e) => setError(plainMessage(toApiError(e))) });
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Gửi khiếu nại</DialogTitle>
          <DialogDescription>Chi nhánh sẽ nhận và phản hồi bạn ngay trong mục Khiếu nại.</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <ComplaintFields
            draft={draft}
            branches={branches}
            onChange={(patch) => {
              setDraft((d) => ({ ...d, ...patch }));
              setError('');
            }}
          />
          <FormError message={error} />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={create.isPending}>
              Hủy
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Đang gửi…' : 'Gửi khiếu nại'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
