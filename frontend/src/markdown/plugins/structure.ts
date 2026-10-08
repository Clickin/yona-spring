import GithubSlugger from 'github-slugger';
import {resolveReference, type ReferenceMetadata} from '../runtime/reference-batch-resolver';
import {linkLegacyReferences, type Candidate} from './reference-tokens';

export type MarkdownContext = {mode: string; owner: string; project: string; ref: string; path: string};

export function applyStructure(root: HTMLElement, context: MarkdownContext, signal: AbortSignal): void {
  if (signal.aborted) return;
  if (context.mode === 'document' && !root.dataset.yonaStructured) {
    relativeLinks(root, context);
    addHeadingAnchors(root);
  }
  root.dataset.yonaStructured = 'true';
  applyLinkPolicy(root);
  applyReferences(root, context, signal);
}

function addHeadingAnchors(root: HTMLElement): void {
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

function applyLinkPolicy(root: HTMLElement): void {
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

function applyReferences(root: HTMLElement, context: MarkdownContext, signal: AbortSignal): void {
  if (!context.owner || !context.project) return;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: node => node.parentElement?.closest('code,pre,a,script,style,textarea')
      ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
  });
  const nodes: Text[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode as Text);
  if (!nodes.length) return;
  void resolveReferences(root, nodes, context, signal);
}

function referenceKey({type, value}: Candidate): string {
  return `${type}:${value}`;
}

async function resolveReferences(root: HTMLElement, nodes: Text[], context: MarkdownContext, signal: AbortSignal): Promise<void> {
  const results = new Map<string, ReferenceMetadata | null>();
  const lookup = (candidate: Candidate) => {
    const key = referenceKey(candidate);
    return results.has(key) ? results.get(key) !== null : undefined;
  };
  // The legacy passes fall through on unresolved matches; a later pass can reveal a new candidate.
  for (let round = 0; round < 4; round++) {
    const pending = new Map<string, Candidate>();
    for (const node of nodes) {
      for (const candidate of linkLegacyReferences(node.data, lookup).unknown) pending.set(referenceKey(candidate), candidate);
    }
    if (!pending.size) break;
    await Promise.all([...pending.values()].map(candidate => new Promise<void>(settle => {
      resolveReference(context, candidate.type, candidate.value, signal, metadata => {
        results.set(referenceKey(candidate), metadata);
        settle();
      });
    })));
    if (signal.aborted || !root.isConnected) return;
  }
  replaceReferences(nodes, results, root);
}

function replaceReferences(nodes: Text[], results: Map<string, ReferenceMetadata | null>, root: HTMLElement): void {
  let linkedUser = false;
  for (const node of nodes) {
    if (!node.isConnected) continue;
    const {pieces} = linkLegacyReferences(node.data, candidate => Boolean(results.get(referenceKey(candidate))));
    if (pieces.length === 1 && typeof pieces[0] === 'string') continue;
    const fragment = document.createDocumentFragment();
    for (const piece of pieces) {
      if (typeof piece === 'string') fragment.append(piece);
      else {
        const metadata = results.get(referenceKey(piece))!;
        linkedUser ||= metadata.type === 'user' && metadata.kind !== 'org';
        fragment.append(referenceLink(metadata));
      }
    }
    node.replaceWith(fragment);
  }
  const common = (window as Window & {$yona?: {initHoverPopovers?: (selector: string, root?: ParentNode) => void}}).$yona;
  if (linkedUser) common?.initHoverPopovers?.('.user-link [data-toggle="popover"]', root);
}

/** Same markup as the server AutoLinkRenderer, built from text only. */
function referenceLink(metadata: ReferenceMetadata): HTMLAnchorElement {
  const anchor = document.createElement('a');
  anchor.href = metadata.href;
  anchor.dataset.yonaReference = metadata.key;
  const label = document.createElement('span');
  label.textContent = metadata.label;
  if (metadata.type === 'issue') {
    anchor.className = 'issueLink';
    anchor.textContent = metadata.label;
    if (metadata.state && /^(open|closed|draft)$/i.test(metadata.state)) {
      const state = document.createElement('span');
      state.className = `issue-state ${metadata.state.toLowerCase()}`;
      state.textContent = metadata.stateLabel ?? metadata.state;
      anchor.append(state);
    }
  } else if (metadata.type === 'commit') {
    anchor.textContent = metadata.label;
  } else if (metadata.type === 'project' || metadata.kind === 'org') {
    label.className = metadata.type === 'project' ? 'project-link' : 'org-link';
    anchor.append(label);
  } else {
    anchor.className = 'no-text-decoration user-link';
    if (metadata.popover) {
      // The current popover renders text only, so the legacy avatar <img> is omitted.
      Object.assign(label.dataset, {toggle: 'popover', placement: 'top', trigger: 'hover', content: metadata.popover});
    }
    anchor.append(label);
  }
  return anchor;
}
