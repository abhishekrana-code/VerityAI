import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Logo } from '@/components/Logo';
import { useRequestPasswordReset } from '@/hooks/useAuth';
import { cn } from '@/lib/utils';
import { AlertCircle, ArrowLeft, Loader2, MailCheck } from 'lucide-react';

const requestSchema = z.object({
  email: z.string().email('Invalid email address'),
});

type RequestForm = z.infer<typeof requestSchema>;

export function ForgotPasswordPage() {
  const [submitted, setSubmitted] = useState(false);
  const { mutate: requestReset, isPending, error } = useRequestPasswordReset();

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RequestForm>({
    resolver: zodResolver(requestSchema),
  });

  const onSubmit = (data: RequestForm) => {
    requestReset(data, { onSuccess: () => setSubmitted(true) });
  };

  return (
    <div className="relative isolate flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-12">
      <div className="pointer-events-none absolute -top-40 left-1/2 -z-10 h-96 w-96 -translate-x-1/2 rounded-full bg-indigo-500/10 blur-[100px]" />
      <main id="main-content" className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex justify-center"><Logo to="/" /></div>
          <h1 className="mt-6 text-2xl font-bold">Reset your password</h1>
          <p className="mt-2 text-muted-foreground">
            Enter your account email and we&apos;ll send a single-use reset link.
          </p>
        </div>

        {submitted ? (
          <div className="surface-card space-y-5 p-6 text-center sm:p-8" role="status" aria-live="polite">
            <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-400/10 text-emerald-400">
              <MailCheck className="h-6 w-6" aria-hidden="true" />
            </div>
            <div className="space-y-2">
              <h2 className="text-lg font-semibold">Check your inbox</h2>
              <p className="text-sm leading-6 text-muted-foreground">
                If an account exists for that address, a password-reset link is on its way. The link expires shortly and can
                only be used once.
              </p>
            </div>
            <Link
              to="/login"
              className="inline-flex items-center gap-2 text-sm font-medium text-primary hover:underline"
            >
              <ArrowLeft className="h-4 w-4" aria-hidden="true" />
              Back to sign in
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
                <p>{error.error?.message || 'Could not request a reset link. Please try again.'}</p>
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
                autoFocus
                {...register('email')}
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

            <button
              type="submit"
              disabled={isPending}
              className={cn(
                'w-full flex h-10 items-center justify-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground',
                'hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed'
              )}
            >
              {isPending && <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />}
              <span>{isPending ? 'Sending…' : 'Send reset link'}</span>
            </button>
          </form>
        )}

        {!submitted && (
          <p className="mt-6 text-center text-sm text-muted-foreground">
            Remembered it?{' '}
            <Link to="/login" className="font-medium text-primary hover:underline">
              Back to sign in
            </Link>
          </p>
        )}
      </main>
    </div>
  );
}
