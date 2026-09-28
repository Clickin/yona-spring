import { highlightCode } from './highlight-registry';
import { queueMermaid } from './mermaid-render-queue';

const mounted = new WeakMap<HTMLElement, AbortSignal>();
type ViewerInstance = { destroy(): void };
type ViewerConstructor = new (element: HTMLElement, options: { transition: boolean }) => ViewerInstance;

/** Enhance immutable output; the caller aborts its mount subscription on disconnect. */
export function enhance(root: HTMLElement, signal: AbortSignal): void {
  if (signal.aborted || !root.isConnected || mounted.get(root) === signal) return;
  mounted.set(root, signal);
  const codeBlocks = root.querySelectorAll<HTMLElement>('pre > code');
  const highlights: [HTMLElement, string][] = [];
  for (const code of codeBlocks) {
    const language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9);
    if (language?.toLowerCase() === 'mermaid') queueMermaid(code, signal);
    else if (language) highlights.push([code, language]);
  }
  // Begin all grammar downloads in the same wave; highlighting itself yields on its CPU budget.
  for (const [code, language] of highlights) void highlightCode(code, language, signal);

  let viewer: ViewerInstance | undefined;
  const images = root.querySelectorAll('img');
  if (images.length) {
    const Viewer = (window as Window & { Viewer?: ViewerConstructor }).Viewer;
    if (Viewer) {
      viewer = new Viewer(root, { transition: false });
      for (const image of images) image.style.cursor = 'pointer';
    }
  }
  signal.addEventListener('abort', () => {
    viewer?.destroy();
    if (mounted.get(root) === signal) mounted.delete(root);
  }, { once: true });
}
