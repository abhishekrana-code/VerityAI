'use client';

import { useAuth } from '@/hooks/useAuth';
import { Link } from 'react-router-dom';
import { Plus, FileText, MessageSquare, Upload, Clock, CheckCircle, AlertCircle, File, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { formatDistanceToNow } from 'date-fns';
import { useQuery } from '@tanstack/react-query';
import { api, type Document, type Conversation } from '@/lib/api';

export function DashboardPage() {
  const { data: auth } = useAuth();
  const userName = auth?.user?.displayName || 'Researcher';

  const { data: documents, isLoading: docsLoading } = useQuery({
    queryKey: ['documents', 'recent'],
    queryFn: () => api.get<{ content: Document[] }>('/documents?size=5&sort=createdAt,desc'),
    select: (data) => data.content,
  });

  const { data: conversations, isLoading: convLoading } = useQuery({
    queryKey: ['conversations', 'recent'],
    queryFn: () => api.get<{ content: Conversation[] }>('/conversations?size=5&sort=updatedAt,desc'),
    select: (data) => data.content,
  });

  const quickActions = [
    { name: 'Upload Document', href: '/dashboard/documents', icon: Upload, description: 'Add a new PDF to your library' },
    { name: 'New Research', href: '/dashboard/workspace', icon: MessageSquare, description: 'Start a research session' },
    { name: 'Browse Documents', href: '/dashboard/documents', icon: FileText, description: 'View your document library' },
  ];

  const getStatusBadge = (status: Document['status']) => {
    const variants = {
      UPLOADED: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
      PROCESSING: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400',
      READY: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
      FAILED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
    };
    const icons = {
      UPLOADED: Clock,
      PROCESSING: Loader2,
      READY: CheckCircle,
      FAILED: AlertCircle,
    };
    const Icon = icons[status];
    return (
      <span className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium', variants[status])}>
        <Icon className="h-3 w-3" />
        {status}
      </span>
    );
  };

  return (
    <div className="space-y-8">
      {/* Welcome section */}
      <section className="space-y-2">
        <h1 className="text-3xl font-bold tracking-tight">
          Welcome back, {userName}
        </h1>
        <p className="text-muted-foreground">
          Continue your research or start a new investigation.
        </p>
      </section>

      {/* Quick actions */}
      <section>
        <h2 className="text-lg font-semibold mb-4">Quick Actions</h2>
        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {quickActions.map((action) => (
            <Link
              key={action.name}
              to={action.href}
              className="flex items-start gap-4 rounded-xl border p-5 hover:border-primary/50 hover:bg-accent/50 transition-colors"
            >
              <div className="inline-flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary flex-shrink-0">
                <action.icon className="h-6 w-6" />
              </div>
              <div>
                <h3 className="font-semibold">{action.name}</h3>
                <p className="text-sm text-muted-foreground mt-1">{action.description}</p>
              </div>
            </Link>
          ))}
        </div>
      </section>

      {/* Recent Documents */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">Recent Documents</h2>
          <Link to="/dashboard/documents" className="text-sm text-primary hover:underline">
            View all
          </Link>
        </div>
        {docsLoading ? (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="animate-pulse rounded-xl border p-5 space-y-3">
                <div className="h-4 bg-muted rounded w-3/4" />
                <div className="h-3 bg-muted rounded w-1/2" />
                <div className="h-3 bg-muted rounded w-1/4" />
              </div>
            ))}
          </div>
        ) : documents?.length === 0 ? (
          <div className="rounded-xl border p-8 text-center">
            <FileText className="h-12 w-12 mx-auto text-muted-foreground/50 mb-4" />
            <h3 className="font-semibold mb-2">No documents yet</h3>
            <p className="text-muted-foreground mb-4">Upload your first PDF to start researching</p>
            <Link to="/dashboard/documents" className="inline-flex items-center gap-2 text-primary hover:underline">
              <Upload className="h-4 w-4" />
              Upload Document
            </Link>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {documents!.map((doc) => (
              <Link
                key={doc.id}
                to={`/dashboard/documents/${doc.id}`}
                className="rounded-xl border p-5 hover:border-primary/50 hover:bg-accent/50 transition-colors group"
              >
                <div className="flex items-start justify-between gap-4 mb-3">
                  <File className="h-10 w-10 text-muted-foreground/50 group-hover:text-primary/50 transition-colors flex-shrink-0" />
                  {getStatusBadge(doc.status)}
                </div>
                <h3 className="font-medium truncate mb-1">{doc.originalFilename}</h3>
                <p className="text-xs text-muted-foreground">
                  {doc.pageCount ? `${doc.pageCount} pages • ` : ''}
                  {formatDistanceToNow(new Date(doc.createdAt), { addSuffix: true })}
                </p>
              </Link>
            ))}
          </div>
        )}
      </section>

      {/* Recent Conversations */}
      <section>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-lg font-semibold">Recent Conversations</h2>
          <Link to="/dashboard/workspace" className="text-sm text-primary hover:underline">
            View all
          </Link>
        </div>
        {convLoading ? (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="animate-pulse rounded-xl border p-4 space-y-2">
                <div className="h-4 bg-muted rounded w-1/3" />
                <div className="h-3 bg-muted rounded w-full" />
              </div>
            ))}
          </div>
        ) : conversations?.length === 0 ? (
          <div className="rounded-xl border p-8 text-center">
            <MessageSquare className="h-12 w-12 mx-auto text-muted-foreground/50 mb-4" />
            <h3 className="font-semibold mb-2">No conversations yet</h3>
            <p className="text-muted-foreground mb-4">Start a research session to see your history here</p>
            <Link to="/dashboard/workspace" className="inline-flex items-center gap-2 text-primary hover:underline">
              <MessageSquare className="h-4 w-4" />
              New Research
            </Link>
          </div>
        ) : (
          <div className="space-y-3">
            {conversations!.map((conv) => (
              <Link
                key={conv.id}
                to={`/dashboard/workspace/${conv.id}`}
                className="flex items-center gap-4 rounded-xl border p-4 hover:border-primary/50 hover:bg-accent/50 transition-colors"
              >
                <MessageSquare className="h-10 w-10 rounded-lg bg-primary/10 text-primary flex-shrink-0 flex items-center justify-center" />
                <div className="flex-1 min-w-0">
                  <h3 className="font-medium truncate">{conv.title}</h3>
                  <p className="text-xs text-muted-foreground">
                    {conv.messageCount} messages • {formatDistanceToNow(new Date(conv.updatedAt), { addSuffix: true })}
                  </p>
                </div>
              </Link>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}