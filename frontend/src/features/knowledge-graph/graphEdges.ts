import type { KnowledgeGraphEdge } from '@/lib/api';

export interface BackboneOptions {
  /** Strongest links kept per entity. */
  perNode?: number;
  /** Hard cap on the number of links returned. */
  maxLinks?: number;
  /** Links whose weight is below this are omitted entirely. */
  minWeight?: number;
}

/**
 * The quiet "overview" link set for the knowledge graph.
 *
 * Co-occurrence graphs are close to complete, so drawing every edge (or even a
 * few per node) is unreadable. Keep only each entity's strongest link, which
 * still reveals the hubs without covering the canvas in lines. Weight-1 links
 * are single-passage coincidences and are skipped by default.
 */
export function backboneLinks(
  edges: KnowledgeGraphEdge[],
  options: BackboneOptions = {},
): KnowledgeGraphEdge[] {
  const perNode = options.perNode ?? 1;
  const maxLinks = options.maxLinks ?? 120;
  const minWeight = options.minWeight ?? 2;

  const adjacency = new Map<string, KnowledgeGraphEdge[]>();
  for (const edge of edges) {
    if (edge.weight < minWeight) continue;
    for (const id of [edge.source, edge.target]) {
      const list = adjacency.get(id);
      if (list) list.push(edge);
      else adjacency.set(id, [edge]);
    }
  }

  const keep = new Set<string>();
  for (const list of adjacency.values()) {
    list.sort((a, b) => b.weight - a.weight || a.id.localeCompare(b.id));
    for (const edge of list.slice(0, perNode)) keep.add(edge.id);
  }

  return edges
    .filter((edge) => keep.has(edge.id))
    .sort((a, b) => b.weight - a.weight)
    .slice(0, maxLinks);
}
