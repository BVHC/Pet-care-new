/**
 * Chuyển chủ thú cưng (UC26, BR-KH-08) — lễ tân. Chủ mới phải đã có hồ sơ; chủ cũ có mặt hoặc đã đồng ý
 * (lễ tân tích xác nhận, A4). BE chặn khi thú có lượt khám mở, Order PENDING hoặc đang lưu trú.
 */
import { useState } from 'react';
import { ArrowRightLeft, Search } from 'lucide-react';
import { toast } from 'sonner';
import { Checkbox, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useDebounce } from '@/shared/hooks/useDebounce';
import { hasErrors, transferErrors, useCustomerLookup, useTransferOwner, type FieldErrors } from '@/domains/pets/hooks/useCustomerPet';
import type { CustomerSearchItem, PetResponse } from '@/domains/pets/types';

export function TransferOwnerDialog({ pet, onClose }: { pet: PetResponse | null; onClose: () => void }) {
  return (
    <Dialog open={pet != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ArrowRightLeft className="h-5 w-5" aria-hidden />
            Chuyển chủ {pet?.name}
          </DialogTitle>
          <DialogDescription>Chủ mới phải đã có hồ sơ. Tạo hồ sơ cho chủ mới trước nếu chưa có.</DialogDescription>
        </DialogHeader>
        {pet && <TransferForm pet={pet} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

/** Gõ số thì tra theo SĐT (đủ 10 số), gõ chữ thì theo họ tên (từ 2 ký tự). */
function lookupQuery(term: string) {
  if (/^\d+$/.test(term)) return { phone: term.length === 10 ? term : '' };
  return { name: term.length >= 2 ? term : '' };
}

function TransferForm({ pet, onClose }: { pet: PetResponse; onClose: () => void }) {
  const [term, setTerm] = useState('');
  const [picked, setPicked] = useState<CustomerSearchItem | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState('');
  const debounced = useDebounce(term.trim(), 300);
  const lookup = useCustomerLookup(lookupQuery(debounced));
  const transfer = useTransferOwner(pet.petId);
  const candidates = (lookup.data?.content ?? []).filter((c) => c.customerId !== pet.customerId);

  const submit = () => {
    const found = transferErrors({ newCustomerId: picked?.customerId ?? null, previousOwnerConfirmed: confirmed }, pet.customerId);
    setErrors(found);
    if (hasErrors(found) || !picked) return;
    setFormError('');
    transfer.mutate(
      { newCustomerId: picked.customerId, previousOwnerConfirmed: true },
      {
        onSuccess: () => {
          toast.success(`Đã chuyển ${pet.name} sang ${picked.fullName}`);
          onClose();
        },
        onError: (e) => setFormError(plainMessage(toApiError(e))),
      },
    );
  };

  return (
    <div className="space-y-4">
      <div>
        <label htmlFor="transfer-owner-search" className="mb-1.5 block text-sm font-medium">
          Tìm chủ mới (SĐT hoặc họ tên)
        </label>
        <div className="relative">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
          <Input
            id="transfer-owner-search"
            className="pl-9"
            value={term}
            onChange={(e) => {
              setTerm(e.target.value);
              setPicked(null);
            }}
            placeholder="0905111222 hoặc Lê Thị Hoa"
            autoFocus
          />
        </div>
        {/^\d+$/.test(term.trim()) && term.trim().length !== 10 && (
          <p className="mt-1 text-xs text-(--ws-ink-3)">Nhập đủ 10 số điện thoại.</p>
        )}
        {errors.newCustomerId && <p className="mt-1 text-xs font-medium text-red-600">{errors.newCustomerId}</p>}
      </div>

      {candidates.length > 0 && (
        <ul className="max-h-56 divide-y divide-(--ws-line) overflow-y-auto rounded-lg border border-(--ws-line)" aria-label="Hồ sơ tìm được">
          {candidates.map((c) => (
            <li key={c.customerId}>
              <button
                type="button"
                aria-pressed={picked?.customerId === c.customerId}
                onClick={() => setPicked(c)}
                className={cn('w-full p-3 text-left hover:bg-(--ws-raised)', picked?.customerId === c.customerId && 'bg-(--ws-accent-soft)')}
              >
                <span className="block font-medium">{c.fullName}</span>
                <span className="block text-xs text-(--ws-ink-2)">
                  {c.phone ?? 'Chưa có SĐT'} · {c.maskedEmail ?? 'Chưa có email'} · {c.pets.length} thú
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {debounced && !lookup.isFetching && lookup.isSuccess && candidates.length === 0 && (
        <p className="text-sm text-(--ws-ink-2)">Không tìm thấy hồ sơ phù hợp.</p>
      )}

      <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900">
        Khi chuyển: lịch sử khám, tiêm, lưu trú đi theo thú; Order và hóa đơn cũ ở lại với chủ cũ; lịch hẹn và đặt chỗ sắp tới của thú bị
        hủy; chủ cũ không xem được thú nữa.
      </div>
      <div>
        <Checkbox label="Chủ cũ có mặt hoặc đã đồng ý chuyển" checked={confirmed} onChange={setConfirmed} />
        {errors.previousOwnerConfirmed && <p className="mt-1 text-xs font-medium text-red-600">{errors.previousOwnerConfirmed}</p>}
      </div>
      <FormError message={formError} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onClose} disabled={transfer.isPending}>
          Hủy
        </Button>
        <Button onClick={submit} disabled={transfer.isPending || !picked}>
          {transfer.isPending ? 'Đang chuyển…' : picked ? `Chuyển sang ${picked.fullName}` : 'Chọn chủ mới'}
        </Button>
      </div>
    </div>
  );
}
