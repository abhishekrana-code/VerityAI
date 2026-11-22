/**
 * Label layout for the knowledge-graph canvas.
 *
 * With ~120 entities, labelling every node overlaps into noise. Labels are
 * placed by importance (mentions first, focused entities always) and skipped
 * when they would collide with one already placed.
 */

const CHAR_WIDTH = 5.9;
const LABEL_HEIGHT = 13;

export interface LabelInputNode {
  id: string;
  name: string;
  mentionCount: number;
}

export interface LabelPosition {
  x: number;
  y: number;
  radius: number;
}

export interface LabelPlacement {
  id: string;
  /** Anchor x (label is centred on the node). */
  x: number;
  /** Baseline y. */
  y: number;
  text: string;
}

interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface LabelOptions {
  /** Maximum number of labels to place. */
  max?: number;
  /** Ids that must always be labelled (the focused entity and its neighbours). */
  always?: ReadonlySet<string>;
  /** Longest label before truncation, in characters. */
  truncateAt?: number;
}

export function visibleLabels(
  nodes: LabelInputNode[],
  positions: Map<string, LabelPosition>,
  options: LabelOptions = {},
): LabelPlacement[] {
  const max = options.max ?? 24;
  const always = options.always ?? new Set<string>();
  const truncateAt = options.truncateAt ?? 24;

  const ordered = [...nodes].sort(
    (a, b) =>
      Number(always.has(b.id)) - Number(always.has(a.id)) ||
      b.mentionCount - a.mentionCount ||
      a.name.localeCompare(b.name),
  );

  const labels: LabelPlacement[] = [];
  const boxes: Box[] = [];

  for (const node of ordered) {
    if (labels.length >= max) break;
    const point = positions.get(node.id);
    if (!point) continue;

    const text = truncate(node.name, truncateAt);
    const width = text.length * CHAR_WIDTH;
    const baseline = point.y + point.radius + 12;
    const box: Box = {
      left: point.x - width / 2 - 2,
      right: point.x + width / 2 + 2,
      top: baseline - LABEL_HEIGHT + 3,
      bottom: baseline + 3,
    };

    const forced = always.has(node.id);
    if (!forced && boxes.some((other) => overlaps(box, other))) continue;

    boxes.push(box);
    labels.push({ id: node.id, x: point.x, y: baseline, text });
  }

  return labels;
}

function overlaps(a: Box, b: Box): boolean {
  return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
}

function truncate(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}
