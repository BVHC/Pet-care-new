import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../../shared/api/axios';
import { identityApi } from '../../../../shared/api/identity.api';
import type { OtpSentResponse } from '../../../../shared/types/auth';
import type { CustomerProfile } from '../../../../shared/types/profile';
import { AccountEmailDialog, LinkAccountDialog } from './CustomerAccountDialogs';

// UC22 (identity-v1 #25–27) chưa có controller ở BE: cả ba đường trả 404 "không có đường dẫn" (GlobalExceptionHandler, NoResourceFound/NoHandlerFound).
// Lỗi đi qua apiClient → identityApi → hook → toApiError thật; chỉ adapter mạng bị thay bằng phản hồi lỗi của BE.

const NO_ROUTE = 'Không tìm thấy đường dẫn yêu cầu';
const UNSUPPORTED = 'Chức năng này chưa được máy chủ hỗ trợ. Vui lòng liên hệ quản trị viên.';

const CUSTOMER: CustomerProfile = {
  customerId: 7,
  fullName: 'Nguyễn Thu Trang',
  phone: '0912345678',
  email: 'trang@gmail.com',
  createdChannel: 'COUNTER',
  hasAccount: true,
  linkDecisionPending: false,
};

const DIALOGS = [
  { name: 'AccountEmailDialog', Dialog: AccountEmailDialog, emailLabel: 'Email mới', submit: 'Gửi mã tới email mới' },
  { name: 'LinkAccountDialog', Dialog: LinkAccountDialog, emailLabel: 'Email tài khoản của khách', submit: 'Liên kết' },
] as const;

let originalAdapter: typeof apiClient.defaults.adapter;
let sent: string[];

beforeEach(() => {
  originalAdapter = apiClient.defaults.adapter;
  sent = [];
});
afterEach(() => {
  apiClient.defaults.adapter = originalAdapter;
  vi.restoreAllMocks();
});

/** Mọi request của apiClient nhận lỗi HTTP `status` với `data` làm thân phản hồi, như axios dựng khi BE trả 4xx. */
function failWith(status: number, data: unknown) {
  const adapter: AxiosAdapter = (config) => {
    sent.push(`${(config.method ?? 'get').toUpperCase()} ${config.url}`);
    const response = { status, statusText: '', data, headers: {}, config } as AxiosResponse;
    return Promise.reject(new AxiosError(`Request failed with status code ${status}`, AxiosError.ERR_BAD_REQUEST, config, {}, response));
  };
  apiClient.defaults.adapter = adapter;
}

const envelope = (statusCode: number, errorCode: string, message: string) => ({
  success: false,
  errorCode,
  message,
  statusCode,
  timestamp: '2026-11-01T03:00:00Z',
  traceId: 'a1b2c3d4e5f60718',
});

/** Mở hộp thoại, đối chiếu CCCD, nhập email rồi bấm nút gửi: lễ tân đi tới lúc BE phản hồi. */
async function submitDialog({ Dialog, emailLabel, submit }: (typeof DIALOGS)[number]) {
  const onClose = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Dialog customer={CUSTOMER} open onClose={onClose} />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(screen.getByLabelText('Đã đối chiếu CCCD của khách với họ tên hồ sơ'));
  await user.type(screen.getByLabelText(emailLabel), 'khach.moi@gmail.com');
  await user.click(screen.getByRole('button', { name: submit }));
  const alert = await screen.findByRole('alert');
  return { alert, onClose, emailLabel, submit };
}

describe.each(DIALOGS)('$name — lỗi từ BE', (dialog) => {
  it('404 "không có đường dẫn" (BE chưa có UC22) hiện câu chưa được máy chủ hỗ trợ, hộp ở lại bước nhập email', async () => {
    failWith(404, envelope(404, 'RESOURCE_NOT_FOUND', NO_ROUTE));
    const { alert, onClose, emailLabel, submit } = await submitDialog(dialog);

    expect(alert).toHaveTextContent(UNSUPPORTED);
    expect(screen.queryByText(NO_ROUTE)).not.toBeInTheDocument();
    expect(sent).toHaveLength(1);
    expect(screen.getByLabelText(emailLabel)).toHaveValue('khach.moi@gmail.com');
    expect(screen.queryByLabelText('Mã OTP')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: submit })).toBeEnabled());
    expect(onClose).not.toHaveBeenCalled();
  });

  it('404 thực thể (cùng errorCode, message khác) giữ nguyên câu của BE', async () => {
    failWith(404, envelope(404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy tài khoản với email này'));
    const { alert } = await submitDialog(dialog);

    expect(alert).toHaveTextContent('Không tìm thấy tài khoản với email này');
    expect(alert).not.toHaveTextContent('chưa được máy chủ hỗ trợ');
  });

  it('400 BR-TK-16 giữ nguyên câu của BE, bỏ đuôi mã rule', async () => {
    failWith(400, envelope(400, 'BUSINESS_RULE_VIOLATION', 'Email đã được sử dụng (BR-TK-16)'));
    const { alert } = await submitDialog(dialog);

    expect(alert).toHaveTextContent('Email đã được sử dụng');
    expect(alert).not.toHaveTextContent('BR-TK-16');
    expect(alert).not.toHaveTextContent('chưa được máy chủ hỗ trợ');
  });
});

// Độ dài mã OTP theo BE (`codeLength` ở response #25, `otp.code_length` 4–8): ô nhập và lời báo theo đó; vắng hoặc không hợp lệ thì 6.
describe('AccountEmailDialog — độ dài mã OTP theo BE (codeLength)', () => {
  const sent = (codeLength?: number): OtpSentResponse => ({
    resendAvailableAt: new Date(Date.now() + 60_000).toISOString(),
    maskedEmail: 'kh***@gmail.com',
    ...(codeLength === undefined ? {} : { codeLength }),
  });

  /** Mở hộp, đối chiếu CCCD, gửi mã; trả ô "Mã OTP" của bước nhập mã. */
  async function openCodeStep(...responses: OtpSentResponse[]) {
    const send = vi.spyOn(identityApi, 'sendCustomerEmailChange');
    responses.forEach((r) => send.mockResolvedValueOnce(r));
    const confirm = vi.spyOn(identityApi, 'confirmCustomerEmailChange').mockResolvedValue(undefined);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <AccountEmailDialog customer={CUSTOMER} open onClose={vi.fn()} />
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Đã đối chiếu CCCD của khách với họ tên hồ sơ'));
    await user.type(screen.getByLabelText('Email mới'), 'khach.moi@gmail.com');
    await user.click(screen.getByRole('button', { name: 'Gửi mã tới email mới' }));
    return { user, confirm, code: await screen.findByLabelText('Mã OTP') };
  }

  it.each([4, 8])('codeLength %i: giới hạn gõ, lời báo thiếu và mã gửi đi đều theo độ dài đó', async (length) => {
    const { user, confirm, code } = await openCodeStep(sent(length));
    expect(code).toHaveAttribute('maxlength', String(length));

    await user.type(code, '1'.repeat(length - 1));
    await user.click(screen.getByRole('button', { name: 'Xác nhận đổi email' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(`Mã OTP gồm ${length} chữ số`);
    expect(confirm).not.toHaveBeenCalled();

    await user.type(code, '1');
    await user.click(screen.getByRole('button', { name: 'Xác nhận đổi email' }));
    await waitFor(() => expect(confirm).toHaveBeenCalledWith(CUSTOMER.customerId, { code: '1'.repeat(length) }));
  });

  it.each([
    ['vắng', undefined],
    ['ngoài 4–8 (9)', 9],
  ])('codeLength %s: dùng 6', async (_label, codeLength) => {
    const { user, confirm, code } = await openCodeStep(sent(codeLength));
    expect(code).toHaveAttribute('maxlength', '6');

    await user.type(code, '12345');
    await user.click(screen.getByRole('button', { name: 'Xác nhận đổi email' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Mã OTP gồm 6 chữ số');
    expect(confirm).not.toHaveBeenCalled();
  });

  it('"Gửi lại mã" nhận độ dài của lần gửi sau', async () => {
    const { user, code } = await openCodeStep(sent(8), sent(4));
    expect(code).toHaveAttribute('maxlength', '8');

    await user.click(screen.getByRole('button', { name: 'Gửi lại mã' }));
    await waitFor(() => expect(screen.getByLabelText('Mã OTP')).toHaveAttribute('maxlength', '4'));
  });
});
