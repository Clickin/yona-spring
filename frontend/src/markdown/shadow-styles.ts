const stylesheets = [
  '/bootstrap/css/bootstrap.css',
  '/stylesheets/yobicon/style.css',
  '/stylesheets/yona.css',
  '/javascripts/lib/highlight/styles/default.css',
];

const loaded = new WeakMap<ShadowRoot, Promise<unknown>>();

/** Reuse cached page styles and their readiness when Vue remounts the same shadow root. */
export function loadShadowStyles(root: ShadowRoot): Promise<unknown> {
  const previous = loaded.get(root);
  if (previous) return previous;
  const before = root.firstChild;
  const ready = Promise.all(stylesheets.map(href => new Promise<void>(resolve => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    link.onload = () => resolve();
    link.onerror = () => resolve();
    root.insertBefore(link, before);
  })));
  loaded.set(root, ready);
  return ready;
}
