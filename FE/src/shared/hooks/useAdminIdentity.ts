// React Query cho quản trị tài khoản (UC08–11): staff, accounts, configs, templates, audit.
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { identityApi } from '../api/identity.api';
import type { PageResponse } from '../types';
import type { AccountQuery, AuditQuery, ChangeRoleRequest, ReactivateStaffRequest, StaffQuery, TransferStaffRequest } from '../types/identity';

// ─── Staff (UC08) ───

export function useStaff(query: StaffQuery = {}) {
  return useQuery({
    queryKey: ['staff', query],
    queryFn: () => identityApi.listStaff(query),
    placeholderData: keepPreviousData,
  });
}

export function useStaffOne(id: number | null) {
  return useQuery({
    queryKey: ['staff', id],
    queryFn: () => identityApi.getStaff(id!),
    enabled: id != null,
  });
}

function useStaffMutation<T extends object>(fn: (id: number, args: T) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; args: T }) => fn(vars.id, vars.args),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['staff'] });
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
}

export const useCreateStaff = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: identityApi.createStaff,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['staff'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

export const useChangeRole = () => useStaffMutation<ChangeRoleRequest>((id, args) => identityApi.changeRole(id, args));
export const useTransfer = () => useStaffMutation<TransferStaffRequest>((id, args) => identityApi.transfer(id, args));
export const useDisable = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => identityApi.disable(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['staff'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};
export const useReactivate = () => useStaffMutation<ReactivateStaffRequest>((id, args) => identityApi.reactivate(id, args));
export const useResendInitialPassword = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => identityApi.resendInitialPassword(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['audit-logs'] }),
  });
};
export const useSendEmailChange = () =>
  useMutation({ mutationFn: (vars: { id: number; newEmail: string }) => identityApi.sendEmailChange(vars.id, { newEmail: vars.newEmail }) });
export const useConfirmEmailChange = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; code: string }) => identityApi.confirmEmailChange(vars.id, { code: vars.code }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['staff'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

// ─── Accounts (UC09) ───

export function useAccounts(query: AccountQuery = {}) {
  return useQuery({
    queryKey: ['accounts', query],
    queryFn: () => identityApi.listAccounts(query),
    placeholderData: keepPreviousData,
  });
}

export const useLock = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason: string }) => identityApi.lock(vars.id, { reason: vars.reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

export const useUnlock = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { id: number; reason: string }) => identityApi.unlock(vars.id, { reason: vars.reason }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

// ─── Configuration (UC10) ───

export const useSystemConfigs = () => useQuery({ queryKey: ['system-configs'], queryFn: identityApi.listSystemConfigs });

export const useUpdateSystemConfig = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { key: string; value: string }) => identityApi.updateSystemConfig(vars.key, { value: vars.value }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['system-configs'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

// ─── Templates (UC10) ───

export const useNotificationTemplates = () =>
  useQuery({ queryKey: ['notification-templates'], queryFn: identityApi.listNotificationTemplates });

export const useUpdateTemplate = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (vars: { code: string; subject: string; body: string }) =>
      identityApi.updateNotificationTemplate(vars.code, { subject: vars.subject, body: vars.body }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notification-templates'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

export const useRestoreTemplateDefault = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => identityApi.restoreTemplateDefault(code),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notification-templates'] });
      queryClient.invalidateQueries({ queryKey: ['audit-logs'] });
    },
  });
};

// ─── Audit (UC11) ───

export function useAuditLogs(query: AuditQuery = {}) {
  return useQuery({
    queryKey: ['audit-logs', query],
    queryFn: () => identityApi.listAuditLogs(query),
    placeholderData: keepPreviousData,
  });
}

// Re-export để giữ tiện
export type { PageResponse };
