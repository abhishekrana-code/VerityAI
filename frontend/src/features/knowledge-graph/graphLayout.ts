/**
 * Deterministic force-directed layout for the knowledge-graph canvas.
 *
 * The graph is small (the API caps nodes), so an O(n²) simulation is fine and
 * avoids pulling in a graph-visualisation dependency. Positions are seeded from
 * a hash of each node id, so the same graph always renders the same way.
 */

export interface LayoutInputNode {
  id: string;
  mentionCount: number;
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

export interface LayoutOptions {
  width?: number;
  height?: number;
  iterations?: number;
}

const DEFAULT_WIDTH = 860;
const DEFAULT_HEIGHT = 540;
const DEFAULT_ITERATIONS = 220;
const PADDING = 34;

export function layoutGraph(
  nodes: LayoutInputNode[],
  edges: LayoutInputEdge[],
  options: LayoutOptions = {},
): Map<string, LayoutNode> {
  const width = options.width ?? DEFAULT_WIDTH;
  const height = options.height ?? DEFAULT_HEIGHT;
  const iterations = options.iterations ?? DEFAULT_ITERATIONS;

  const result = new Map<string, LayoutNode>();
  if (nodes.length === 0) {
    return result;
  }

  const centerX = width / 2;
  const centerY = height / 2;
  const orbit = Math.min(width, height) * 0.33;

  interface Point {
    id: string;
    x: number;
    y: number;
    vx: number;
    vy: number;
    radius: number;
  }

  const points: Point[] = nodes.map((node, index) => {
    const angle = (index / nodes.length) * Math.PI * 2;
    const jitter = (hash(node.id) % 100) / 100;
    return {
      id: node.id,
      x: centerX + Math.cos(angle) * (orbit + jitter * 24),
      y: centerY + Math.sin(angle) * (orbit + jitter * 24),
      vx: 0,
      vy: 0,
      radius: 10 + Math.min(18, Math.sqrt(Math.max(1, node.mentionCount)) * 4),
    };
  });

  const byId = new Map(points.map((point) => [point.id, point]));
  const links = edges
    .map((edge) => ({ source: byId.get(edge.source), target: byId.get(edge.target) }))
    .filter((link): link is { source: Point; target: Point } =>
      Boolean(link.source && link.target && link.source !== link.target));

  const repulsion = 5200;
  const springLength = 110;
  const springStrength = 0.012;
  const gravity = 0.008;
  const damping = 0.84;

  for (let step = 0; step < iterations; step++) {
    // Pairwise repulsion.
    for (let i = 0; i < points.length; i++) {
      for (let j = i + 1; j < points.length; j++) {
        const a = points[i];
        const b = points[j];
        let dx = a.x - b.x;
        let dy = a.y - b.y;
        let distanceSq = dx * dx + dy * dy;
        if (distanceSq < 1) {
          dx = (hash(a.id + b.id) % 20) - 10 || 1;
          dy = (hash(b.id + a.id) % 20) - 10 || 1;
          distanceSq = dx * dx + dy * dy;
        }
        const distance = Math.sqrt(distanceSq);
        const force = repulsion / distanceSq;
        const fx = (dx / distance) * force;
        const fy = (dy / distance) * force;
        a.vx += fx;
        a.vy += fy;
        b.vx -= fx;
        b.vy -= fy;
      }
    }

    // Spring attraction along edges.
    for (const link of links) {
      const dx = link.target.x - link.source.x;
      const dy = link.target.y - link.source.y;
      const distance = Math.sqrt(dx * dx + dy * dy) || 1;
      const force = (distance - springLength) * springStrength;
      const fx = (dx / distance) * force;
      const fy = (dy / distance) * force;
      link.source.vx += fx;
      link.source.vy += fy;
      link.target.vx -= fx;
      link.target.vy -= fy;
    }

    // Gravity towards the centre keeps disconnected nodes on screen.
    for (const point of points) {
      point.vx += (centerX - point.x) * gravity;
      point.vy += (centerY - point.y) * gravity;
      point.vx *= damping;
      point.vy *= damping;
      point.x = clamp(point.x + point.vx, PADDING, width - PADDING);
      point.y = clamp(point.y + point.vy, PADDING, height - PADDING);
    }
  }

  for (const point of points) {
    result.set(point.id, {
      x: Math.round(point.x * 10) / 10,
      y: Math.round(point.y * 10) / 10,
      radius: point.radius,
    });
  }
  return result;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** Small deterministic string hash (djb2). */
function hash(value: string): number {
  let result = 5381;
  for (let i = 0; i < value.length; i++) {
    result = ((result << 5) + result + value.charCodeAt(i)) >>> 0;
  }
  return result;
}
