// ===========================================
// ProfileLinking Component - T13: Liên kết hồ sơ
// Theo BR-TK-19:
// - Khách online có thể liên kết với hồ sơ tại quầy
// - Cần OTP gửi tới email hồ sơ tại quầy
// - Hoặc chọn "Không phải tôi"
// ===========================================
import { useState, useEffect } from 'react';
import { Link2, AlertTriangle, Check, X, Loader2 } from 'lucide-react';
import { toast } from 'sonner';
import {
  getLinkCandidates,
  sendLinkOtp,
  confirmLink,
  declineLink,
} from '@/shared/api/profile.api';
import type { LinkCandidate } from '@/shared/models/profile.model';
import styles from './ProfileLinking.module.css';

interface ProfileLinkingProps {
  /** Đã có quyết định liên kết chưa? */
  linkDecisionPending: boolean;
  /** Callback khi liên kết thành công */
  onLinked?: () => void;
  /** Callback khi declined */
  onDeclined?: () => void;
}

type Step = 'idle' | 'candidates' | 'otp' | 'verifying' | 'success';

export function ProfileLinking({
  linkDecisionPending,
  onLinked,
  onDeclined,
}: ProfileLinkingProps) {
  const [step, setStep] = useState<Step>('idle');
  const [candidates, setCandidates] = useState<LinkCandidate[]>([]);
  const [selected, setSelected] = useState<LinkCandidate | null>(null);
  const [phoneFilter, setPhoneFilter] = useState('');
  const [otpCode, setOtpCode] = useState('');
  const [otpSentAt, setOtpSentAt] = useState<string | null>(null);
  const [countdown, setCountdown] = useState(0);
  const [loading, setLoading] = useState(false);
  const [verifying, setVerifying] = useState(false);

  // Load candidates when step changes to candidates
  useEffect(() => {
    if (step === 'candidates') {
      loadCandidates();
    }
  }, [step]);

  // Countdown timer
  useEffect(() => {
    if (countdown <= 0) return;
    const timer = setInterval(() => {
      setCountdown((c) => c - 1);
    }, 1000);
    return () => clearInterval(timer);
  }, [countdown]);

  async function loadCandidates() {
    try {
      setLoading(true);
      const data = await getLinkCandidates(phoneFilter || undefined);
      setCandidates(data);
    } catch {
      toast.error('Không tải được danh sách hồ sơ');
    } finally {
      setLoading(false);
    }
  }

  async function handleSearch() {
    setStep('candidates');
    await loadCandidates();
  }

  async function handleSelectCandidate(candidate: LinkCandidate) {
    if (!candidate.hasEmail) {
      toast.error('Hồ sơ này không có email, không thể liên kết online. Vui lòng đến quầy để được hỗ trợ.');
      return;
    }
    setSelected(candidate);
    await sendOtp(candidate);
  }

  async function sendOtp(candidate: LinkCandidate) {
    try {
      setLoading(true);
      const res = await sendLinkOtp({ customerId: candidate.customerId });
      setOtpSentAt(res.resendAvailableAt);
      // Calculate countdown from resendAvailableAt
      const now = new Date();
      const available = new Date(res.resendAvailableAt);
      const diff = Math.max(0, Math.ceil((available.getTime() - now.getTime()) / 1000));
      setCountdown(diff);
      setStep('otp');
      setOtpCode('');
      toast.success(`Mã OTP đã được gửi đến email của hồ sơ`);
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      toast.error(msg ?? 'Gửi OTP thất bại');
    } finally {
      setLoading(false);
    }
  }

  async function handleResendOtp() {
    if (selected && countdown === 0) {
      await sendOtp(selected);
    }
  }

  async function handleVerify(e: React.FormEvent) {
    e.preventDefault();
    if (!selected || otpCode.length !== 6) {
      toast.error('Vui lòng nhập mã OTP 6 số');
      return;
    }

    try {
      setVerifying(true);
      await confirmLink({ customerId: selected.customerId, code: otpCode });
      setStep('success');
      toast.success('Liên kết thành công!');
      onLinked?.();
    } catch (err) {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      toast.error(msg ?? 'Xác nhận liên kết thất bại');
    } finally {
      setVerifying(false);
    }
  }

  async function handleDecline() {
    if (!confirm('Bạn có chắc chọn "Không phải tôi"? Hồ sơ online sẽ được giữ lại.')) {
      return;
    }
    try {
      setLoading(true);
      await declineLink();
      toast.success('Đã xác nhận. Bạn sử dụng hồ sơ online.');
      onDeclined?.();
    } catch (err) {
      toast.error('Không thể xử lý lúc này');
    } finally {
      setLoading(false);
    }
  }

  function formatCountdown(seconds: number): string {
    if (seconds <= 0) return '';
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return mins > 0 ? `${mins}:${secs.toString().padStart(2, '0')}` : `${secs}s`;
  }

  // Nếu chưa có quyết định, hiển thị banner nhắc nhở
  if (!linkDecisionPending && step === 'idle') {
    return null;
  }

  return (
    <div className={styles.container}>
      <h3 className={styles.title}>
        <Link2 size={18} />
        Liên kết hồ sơ
      </h3>

      {/* Success state */}
      {step === 'success' && (
        <div className={styles.successBox}>
          <Check size={32} className={styles.successIcon} />
          <p className={styles.successText}>Liên kết thành công!</p>
          <p className={styles.successSubtext}>
            Tài khoản đã được gắn với hồ sơ tại quầy.
          </p>
        </div>
      )}

      {/* Candidates list */}
      {(step === 'idle' || step === 'candidates') && (
        <div className={styles.section}>
          {step === 'idle' && (
            <div className={styles.pendingBanner}>
              <AlertTriangle size={20} />
              <div>
                <p className={styles.pendingTitle}>Có vẻ bạn đã có hồ sơ tại phòng khám</p>
                <p className={styles.pendingSubtext}>
                  Chúng tôi nhận thấy số điện thoại của bạn trùng với một hồ sơ tại quầy.
                  Bạn có muốn liên kết tài khoản với hồ sơ đó không?
                </p>
              </div>
            </div>
          )}

          {step === 'candidates' && candidates.length === 0 && !loading && (
            <div className={styles.emptyBox}>
              <p>Không tìm thấy hồ sơ nào phù hợp.</p>
            </div>
          )}

          {step === 'candidates' && candidates.length > 0 && (
            <div className={styles.candidateList}>
              <p className={styles.candidateHint}>
                Chọn hồ sơ của bạn (thông tin đã được che):
              </p>
              {candidates.map((c) => (
                <button
                  key={c.customerId}
                  type="button"
                  className={styles.candidateCard}
                  onClick={() => handleSelectCandidate(c)}
                  disabled={loading}
                >
                  <div className={styles.candidateInfo}>
                    <span className={styles.candidateName}>{c.maskedFullName}</span>
                    {c.hasEmail ? (
                      <span className={styles.candidateEmail}>✓ Có email</span>
                    ) : (
                      <span className={styles.candidateNoEmail}>✗ Không có email</span>
                    )}
                  </div>
                  <span className={styles.candidateSelect}>Liên kết</span>
                </button>
              ))}
            </div>
          )}

          {/* Search input */}
          {step === 'idle' && (
            <div className={styles.searchBox}>
              <input
                type="tel"
                className={styles.searchInput}
                placeholder="Tìm theo số điện thoại..."
                value={phoneFilter}
                onChange={(e) => setPhoneFilter(e.target.value)}
              />
              <button
                type="button"
                className={styles.searchBtn}
                onClick={handleSearch}
                disabled={!phoneFilter.trim()}
              >
                Tìm kiếm
              </button>
            </div>
          )}

          {loading && (
            <div className={styles.loadingBox}>
              <Loader2 size={24} className={styles.spinner} />
              <span>Đang tải...</span>
            </div>
          )}

          {/* Decline option */}
          {!loading && (
            <button
              type="button"
              className={styles.declineBtn}
              onClick={handleDecline}
            >
              <X size={14} />
              Không phải tôi — sử dụng hồ sơ online
            </button>
          )}
        </div>
      )}

      {/* OTP verification */}
      {step === 'otp' && selected && (
        <div className={styles.otpSection}>
          <p className={styles.otpHint}>
            Mã OTP đã được gửi đến email của hồ sơ <strong>{selected.maskedFullName}</strong>.
            Vui lòng kiểm tra email và nhập mã bên dưới.
          </p>

          <form onSubmit={handleVerify} className={styles.otpForm}>
            <div className={styles.otpInput}>
              <input
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                maxLength={6}
                className={styles.otpField}
                placeholder="_ _ _ _ _ _"
                value={otpCode}
                onChange={(e) => setOtpCode(e.target.value.replace(/\D/g, ''))}
                autoFocus
              />
            </div>

            <button
              type="submit"
              className={styles.verifyBtn}
              disabled={otpCode.length !== 6 || verifying}
            >
              {verifying ? (
                <>
                  <Loader2 size={16} className={styles.spinner} />
                  Đang xác nhận...
                </>
              ) : (
                'Xác nhận liên kết'
              )}
            </button>
          </form>

          <div className={styles.otpActions}>
            <button
              type="button"
              className={styles.resendBtn}
              onClick={handleResendOtp}
              disabled={countdown > 0 || loading}
            >
              {countdown > 0
                ? `Gửi lại sau ${formatCountdown(countdown)}`
                : 'Gửi lại mã OTP'}
            </button>
            <button
              type="button"
              className={styles.backBtn}
              onClick={() => setStep('candidates')}
            >
              ← Quay lại
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
