/**
 * Tạo hồ sơ khách tại quầy (UC22, BR-KH-01): họ tên + SĐT bắt buộc, email không bắt buộc, chưa gắn tài khoản.
 * SĐT trùng hồ sơ khác vẫn tạo; màn kết quả liệt kê các hồ sơ đó để lễ tân kiểm tra nghi trùng (BR-KH-10).
 */
import { useState, type FormEvent } from 'react';
import { AlertTriangle, UserPlus } from 'lucide-react';
import { Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { customerErrors, hasErrors, useCreateCounterCustomer, type FieldErrors } from '@/domains/pets/hooks/useCustomerPet';
import type { CustomerWriteResult } from '@/domains/pets/types';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Mở hồ sơ vừa tạo hoặc một hồ sơ trùng SĐT. */
  onOpenCustomer: (customerId: number) => void;
}

export function CreateCustomerDialog({ open, onOpenChange, onOpenCustomer }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-md">
        <CreateCustomerForm onClose={() => onOpenChange(false)} onOpenCustomer={onOpenCustomer} />
      </DialogContent>
    </Dialog>
  );
}

function CreateCustomerForm({ onClose, onOpenCustomer }: { onClose: () => void; onOpenCustomer: (id: number) => void }) {
  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [email, setEmail] = useState('');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState('');
  const [result, setResult] = useState<CustomerWriteResult | null>(null);
  const create = useCreateCounterCustomer();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setFormError('');
    const found = customerErrors({ fullName, phone, email }, true);
    setErrors(found);
    if (hasErrors(found)) return;
    create.mutate(
      { fullName: fullName.trim(), phone: phone.trim(), email: email.trim() || undefined },
      {
        onSuccess: setResult,
        onError: (err) => {
          const error = toApiError(err);
          const fields = fieldErrorsOf(error);
          if (Object.keys(fields).length) setErrors(fields);
          else setFormError(plainMessage(error));
        },
      },
    );
  };

  const open = (customerId: number) => {
    onClose();
    onOpenCustomer(customerId);
  };

  if (result) {
    const duplicates = result.duplicatePhoneProfiles;
    return (
      <>
        <DialogHeader>
          <DialogTitle>Đã tạo hồ sơ {result.customer.fullName}</DialogTitle>
          <DialogDescription>SĐT {result.customer.phone}. Thêm thú cưng trong hồ sơ để tiếp nhận hoặc bán hàng.</DialogDescription>
        </DialogHeader>
        {duplicates.length > 0 && (
          <div role="alert" className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900">
            <p className="flex items-center gap-2 font-semibold">
              <AlertTriangle className="h-4 w-4" aria-hidden />
              SĐT này đã có ở {duplicates.length} hồ sơ khác — kiểm tra có phải cùng một người không
            </p>
            <ul className="space-y-1.5">
              {duplicates.map((d) => (
                <li key={d.customerId} className="flex items-center justify-between gap-2">
                  <span>
                    {d.fullName} · {d.maskedEmail ?? 'chưa có email'} · {d.pets.map((p) => p.name).join(', ') || 'chưa có thú'}
                  </span>
                  <Button size="sm" variant="outline" onClick={() => open(d.customerId)}>
                    Mở
                  </Button>
                </li>
              ))}
            </ul>
          </div>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Đóng
          </Button>
          <Button onClick={() => open(result.customer.customerId)}>Mở hồ sơ vừa tạo</Button>
        </div>
      </>
    );
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <UserPlus className="h-5 w-5" aria-hidden />
          Tạo hồ sơ khách tại quầy
        </DialogTitle>
        <DialogDescription>Họ tên và số điện thoại là bắt buộc. Hồ sơ chưa gắn tài khoản online.</DialogDescription>
      </DialogHeader>
      <form className="space-y-3" onSubmit={submit} noValidate>
        <Field label="Họ tên" error={errors.fullName}>
          {(id) => <Input id={id} value={fullName} maxLength={100} onChange={(e) => setFullName(e.target.value)} autoFocus />}
        </Field>
        <Field label="Số điện thoại" error={errors.phone}>
          {(id) => <Input id={id} value={phone} inputMode="tel" maxLength={10} placeholder="0xxxxxxxxx" onChange={(e) => setPhone(e.target.value)} />}
        </Field>
        <Field label="Email (không bắt buộc)" error={errors.email} hint="Cần email để khách liên kết tài khoản online về sau (BR-TK-19).">
          {(id) => <Input id={id} type="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
        </Field>
        <FormError message={formError} />
        <div className="flex justify-end gap-2 pt-1">
          <Button type="button" variant="outline" onClick={onClose}>
            Hủy
          </Button>
          <Button type="submit" disabled={create.isPending}>
            {create.isPending ? 'Đang tạo…' : 'Tạo hồ sơ'}
          </Button>
        </div>
      </form>
    </>
  );
}
