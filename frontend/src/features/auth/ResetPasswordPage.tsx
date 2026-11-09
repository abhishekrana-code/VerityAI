import { Link, useSearchParams } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Logo } from '@/components/Logo';
import { useConfirmPasswordReset } from '@/hooks/useAuth';
import { cn } from '@/lib/utils';
import { AlertCircle, ArrowLeft, CheckCircle2, Loader2 } from 'lucide-react';

const resetSchema = z
  .object({
    password: z.string().min(8, 'Password must be at least 8 characters'),
    confirmPassword: z.string(),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

type ResetForm = z.infer<typeof resetSchema>;

export function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token')?.trim() ?? '';
  const { mutate: confirmReset, isPending, error, isSuccess } = useConfirmPasswordReset();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ResetForm>({
    resolver: zodResolver(resetSchema),
  });

  const onSubmit = (data: ResetForm) => {
    confirmReset({ token, newPassword: data.password });
  };

  return (
    <div className="relative isolate flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-12">
      <div className="pointer-events-none absolute -top-40 left-1/2 -z-10 h-96 w-96 -translate-x-1/2 rounded-full bg-indigo-500/10 blur-[100px]" />
      <main id="main-content" className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex justify-center"><Logo to="/" /></div>
          <h1 className="mt-6 text-2xl font-bold">Choose a new password</h1>
          <p className="mt-2 text-muted-foreground">
            Your reset link is single-use. Signing in afterwards will end any other active sessions.
          </p>
        </div>

        {!token ? (
          <div className="surface-card space-y-5 p-6 text-center sm:p-8" role="alert">
            <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-destructive/10 text-destructive">
              <AlertCircle className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="space-y-2">
              <h2 className="text-lg font-semibold">This reset link is incomplete</h2>
              <p className="text-sm leading-6 text-muted-foreground">
                The link is missing its token. Request a new password-reset email to continue.
              </p>
            </div>
            <Link
              to="/forgot-password"
              className="inline-flex items-center gap-2 text-sm font-medium text-primary hover:underline"
            >
              Request a new link
            </Link>
          </div>
        ) : isSuccess ? (
          <div className="surface-card space-y-5 p-6 text-center sm:p-8" role="status" aria-live="polite">
            <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-400/10 text-emerald-400">
              <CheckCircle2 className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="space-y-2">
              <h2 className="text-lg font-semibold">Password updated</h2>
              <p className="text-sm leading-6 text-muted-foreground">
                Your password has been changed and every existing session was signed out. Sign in with your new password.
              </p>
            </div>
            <Link
              to="/login"
              className="inline-flex items-center gap-2 text-sm font-medium text-primary hover:underline"
            >
              <ArrowLeft className="h-4 w-4" aria-hidden="true" />
              Go to sign in
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit(onSubmit)} className="surface-card space-y-5 p-6 sm:p-8" noValidate>
            {error && (
              <div
                role="alert"
                className="flex items-center gap-2 rounded-lg bg-destructive/10 p-4 text-sm text-destructive"
              >
                <AlertCircle className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
                <p>{error.error?.message || 'This reset link is invalid or has expired. Request a new one.'}</p>
              </div>
            )}

            <div className="space-y-2">
              <label htmlFor="password" className="block text-sm font-medium">
                New password
              </label>
              <input
                id="password"
                type="password"
                autoComplete="new-password"
                autoFocus
                {...register('password')}
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

            <div className="space-y-2">
              <label htmlFor="confirmPassword" className="block text-sm font-medium">
                Confirm new password
              </label>
              <input
                id="confirmPassword"
                type="password"
                autoComplete="new-password"
                {...register('confirmPassword')}
                className={cn(
                  'flex h-10 w-full rounded-lg border border-input bg-background px-3 py-2 text-sm ring-offset-background',
                  'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  'disabled:cursor-not-allowed disabled:opacity-50',
                  errors.confirmPassword && 'border-destructive focus-visible:ring-destructive'
                )}
                disabled={isPending}
                placeholder="••••••••"
                aria-invalid={Boolean(errors.confirmPassword)}
                aria-describedby={errors.confirmPassword ? 'confirm-password-error' : undefined}
              />
              {errors.confirmPassword && (
                <p id="confirm-password-error" className="text-sm text-destructive">
                  {errors.confirmPassword.message}
                </p>
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
              {isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              <span>{isPending ? 'Updating…' : 'Update password'}</span>
            </button>
          </form>
        )}

        {token && !isSuccess && (
          <p className="mt-6 text-center text-sm text-muted-foreground">
            Link expired?{' '}
            <Link to="/forgot-password" className="font-medium text-primary hover:underline">
              Request a new one
            </Link>
          </p>
        )}
      </main>
    </div>
  );
}
