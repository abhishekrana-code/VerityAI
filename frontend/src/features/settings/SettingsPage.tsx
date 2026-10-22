'use client';

import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, type User } from '@/lib/api';
import { useAuth, useUpdateProfile, useChangePassword } from '@/hooks/useAuth';
import { Loader2, User as UserIcon, Lock, Bell, Palette, Save, Check, Sun, Moon, Monitor } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';

const profileSchema = z.object({
  displayName: z.string().max(100).optional(),
});

const passwordSchema = z.object({
  currentPassword: z.string().min(1, 'Current password is required'),
  newPassword: z.string().min(8, 'New password must be at least 8 characters'),
  confirmPassword: z.string(),
}).refine((data) => data.newPassword === data.confirmPassword, {
  message: 'Passwords do not match',
  path: ['confirmPassword'],
});

type ProfileForm = z.infer<typeof profileSchema>;
type PasswordForm = z.infer<typeof passwordSchema>;

export function SettingsPage() {
  const queryClient = useQueryClient();
  const { data: auth } = useAuth();
  const { toast } = useToast();
  const [activeTab, setActiveTab] = useState<'profile' | 'password' | 'preferences'>('profile');

  const updateProfile = useUpdateProfile();
  const changePassword = useChangePassword();

  const profileForm = useForm<ProfileForm>({
    resolver: zodResolver(profileSchema),
    defaultValues: { displayName: auth?.user?.displayName || '' },
  });

  const passwordForm = useForm<PasswordForm>({
    resolver: zodResolver(passwordSchema),
  });

  const onProfileSubmit = (data: ProfileForm) => {
    updateProfile.mutate(data, {
      onSuccess: () => {
        queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
        toast({ title: 'Profile updated', description: 'Your changes have been saved' });
      },
      onError: () => toast({ title: 'Update failed', variant: 'destructive' }),
    });
  };

  const onPasswordSubmit = (data: PasswordForm) => {
    changePassword.mutate({ currentPassword: data.currentPassword, newPassword: data.newPassword }, {
      onSuccess: () => {
        passwordForm.reset();
        toast({ title: 'Password changed', description: 'Your password has been updated' });
      },
      onError: () => toast({ title: 'Change failed', variant: 'destructive' }),
    });
  };

  return (
    <div className="max-w-3xl mx-auto space-y-8">
      <div>
        <h1 className="text-2xl font-bold">Settings</h1>
        <p className="text-muted-foreground">Manage your account and preferences</p>
      </div>

      <div className="rounded-xl border">
        {/* Tabs */}
        <div className="flex border-b">
          {[
            { id: 'profile', label: 'Profile', icon: User },
            { id: 'password', label: 'Password', icon: Lock },
            { id: 'preferences', label: 'Preferences', icon: Palette },
          ].map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as typeof activeTab)}
              className={cn(
                'flex items-center gap-2 px-4 py-3 text-sm font-medium border-b-2 transition-colors',
                activeTab === tab.id
                  ? 'border-primary text-primary'
                  : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-accent/50'
              )}
            >
              <tab.icon className="h-4 w-4" />
              {tab.label}
            </button>
          ))}
        </div>

        {/* Tab Panels */}
        <div className="p-6">
          {activeTab === 'profile' && (
            <form onSubmit={profileForm.handleSubmit(onProfileSubmit)} className="space-y-6">
              <div>
                <label className="block text-sm font-medium mb-2">Email</label>
                <input
                  type="email"
                  value={auth?.user?.email}
                  readOnly
                  className="w-full h-10 rounded-lg border border-input bg-muted px-3 text-sm"
                />
                <p className="mt-1 text-xs text-muted-foreground">Email cannot be changed</p>
              </div>
              <div>
                <label htmlFor="displayName" className="block text-sm font-medium mb-2">
                  Display Name
                </label>
                <input
                  id="displayName"
                  {...profileForm.register('displayName')}
                  className="w-full h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
              </div>
              <div className="flex justify-end">
                <button
                  type="submit"
                  disabled={updateProfile.isPending}
                  className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {updateProfile.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                  <Save className="h-4 w-4" />
                  Save Changes
                </button>
              </div>
            </form>
          )}

          {activeTab === 'password' && (
            <form onSubmit={passwordForm.handleSubmit(onPasswordSubmit)} className="space-y-6">
              <div>
                <label htmlFor="currentPassword" className="block text-sm font-medium mb-2">
                  Current Password
                </label>
                <input
                  id="currentPassword"
                  type="password"
                  {...passwordForm.register('currentPassword')}
                  className="w-full h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {passwordForm.formState.errors.currentPassword && (
                  <p className="mt-1 text-sm text-destructive">{passwordForm.formState.errors.currentPassword.message}</p>
                )}
              </div>
              <div>
                <label htmlFor="newPassword" className="block text-sm font-medium mb-2">
                  New Password
                </label>
                <input
                  id="newPassword"
                  type="password"
                  {...passwordForm.register('newPassword')}
                  className="w-full h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {passwordForm.formState.errors.newPassword && (
                  <p className="mt-1 text-sm text-destructive">{passwordForm.formState.errors.newPassword.message}</p>
                )}
              </div>
              <div>
                <label htmlFor="confirmPassword" className="block text-sm font-medium mb-2">
                  Confirm New Password
                </label>
                <input
                  id="confirmPassword"
                  type="password"
                  {...passwordForm.register('confirmPassword')}
                  className="w-full h-10 rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                />
                {passwordForm.formState.errors.confirmPassword && (
                  <p className="mt-1 text-sm text-destructive">{passwordForm.formState.errors.confirmPassword.message}</p>
                )}
              </div>
              <div className="flex justify-end">
                <button
                  type="submit"
                  disabled={changePassword.isPending}
                  className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {changePassword.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                  <Lock className="h-4 w-4" />
                  Change Password
                </button>
              </div>
            </form>
          )}

          {activeTab === 'preferences' && (
            <div className="space-y-6">
              <div>
                <h3 className="font-medium mb-4">Theme</h3>
                <div className="grid grid-cols-3 gap-4">
                  {['light', 'dark', 'system'].map((theme) => (
                    <button
                      key={theme}
                      className={cn(
                        'flex flex-col items-center gap-2 rounded-lg border p-4 transition-colors',
                        'hover:border-primary/50'
                      )}
                    >
                      {theme === 'light' && <Sun className="h-8 w-8 text-amber-500" />}
                      {theme === 'dark' && <Moon className="h-8 w-8 text-blue-500" />}
                      {theme === 'system' && <Monitor className="h-8 w-8 text-muted-foreground" />}
                      <span className="capitalize font-medium">{theme}</span>
                    </button>
                  ))}
                </div>
              </div>
              <div className="border-t pt-6">
                <h3 className="font-medium mb-4">Notifications</h3>
                <div className="space-y-4">
                  {[
                    { label: 'Email notifications', description: 'Receive email updates about your research' },
                    { label: 'Processing complete', description: 'Notify when document processing finishes' },
                    { label: 'Weekly digest', description: 'Summary of your research activity' },
                  ].map((item, i) => (
                    <label key={i} className="flex items-center justify-between">
                      <div>
                        <p className="font-medium">{item.label}</p>
                        <p className="text-sm text-muted-foreground">{item.description}</p>
                      </div>
                      <input type="checkbox" className="h-4 w-4 rounded border-input" defaultChecked />
                    </label>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}