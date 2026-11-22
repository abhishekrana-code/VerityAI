/**
 * Deterministic, type-clustered layout for the knowledge-graph canvas.
 *
 * A force simulation on a co-occurrence graph (where most entities appear in
 * the same passages) always collapses into a hairball. Instead, entities are
 * grouped into one island per entity type and packed into a sunflower spiral
 * inside each island, most-connected entities closest to the island centre.
 *
 * Positions are derived purely from the input, so the same graph always
 * renders the same way and no graph-visualisation dependency is needed.
 */

export interface LayoutInputNode {
  id: string;
  mentionCount: number;
  type?: string;
}

export interface LayoutInputEdge {
  source: string;
  target: string;
}

export interface LayoutNode {
  x: number;
  y: number;
  radius: number;
}

export interface LayoutIsland {
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  count: number;
}

export interface GraphLayout {
  nodes: Map<string, LayoutNode>;
  islands: LayoutIsland[];
}

export interface LayoutOptions {
  width?: number;
  height?: number;
}

const DEFAULT_WIDTH = 900;
const DEFAULT_HEIGHT = 560;
const ISLAND_GAP = 12;
const ISLAND_HEADER = 18;
const ISLAND_PADDING = 10;
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

export function layoutGraph(
  nodes: LayoutInputNode[],
  edges: LayoutInputEdge[],
  options: LayoutOptions = {},
): GraphLayout {
  const width = options.width ?? DEFAULT_WIDTH;
  const height = options.height ?? DEFAULT_HEIGHT;

  const result: GraphLayout = { nodes: new Map<string, LayoutNode>(), islands: [] };
  if (nodes.length === 0) {
    return result;
  }

  const degree = new Map<string, number>();
  for (const edge of edges) {
    degree.set(edge.source, (degree.get(edge.source) ?? 0) + 1);
    degree.set(edge.target, (degree.get(edge.target) ?? 0) + 1);
  }

  const groups = new Map<string, LayoutInputNode[]>();
  for (const node of nodes) {
    const type = node.type ?? 'OTHER';
    const group = groups.get(type);
    if (group) {
      group.push(node);
    } else {
      groups.set(type, [node]);
    }
  }

  // Largest islands first; ties by type name so the output stays deterministic.
  const ordered = [...groups.entries()].sort(
    (a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0]),
  );

  const aspect = width / height;
  const columns = Math.max(1, Math.min(ordered.length, Math.round(Math.sqrt(ordered.length * aspect))));
  const rows = Math.ceil(ordered.length / columns);
  const cellWidth = width / columns;
  const cellHeight = height / rows;

  ordered.forEach(([type, group], index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const x = column * cellWidth + ISLAND_GAP / 2;
    const y = row * cellHeight + ISLAND_GAP / 2;
    const islandWidth = cellWidth - ISLAND_GAP;
    const islandHeight = cellHeight - ISLAND_GAP;

    result.islands.push({ type, x, y, width: islandWidth, height: islandHeight, count: group.length });

    const members = [...group].sort(
      (a, b) =>
        (degree.get(b.id) ?? 0) - (degree.get(a.id) ?? 0) ||
        b.mentionCount - a.mentionCount ||
        a.id.localeCompare(b.id),
    );

    const centerX = x + islandWidth / 2;
    const centerY = y + ISLAND_HEADER + (islandHeight - ISLAND_HEADER) / 2;
    const maxRadius = Math.max(
      8,
      Math.min(islandWidth, islandHeight - ISLAND_HEADER) / 2 - ISLAND_PADDING,
    );

    const baseRadii = members.map((member) => radiusForMentions(member.mentionCount));
    const largest = Math.max(...baseRadii);
    const spacing = maxRadius / Math.sqrt(members.length);
    // Shrink nodes when an island is crowded so neighbours stay distinguishable.
    const radiusScale = clamp((spacing - 3) / (largest * 2), 0.3, 1);

    const placed = members.map((node, memberIndex) => {
      const radius = Math.max(3.5, (baseRadii[memberIndex] ?? 10) * radiusScale);
      const distance = spacing * Math.sqrt(memberIndex + 0.4);
      const angle = memberIndex * GOLDEN_ANGLE;
      return {
        id: node.id,
        radius,
        x: clamp(centerX + Math.cos(angle) * distance, x + radius, x + islandWidth - radius),
        y: clamp(
          centerY + Math.sin(angle) * distance,
          y + ISLAND_HEADER + radius,
          y + islandHeight - radius,
        ),
      };
    });

    // Hug the packed nodes instead of drawing the whole grid cell: a sparse
    // graph would otherwise leave large, empty-looking boxes on screen.
    const widest = Math.max(...placed.map((point) => point.radius));
    const labelSpace = 20;
    const islandX = Math.max(4, Math.min(...placed.map((point) => point.x)) - widest - ISLAND_PADDING);
    const islandY = Math.max(
      4,
      Math.min(...placed.map((point) => point.y)) - widest - ISLAND_PADDING - labelSpace,
    );
    const right = Math.min(
      width - 4,
      Math.max(...placed.map((point) => point.x)) + widest + ISLAND_PADDING,
    );
    const bottom = Math.min(
      height - 4,
      Math.max(...placed.map((point) => point.y)) + widest + ISLAND_PADDING,
    );
    result.islands[result.islands.length - 1] = {
      type,
      count: group.length,
      x: islandX,
      y: islandY,
      width: Math.max(96, right - islandX),
      height: Math.max(56, bottom - islandY),
    };

    for (const point of placed) {
      result.nodes.set(point.id, { x: point.x, y: point.y, radius: point.radius });
    }
  });

  return result;
}

function radiusForMentions(mentions: number): number {
  return 7 + Math.min(15, Math.sqrt(Math.max(1, mentions)) * 3.2);
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
