import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from '@/hooks/useAuth';
import { Layout } from '@/components/Layout';
import { LoginPage } from '@/features/auth/LoginPage';
import { RegisterPage } from '@/features/auth/RegisterPage';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import { DocumentsPage } from '@/features/documents/DocumentsPage';
import { DocumentDetailPage } from '@/features/documents/DocumentDetailPage';
import { WorkspacePage } from '@/features/workspace/WorkspacePage';
import { ConversationPage } from '@/features/workspace/ConversationPage';
import { SearchPage } from '@/features/search/SearchPage';
import { KnowledgeGraphPage } from '@/features/knowledge-graph/KnowledgeGraphPage';
import { VerificationPage } from '@/features/verification/VerificationPage';
import { ReportsPage } from '@/features/reports/ReportsPage';
import { HistoryPage } from '@/features/history/HistoryPage';
import { SettingsPage } from '@/features/settings/SettingsPage';
import { LandingPage } from '@/features/landing/LandingPage';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { data: auth, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (!auth?.authenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { data: auth, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
      </div>
    );
  }

  if (auth?.authenticated) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
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
  );
}