import GithubSlugger from 'github-slugger';
import {resolveReference, type ReferenceType} from '../runtime/reference-batch-resolver';

export type MarkdownContext = {mode: string; owner: string; project: string; ref: string; path: string};
const name = '[a-zA-Z0-9_.가-힣-]+';
const candidates = new RegExp(`(?<![\\p{L}\\p{N}_/@])(?:@?${name}/${name}#[0-9]+|${name}/${name}@[a-f0-9]{7,40}|#[0-9]+|@?${name}/${name}|@${name}|[a-f0-9]{7,40})(?![\\p{L}\\p{N}_/])`, 'gu');

export function applyStructure(root: HTMLElement, context: MarkdownContext, signal: AbortSignal): void {
  if (signal.aborted) return;
  if (context.mode === 'document' && !root.dataset.yonaStructured) {
    relativeLinks(root, context);
    const slugger = new GithubSlugger();
    root.querySelectorAll<HTMLElement>('h1,h2,h3,h4,h5,h6').forEach(heading => {
      heading.id = slugger.slug(heading.textContent ?? '');
      const anchor = document.createElement('a');
      anchor.className = 'heading-anchor';
      anchor.href = `#${encodeURIComponent(heading.id)}`;
      anchor.setAttribute('aria-label', `Link to ${heading.textContent}`);
      anchor.textContent = '#';
      heading.append(anchor);
    });
  }
  root.dataset.yonaStructured = 'true';
  const noreferrer = document.querySelector<HTMLMetaElement>('meta[name="yona-markdown-noreferrer"]')?.content === 'true';
  root.querySelectorAll<HTMLAnchorElement>('a[href]').forEach(anchor => {
    if (anchor.target === '_blank') anchor.relList.add('noopener');
    try {
      const url = new URL(anchor.href, location.origin);
      if (url.host && url.origin !== location.origin) {
        anchor.relList.add('noopener');
        if (noreferrer) anchor.relList.add('noreferrer');
      }
    } catch {
      // Invalid sanitized links do not acquire navigation privileges.
    }
  });
  references(root, context, signal);
  const wrap = root.closest<HTMLElement>('.markdown-wrap') ?? root;
  const yona = (window as Window & {yona?: {initTasklist?: (root: HTMLElement) => void}}).yona;
  yona?.initTasklist?.(wrap);
}

function relativeLinks(root: HTMLElement, context: MarkdownContext): void {
  if (!context.owner || !context.project || !context.path) return;
  const project = `/${encodeURIComponent(context.owner)}/${encodeURIComponent(context.project)}`;
  const source = new URL(`/${context.path.split('/').map(encodeURIComponent).join('/')}`, location.origin);
  for (const element of root.querySelectorAll<HTMLAnchorElement | HTMLImageElement>('a[href],img[src]')) {
    const attribute = element.tagName === 'IMG' ? 'src' : 'href';
    const value = element.getAttribute(attribute)?.trim();
    if (!value || /^(?:[a-z][a-z0-9+.-]*:|\/|#|\?)/i.test(value)) continue;
    try {
      const resolved = new URL(value, source);
      if (resolved.origin !== location.origin) continue;
      const route = context.ref
        ? `${project}/${attribute === 'src' ? 'files' : 'code'}/${encodeURIComponent(context.ref)}`
        : `${project}/wiki`;
      element.setAttribute(attribute, `${route}${resolved.pathname}${resolved.search}${resolved.hash}`);
    } catch {
      // Invalid relative URLs remain inert sanitized parser output.
    }
  }
}

function references(root: HTMLElement, context: MarkdownContext, signal: AbortSignal): void {
  if (!context.owner || !context.project) return;
  function subscribe(placeholder: HTMLElement, type: ReferenceType, value: string): void {
    resolveReference(context, type, value, signal, metadata => {
      if (signal.aborted || !root.isConnected) return;
      const anchor = document.createElement('a');
      anchor.href = metadata.href;
      anchor.className = type === 'issue' ? 'issueLink' : `${type}-link`;
      anchor.dataset.yonaReference = metadata.key;
      anchor.textContent = metadata.label;
      if (type === 'issue' && metadata.state && /^(open|closed|draft)$/i.test(metadata.state)) {
        const state = document.createElement('span');
        state.className = `issue-state ${metadata.state.toLowerCase()}`;
        state.textContent = metadata.state;
        anchor.append(state);
      }
      placeholder.replaceWith(anchor);
    });
  }
  // A moved immutable renderer keeps its placeholders, but owns a new abort signal.
  root.querySelectorAll<HTMLElement>('span[data-yona-reference]').forEach(placeholder => {
    const key = placeholder.dataset.yonaReference!;
    const separator = key.indexOf(':');
    subscribe(placeholder, key.slice(0, separator) as ReferenceType, key.slice(separator + 1));
  });
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement?.closest('code,pre,a,[data-yona-reference],script,style,textarea')
      ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
  });
  const nodes: Text[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode as Text);
  for (const node of nodes) {
    const text = node.data;
    const fragment = document.createDocumentFragment();
    let offset = 0;
    for (const match of text.matchAll(candidates)) {
      const token = match[0];
      let value = token;
      let type: ReferenceType;
      if (token.includes('#')) { type = 'issue'; value = token.replace(/^@/, ''); }
      else if (/^(?:[^@]+\/[^@]+@|@)?[a-f0-9]{7,40}$/.test(token)) {
        type = 'commit';
        value = token.replace(/^@/, '');
      }
      else if (token.includes('/')) { type = 'project'; value = token.replace(/^@/, ''); }
      else type = 'user';
      fragment.append(document.createTextNode(text.slice(offset, match.index)));
      const placeholder = document.createElement('span');
      placeholder.dataset.yonaReference = `${type}:${value}`;
      placeholder.textContent = token;
      fragment.append(placeholder);
      subscribe(placeholder, type, value);
      offset = match.index! + token.length;
    }
    if (offset) {
      fragment.append(document.createTextNode(text.slice(offset)));
      node.replaceWith(fragment);
    }
  }
}
