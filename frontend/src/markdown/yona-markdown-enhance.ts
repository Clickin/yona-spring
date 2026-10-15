import { highlightCode } from './runtime/highlight-registry';
import { queueMermaid } from './runtime/mermaid-render-queue';
export { highlightCode };

const active = new Map<HTMLElement, AbortController>();
type ViewerInstance = { destroy(): void; update(): void };
type ViewerConstructor = new (element: HTMLElement, options: { transition: boolean }) => ViewerInstance;
const viewers = new Map<HTMLElement, ViewerInstance>();

function enhance(root: ParentNode): void {
  const blocks = root instanceof HTMLElement && root.matches('pre > code')
    ? [root] : root.querySelectorAll<HTMLElement>('pre > code');
  for (const code of blocks) {
    if (active.has(code) || !code.isConnected) continue;
    const language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9);
    if (!language) continue;
    const controller = new AbortController();
    active.set(code, controller);
    if (language.toLowerCase() === 'mermaid') queueMermaid(code, controller.signal);
    else void highlightCode(code, language, controller.signal);
  }
  for (const checkbox of root.querySelectorAll<HTMLInputElement>('[data-markdown-preview] input[type="checkbox"]')) {
    checkbox.disabled = true;
  }
  const Viewer = (window as Window & { Viewer?: ViewerConstructor }).Viewer;
  if (Viewer) {
    const containers = new Set<HTMLElement>(root.querySelectorAll<HTMLElement>('.markdown-wrap'));
    if (root instanceof HTMLElement) {
      const container = root.closest<HTMLElement>('.markdown-wrap');
      if (container) containers.add(container);
    }
    for (const container of containers) {
      if (!container.querySelector('img')) continue;
      const viewer = viewers.get(container);
      if (!viewer) viewers.set(container, new Viewer(container, { transition: false }));
      else if (root instanceof HTMLImageElement || root.querySelector('img')) viewer.update();
    }
  }
}

// SSR stays readable before this module or optional rendering libraries arrive.
const observer = new MutationObserver(records => {
  for (const [code, controller] of active) {
    if (!code.isConnected) {
      controller.abort();
      active.delete(code);
    }
  }
  for (const [container, viewer] of viewers) {
    if (!container.isConnected) {
      viewer.destroy();
      viewers.delete(container);
    }
  }
  for (const record of records) {
    for (const node of record.addedNodes) {
      if (node instanceof HTMLElement) enhance(node);
    }
  }
});
observer.observe(document.documentElement, { childList: true, subtree: true });
enhance(document);

document.addEventListener('turbo:before-cache', () => {
  for (const controller of active.values()) controller.abort();
  active.clear();
  for (const viewer of viewers.values()) viewer.destroy();
  viewers.clear();
});
document.addEventListener('turbo:load', () => enhance(document));
