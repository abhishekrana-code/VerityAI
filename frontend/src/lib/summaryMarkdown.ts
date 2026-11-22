import type { SummarySection } from './api';

/** Section headings in reading order, matching the Summary tab. */
const SECTION_TITLES: Record<string, string> = {
  EXECUTIVE_SUMMARY: 'Executive summary',
  KEY_FINDINGS: 'Key findings',
  ENTITIES: 'Entities',
  DATES_AND_STATS: 'Dates & statistics',
  TOPICS: 'Topics',
  SUGGESTED_QUESTIONS: 'Questions this document can answer',
};

type Content = {
  summary?: string;
  findings?: Array<{ text?: string; type?: string }>;
  entities?: Array<{ name?: string; type?: string }>;
  items?: Array<{ text?: string }>;
  topics?: string[];
  questions?: string[];
};

function sectionBody(kind: string, content: Content): string {
  const bullets = (values: Array<string | undefined>) =>
    values
      .filter((value): value is string => !!value?.trim())
      .map((value) => `- ${value.trim()}`)
      .join('\n');

  switch (kind) {
    case 'EXECUTIVE_SUMMARY':
      return content.summary?.trim() ?? '';
    case 'KEY_FINDINGS':
      return bullets((content.findings ?? []).map((item) => item.text));
    case 'ENTITIES':
      return bullets(
        (content.entities ?? []).map((item) =>
          item.name ? (item.type ? `${item.name} (${item.type.toLowerCase()})` : item.name) : undefined,
        ),
      );
    case 'DATES_AND_STATS':
      return bullets((content.items ?? []).map((item) => item.text));
    case 'TOPICS':
      return bullets(content.topics ?? []);
    case 'SUGGESTED_QUESTIONS':
      return bullets(content.questions ?? []);
    default:
      return '';
  }
}

/**
 * Markdown for a document's stored summary sections, generated in the browser
 * so exporting costs nothing and works without a Reports page.
 */
export function summaryToMarkdown(
  document: { originalFilename: string; pageCount?: number | null },
  sections: SummarySection[],
): string {
  const lines: string[] = [`# ${document.originalFilename}`, ''];

  const pages = document.pageCount ? ` — ${document.pageCount} pages` : '';
  lines.push(`Summary exported from VerityAI${pages}.`, '');

  let written = 0;
  for (const section of sections) {
    const body = sectionBody(section.kind, section.content as Content);
    if (!body) continue;
    const title = SECTION_TITLES[section.kind] ?? section.kind;
    lines.push(`## ${title}`, '', body, '');
    written++;
  }

  if (written === 0) {
    lines.push('_No summary content to export yet._', '');
  }
  return lines.join('\n').trimEnd() + '\n';
}
