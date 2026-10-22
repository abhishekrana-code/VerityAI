'use client';

import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { cn } from '@/lib/utils';

interface MarkdownProps {
  children: string;
  className?: string;
}

export function Markdown({ children, className }: MarkdownProps) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        p: ({ children, ...props }) => (
          <p {...props} className={cn('text-sm leading-relaxed', className)}>{children}</p>
        ),
        code: ({ children, ...props }) => (
          <code {...props} className={cn('relative rounded bg-muted px-1.5 py-0.5 text-sm font-mono text-foreground', props.className)}>
            {children}
          </code>
        ),
        pre: ({ children, ...props }) => (
          <pre {...props} className={cn('rounded-lg bg-muted p-4 overflow-x-auto text-sm font-mono', props.className)}>
            {children}
          </pre>
        ),
        blockquote: ({ children, ...props }) => (
          <blockquote {...props} className={cn('border-l-4 border-primary pl-4 italic text-muted-foreground my-4', props.className)}>
            {children}
          </blockquote>
        ),
        ul: ({ children, ...props }) => (
          <ul {...props} className={cn('list-disc pl-6 space-y-1 my-4', props.className)}>{children}</ul>
        ),
        ol: ({ children, ...props }) => (
          <ol {...props} className={cn('list-decimal pl-6 space-y-1 my-4', props.className)}>{children}</ol>
        ),
        a: ({ children, href, ...props }) => (
          <a href={href} target="_blank" rel="noopener noreferrer" className={cn('text-primary underline hover:no-underline', props.className)}>
            {children}
          </a>
        ),
        strong: ({ children, ...props }) => <strong {...props} className={cn('font-semibold', props.className)}>{children}</strong>,
        em: ({ children, ...props }) => <em {...props} className={cn('italic', props.className)}>{children}</em>,
        h1: ({ children, ...props }) => <h1 {...props} className={cn('text-2xl font-bold mt-6 mb-3', props.className)}>{children}</h1>,
        h2: ({ children, ...props }) => <h2 {...props} className={cn('text-xl font-bold mt-5 mb-2', props.className)}>{children}</h2>,
        h3: ({ children, ...props }) => <h3 {...props} className={cn('text-lg font-bold mt-4 mb-2', props.className)}>{children}</h3>,
      }}
    >
      {children}
    </ReactMarkdown>
  );
}