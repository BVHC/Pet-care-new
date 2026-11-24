// Khách Google chưa có tài khoản: xác nhận 18+ và điều khoản (BR-TK-02) rồi mới tạo (spec 2026-11-24 §6.8). Hai ô và SĐT dùng đúng chữ và luật của RegisterPage.
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Phone } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { AuthField, SubmitButton } from './AuthField';
import { normalizePhone, phoneError } from './phone';

type Props = {
  open: boolean;
  busy: boolean;
  onSubmit: (v: { phone?: string }) => void;
  onClose: () => void;
};

export function GoogleConsentDialog({ open, busy, onSubmit, onClose }: Props) {
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white text-[#191919] sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Hoàn tất đăng ký bằng Google</DialogTitle>
          <DialogDescription>Xác nhận để tạo tài khoản Pet Care bằng email Google của bạn.</DialogDescription>
        </DialogHeader>
        <ConsentForm busy={busy} onSubmit={onSubmit} />
      </DialogContent>
    </Dialog>
  );
}

/** Trạng thái nằm trong nội dung hộp thoại: đóng rồi mở lại là form trắng. */
function ConsentForm({ busy, onSubmit }: Pick<Props, 'busy' | 'onSubmit'>) {
  const [isAdult, setIsAdult] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [phone, setPhone] = useState('');
  const [phoneMessage, setPhoneMessage] = useState<string>();
  const ready = isAdult && agreed;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!ready || busy) return;
    const message = phoneError(phone);
    setPhoneMessage(message);
    if (!message) onSubmit({ phone: normalizePhone(phone) });
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <AuthField
        label="Số điện thoại"
        icon={<Phone size={19} strokeWidth={1.7} />}
        type="tel"
        autoComplete="tel"
        value={phone}
        onChange={(v) => {
          setPhone(v);
          setPhoneMessage(undefined);
        }}
        onBlur={() => setPhoneMessage(phoneError(phone))}
        error={phoneMessage}
        placeholder="0901 234 567"
        action={<span className="text-[12.5px] font-semibold text-[#a3968a]">Không bắt buộc</span>}
      />

      <label className="flex cursor-pointer items-start gap-2.5 text-[13.5px] leading-[1.5] text-[#7a6a5d]">
        <input
          type="checkbox"
          checked={isAdult}
          onChange={(e) => setIsAdult(e.target.checked)}
          className="mt-0.5 h-4 w-4 shrink-0 accent-[#a43324]"
        />
        <span>Tôi xác nhận đã đủ 18 tuổi</span>
      </label>

      <label className="flex cursor-pointer items-start gap-2.5 pb-2 text-[13.5px] leading-[1.5] text-[#7a6a5d]">
        <input
          type="checkbox"
          checked={agreed}
          onChange={(e) => setAgreed(e.target.checked)}
          className="mt-0.5 h-4 w-4 shrink-0 accent-[#a43324]"
        />
        <span>
          Tôi đồng ý với{' '}
          {/* Mở tab mới: rời trang này thì mất credential đang giữ trong bộ nhớ. */}
          <Link to="/terms" target="_blank" rel="noreferrer" className="font-semibold text-accent hover:underline">
            Điều khoản sử dụng
          </Link>{' '}
          và{' '}
          <Link to="/privacy" target="_blank" rel="noreferrer" className="font-semibold text-accent hover:underline">
            Chính sách bảo mật
          </Link>
        </span>
      </label>

      <SubmitButton loading={busy} disabled={!ready}>
        {busy ? 'Đang xử lý...' : 'Tiếp tục'}
      </SubmitButton>
    </form>
  );
}
