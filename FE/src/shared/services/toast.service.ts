// Wrapper Sonner — gọi toast qua một cửa để dễ đổi lib/spy trong test.
// Cài: npm install sonner
import { toast } from 'sonner';

export const notify = {
  success: (msg: string) => toast.success(msg),
  error: (msg: string) => toast.error(msg),
  info: (msg: string) => toast.info(msg),
  warning: (msg: string) => toast.warning(msg),
};
