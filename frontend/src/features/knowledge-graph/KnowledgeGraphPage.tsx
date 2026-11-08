'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  api,
  type Document,
  type KnowledgeEntityType,
  type KnowledgeGraphEdge,
  type KnowledgeGraphNode,
  type KnowledgeGraphRebuildResponse,
  type KnowledgeGraphResponse,
} from '@/lib/api';
import { errorToast } from '@/lib/errors';
import { cn } from '@/lib/utils';
import { PdfPreviewModal, type PdfPreviewTarget } from '@/components/PdfPreviewModal';
import { PageEmpty, PageError, PageLoading, StatCard } from '@/components/ui/PageStates';
import { ToastProvider, ToastViewport } from '@/components/ui/toast';
import { useToast } from '@/hooks/useToast';
import { layoutGraph } from './graphLayout';
import { formatDistanceToNow } from 'date-fns';
import {
  ArrowUpRight, FileText, Filter, Loader2, Network, RefreshCw, Search, X,
} from 'lucide-react';

const ENTITY_TYPES: Array<{ value: KnowledgeEntityType; label: string; color: string; badge: string }> = [
  { value: 'PERSON', label: 'Person', color: '#818cf8', badge: 'bg-indigo-500/15 text-indigo-300 border-indigo-400/25' },
  { value: 'ORGANIZATION', label: 'Organization', color: '#34d399', badge: 'bg-emerald-500/15 text-emerald-300 border-emerald-400/25' },
  { value: 'LOCATION', label: 'Location', color: '#fbbf24', badge: 'bg-amber-500/15 text-amber-300 border-amber-400/25' },
  { value: 'PRODUCT', label: 'Product', color: '#f472b6', badge: 'bg-pink-500/15 text-pink-300 border-pink-400/25' },
  { value: 'EVENT', label: 'Event', color: '#fb923c', badge: 'bg-orange-500/15 text-orange-300 border-orange-400/25' },
  { value: 'DATE', label: 'Date', color: '#22d3ee', badge: 'bg-cyan-500/15 text-cyan-300 border-cyan-400/25' },
  { value: 'METRIC', label: 'Metric', color: '#a3e635', badge: 'bg-lime-500/15 text-lime-300 border-lime-400/25' },
  { value: 'ACRONYM', label: 'Acronym', color: '#c084fc', badge: 'bg-purple-500/15 text-purple-300 border-purple-400/25' },
  { value: 'CONCEPT', label: 'Concept', color: '#94a3b8', badge: 'bg-slate-500/15 text-slate-300 border-slate-400/25' },
  { value: 'OTHER', label: 'Other', color: '#64748b', badge: 'bg-slate-600/15 text-slate-400 border-slate-500/25' },
];

const TYPE_MAP = new Map(ENTITY_TYPES.map((type) => [type.value, type]));

const GRAPH_WIDTH = 860;
const GRAPH_HEIGHT = 540;

export function KnowledgeGraphPage() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const [documentId, setDocumentId] = useState('');
  const [entityType, setEntityType] = useState('');
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [preview, setPreview] = useState<{ doc: PdfPreviewTarget; page: number } | null>(null);

  const { data: documents } = useQuery({
    queryKey: ['documents', 'ready', 'knowledge-graph'],
    queryFn: () => api.get<{ content: Document[] }>('/documents?size=100&status=READY'),
    select: (data) => data.content,
  });

  const graphQuery = useQuery({
    queryKey: ['knowledge-graph', documentId, entityType, query],
    queryFn: () => {
      const params = new URLSearchParams();
      if (documentId) params.set('documentId', documentId);
      if (entityType) params.set('type', entityType);
      if (query) params.set('q', query);
      params.set('limit', '120');
      return api.get<KnowledgeGraphResponse>(`/knowledge-graph?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
    refetchInterval: (query) => query.state.data?.status === 'PENDING' ? 2500 : false,
  });

  const rebuild = useMutation({
    mutationFn: () =>
      api.post<KnowledgeGraphRebuildResponse>('/knowledge-graph/rebuild',
        documentId ? { documentIds: [documentId] } : {}),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['knowledge-graph'] });
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      if (result.rebuilt > 0) {
        toast({
          title: `Rebuilt ${result.rebuilt} graph${result.rebuilt === 1 ? '' : 's'}`,
          description: result.skipped > 0
            ? `${result.skipped} document(s) were skipped (not READY or not owned).`
            : 'Entities and co-occurrence links are up to date.',
        });
      } else if (result.errors.length > 0) {
        toast({ title: 'Graph rebuild failed', description: result.errors[0], variant: 'destructive' });
      } else {
        toast({ title: 'Nothing to rebuild', description: 'No processed documents were available.' });
      }
    },
    onError: (error: Error) => toast(errorToast(error, 'Graph rebuild failed')),
  });

  const graph = graphQuery.data;
  const layout = useMemo(
    () => layoutGraph(graph?.nodes ?? [], graph?.edges ?? [], { width: GRAPH_WIDTH, height: GRAPH_HEIGHT }),
    [graph],
  );
  const selectedNode = graph?.nodes.find((node) => node.id === selectedId) ?? null;
  const connected = selectedNode && graph
    ? graph.edges
        .filter((edge) => edge.source === selectedNode.id || edge.target === selectedNode.id)
        .map((edge) => ({
          edge,
          node: graph.nodes.find((node) => node.id === (edge.source === selectedNode.id ? edge.target : edge.source)),
        }))
        .filter((entry): entry is { edge: KnowledgeGraphEdge; node: KnowledgeGraphNode } => Boolean(entry.node))
    : [];

  const submitSearch = (event: React.FormEvent) => {
    event.preventDefault();
    setQuery(queryInput.trim());
    setSelectedId(null);
  };

  const resetFilters = () => {
    setDocumentId('');
    setEntityType('');
    setQueryInput('');
    setQuery('');
    setSelectedId(null);
  };

  return (
    <div className="space-y-6">
      <section className="relative isolate overflow-hidden rounded-[26px] border border-indigo-400/15 bg-gradient-to-br from-indigo-500/[0.14] via-card to-violet-500/[0.08] p-6 sm:p-8">
        <div className="pointer-events-none absolute -right-16 -top-24 -z-10 h-64 w-64 rounded-full bg-violet-500/15 blur-[90px]" />
        <div className="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
          <div className="max-w-2xl">
            <p className="eyebrow">Knowledge graph</p>
            <h1 className="mt-2 text-2xl font-extrabold tracking-tight sm:text-3xl">See what your library connects</h1>
            <p className="mt-2 text-sm leading-7 text-muted-foreground">
              Entities and passages extracted from your own documents, stored entirely in
              PostgreSQL. Links mean “mentioned in the same passage” — they are evidence
              pointers, not asserted facts.
            </p>
          </div>
          <button
            onClick={() => rebuild.mutate()}
            disabled={rebuild.isPending}
            className="inline-flex shrink-0 items-center justify-center gap-2 rounded-xl border border-border bg-card/80 px-4 py-2.5 text-sm font-semibold transition hover:bg-accent disabled:opacity-50"
          >
            {rebuild.isPending
              ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              : <RefreshCw className="h-4 w-4" aria-hidden="true" />}
            Rebuild {documentId ? 'this graph' : 'graphs'}
          </button>
        </div>
      </section>

      <section className="flex flex-col gap-3 rounded-2xl border border-border/80 bg-card/60 p-3 lg:flex-row lg:items-center">
        <label className="flex min-w-0 flex-1 items-center gap-2 rounded-xl border border-border/70 bg-background/70 px-3 py-2">
          <FileText className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="sr-only">Document scope</span>
          <select
            value={documentId}
            onChange={(event) => { setDocumentId(event.target.value); setSelectedId(null); }}
            className="min-w-0 flex-1 bg-transparent text-sm outline-none"
            aria-label="Document scope"
          >
            <option value="">All documents</option>
            {documents?.map((document) => (
              <option key={document.id} value={document.id}>{document.originalFilename}</option>
            ))}
          </select>
        </label>

        <label className="flex items-center gap-2 rounded-xl border border-border/70 bg-background/70 px-3 py-2">
          <Filter className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <span className="sr-only">Entity type</span>
          <select
            value={entityType}
            onChange={(event) => { setEntityType(event.target.value); setSelectedId(null); }}
            className="bg-transparent text-sm outline-none"
            aria-label="Entity type"
          >
            <option value="">All entity types</option>
            {ENTITY_TYPES.map((type) => (
              <option key={type.value} value={type.value}>{type.label}</option>
            ))}
          </select>
        </label>

        <form onSubmit={submitSearch} className="flex min-w-0 flex-1 items-center gap-2">
          <div className="relative min-w-0 flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <input
              type="search"
              value={queryInput}
              onChange={(event) => setQueryInput(event.target.value)}
              placeholder="Filter entities…"
              aria-label="Filter entities by name"
              className="h-10 w-full rounded-xl border border-border/70 bg-background/70 pl-9 pr-8 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/25"
            />
            {queryInput && (
              <button
                type="button"
                onClick={() => { setQueryInput(''); setQuery(''); }}
                className="absolute right-1.5 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                aria-label="Clear entity filter"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
          <button type="submit" className="rounded-lg border border-border bg-card px-3 py-2 text-xs font-semibold hover:bg-accent">
            Filter
          </button>
        </form>

        {(documentId || entityType || query) && (
          <button onClick={resetFilters} className="rounded-lg px-3 py-2 text-xs font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline">
            Reset
          </button>
        )}
      </section>

      {graphQuery.isLoading && <PageLoading label="Loading knowledge graph…" />}

      {graphQuery.isError && !graphQuery.isLoading && (
        <PageError
          title="Could not load the knowledge graph"
          message={graphQuery.error instanceof Error ? graphQuery.error.message : undefined}
          onRetry={() => graphQuery.refetch()}
        />
      )}

      {graph && !graphQuery.isLoading && !graphQuery.isError && (
        <>
          <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatCard label="Entities" value={graph.entityCount} hint="in the selected scope" />
            <StatCard label="Co-occurrence links" value={graph.relationCount} hint="same-passage mentions" />
            <StatCard label="Documents" value={graph.documentCount} hint="with extracted entities" />
            <StatCard
              label="Last built"
              value={graph.generatedAt ? formatDistanceToNow(new Date(graph.generatedAt), { addSuffix: true }) : '—'}
              hint="deterministic extraction"
            />
          </section>

          {graph.status === 'PENDING' ? (
            <PageLoading label="The graph is being built…" />
          ) : graph.nodes.length === 0 ? (
            <PageEmpty
              icon={Network}
              title={graph.status === 'READY' ? 'No entities found' : 'No graph yet'}
              description={
                graph.status === 'FAILED'
                  ? (graph.error ?? 'The last build failed. Try rebuilding the graph.')
                  : graph.status === 'READY'
                    ? 'This document was processed, but no entities or dates/metrics matched the extractor. Try another document or rebuild after summaries finish.'
                    : 'Build the graph to extract entities and same-passage links from this document. Graph extraction is deterministic and does not call a model.'
              }
              action={
                <button
                  onClick={() => rebuild.mutate()}
                  disabled={rebuild.isPending}
                  className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
                >
                  {rebuild.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
                  Build graph
                </button>
              }
            />
          ) : (
            <>
              {graph.truncated && (
                <p className="rounded-xl border border-amber-400/25 bg-amber-500/10 px-4 py-3 text-xs text-amber-200">
                  Showing the {graph.returnedEntities} most-mentioned entities. Narrow the document
                  scope or filter by type/name to see the rest.
                </p>
              )}

              <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]">
                <div className="overflow-hidden rounded-2xl border border-border/80 bg-card/50">
                  <GraphCanvas
                    nodes={graph.nodes}
                    edges={graph.edges}
                    layout={layout}
                    selectedId={selectedId}
                    onSelect={setSelectedId}
                  />
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-border/60 px-4 py-3">
                    {ENTITY_TYPES.map((type) => (
                      <span key={type.value} className="inline-flex items-center gap-1.5 text-[10px] text-muted-foreground">
                        <span className="h-2 w-2 rounded-full" style={{ backgroundColor: type.color }} />
                        {type.label}
                      </span>
                    ))}
                  </div>
                </div>

                <EntityPanel
                  node={selectedNode}
                  connected={connected}
                  onSelect={setSelectedId}
                  onOpenSource={(node) => setPreview({
                    doc: { id: node.documentId, originalFilename: node.documentName },
                    page: node.firstPage ?? 1,
                  })}
                  onClose={() => setSelectedId(null)}
                />
              </div>

              <EntityIndex
                nodes={graph.nodes}
                selectedId={selectedId}
                onSelect={setSelectedId}
              />
            </>
          )}

          <p className="rounded-xl border border-border/70 bg-muted/30 px-4 py-3 text-[11px] leading-5 text-muted-foreground">
            <strong className="text-foreground/80">How to read this:</strong> entities come from
            deterministic patterns (dates, metrics, acronyms, proper nouns) merged with the
            summary model’s ENTITIES section when available. A link means two entities appear
            in the same passage; it does not prove a relationship. Open the source page to judge
            the evidence yourself.
          </p>
        </>
      )}

      {preview && (
        <PdfPreviewModal doc={preview.doc} initialPage={preview.page} onClose={() => setPreview(null)} />
      )}

      <ToastProvider>
        <ToastViewport />
      </ToastProvider>
    </div>
  );
}

function GraphCanvas({
  nodes,
  edges,
  layout,
  selectedId,
  onSelect,
}: {
  nodes: KnowledgeGraphNode[];
  edges: KnowledgeGraphEdge[];
  layout: Map<string, { x: number; y: number; radius: number }>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
}) {
  const connectedIds = useMemo(() => {
    if (!selectedId) return new Set<string>();
    const ids = new Set<string>();
    for (const edge of edges) {
      if (edge.source === selectedId) ids.add(edge.target);
      if (edge.target === selectedId) ids.add(edge.source);
    }
    return ids;
  }, [edges, selectedId]);

  return (
    <svg
      viewBox={`0 0 ${GRAPH_WIDTH} ${GRAPH_HEIGHT}`}
      className="h-auto w-full"
      role="img"
      aria-label={`Knowledge graph with ${nodes.length} entities and ${edges.length} co-occurrence links`}
    >
      <rect width={GRAPH_WIDTH} height={GRAPH_HEIGHT} fill="transparent" onClick={() => onSelect(null)} />
      {edges.map((edge) => {
        const source = layout.get(edge.source);
        const target = layout.get(edge.target);
        if (!source || !target) return null;
        const active = !selectedId || edge.source === selectedId || edge.target === selectedId;
        return (
          <line
            key={edge.id}
            x1={source.x}
            y1={source.y}
            x2={target.x}
            y2={target.y}
            stroke={active ? '#818cf8' : '#475569'}
            strokeWidth={active ? Math.min(3, 0.8 + edge.weight * 0.25) : 0.8}
            strokeOpacity={active ? Math.min(0.65, 0.18 + edge.weight * 0.08) : 0.12}
          >
            <title>{`Co-occurs in ${edge.weight} passage${edge.weight === 1 ? '' : 's'}`}</title>
          </line>
        );
      })}
      {nodes.map((node) => {
        const point = layout.get(node.id);
        if (!point) return null;
        const style = TYPE_MAP.get(node.type) ?? TYPE_MAP.get('OTHER')!;
        const selected = node.id === selectedId;
        const dimmed = Boolean(selectedId && !selected && !connectedIds.has(node.id));
        return (
          <g
            key={node.id}
            role="button"
            tabIndex={0}
            aria-label={`${node.name}, ${style.label}, ${node.mentionCount} mentions`}
            onClick={(event) => {
              event.stopPropagation();
              onSelect(selected ? null : node.id);
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onSelect(selected ? null : node.id);
              }
            }}
            className="cursor-pointer outline-none"
            opacity={dimmed ? 0.28 : 1}
          >
            <circle
              cx={point.x}
              cy={point.y}
              r={point.radius + (selected ? 4 : 0)}
              fill={style.color}
              fillOpacity={selected ? 0.95 : 0.75}
              stroke={selected ? '#e0e7ff' : '#0f172a'}
              strokeWidth={selected ? 2.5 : 1.5}
            />
            <text
              x={point.x}
              y={point.y + point.radius + 13}
              textAnchor="middle"
              className="pointer-events-none select-none"
              fontSize={10}
              fill="#cbd5e1"
            >
              {truncate(node.name, 22)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

function EntityPanel({
  node,
  connected,
  onSelect,
  onOpenSource,
  onClose,
}: {
  node: KnowledgeGraphNode | null;
  connected: Array<{ edge: KnowledgeGraphEdge; node: KnowledgeGraphNode }>;
  onSelect: (id: string | null) => void;
  onOpenSource: (node: KnowledgeGraphNode) => void;
  onClose: () => void;
}) {
  if (!node) {
    return (
      <aside className="rounded-2xl border border-border/80 bg-card/60 p-5">
        <div className="flex items-center gap-2 text-sm font-semibold">
          <Network className="h-4 w-4 text-indigo-300" aria-hidden="true" />
          Entity inspector
        </div>
        <p className="mt-3 text-sm leading-6 text-muted-foreground">
          Select a node to see its type, mentions, evidence passage and connected entities.
          You can also use the entity index below the graph.
        </p>
      </aside>
    );
  }

  const style = TYPE_MAP.get(node.type) ?? TYPE_MAP.get('OTHER')!;
  return (
    <aside className="rounded-2xl border border-border/80 bg-card/60 p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="eyebrow">Entity</p>
          <h2 className="mt-1 break-words text-lg font-bold">{node.name}</h2>
          <span className={cn('mt-2 inline-flex rounded-full border px-2.5 py-0.5 text-[11px] font-semibold', style.badge)}>
            {style.label}
          </span>
        </div>
        <button onClick={onClose} className="rounded-lg p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground" aria-label="Clear selected entity">
          <X className="h-4 w-4" />
        </button>
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-3 text-xs">
        <div className="rounded-xl border border-border/60 bg-background/40 p-3">
          <dt className="text-muted-foreground">Mentions</dt>
          <dd className="mt-1 text-base font-bold tabular-nums">{node.mentionCount}</dd>
        </div>
        <div className="rounded-xl border border-border/60 bg-background/40 p-3">
          <dt className="text-muted-foreground">First page</dt>
          <dd className="mt-1 text-base font-bold tabular-nums">{node.firstPage ?? '—'}</dd>
        </div>
      </dl>

      <div className="mt-4">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Document</p>
        <p className="mt-1 flex items-center gap-1.5 text-sm">
          <FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="truncate">{node.documentName}</span>
        </p>
      </div>

      {node.evidenceExcerpt ? (
        <blockquote className="mt-4 rounded-xl border-l-2 border-indigo-400/50 bg-indigo-500/[0.06] px-3 py-2 text-xs leading-6 text-muted-foreground">
          “{node.evidenceExcerpt}”
        </blockquote>
      ) : (
        <p className="mt-4 text-xs text-muted-foreground">
          No passage excerpt is stored for this entity (it may come from the summary model).
        </p>
      )}

      <button
        onClick={() => onOpenSource(node)}
        className="mt-4 inline-flex w-full items-center justify-center gap-2 rounded-lg border border-border bg-background/70 px-3 py-2 text-xs font-semibold transition hover:bg-accent"
      >
        Open source page
        <ArrowUpRight className="h-3.5 w-3.5" aria-hidden="true" />
      </button>

      <div className="mt-5 border-t border-border/60 pt-4">
        <p className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
          Connected entities ({connected.length})
        </p>
        {connected.length === 0 ? (
          <p className="mt-2 text-xs text-muted-foreground">No co-occurrence links for this entity.</p>
        ) : (
          <ul className="mt-2 max-h-52 space-y-1 overflow-y-auto pr-1">
            {connected.map(({ edge, node: neighbour }) => (
              <li key={edge.id}>
                <button
                  onClick={() => onSelect(neighbour.id)}
                  className="flex w-full items-center justify-between gap-2 rounded-lg px-2 py-1.5 text-left text-xs hover:bg-accent/70"
                >
                  <span className="min-w-0 truncate">{neighbour.name}</span>
                  <span className="shrink-0 text-[10px] text-muted-foreground">{edge.weight}×</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </aside>
  );
}

function EntityIndex({
  nodes,
  selectedId,
  onSelect,
}: {
  nodes: KnowledgeGraphNode[];
  selectedId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <details className="rounded-2xl border border-border/80 bg-card/50">
      <summary className="cursor-pointer px-4 py-3 text-sm font-semibold marker:text-muted-foreground">
        Entity index ({nodes.length})
      </summary>
      <div className="max-h-80 overflow-auto border-t border-border/60">
        <table className="w-full text-left text-xs">
          <thead className="sticky top-0 bg-card text-[10px] uppercase tracking-wider text-muted-foreground">
            <tr>
              <th scope="col" className="px-4 py-2 font-semibold">Entity</th>
              <th scope="col" className="px-4 py-2 font-semibold">Type</th>
              <th scope="col" className="px-4 py-2 text-right font-semibold">Mentions</th>
              <th scope="col" className="hidden px-4 py-2 font-semibold sm:table-cell">Document</th>
              <th scope="col" className="px-4 py-2 text-right font-semibold">Page</th>
            </tr>
          </thead>
          <tbody>
            {nodes.map((node) => {
              const style = TYPE_MAP.get(node.type) ?? TYPE_MAP.get('OTHER')!;
              return (
                <tr
                  key={node.id}
                  role="button"
                  tabIndex={0}
                  aria-label={`${node.name}, ${style.label}, ${node.mentionCount} mentions`}
                  className={cn(
                    'cursor-pointer border-t border-border/40 hover:bg-accent/50 focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/40',
                    node.id === selectedId && 'bg-indigo-500/10',
                  )}
                  onClick={() => onSelect(node.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onSelect(node.id);
                    }
                  }}
                >
                  <td className="max-w-[220px] truncate px-4 py-2 font-medium">{node.name}</td>
                  <td className="px-4 py-2">
                    <span className={cn('inline-flex rounded-full border px-2 py-0.5 text-[10px] font-semibold', style.badge)}>
                      {style.label}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">{node.mentionCount}</td>
                  <td className="hidden max-w-[220px] truncate px-4 py-2 text-muted-foreground sm:table-cell">{node.documentName}</td>
                  <td className="px-4 py-2 text-right tabular-nums text-muted-foreground">{node.firstPage ?? '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </details>
  );
}

function truncate(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}
