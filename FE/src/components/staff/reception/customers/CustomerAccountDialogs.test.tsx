import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import { apiClient } from '../../../../shared/api/axios';
import { identityApi } from '../../../../shared/api/identity.api';
import type { OtpSentResponse } from '../../../../shared/types/auth';
import type { CustomerProfile } from '../../../../shared/types/profile';
import { AccountEmailDialog, LinkAccountDialog } from './CustomerAccountDialogs';

// UC22: BE đã có #25–27 (sửa email tài khoản, liên kết tài khoản; CustomerAccountController). Câu 404 "không có đường dẫn" (GlobalExceptionHandler, NoResourceFound/NoHandlerFound)
// chỉ còn gặp khi chạy với BE cũ; hộp thoại vẫn đổi nó thành câu "chưa được máy chủ hỗ trợ" cho cả hai hộp.
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

// #25 trả 400 `VALIDATION_FAILED` KHÔNG kèm tên trường và KHÔNG đuôi (BR-…) khi hồ sơ chưa gắn tài khoản hoặc tài khoản còn PENDING
// (CustomerAccountNotEditableException): hộp phải hiện nguyên câu đó, không nuốt thành lỗi chung.
describe('AccountEmailDialog — 400 VALIDATION_FAILED không đuôi BR của BE', () => {
  it.each([
    ['hồ sơ chưa gắn tài khoản online', 'Hồ sơ chưa gắn tài khoản online nên không có email tài khoản để sửa'],
    ['tài khoản PENDING', 'Tài khoản chưa xác thực email nên chưa sửa được email'],
  ])('%s: hiện nguyên câu của BE, hộp ở lại bước nhập email', async (_label, message) => {
    failWith(400, envelope(400, 'VALIDATION_FAILED', message));
    const { alert, onClose } = await submitDialog(DIALOGS[0]);

    expect(alert).toHaveTextContent(message);
    expect(alert).not.toHaveTextContent('BR-');
    expect(screen.queryByLabelText('Mã OTP')).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });
});

// UC22 #27 (CustomerAccountController.linkAccount, CounterLinkStepService MSG_*, LinkAccountNotFoundException, LoginAttemptService.MSG_LOCKED): mọi câu lỗi của BE hiện nguyên văn trong khung đỏ,
// chỉ bỏ đuôi mã rule. Riêng 404 "không tìm thấy tài khoản khách hàng đã kích hoạt" là 404 THỰC THỂ (cùng errorCode RESOURCE_NOT_FOUND với 404 "không có đường dẫn", message khác):
// không được nhận nhầm thành câu "chưa được máy chủ hỗ trợ". Câu BR-TK-19 của BE tự có ngoặc trong thân ("(hồ sơ online hoặc đã liên kết)", "(sửa hồ sơ khách)"): chỉ đuôi rule bị bỏ.
describe('LinkAccountDialog — câu lỗi của BE (#27) hiện nguyên văn, hộp ở lại bước nhập email', () => {
  const NO_EMAIL = 'Hồ sơ tại quầy chưa có email. Hãy bổ sung email vào hồ sơ (sửa hồ sơ khách) rồi liên kết lại';
  const HAS_ACCOUNT = 'Hồ sơ này đã gắn tài khoản (hồ sơ online hoặc đã liên kết)';
  const ACCOUNT_LINKED = 'Tài khoản đã được liên kết với một hồ sơ tại quầy khác';
  const ONLINE_HAS_DATA = 'Hồ sơ online của tài khoản đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không liên kết được';
  const LOCKED = 'Tài khoản đã bị khóa. Vui lòng liên hệ Pet Care để được hỗ trợ';
  const ACCOUNT_NOT_FOUND = 'Không tìm thấy tài khoản khách hàng đã kích hoạt với email này';

  it.each([
    ['400 BR-TK-19 hồ sơ chưa có email (hướng dẫn bổ sung email)', 400, 'BUSINESS_RULE_VIOLATION', `${NO_EMAIL} (BR-TK-19)`, NO_EMAIL],
    ['400 BR-TK-19 hồ sơ đã gắn tài khoản', 400, 'BUSINESS_RULE_VIOLATION', `${HAS_ACCOUNT} (BR-TK-19)`, HAS_ACCOUNT],
    ['400 BR-TK-19 tài khoản đã liên kết hồ sơ quầy khác', 400, 'BUSINESS_RULE_VIOLATION', `${ACCOUNT_LINKED} (BR-TK-19)`, ACCOUNT_LINKED],
    ['400 BR-TK-19 hồ sơ online của tài khoản đã có dữ liệu', 400, 'BUSINESS_RULE_VIOLATION', `${ONLINE_HAS_DATA} (BR-TK-19)`, ONLINE_HAS_DATA],
    ['400 BR-TK-11 tài khoản bị khóa', 400, 'BUSINESS_RULE_VIOLATION', `${LOCKED} (BR-TK-11)`, LOCKED],
    ['404 không có tài khoản khách đã kích hoạt (một câu chung, không #id)', 404, 'RESOURCE_NOT_FOUND', ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND],
    ['404 không có hồ sơ khách', 404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy hồ sơ khách #7', 'Không tìm thấy hồ sơ khách #7'],
    [
      '400 VALIDATION_FAILED theo trường',
      400,
      'VALIDATION_FAILED',
      'accountEmail: Email tài khoản tối đa 255 ký tự; verificationMethod: Phương thức xác minh không được để trống',
      'Email tài khoản tối đa 255 ký tự. Phương thức xác minh không được để trống',
    ],
  ])('%s', async (_label, status, errorCode, message, shown) => {
    failWith(status, envelope(status, errorCode, message));
    const { alert, onClose, emailLabel, submit } = await submitDialog(DIALOGS[1]);

    expect(alert.textContent).toBe(shown);
    expect(alert).toHaveClass('bg-red-50', 'text-red-700');
    expect(alert).not.toHaveTextContent('chưa được máy chủ hỗ trợ');
    expect(alert).not.toHaveTextContent('BR-');
    expect(sent).toEqual(['POST /api/customers/7/link-account']);
    expect(screen.getByLabelText(emailLabel)).toHaveValue('khach.moi@gmail.com');
    await waitFor(() => expect(screen.getByRole('button', { name: submit })).toBeEnabled());
    expect(onClose).not.toHaveBeenCalled();
  });

  it('thành công: gửi accountEmail đã cắt khoảng trắng kèm ID_CARD_IN_PERSON, không OTP, đóng hộp', async () => {
    const bodies: unknown[] = [];
    const adapter: AxiosAdapter = (config) => {
      bodies.push(JSON.parse(String(config.data)));
      const data = { data: { customerId: 7 }, message: 'Đã liên kết hồ sơ với tài khoản', code: 200 };
      return Promise.resolve({ status: 200, statusText: 'OK', data, headers: {}, config } as AxiosResponse);
    };
    apiClient.defaults.adapter = adapter;
    const onClose = vi.fn();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <LinkAccountDialog customer={CUSTOMER} open onClose={onClose} />
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('Đã đối chiếu CCCD của khách với họ tên hồ sơ'));
    await user.type(screen.getByLabelText('Email tài khoản của khách'), '  khach.moi@gmail.com ');
    await user.click(screen.getByRole('button', { name: 'Liên kết' }));

    await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
    expect(bodies).toEqual([{ accountEmail: 'khach.moi@gmail.com', verificationMethod: 'ID_CARD_IN_PERSON' }]);
    expect(screen.queryByLabelText('Mã OTP')).not.toBeInTheDocument();
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

  // BE #25 luôn trả `maskedEmail: null`: bước nhập mã nêu email lễ tân vừa nhập, không bao giờ chữ "null" (như lỗi K16 ở hộp sửa email nhân viên)
  // và không dùng email đã che nếu có trường đó.
  it.each([
    ['maskedEmail null (đúng BE)', null],
    ['maskedEmail có giá trị (không dùng)', 'kh***@gmail.com'],
  ])('bước nhập mã nêu email vừa nhập: %s', async (_label, maskedEmail) => {
    await openCodeStep({ ...sent(6), maskedEmail });

    expect(screen.getByText('khach.moi@gmail.com')).toBeInTheDocument();
    expect(screen.queryByText(/null/)).not.toBeInTheDocument();
    expect(screen.queryByText('kh***@gmail.com')).not.toBeInTheDocument();
  });

  // identityApi.sendCustomerEmailChange trả OtpSentResponse của auth.ts (có `codeLength`), không phải bản khai trùng thiếu trường ở types/identity.ts (tsc kiểm).
  it('kiểu trả về của identityApi.sendCustomerEmailChange có codeLength', () => {
    expectTypeOf<Awaited<ReturnType<typeof identityApi.sendCustomerEmailChange>>>().toEqualTypeOf<OtpSentResponse>();
  });
});
