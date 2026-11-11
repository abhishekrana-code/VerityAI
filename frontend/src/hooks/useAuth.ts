import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiException, type AuthResponse, type User, type CsrfResponse } from '@/lib/api';
import type {
  RegisterRequest,
  LoginRequest,
  ChangePasswordRequest,
  UpdateProfileRequest,
  PasswordResetRequest,
  PasswordResetConfirmRequest,
} from '@/types/api';

export function useAuth() {
  return useQuery({
    queryKey: ['auth', 'me'],
    queryFn: () => api.get<AuthResponse>('/auth/me'),
    retry: false,
    staleTime: 1000 * 60 * 5,
  });
}

export function useCsrfToken() {
  return useQuery({
    queryKey: ['auth', 'csrf'],
    queryFn: () => api.get<CsrfResponse>('/auth/csrf'),
    staleTime: 1000 * 60 * 30,
  });
}

export function useRegister() {
  return useMutation<AuthResponse, ApiException, RegisterRequest>({
    mutationFn: (data: RegisterRequest) => api.post<AuthResponse>('/auth/register', data),
    // Registration deliberately does not create a session: the response is
    // always generic (`authenticated: false`), so there is no auth state to
    // cache. The user signs in explicitly afterwards.
  });
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation<AuthResponse, ApiException, LoginRequest>({
    mutationFn: (data: LoginRequest) => api.post<AuthResponse>('/auth/login', data),
    onSuccess: (response) => {
      queryClient.setQueryData(['auth', 'me'], response);
      queryClient.invalidateQueries({ queryKey: ['auth'] });
    },
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSuccess: () => {
      queryClient.setQueryData(['auth', 'me'], { user: null, authenticated: false });
      queryClient.clear();
    },
  });
}

export function useUpdateProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdateProfileRequest) => api.patch<User>('/auth/users/me', data),
    onSuccess: (user) => {
      queryClient.setQueryData(['auth', 'me'], (old: AuthResponse | undefined) =>
        old ? { ...old, user } : undefined
      );
    },
  });
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (data: ChangePasswordRequest) => api.post<void>('/auth/users/me/password', data),
  });
}

export function useRequestPasswordReset() {
  return useMutation<void, ApiException, PasswordResetRequest>({
    mutationFn: (data: PasswordResetRequest) =>
      api.post<void>('/auth/password-reset/request', data),
  });
}

export function useConfirmPasswordReset() {
  const queryClient = useQueryClient();
  return useMutation<void, ApiException, PasswordResetConfirmRequest>({
    mutationFn: (data: PasswordResetConfirmRequest) =>
      api.post<void>('/auth/password-reset/confirm', data),
    onSuccess: () => {
      // The server just invalidated every session for the account. Drop the
      // cached principal so this tab stops believing it is still signed in.
      // (Do not clear the whole client: that would also discard this
      // mutation's success state and hide the confirmation screen.)
      queryClient.setQueryData(['auth', 'me'], { user: null, authenticated: false });
      queryClient.invalidateQueries({ queryKey: ['auth'] });
    },
  });
}