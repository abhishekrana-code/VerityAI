'use client';

import { Link, useNavigate } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Logo } from '@/components/Logo';
import { useRegister } from '@/hooks/useAuth';
import { cn } from '@/lib/utils';
import { AlertCircle, Loader2 } from 'lucide-react';

const registerSchema = z.object({
  email: z.string().email('Invalid email address'),
  password: z.string().min(8, 'Password must be at least 8 characters'),
  confirmPassword: z.string(),
  displayName: z.string().max(100).optional(),
}).refine((data) => data.password === data.confirmPassword, {
  message: 'Passwords do not match',
  path: ['confirmPassword'],
});

type RegisterForm = z.infer<typeof registerSchema>;

export function RegisterPage() {
  const navigate = useNavigate();
  const { mutate: register, isPending, error } = useRegister();

  const {
    register: registerField,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
    defaultValues: { displayName: '' },
  });

  const onSubmit = (data: RegisterForm) => {
    register(
      { email: data.email, password: data.password, displayName: data.displayName },
      {
        // Registration is intentionally session-free: the account is created
        // but the user must sign in explicitly.
        onSuccess: () => navigate('/login?registered=1'),
      }
    );
  };

  return (
    <div className="relative isolate flex min-h-screen items-center justify-center overflow-hidden bg-background px-4 py-12">
      <div className="pointer-events-none absolute -top-40 left-1/2 -z-10 h-96 w-96 -translate-x-1/2 rounded-full bg-indigo-500/10 blur-[100px]" />
      <main id="main-content" tabIndex={-1} className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="flex justify-center"><Logo to="/" /></div>
          <h1 className="mt-6 text-2xl font-bold">Create your account</h1>
          <p className="mt-2 text-muted-foreground">Start researching with evidence-based answers</p>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="surface-card space-y-5 p-6 sm:p-8" noValidate>
          {error && (
            <div
              role="alert"
              className="flex items-center gap-2 rounded-lg bg-destructive/10 p-4 text-sm text-destructive"
            >
              <AlertCircle className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
              <p>{error.error?.message || 'Registration failed. Please try again.'}</p>
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
            <label htmlFor="displayName" className="block text-sm font-medium">
              Display Name (optional)
            </label>
            <input
              id="displayName"
              type="text"
              autoComplete="name"
              {...registerField('displayName')}
              className={cn(
                'flex h-10 w-full rounded-lg border border-input bg-background px-3 py-2 text-sm ring-offset-background',
                'placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                'disabled:cursor-not-allowed disabled:opacity-50'
              )}
              disabled={isPending}
              placeholder="Your Name"
            />
          </div>

          <div className="space-y-2">
            <label htmlFor="password" className="block text-sm font-medium">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
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

          <div className="space-y-2">
            <label htmlFor="confirmPassword" className="block text-sm font-medium">
              Confirm Password
            </label>
            <input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              {...registerField('confirmPassword')}
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
            {isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            <span>Create Account</span>
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-muted-foreground">
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-primary hover:underline">
            Sign in
          </Link>
        </p>
      </main>
    </div>
  );
}