import DOMPurify from 'dompurify';
import type * as HighlightCore from 'highlight.js/lib/core';
import { languageAliases, languageLoaders } from './highlight-languages';
import { yieldToMain } from './work-scheduler';

type Language = keyof typeof languageLoaders;
let core: Promise<typeof HighlightCore> | undefined;
const registrations = new Map<Language, Promise<void>>();
const highlighted = new WeakSet<HTMLElement>();
let budgetStarted = 0;

const dependencies: Partial<Record<Language, Language[]>> = {
  'clojure-repl': ['clojure'], coffeescript: ['javascript'], django: ['xml'],
  dockerfile: ['bash'], haml: ['ruby'], handlebars: ['xml'],
  javascript: ['xml', 'css', 'graphql'], markdown: ['xml'],
  'php-template': ['xml', 'php'], 'python-repl': ['python'],
  shell: ['bash'], typescript: ['xml', 'css', 'graphql'],
  xml: ['css', 'javascript'], yaml: ['ruby'],
};

export function resolveLanguage(identifier: string): Language | undefined {
  const name = identifier.toLowerCase();
  if (Object.hasOwn(languageLoaders, name)) return name as Language;
  return Object.hasOwn(languageAliases, name) ? languageAliases[name] : undefined;
}

async function loadLanguage(language: Language) {
  const hljs = (await (core ??= import('highlight.js/lib/core'))).default;
  const needed = new Set<Language>();
  function collect(name: Language) {
    if (needed.has(name)) return;
    needed.add(name);
    dependencies[name]?.forEach(collect);
  }
  collect(language);
  await Promise.all([...needed].map(name => {
    let registration = registrations.get(name);
    if (!registration) {
      registration = languageLoaders[name]().then(module => {
        hljs.registerLanguage(name, module.default);
      });
      registrations.set(name, registration);
    }
    return registration;
  }));
  return hljs;
}

/** Shared by Markdown fences and the non-Markdown repository code viewer. */
export async function highlightCode(element: HTMLElement, identifier: string, signal?: AbortSignal): Promise<void> {
  const language = resolveLanguage(identifier);
  if (!language || highlighted.has(element) || signal?.aborted || !element.isConnected) return;
  const source = element.textContent ?? '';
  try {
    const hljs = await loadLanguage(language);
    if (performance.now() - budgetStarted >= 8) {
      await yieldToMain();
      budgetStarted = performance.now();
    }
    if (signal?.aborted || !element.isConnected || highlighted.has(element)) return;
    const html = hljs.highlight(source, { language, ignoreIllegals: true }).value;
    const fragment = DOMPurify.sanitize(html, {
      ALLOWED_TAGS: ['span'], ALLOWED_ATTR: ['class'], RETURN_DOM_FRAGMENT: true,
    });
    for (const span of fragment.querySelectorAll('span')) {
      span.className = [...span.classList].filter(name => /^hljs-[\w-]+$/.test(name)).join(' ');
    }
    element.replaceChildren(fragment);
    element.classList.add('hljs');
    highlighted.add(element);
  } catch {
    // A failed optional grammar download must leave the original source readable.
  }
}
