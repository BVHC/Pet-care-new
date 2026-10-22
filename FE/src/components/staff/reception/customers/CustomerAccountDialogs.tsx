/**
 * Tài khoản online của hồ sơ khách tại quầy (UC22, identity-v1 #25–27) — chỉ lễ tân.
 * Trước mọi thao tác, lễ tân đối chiếu CCCD với họ tên hồ sơ; hệ thống không lưu CCCD, chỉ ghi phương thức (BR-TK-16).
 */
import { useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { Checkbox, Field, FormError } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import type { CustomerProfile } from '@/shared/types/profile';
import {
  emailError,
  otpError,
  useConfirmCustomerEmailChange,
  useLinkCounterAccount,
  useSendCustomerEmailChange,
} from '@/domains/pets/hooks/useCustomerPet';

const DIALOG = 'ws-portal border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-md';
const ID_CHECK = 'Đã đối chiếu CCCD của khách với họ tên hồ sơ';

/** Lỗi theo trường của BE (VALIDATION_FAILED) hoặc câu báo chung. */
function messageOf(e: unknown): string {
  const error = toApiError(e);
  const fields = Object.values(fieldErrorsOf(error));
  return fields.length ? fields.join('. ') : plainMessage(error);
}

/**
 * Sửa email tài khoản khách, cũng là cách khôi phục tài khoản khi khách mất email (BR-TK-16):
 * mã gửi tới email mới, khách đọc mã cho lễ tân; chưa xác nhận thì email cũ giữ nguyên.
 */
export function AccountEmailDialog({ customer, open, onClose }: { customer: CustomerProfile; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className={DIALOG}>
        <DialogHeader>
          <DialogTitle>Sửa email tài khoản</DialogTitle>
          <DialogDescription>
            Email hiện tại: {customer.email ?? '—'}. Dùng khi khách đổi email hoặc mất quyền truy cập email cũ.
          </DialogDescription>
        </DialogHeader>
        {open && <AccountEmailForm customerId={customer.customerId} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function AccountEmailForm({ customerId, onClose }: { customerId: number; onClose: () => void }) {
  const [verified, setVerified] = useState(false);
  const [newEmail, setNewEmail] = useState('');
  const [code, setCode] = useState('');
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [error, setError] = useState('');
  const send = useSendCustomerEmailChange(customerId);
  const confirm = useConfirmCustomerEmailChange(customerId);

  const sendCode = (e?: FormEvent) => {
    e?.preventDefault();
    const invalid = !verified ? 'Cần đối chiếu CCCD trước khi sửa email' : emailError(newEmail);
    setError(invalid);
    if (invalid) return;
    send.mutate(newEmail.trim(), {
      onSuccess: (result) => {
        setSentTo(result.maskedEmail ?? newEmail.trim());
        setCode('');
      },
      onError: (err) => setError(messageOf(err)),
    });
  };

  const confirmCode = (e: FormEvent) => {
    e.preventDefault();
    const invalid = otpError(code);
    setError(invalid);
    if (invalid) return;
    confirm.mutate(code.trim(), {
      onSuccess: () => {
        toast.success(`Đã đổi email tài khoản thành ${newEmail.trim()}`);
        onClose();
      },
      onError: (err) => setError(messageOf(err)),
    });
  };

  if (sentTo) {
    return (
      <form className="space-y-3" onSubmit={confirmCode} noValidate>
        <p className="text-sm">
          Đã gửi mã tới <strong>{sentTo}</strong>. Khách mở email mới và đọc mã cho lễ tân.
        </p>
        <Field label="Mã OTP">
          {(id) => <Input id={id} inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} autoFocus />}
        </Field>
        <FormError message={error} />
        <div className="flex flex-wrap justify-end gap-2">
          <Button type="button" variant="outline" onClick={() => sendCode()} disabled={send.isPending || confirm.isPending}>
            Gửi lại mã
          </Button>
          <Button type="submit" disabled={confirm.isPending}>
            {confirm.isPending ? 'Đang xác nhận…' : 'Xác nhận đổi email'}
          </Button>
        </div>
      </form>
    );
  }

  return (
    <form className="space-y-3" onSubmit={sendCode} noValidate>
      <Checkbox label={ID_CHECK} checked={verified} onChange={setVerified} />
      <Field label="Email mới" hint="Mã xác nhận gửi tới email mới (BR-TK-16).">
        {(id) => <Input id={id} type="email" value={newEmail} onChange={(e) => setNewEmail(e.target.value)} />}
      </Field>
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onClose}>
          Hủy
        </Button>
        <Button type="submit" disabled={send.isPending}>
          {send.isPending ? 'Đang gửi…' : 'Gửi mã tới email mới'}
        </Button>
      </div>
    </form>
  );
}

/**
 * Liên kết hồ sơ tại quầy với tài khoản online khách đã đăng ký (BR-TK-19): không cần OTP vì lễ tân đã đối chiếu CCCD.
 * Hồ sơ online của tài khoản (chưa phát sinh dữ liệu) bị xóa, email hồ sơ theo email tài khoản, SĐT giữ nguyên.
 */
export function LinkAccountDialog({ customer, open, onClose }: { customer: CustomerProfile; open: boolean; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className={DIALOG}>
        <DialogHeader>
          <DialogTitle>Liên kết tài khoản online</DialogTitle>
          <DialogDescription>
            Gắn tài khoản khách đã đăng ký vào hồ sơ {customer.fullName}. Thú cưng và lịch sử của hồ sơ sẽ hiện trong tài khoản đó.
          </DialogDescription>
        </DialogHeader>
        {open && <LinkAccountForm customerId={customer.customerId} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function LinkAccountForm({ customerId, onClose }: { customerId: number; onClose: () => void }) {
  const [verified, setVerified] = useState(false);
  const [accountEmail, setAccountEmail] = useState('');
  const [error, setError] = useState('');
  const link = useLinkCounterAccount(customerId);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const invalid = !verified ? 'Cần đối chiếu CCCD trước khi liên kết' : emailError(accountEmail);
    setError(invalid);
    if (invalid) return;
    link.mutate(accountEmail.trim(), {
      onSuccess: () => {
        toast.success(`Đã liên kết hồ sơ với tài khoản ${accountEmail.trim()}`);
        onClose();
      },
      onError: (err) => setError(messageOf(err)),
    });
  };

  return (
    <form className="space-y-3" onSubmit={submit} noValidate>
      <Checkbox label={ID_CHECK} checked={verified} onChange={setVerified} />
      <Field label="Email tài khoản của khách" hint="Tài khoản phải đã xác thực email và chưa có thú cưng, lịch hẹn hay đơn hàng.">
        {(id) => <Input id={id} type="email" value={accountEmail} onChange={(e) => setAccountEmail(e.target.value)} />}
      </Field>
      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onClose}>
          Hủy
        </Button>
        <Button type="submit" disabled={link.isPending}>
          {link.isPending ? 'Đang liên kết…' : 'Liên kết'}
        </Button>
      </div>
    </form>
  );
}
