'use client';

import { useAuth } from '@/hooks/useAuth';
import { Link } from 'react-router-dom';
import { FileText, MessageSquare, Upload, Clock, CheckCircle, AlertCircle, File, Loader2, ArrowUpRight, Sparkles, ShieldCheck } from 'lucide-react';
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
      <section className="relative isolate overflow-hidden rounded-[26px] border border-indigo-400/15 bg-gradient-to-br from-indigo-500/[0.16] via-card to-violet-500/[0.10] p-6 sm:p-8 lg:p-10">
        <div className="pointer-events-none absolute -right-20 -top-28 -z-10 h-72 w-72 rounded-full bg-indigo-500/15 blur-[90px]" />
        <div className="pointer-events-none absolute bottom-0 right-0 -z-10 h-40 w-1/2 bg-gradient-to-l from-violet-500/[0.06] to-transparent" />
        <div className="flex flex-col gap-7 lg:flex-row lg:items-end lg:justify-between">
          <div className="max-w-2xl">
            <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-indigo-300/20 bg-indigo-400/10 px-3 py-1.5 text-[11px] font-semibold text-indigo-300"><Sparkles className="h-3.5 w-3.5"/> Your research, in focus</div>
            <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">Welcome back, {userName}</h1>
            <p className="mt-3 max-w-xl text-sm leading-7 text-muted-foreground sm:text-base">Pick up where you left off, explore your document library, or start a new evidence-led investigation.</p>
          </div>
          <Link to="/dashboard/workspace" className="inline-flex shrink-0 items-center justify-center gap-2 rounded-xl bg-primary px-5 py-3 text-sm font-semibold text-primary-foreground shadow-lg shadow-indigo-950/20 transition hover:-translate-y-0.5 hover:bg-primary/90">
            <MessageSquare className="h-4 w-4"/> Start researching <ArrowUpRight className="h-4 w-4"/>
          </Link>
        </div>
        <div className="mt-8 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border/60 pt-5 text-[11px] text-muted-foreground">
          <span className="inline-flex items-center gap-1.5"><ShieldCheck className="h-3.5 w-3.5 text-emerald-400"/> Evidence-first answers</span>
          <span className="inline-flex items-center gap-1.5"><FileText className="h-3.5 w-3.5 text-indigo-300"/> Your document library</span>
          <span className="inline-flex items-center gap-1.5"><MessageSquare className="h-3.5 w-3.5 text-violet-300"/> Research history</span>
        </div>
      </section>

      {/* Quick actions */}
      <section>
        <div className="mb-4 flex items-center justify-between"><div><p className="eyebrow">Get started</p><h2 className="mt-1 text-lg font-bold">Quick actions</h2></div></div>
        <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {quickActions.map((action) => (
            <Link
              key={action.name}
              to={action.href}
              className="group flex items-start gap-4 rounded-2xl border border-border/80 bg-card/75 p-5 shadow-sm transition duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:bg-accent/45 hover:shadow-lg"
            >
              <div className="inline-flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-xl border border-primary/15 bg-primary/10 text-primary transition group-hover:bg-primary group-hover:text-primary-foreground">
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
          <h2 className="text-lg font-bold">Recent documents</h2>
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
                className="group rounded-2xl border border-border/80 bg-card/75 p-5 shadow-sm transition hover:-translate-y-0.5 hover:border-primary/40 hover:bg-accent/45"
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
          <h2 className="text-lg font-bold">Recent conversations</h2>
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
                className="flex items-center gap-4 rounded-2xl border border-border/80 bg-card/75 p-4 shadow-sm transition hover:border-primary/40 hover:bg-accent/45"
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