// Luồng nút Google dùng chung cho LoginPage và RegisterPage (identity-v1 #40, spec 2026-11-24 §6.1): cùng một endpoint, BE tự biết đăng nhập hay tạo mới.
import { useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { plainMessage, toApiError } from '../../shared/api/api-error';
import { useSession } from '../../shared/stores/session.store';
import { afterLoginPath } from './afterLogin';
import { GoogleConsentDialog } from './GoogleConsentDialog';

const TOKEN_EXPIRED = 'Phiên đăng nhập Google đã hết hạn. Vui lòng bấm đăng nhập lại';

export function useGoogleLogin(from?: string): { onCredential: (credential: string) => void; dialog: ReactNode } {
  const navigate = useNavigate();
  const busy = useRef(false); // GIS có thể gọi callback hai lần, khách có thể bấm liên tục: chỉ một request tại một thời điểm
  const held = useRef<string | null>(null); // credential chờ khách xác nhận; ID token sống khoảng 1 giờ
  const [dialogOpen, setDialogOpen] = useState(false);
  const [sending, setSending] = useState(false);

  const closeDialog = () => {
    held.current = null;
    setDialogOpen(false);
  };

  async function send(credential: string, consent?: { phone?: string }) {
    if (busy.current) return;
    busy.current = true;
    setSending(true);
    try {
      const account = await useSession.getState().loginWithGoogle({
        credential,
        ...(consent && { isAdult: true, termsAccepted: true, ...consent }),
      });
      toast.success('Đăng nhập thành công!');
      navigate(afterLoginPath(account, from));
    } catch (error) {
      const err = toApiError(error);
      if (!consent && err.ruleId === 'BR-TK-02') {
        held.current = credential;
        setDialogOpen(true);
      } else if (consent && err.statusCode === 401) {
        closeDialog();
        toast.error(TOKEN_EXPIRED);
      } else {
        toast.error(plainMessage(err));
      }
    } finally {
      busy.current = false;
      setSending(false);
    }
  }

  return {
    onCredential: (credential) => void send(credential),
    dialog: (
      <GoogleConsentDialog
        open={dialogOpen}
        busy={sending}
        onSubmit={(consent) => {
          if (held.current) void send(held.current, consent);
        }}
        onClose={closeDialog}
      />
    ),
  };
}
