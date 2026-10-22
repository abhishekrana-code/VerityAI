import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type AuthResponse, type User, type CsrfResponse } from '@/lib/api';
import type { RegisterRequest, LoginRequest, ChangePasswordRequest, UpdateProfileRequest } from '@/types/api';

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
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: RegisterRequest) => api.post<AuthResponse>('/auth/register', data),
    onSuccess: (response) => {
      queryClient.setQueryData(['auth', 'me'], response);
      queryClient.invalidateQueries({ queryKey: ['auth'] });
    },
  });
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
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
    mutationFn: (data: UpdateProfileRequest) => api.patch<User>('/users/me', data),
    onSuccess: (user) => {
      queryClient.setQueryData(['auth', 'me'], (old: AuthResponse | undefined) =>
        old ? { ...old, user } : undefined
      );
    },
  });
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (data: ChangePasswordRequest) => api.post<void>('/users/me/password', data),
  });
}