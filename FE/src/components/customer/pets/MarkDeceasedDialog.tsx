/**
 * Đánh dấu thú đã mất (UC24, BR-KH-05): xác nhận 2 bước ở giao diện, API nhận `confirmed = true` (A3).
 * Không hoàn tác được; BE chặn khi thú có lượt khám mở hoặc đang lưu trú.
 */
import { useState } from 'react';
import { HeartCrack } from 'lucide-react';
import { toast } from 'sonner';
import { Checkbox, Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { deceasedErrors, hasErrors, todayVn, useMarkDeceased, type FieldErrors } from '@/domains/pets/hooks/useCustomerPet';
import type { PetResponse } from '@/domains/pets/types';

export function MarkDeceasedDialog({ pet, onClose }: { pet: PetResponse | null; onClose: () => void }) {
  return (
    <Dialog open={pet != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-red-600">
            <HeartCrack className="h-5 w-5" aria-hidden />
            Đánh dấu {pet?.name} đã mất
          </DialogTitle>
          <DialogDescription>
            Không hoàn tác được. Hồ sơ chuyển sang chỉ đọc; lịch hẹn, đặt chỗ lưu trú sắp tới và các nhắc tái chủng, tái khám bị hủy.
          </DialogDescription>
        </DialogHeader>
        {pet && <DeceasedForm pet={pet} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function DeceasedForm({ pet, onClose }: { pet: PetResponse; onClose: () => void }) {
  const [step, setStep] = useState<1 | 2>(1);
  const [deceasedOn, setDeceasedOn] = useState(todayVn());
  const [confirmed, setConfirmed] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState('');
  const mark = useMarkDeceased(pet.petId);

  const next = () => {
    const found = deceasedErrors({ deceasedOn, confirmed });
    setErrors(found);
    if (!hasErrors(found)) setStep(2);
  };
  const submit = () =>
    mark.mutate(
      { deceasedOn, confirmed: true },
      {
        onSuccess: () => {
          toast.success(`Đã ghi nhận ${pet.name} đã mất`);
          onClose();
        },
        onError: (e) => setFormError(plainMessage(toApiError(e))),
      },
    );

  if (step === 1) {
    return (
      <div className="space-y-4">
        <Field label="Ngày mất" error={errors.deceasedOn}>
          {(id) => <Input id={id} type="date" max={todayVn()} value={deceasedOn} onChange={(e) => setDeceasedOn(e.target.value)} />}
        </Field>
        <div>
          <Checkbox label="Đã xác nhận với chủ nuôi" checked={confirmed} onChange={setConfirmed} />
          {errors.confirmed && <p className="mt-1 text-xs font-medium text-red-600">{errors.confirmed}</p>}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Hủy
          </Button>
          <Button onClick={next}>Tiếp tục</Button>
        </div>
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <p role="alert" className="rounded-lg border border-red-300 bg-red-50 p-3 text-sm text-red-800">
        Xác nhận lần cuối: đánh dấu <strong>{pet.name}</strong> đã mất ngày <strong>{deceasedOn}</strong>.
      </p>
      <FormError message={formError} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={() => setStep(1)} disabled={mark.isPending}>
          Quay lại
        </Button>
        <Button variant="destructive" onClick={submit} disabled={mark.isPending}>
          {mark.isPending ? 'Đang lưu…' : 'Xác nhận đã mất'}
        </Button>
      </div>
    </div>
  );
}
