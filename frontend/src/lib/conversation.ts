import { api, type Conversation } from '@/lib/api';

export const DEFAULT_CONVERSATION_TITLE = 'New conversation';

/**
 * Titles a conversation from the first question so asking never requires
 * naming it first. Cuts on a word boundary and appends an ellipsis, so the
 * sidebar never shows a half-word.
 */
export function deriveConversationTitle(question: string, maxLength = 60): string {
  const text = question.replace(/\s+/g, ' ').trim();
  if (!text) return DEFAULT_CONVERSATION_TITLE;
  if (text.length <= maxLength) return text;

  const cut = text.slice(0, maxLength);
  const lastSpace = cut.lastIndexOf(' ');
  const body = lastSpace > 0 ? cut.slice(0, lastSpace) : cut;
  return `${body.trimEnd()}…`;
}

/** Creates a conversation scoped to the given documents (empty = all of them). */
export function createConversation(
  title: string,
  documentIds: string[] = [],
): Promise<Conversation> {
  return api.post<Conversation>('/conversations', {
    title: title.trim() || DEFAULT_CONVERSATION_TITLE,
    documentIds,
  });
}

/** One-click "ask about this document" session. */
export function askAboutDocument(documentId: string, filename: string): Promise<Conversation> {
  return createConversation(deriveConversationTitle(`Ask about ${filename}`, 80), [documentId]);
}
