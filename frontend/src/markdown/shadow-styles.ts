const stylesheets = [
  '/bootstrap/css/bootstrap.css',
  '/stylesheets/yobicon/style.css',
  '/stylesheets/yona.css',
  '/javascripts/lib/highlight/styles/default.css',
];

/** Reuse the page's cached styles inside the boundary, not the page's selectors. */
export function mountShadow(host: HTMLElement, css: string) {
  const root = host.attachShadow({mode: 'open'});
  const ready = Promise.all(stylesheets.map(href => new Promise<void>(resolve => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    link.onload = () => resolve();
    link.onerror = () => resolve();
    root.append(link);
  })));
  const style = document.createElement('style');
  style.textContent = css;
  const mount = document.createElement('div');
  root.append(style, mount);
  return {root, mount, ready};
}
