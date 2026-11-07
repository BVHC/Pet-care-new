/**
 * Lễ tân tạo khiếu nại thay khách (UC85, A06) — complaint-v1 #7. Có `customerId` (từ hồ sơ khách đang mở) thì vào thẳng biểu mẫu;
 * không có thì tìm khách trước (UC23). Chi nhánh luôn là chi nhánh của lễ tân (khác thì máy chủ trả 403). Nơi gọi chỉ cần giữ `open`.
 */
import { useState } from 'react';
import { Search } from 'lucide-react';
import { toast } from 'sonner';
import { EMPTY_COMPLAINT_DRAFT, complaintRequestOf, type ComplaintDraft } from '@/components/complaint/draft';
import { ComplaintFields } from '@/components/complaint/ComplaintFields';
import { FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { LookupTruncatedNote } from '@/components/ui/LookupTruncatedNote';
import { useCustomerDetail, useCustomerLookup } from '@/domains/pets/hooks/useCustomerPet';
import type { CustomerSearchQuery } from '@/domains/pets/types';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { complaintApi } from '@/shared/api/complaint.api';
import { useComplaintMutation } from '@/shared/hooks/useComplaints';
import { useDebounce } from '@/shared/hooks/useDebounce';
import { useStaffBranch } from '@/shared/hooks/useStaffBranch';

const PHONE = /^0\d{9}$/;
/** SĐT chỉ tra khi đủ 10 số; họ tên từ 2 ký tự (BR-KH-10). */
const queryOf = (term: string): CustomerSearchQuery =>
  /^\d+$/.test(term) ? { phone: PHONE.test(term) ? term : '' } : { name: term.length >= 2 ? term : '' };

export interface CreateComplaintDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Khách đã biết (vd đang mở hồ sơ); vắng thì hộp có bước tìm khách. */
  customerId?: number;
}

export function CreateComplaintDialog({ open, onOpenChange, customerId }: CreateComplaintDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Tạo khiếu nại thay khách</DialogTitle>
          <DialogDescription>Ghi lại khiếu nại khách nói tại quầy. Quản lý chi nhánh sẽ nhận và xử lý.</DialogDescription>
        </DialogHeader>
        {open && <CreateForm fixedCustomerId={customerId} onClose={() => onOpenChange(false)} />}
      </DialogContent>
    </Dialog>
  );
}

function CreateForm({ fixedCustomerId, onClose }: { fixedCustomerId?: number; onClose: () => void }) {
  const [picked, setPicked] = useState<number | null>(null);
  const customerId = fixedCustomerId ?? picked;
  const detail = useCustomerDetail(customerId);
  const { branchId, isPending: branchPending } = useStaffBranch();
  const [draft, setDraft] = useState<ComplaintDraft>(EMPTY_COMPLAINT_DRAFT);
  const [error, setError] = useState('');
  const create = useComplaintMutation(complaintApi.createComplaintForCustomer);

  if (customerId == null) return <CustomerPicker onPick={setPicked} />;

  const submit = () => {
    const body = complaintRequestOf(draft, branchId);
    if (typeof body === 'string') return setError(body);
    create.mutate(
      { ...body, customerId },
      {
        onSuccess: (c) => {
          toast.success(`Đã tạo khiếu nại ${c.code} cho ${c.customerName}`);
          onClose();
        },
        onError: (e) => setError(plainMessage(toApiError(e))),
      },
    );
  };

  const customer = detail.data?.customer;
  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <p className="flex items-center justify-between gap-3 rounded-lg bg-(--bg-secondary) px-3 py-2 text-sm">
        <span>
          Khách: <strong>{customer?.fullName ?? `#${customerId}`}</strong>
          {customer?.phone && <span className="text-(--text-secondary)"> · {customer.phone}</span>}
        </span>
        {fixedCustomerId == null && (
          <Button type="button" variant="ghost" size="sm" onClick={() => setPicked(null)}>
            Đổi khách
          </Button>
        )}
      </p>
      <ComplaintFields
        draft={draft}
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
        <Button type="submit" disabled={create.isPending || branchPending}>
          {create.isPending ? 'Đang lưu…' : 'Tạo khiếu nại'}
        </Button>
      </DialogFooter>
    </form>
  );
}

function CustomerPicker({ onPick }: { onPick: (customerId: number) => void }) {
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term.trim(), 250);
  const lookup = useCustomerLookup(queryOf(debounced));
  const waitingForPhone = /^\d+$/.test(term.trim()) && !PHONE.test(term.trim());
  const items = debounced && !waitingForPhone ? (lookup.data?.content ?? []) : [];
  return (
    <div className="space-y-3">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" aria-hidden />
        <Input aria-label="Tìm khách theo SĐT hoặc họ tên" placeholder="SĐT 10 số hoặc họ tên" className="pl-9" value={term} onChange={(e) => setTerm(e.target.value)} autoFocus />
      </div>
      {waitingForPhone && <p className="text-xs text-(--text-secondary)">Nhập đủ 10 số điện thoại để tra cứu.</p>}
      {lookup.isError && <FormError message={plainMessage(toApiError(lookup.error))} />}
      {debounced && !waitingForPhone && lookup.isSuccess && items.length === 0 && <p className="text-sm text-(--text-secondary)">Không tìm thấy khách.</p>}
      {items.length > 0 && lookup.data && <LookupTruncatedNote shown={items.length} total={lookup.data.totalElements} className="text-xs text-(--text-secondary)" />}
      <ul className="space-y-2" aria-label="Kết quả tìm khách">
        {items.map((c) => (
          <li key={c.customerId} className="flex items-start justify-between gap-3 rounded-lg border border-(--color-border-default) p-3">
            <div className="min-w-0 text-sm">
              <p className="font-semibold">{c.fullName}</p>
              <p className="text-xs text-(--text-secondary)">
                {c.phone ?? 'Chưa có SĐT'} · {c.maskedEmail ?? 'Chưa có email'}
              </p>
            </div>
            <Button type="button" size="sm" variant="outline" onClick={() => onPick(c.customerId)} aria-label={`Chọn khách ${c.fullName}`}>
              Chọn
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}
