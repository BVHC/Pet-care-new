// Liên kết tài khoản với hồ sơ tại quầy (UC07, BR-TK-19, ADR-0027). Có cờ chờ quyết định: hiện ngay các hồ sơ
// trùng SĐT đã khai, kèm "Không phải tôi". Không có cờ: khách nhập SĐT ghi trong hồ sơ tại quầy để tìm.
// Xác thực bằng mã gửi tới email ghi trong hồ sơ tại quầy. identity-v1 #11–14.
import { useState, type FormEvent } from 'react';
import { AlertTriangle, CheckCircle2, Link2, Loader2, X } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { ConfirmModal } from '@/components/ui/modal-templates';
import { Field, FormError, NoticeBar, type Notice } from '@/components/admin/form-ui';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { profileApi } from '@/shared/api/profile.api';
import { useAddresses, useProfileMutation } from '@/shared/hooks/useProfile';
import { useNow } from '@/shared/hooks/useNow';
import { useSession } from '@/shared/stores/session.store';
import type { OtpSentResponse } from '@/shared/types/auth';
import type { CustomerProfile, LinkCandidate } from '@/shared/types/profile';
import styles from './ProfileLinking.module.css';

export function ProfileLinking({ profile, pending }: { profile: CustomerProfile; pending: boolean }) {
  const [notice, setNotice] = useState<Notice | null>(null);
  const linked = profile.createdChannel === 'COUNTER';

  return (
    <section className={styles.container}>
      <h2 className={styles.title}>
        <Link2 size={18} />
        Liên kết hồ sơ
      </h2>
      <NoticeBar notice={notice} />
      {linked ? (
        <div className={styles.successBox}>
          <CheckCircle2 size={32} className={styles.successIcon} />
          <p className={styles.successText}>Tài khoản đã gắn với hồ sơ tại phòng khám</p>
          <p className={styles.successSubtext}>Thú cưng, lịch sử khám và đơn hàng của hồ sơ này thuộc tài khoản của bạn.</p>
        </div>
      ) : (
        <LinkFlow pending={pending} onDone={setNotice} />
      )}
    </section>
  );
}

type Step = { kind: 'choose' } | { kind: 'confirm'; candidate: LinkCandidate } | { kind: 'code'; candidate: LinkCandidate; sent: OtpSentResponse };

function LinkFlow({ pending, onDone }: { pending: boolean; onDone: (notice: Notice) => void }) {
  const setLinkDecisionPending = useSession((s) => s.setLinkDecisionPending);
  const [phoneInput, setPhoneInput] = useState('');
  /** SĐT đã tìm (khách không có cờ); có cờ thì BE dùng SĐT của hồ sơ online. */
  const [phone, setPhone] = useState<string | undefined>();
  const [step, setStep] = useState<Step>({ kind: 'choose' });
  const [declining, setDeclining] = useState(false);
  const [error, setError] = useState('');
  const candidates = useQuery({
    queryKey: ['me', 'link-candidates', phone ?? null],
    queryFn: () => profileApi.linkCandidates(phone),
    enabled: pending || phone !== undefined,
    retry: false,
  });
  const decline = useProfileMutation(profileApi.declineLink);

  const search = (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setPhone(phoneInput.trim());
  };

  const confirmDecline = async () => {
    setDeclining(false);
    try {
      await decline.mutateAsync(undefined);
      setLinkDecisionPending(false);
      onDone({ kind: 'success', text: 'Đã ghi nhận "Không phải tôi". Bạn tiếp tục dùng hồ sơ online với số điện thoại đã khai.' });
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  if (step.kind === 'confirm' || step.kind === 'code') {
    return (
      <LinkSteps
        step={step}
        phone={phone}
        onStep={setStep}
        onLinked={() => {
          setLinkDecisionPending(false);
          onDone({ kind: 'success', text: 'Liên kết hồ sơ thành công' });
        }}
      />
    );
  }

  return (
    <div className={styles.section}>
      {pending ? (
        <div className={styles.pendingBanner}>
          <AlertTriangle size={20} />
          <div>
            <p className={styles.pendingTitle}>Có vẻ bạn đã có hồ sơ tại phòng khám</p>
            <p className={styles.pendingSubtext}>
              Số điện thoại bạn khai trùng với các hồ sơ tại quầy dưới đây (họ tên đã che). Chọn hồ sơ của bạn để liên kết, hoặc chọn
              "Không phải tôi" để tiếp tục dùng hồ sơ online.
            </p>
          </div>
        </div>
      ) : (
        <form onSubmit={search} className="space-y-3">
          <p className={styles.candidateHint}>
            Bạn từng làm hồ sơ tại quầy của Pet Care? Nhập số điện thoại ghi trong hồ sơ đó để tìm và liên kết. Chỉ liên kết được khi
            hồ sơ online chưa có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hay góp ý.
          </p>
          <Field label="Số điện thoại trong hồ sơ tại phòng khám">
            {(id) => (
              <div className={styles.searchBox}>
                <input
                  id={id}
                  type="tel"
                  className={styles.searchInput}
                  placeholder="0xxxxxxxxx"
                  value={phoneInput}
                  onChange={(e) => setPhoneInput(e.target.value)}
                />
                <button type="submit" className={styles.searchBtn} disabled={!phoneInput.trim()}>
                  Tìm hồ sơ
                </button>
              </div>
            )}
          </Field>
        </form>
      )}

      {candidates.isFetching && (
        <div className={styles.loadingBox}>
          <Loader2 size={20} className={styles.spinner} />
          <span>Đang tìm hồ sơ...</span>
        </div>
      )}
      <FormError message={error || (candidates.isError ? plainMessage(toApiError(candidates.error)) : '')} />

      {candidates.isSuccess && candidates.data.length === 0 && (
        <div className={styles.emptyBox}>
          <p>Không có hồ sơ tại quầy nào chưa liên kết với số điện thoại này.</p>
        </div>
      )}
      {candidates.isSuccess && candidates.data.length > 0 && (
        <div className={styles.candidateList}>
          {candidates.data.map((c) => (
            <div key={c.customerId} className={styles.candidateCard}>
              <div className={styles.candidateInfo}>
                <span className={styles.candidateName}>{c.maskedFullName}</span>
                {c.hasEmail ? (
                  <span className={styles.candidateEmail}>Có email nhận mã xác thực</span>
                ) : (
                  <span className={styles.candidateNoEmail}>Chưa có email, vui lòng ra quầy để liên kết</span>
                )}
              </div>
              <button
                type="button"
                className={styles.candidateSelect}
                disabled={!c.hasEmail}
                aria-label={`Chọn hồ sơ ${c.maskedFullName}`}
                onClick={() => setStep({ kind: 'confirm', candidate: c })}
              >
                Chọn
              </button>
            </div>
          ))}
        </div>
      )}

      {pending && (
        <button type="button" className={styles.declineBtn} onClick={() => setDeclining(true)} disabled={decline.isPending}>
          <X size={14} />
          Không phải tôi
        </button>
      )}

      <ConfirmModal
        open={declining}
        onOpenChange={setDeclining}
        title="Không phải hồ sơ của bạn?"
        description="Bạn tiếp tục dùng hồ sơ online với số điện thoại đã khai. Sau này vẫn tự liên kết được khi hồ sơ online chưa có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hay góp ý."
        onConfirm={confirmDecline}
        loading={decline.isPending}
      />
    </div>
  );
}

function LinkSteps({
  step,
  phone,
  onStep,
  onLinked,
}: {
  step: Extract<Step, { kind: 'confirm' | 'code' }>;
  phone?: string;
  onStep: (step: Step) => void;
  onLinked: () => void;
}) {
  const addresses = useAddresses();
  // Vẽ lại mỗi giây cho đếm ngược gửi lại mã.
  useNow(1000);
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const confirm = useProfileMutation((c: string) => profileApi.confirmLink({ customerId: step.candidate.customerId, code: c }));
  const addressCount = addresses.data?.length ?? 0;

  const send = async () => {
    setError('');
    setSending(true);
    try {
      const sent = await profileApi.sendLinkOtp({ customerId: step.candidate.customerId, phone });
      setCode('');
      onStep({ kind: 'code', candidate: step.candidate, sent });
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    } finally {
      setSending(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      await confirm.mutateAsync(code.trim());
      onLinked();
    } catch (err) {
      setError(plainMessage(toApiError(err)));
    }
  };

  const back = (
    <button type="button" className={styles.backBtn} onClick={() => onStep({ kind: 'choose' })}>
      ← Chọn hồ sơ khác
    </button>
  );

  if (step.kind === 'confirm') {
    return (
      <div className={styles.otpSection}>
        <div className={styles.pendingBanner}>
          <AlertTriangle size={20} />
          <div className="space-y-1">
            <p className={styles.pendingTitle}>Liên kết với hồ sơ {step.candidate.maskedFullName}</p>
            <p className={styles.pendingSubtext}>
              Tài khoản sẽ dùng hồ sơ tại phòng khám: thú cưng, lịch sử khám và đơn hàng của hồ sơ đó. Email hồ sơ đổi theo email đăng nhập,
              số điện thoại giữ như hồ sơ tại quầy.
            </p>
            <p className={styles.pendingSubtext}>
              {addressCount > 0
                ? `Hồ sơ online cùng sổ địa chỉ hiện tại (${addressCount} địa chỉ) sẽ bị xóa, không khôi phục được.`
                : 'Hồ sơ online hiện tại sẽ bị xóa, không khôi phục được.'}
            </p>
          </div>
        </div>
        <FormError message={error} />
        <div className={styles.otpActions}>
          <button type="button" className={styles.verifyBtn} onClick={send} disabled={sending}>
            {sending && <Loader2 size={16} className={styles.spinner} />}
            Gửi mã xác thực
          </button>
          {back}
        </div>
      </div>
    );
  }

  const wait = Math.max(0, Math.ceil((Date.parse(step.sent.resendAvailableAt) - Date.now()) / 1000));
  return (
    <div className={styles.otpSection}>
      <p className={styles.otpHint}>
        Mã xác thực đã gửi tới <strong>{step.sent.maskedEmail}</strong>, email ghi trong hồ sơ tại phòng khám.
      </p>
      <form onSubmit={submit} className={styles.otpForm} noValidate>
        <Field label="Mã xác thực">
          {(id) => (
            <input
              id={id}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={8}
              className={styles.otpField}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
            />
          )}
        </Field>
        <FormError message={error} />
        <button type="submit" className={styles.verifyBtn} disabled={!code || confirm.isPending}>
          {confirm.isPending && <Loader2 size={16} className={styles.spinner} />}
          Xác nhận liên kết
        </button>
      </form>
      <div className={styles.otpActions}>
        <button type="button" className={styles.resendBtn} onClick={send} disabled={wait > 0 || sending}>
          {wait > 0 ? `Gửi lại mã sau ${wait} giây` : 'Gửi lại mã'}
        </button>
        {back}
      </div>
    </div>
  );
}
