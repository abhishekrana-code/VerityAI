import { lazy, Suspense } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from '@/hooks/useAuth';
import { Layout } from '@/components/Layout';
import { Toaster } from '@/components/ui/toaster';

// Route-level code splitting keeps the initial bundle small: the landing page
// does not pay for the research workspace, graph renderer, or settings UI.
const LandingPage = lazy(() => import('@/features/landing/LandingPage').then((m) => ({ default: m.LandingPage })));
const LoginPage = lazy(() => import('@/features/auth/LoginPage').then((m) => ({ default: m.LoginPage })));
const RegisterPage = lazy(() => import('@/features/auth/RegisterPage').then((m) => ({ default: m.RegisterPage })));
const ForgotPasswordPage = lazy(() => import('@/features/auth/ForgotPasswordPage').then((m) => ({ default: m.ForgotPasswordPage })));
const ResetPasswordPage = lazy(() => import('@/features/auth/ResetPasswordPage').then((m) => ({ default: m.ResetPasswordPage })));
const PrivacyPage = lazy(() => import('@/features/legal/LegalPages').then((m) => ({ default: m.PrivacyPage })));
const TermsPage = lazy(() => import('@/features/legal/LegalPages').then((m) => ({ default: m.TermsPage })));
const SecurityPage = lazy(() => import('@/features/legal/LegalPages').then((m) => ({ default: m.SecurityPage })));
const DashboardPage = lazy(() => import('@/features/dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const DocumentsPage = lazy(() => import('@/features/documents/DocumentsPage').then((m) => ({ default: m.DocumentsPage })));
const DocumentDetailPage = lazy(() => import('@/features/documents/DocumentDetailPage').then((m) => ({ default: m.DocumentDetailPage })));
const WorkspacePage = lazy(() => import('@/features/workspace/WorkspacePage').then((m) => ({ default: m.WorkspacePage })));
const ConversationPage = lazy(() => import('@/features/workspace/ConversationPage').then((m) => ({ default: m.ConversationPage })));
const SearchPage = lazy(() => import('@/features/search/SearchPage').then((m) => ({ default: m.SearchPage })));
const KnowledgeGraphPage = lazy(() => import('@/features/knowledge-graph/KnowledgeGraphPage').then((m) => ({ default: m.KnowledgeGraphPage })));
const VerificationPage = lazy(() => import('@/features/verification/VerificationPage').then((m) => ({ default: m.VerificationPage })));
const ReportsPage = lazy(() => import('@/features/reports/ReportsPage').then((m) => ({ default: m.ReportsPage })));
const HistoryPage = lazy(() => import('@/features/history/HistoryPage').then((m) => ({ default: m.HistoryPage })));
const SettingsPage = lazy(() => import('@/features/settings/SettingsPage').then((m) => ({ default: m.SettingsPage })));

function PageLoader() {
  return (
    <div
      className="flex min-h-[60vh] items-center justify-center"
      role="status"
      aria-live="polite"
      aria-label="Loading page"
    >
      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
    </div>
  );
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { data: auth, isLoading } = useAuth();

  if (isLoading) {
    return <PageLoader />;
  }

  if (!auth?.authenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { data: auth, isLoading } = useAuth();

  if (isLoading) {
    return <PageLoader />;
  }

  if (auth?.authenticated) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}

export function App() {
  return (
    <>
      <a href="#main-content" className="skip-link">
        Skip to main content
      </a>
      <Suspense fallback={<PageLoader />}>
        <Routes>
          <Route path="/" element={<LandingPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/security" element={<SecurityPage />} />
          <Route
            path="/login"
            element={
              <PublicRoute>
                <LoginPage />
              </PublicRoute>
            }
          />
          <Route
            path="/register"
            element={
              <PublicRoute>
                <RegisterPage />
              </PublicRoute>
            }
          />
          <Route
            path="/forgot-password"
            element={
              <PublicRoute>
                <ForgotPasswordPage />
              </PublicRoute>
            }
          />
          <Route
            path="/reset-password"
            // Deliberately not wrapped in PublicRoute: a reset link must work
            // even when the browser already has a session. Bouncing signed-in
            // users to /dashboard makes the emailed link appear broken, and the
            // reset itself signs every session out anyway.
            element={<ResetPasswordPage />}
          />
          <Route
            path="/dashboard/*"
            element={
              <ProtectedRoute>
                <Layout />
              </ProtectedRoute>
            }
          >
            <Route index element={<DashboardPage />} />
            <Route path="search" element={<SearchPage />} />
            <Route path="documents" element={<DocumentsPage />} />
            <Route path="documents/:id" element={<DocumentDetailPage />} />
            <Route path="workspace" element={<WorkspacePage />} />
            <Route path="workspace/:conversationId" element={<ConversationPage />} />
            <Route path="knowledge-graph" element={<KnowledgeGraphPage />} />
            <Route path="verification" element={<VerificationPage />} />
            <Route path="reports" element={<ReportsPage />} />
            <Route path="history" element={<HistoryPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
      <Toaster />
    </>
  );
}
