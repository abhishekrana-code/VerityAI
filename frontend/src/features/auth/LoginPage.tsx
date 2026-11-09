'use client';

import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Logo } from '@/components/Logo';
import { useLogin } from '@/hooks/useAuth';
import { cn } from '@/lib/utils';
import { AlertCircle, CheckCircle2, Loader2 } from 'lucide-react';

const loginSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z.string().min(1, 'Password is required'),
});

type LoginForm = z.infer<typeof loginSchema>;

export function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const justRegistered = searchParams.get('registered') === '1';
  const { mutate: login, isPending, error } = useLogin();

  const {
    register: registerField,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
  });

  const onSubmit = (data: LoginForm) => {
    login(data, {
      onSuccess: () => navigate('/dashboard'),
    });
  };

  return (
    <div className="relative isolate flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-12">
      <div className="pointer-events-none absolute -top-40 left-1/2 -z-10 h-96 w-96 -translate-x-1/2 rounded-full bg-indigo-500/10 blur-[100px]" />
      <main id="main-content" tabIndex={-1} className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex justify-center"><Logo to="/" /></div>
          <h1 className="mt-6 text-2xl font-bold">Welcome back</h1>
          <p className="mt-2 text-muted-foreground">Sign in to continue your research</p>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="surface-card space-y-5 p-6 sm:p-8" noValidate>
          {justRegistered && !error && (
            <div
              role="status"
              aria-live="polite"
              className="flex items-center gap-2 rounded-lg bg-emerald-400/10 p-4 text-sm text-emerald-300"
            >
              <CheckCircle2 className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
              <p>Account created. Sign in to start researching.</p>
            </div>
          )}
          {error && (
            <div
              role="alert"
              className="flex items-center gap-2 rounded-lg bg-destructive/10 p-4 text-sm text-destructive"
            >
              <AlertCircle className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
              <p>{error.error?.message || 'Invalid credentials. Please try again.'}</p>
            </div>
          )}

          <div className="space-y-2">
            <label htmlFor="email" className="block text-sm font-medium">
              Email
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              {...registerField('email')}
              className={cn(
                'flex h-10 w-full rounded-lg border border-input bg-background px-3 py-2 text-sm ring-offset-background',
                'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                'disabled:cursor-not-allowed disabled:opacity-50',
                errors.email && 'border-destructive focus-visible:ring-destructive'
              )}
              disabled={isPending}
              placeholder="you@example.com"
              aria-invalid={Boolean(errors.email)}
              aria-describedby={errors.email ? 'email-error' : undefined}
            />
            {errors.email && (
              <p id="email-error" className="text-sm text-destructive">{errors.email.message}</p>
            )}
          </div>

          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <label htmlFor="password" className="block text-sm font-medium">
                Password
              </label>
              <Link
                to="/forgot-password"
                className="text-xs font-medium text-primary hover:underline"
              >
                Forgot password?
              </Link>
            </div>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              {...registerField('password')}
              className={cn(
                'flex h-10 w-full rounded-lg border border-input bg-background px-3 py-2 text-sm ring-offset-background',
                'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                'disabled:cursor-not-allowed disabled:opacity-50',
                errors.password && 'border-destructive focus-visible:ring-destructive'
              )}
              disabled={isPending}
              placeholder="••••••••"
              aria-invalid={Boolean(errors.password)}
              aria-describedby={errors.password ? 'password-error' : undefined}
            />
            {errors.password && (
              <p id="password-error" className="text-sm text-destructive">{errors.password.message}</p>
            )}
          </div>

          <button
            type="submit"
            disabled={isPending}
            className={cn(
              'w-full flex h-10 items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground',
              'hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed'
            )}
          >
            {isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            <span>Sign In</span>
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Don't have an account?{' '}
          <Link to="/register" className="font-medium text-primary hover:underline">
            Create one
          </Link>
        </p>
      </main>
    </div>
  );
}