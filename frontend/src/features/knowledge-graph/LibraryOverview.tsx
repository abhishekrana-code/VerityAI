'use client';

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  api,
  type KnowledgeConnectionsResponse,
  type KnowledgeEntityIndexResponse,
} from '@/lib/api';
import { cn, pluralize } from '@/lib/utils';
import { ArrowUpRight, FileText, Loader2, Network, Search, X } from 'lucide-react';

/**
 * Library-wide view over the same entity tables the canvas uses: entities are
 * merged by normalized name (one row per entity, not one per document), and the
 * documents that mention the same names are listed as connections.
 */
export function LibraryOverview() {
  const [queryInput, setQueryInput] = useState('');
  const [query, setQuery] = useState('');

  const entitiesQuery = useQuery({
    // Under the graph key prefix so any graph invalidation (e.g. a rebuild)
    // refreshes the library panels along with the canvas.
    queryKey: ['knowledge-graph', 'library', 'entities', query],
    queryFn: () => {
      const params = new URLSearchParams({ limit: '60' });
      if (query) params.set('q', query);
      return api.get<KnowledgeEntityIndexResponse>(`/knowledge-graph/entities?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
  });

  const connectionsQuery = useQuery({
    queryKey: ['knowledge-graph', 'library', 'connections'],
    queryFn: () => api.get<KnowledgeConnectionsResponse>('/knowledge-graph/connections?limit=8'),
  });

  const entities = entitiesQuery.data?.entities ?? [];
  const connections = connectionsQuery.data?.connections ?? [];

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setQuery(queryInput.trim());
  };

  return (
    <section className="space-y-5 rounded-2xl border border-border/80 bg-card/40 p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-sm font-semibold">
            <Network className="h-4 w-4 text-primary" aria-hidden="true" />
            Library view
          </h2>
          <p className="mt-1 max-w-2xl text-xs leading-5 text-muted-foreground">
            One row per entity across every document, and the document pairs that mention the
            same entities. Unlike the canvas, this is not limited to a single document.
          </p>
        </div>
        <form onSubmit={submit} className="flex items-center gap-2">
          <div className="relative">
            <Search
              className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <input
              type="search"
              value={queryInput}
              onChange={(event) => setQueryInput(event.target.value)}
              placeholder="Find an entity…"
              aria-label="Find an entity across documents"
              className="h-9 w-52 rounded-lg border border-border/70 bg-background/70 pl-8 pr-7 text-xs outline-none focus:border-ring focus:ring-2 focus:ring-ring/25"
            />
            {queryInput && (
              <button
                type="button"
                onClick={() => {
                  setQueryInput('');
                  setQuery('');
                }}
                className="absolute right-1 top-1/2 -translate-y-1/2 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
                aria-label="Clear entity search"
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </div>
          <button
            type="submit"
            className="rounded-lg border border-border bg-card px-3 py-2 text-xs font-semibold hover:bg-accent"
          >
            Find
          </button>
        </form>
      </div>

      <div>
        <h3 className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
          Documents that share entities
          {connectionsQuery.data ? ` (${connectionsQuery.data.pairCount})` : ''}
        </h3>
        {connectionsQuery.isLoading ? (
          <p className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" /> Comparing documents…
          </p>
        ) : connections.length === 0 ? (
          <p className="mt-2 text-xs text-muted-foreground">
            No two documents mention the same entity yet. Upload related PDFs and rebuild the graph
            to see links between them.
          </p>
        ) : (
          <ul className="mt-2 grid gap-2 sm:grid-cols-2">
            {connections.map((connection) => (
              <li
                key={`${connection.documentAId}-${connection.documentBId}`}
                className="rounded-xl border border-border/70 bg-background/60 px-3 py-2.5"
              >
                <div className="flex items-start justify-between gap-2">
                  <p className="min-w-0 text-xs font-medium">
                    <DocumentLink id={connection.documentAId} name={connection.documentAName} />
                    <span className="mx-1 text-muted-foreground">↔</span>
                    <DocumentLink id={connection.documentBId} name={connection.documentBName} />
                  </p>
                  <span className="shrink-0 rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-semibold text-primary">
                    {connection.sharedEntities} shared
                  </span>
                </div>
                {connection.sampleEntities.length > 0 && (
                  <p className="mt-1 truncate text-[11px] text-muted-foreground">
                    {connection.sampleEntities.join(', ')}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
        {connectionsQuery.data?.truncated && (
          <p className="mt-2 text-[11px] text-muted-foreground">
            Showing the {connections.length} strongest document pairs.
          </p>
        )}
      </div>

      <div>
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h3 className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
            Entities across documents
            {entitiesQuery.data ? ` (${entitiesQuery.data.totalEntities})` : ''}
          </h3>
          {entitiesQuery.data?.truncated && (
            <span className="text-[11px] text-muted-foreground">
              Showing the {entitiesQuery.data.returnedEntities} most-mentioned
            </span>
          )}
        </div>

        {entitiesQuery.isLoading ? (
          <p className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" /> Loading entities…
          </p>
        ) : entitiesQuery.isError ? (
          <p className="mt-2 text-xs text-destructive">
            Could not load the entity index
            {entitiesQuery.error instanceof Error ? `: ${entitiesQuery.error.message}` : '.'}
          </p>
        ) : entities.length === 0 ? (
          <p className="mt-2 text-xs text-muted-foreground">
            {query
              ? `No entity matches “${query}”.`
              : 'No entities yet. Process a document and build its graph first.'}
          </p>
        ) : (
          <div className="mt-2 overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-[10px] uppercase tracking-wider text-muted-foreground">
                <tr>
                  <th scope="col" className="px-2 py-1.5 font-semibold">Entity</th>
                  <th scope="col" className="px-2 py-1.5 font-semibold">Type</th>
                  <th scope="col" className="px-2 py-1.5 text-right font-semibold">Mentions</th>
                  <th scope="col" className="px-2 py-1.5 font-semibold">Documents</th>
                </tr>
              </thead>
              <tbody>
                {entities.map((entity) => (
                  <tr key={entity.normalizedName} className="border-t border-border/40">
                    <td className="max-w-[16rem] truncate px-2 py-1.5 font-medium" title={entity.name}>
                      {entity.name}
                    </td>
                    <td className="whitespace-nowrap px-2 py-1.5 text-muted-foreground">
                      {entity.type.toLowerCase()}
                    </td>
                    <td className="whitespace-nowrap px-2 py-1.5 text-right font-mono text-[11px]">
                      {entity.mentionCount}
                    </td>
                    <td className="px-2 py-1.5">
                      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        {entity.documents.slice(0, 3).map((document) => (
                          <Link
                            key={document.id}
                            to={`/dashboard/documents/${document.id}`}
                            className="inline-flex max-w-[14rem] items-center gap-1 truncate text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
                            title={`${document.name} — ${pluralize(document.mentionCount, 'mention')}`}
                          >
                            <FileText className="h-3 w-3 shrink-0" aria-hidden="true" />
                            <span className="truncate">{document.name}</span>
                          </Link>
                        ))}
                        {entity.documents.length > 3 && (
                          <span className={cn('text-muted-foreground')}>
                            +{entity.documents.length - 3} more
                          </span>
                        )}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}

function DocumentLink({ id, name }: { id: string; name: string }) {
  return (
    <Link
      to={`/dashboard/documents/${id}`}
      className="inline-flex max-w-[10rem] items-center gap-1 align-bottom text-foreground underline-offset-2 hover:underline"
      title={name}
    >
      <span className="truncate">{name}</span>
      <ArrowUpRight className="h-3 w-3 shrink-0 text-muted-foreground" aria-hidden="true" />
    </Link>
  );
}
