import DOMPurify from 'dompurify';
import type { Mermaid } from 'mermaid';
import { yieldToMain } from './work-scheduler';

const MAX_TEXT_SIZE = 50_000;
const MAX_EDGES = 500;
const pending: Job[] = [];
const jobs = new WeakMap<HTMLElement, Job>();
const finished = new WeakSet<HTMLElement>();
let mermaid: Promise<Mermaid> | undefined;
let observer: IntersectionObserver | undefined;
let timer: number | undefined;
let running = false;
let sequence = 0;

type Job = {
  code: HTMLElement;
  signal: AbortSignal;
  visible: boolean;
  cancel: () => void;
};

function loadMermaid(): Promise<Mermaid> {
  return mermaid ??= import('mermaid').then(({ default: api }) => {
    api.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      maxTextSize: MAX_TEXT_SIZE,
      maxEdges: MAX_EDGES,
      htmlLabels: false,
      suppressErrorRendering: true,
      secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges',
        'htmlLabels', 'suppressErrorRendering', 'themeCSS'],
    });
    return api;
  });
}

function active(job: Job): boolean {
  return !job.signal.aborted && job.code.isConnected;
}

function forget(job: Job): void {
  if (jobs.get(job.code) === job) {
    observer?.unobserve(job.code);
    jobs.delete(job.code);
  }
  job.signal.removeEventListener('abort', job.cancel);
  if (!pending.length) {
    observer?.disconnect();
    observer = undefined;
  }
}

function showError(job: Job): void {
  if (!active(job)) return;
  const message = document.createElement('p');
  message.className = 'markdown-mermaid-error';
  message.setAttribute('role', 'status');
  message.textContent = 'Unable to render this Mermaid diagram. The original source is shown above.';
  job.code.parentElement?.after(message);
  finished.add(job.code);
}

function sanitizeSvg(source: string): DocumentFragment {
  const fragment = DOMPurify.sanitize(source, {
    USE_PROFILES: { svg: true, svgFilters: true },
    FORBID_TAGS: ['foreignObject', 'script', 'iframe', 'image', 'a'],
    RETURN_DOM_FRAGMENT: true,
  });
  // Mermaid needs local marker/gradient URLs, never external SVG resources.
  for (const element of fragment.querySelectorAll('*')) {
    for (const attribute of [...element.attributes]) {
      if (/^(?:href|xlink:href)$/i.test(attribute.name) && !attribute.value.startsWith('#')) {
        element.removeAttribute(attribute.name);
      } else if (/url\(\s*['"]?(?!#)/i.test(attribute.value)) {
        element.removeAttribute(attribute.name);
      }
    }
  }
  for (const style of fragment.querySelectorAll('style')) {
    if (/@import|@font-face|url\(\s*['"]?(?!#)/i.test(style.textContent ?? '')) style.remove();
  }
  return fragment;
}

async function render(job: Job): Promise<void> {
  if (!active(job)) return;
  const source = job.code.textContent ?? '';
  if (source.length > MAX_TEXT_SIZE) {
    showError(job);
    return;
  }
  let scratch: HTMLDivElement | undefined;
  try {
    const api = await loadMermaid();
    if (!active(job)) return;
    scratch = document.createElement('div');
    scratch.setAttribute('aria-hidden', 'true');
    scratch.style.cssText = 'position:absolute;left:-100000px;top:0;visibility:hidden;pointer-events:none';
    document.body.append(scratch);
    const { svg } = await api.render(`yona-mermaid-${++sequence}`, source, scratch);
    if (!active(job)) return;
    const fragment = sanitizeSvg(svg);
    if (!fragment.querySelector('svg')) throw new Error('Missing diagram');
    const diagram = document.createElement('div');
    diagram.className = 'markdown-mermaid';
    diagram.setAttribute('role', 'img');
    diagram.setAttribute('aria-label', 'Mermaid diagram');
    diagram.append(fragment);
    job.code.parentElement?.replaceWith(diagram);
    finished.add(job.code);
  } catch {
    showError(job);
  } finally {
    scratch?.remove();
  }
}

async function drain(): Promise<void> {
  timer = undefined;
  running = true;
  try {
    while (pending.length) {
      const visible = pending.findIndex(job => job.visible);
      const job = pending.splice(visible < 0 ? 0 : visible, 1)[0];
      observer?.unobserve(job.code);
      await render(job);
      forget(job);
      await yieldToMain();
    }
  } finally {
    running = false;
  }
}

export function queueMermaid(code: HTMLElement, signal: AbortSignal): void {
  if (signal.aborted || !code.isConnected || jobs.has(code) || finished.has(code)) return;
  const bounds = code.getBoundingClientRect();
  const job: Job = {
    code, signal,
    visible: Boolean(code.closest('yona-markdown-editor')) ||
      (bounds.bottom >= -800 && bounds.top <= innerHeight + 800),
    cancel() {
      const index = pending.indexOf(job);
      if (index >= 0) pending.splice(index, 1);
      forget(job);
      if (!pending.length && timer !== undefined) {
        clearTimeout(timer);
        timer = undefined;
      }
    },
  };
  jobs.set(code, job);
  pending.push(job);
  signal.addEventListener('abort', job.cancel, { once: true });
  if (typeof IntersectionObserver !== 'undefined') {
    observer ??= new IntersectionObserver(entries => {
      for (const entry of entries) {
        const waiting = jobs.get(entry.target as HTMLElement);
        if (waiting) waiting.visible = entry.isIntersecting || Boolean(waiting.code.closest('yona-markdown-editor'));
      }
    }, { rootMargin: '800px' });
    observer.observe(code);
  }
  // Fixed from the first job: continuous mounts cannot postpone work indefinitely.
  if (!running && timer === undefined) timer = window.setTimeout(() => void drain(), 24);
}
