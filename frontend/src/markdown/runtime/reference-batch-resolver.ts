import type {ReferenceType} from '../plugins/reference-tokens';
export type {ReferenceType};
export type ReferenceMetadata = {
  key: string; type: ReferenceType; href: string; label: string;
  state?: string; stateLabel?: string; kind?: 'user' | 'org'; popover?: string;
};
type Context = {owner: string; project: string};
type Subscriber = {apply: (metadata: ReferenceMetadata | null) => void; signal: AbortSignal; cancel: () => void};
type Entry = {
  context: Context; type: ReferenceType; value: string; subscribers: Set<Subscriber>;
  result?: ReferenceMetadata | null; batch?: Batch;
};
type Batch = {entries: Entry[]; controller: AbortController};
const entries = new Map<string, Entry>();
const pending = new Set<Entry>();
let timer: number | undefined;
const entryKey = (context: Context, type: ReferenceType, value: string) => JSON.stringify([context.owner, context.project, type, value]);

/** Settles with metadata, or null when the reference does not resolve; never settles once aborted. */
export function resolveReference(context: Context, type: ReferenceType, value: string, signal: AbortSignal,
  apply: (metadata: ReferenceMetadata | null) => void): void {
  if (signal.aborted) return;
  if (!context.owner || !context.project || value.length > 200) {
    apply(null);
    return;
  }
  const key = entryKey(context, type, value);
  let entry = entries.get(key);
  if (entry?.result !== undefined) {
    apply(entry.result);
    return;
  }
  if (!entry) {
    entry = {context: {owner: context.owner, project: context.project}, type, value, subscribers: new Set()};
    entries.set(key, entry);
    pending.add(entry);
  }
  const target = entry;
  const subscriber: Subscriber = {apply, signal, cancel: () => {
    target.subscribers.delete(subscriber);
    if (target.subscribers.size) return;
    pending.delete(target);
    if (entries.get(key) === target) entries.delete(key);
    if (target.batch?.entries.every(item => !item.subscribers.size)) target.batch.controller.abort();
  }};
  target.subscribers.add(subscriber);
  signal.addEventListener('abort', subscriber.cancel, {once: true});
  // A fixed window (not trailing debounce) also bounds waiting under continuous mounts.
  if (pending.size && timer === undefined) timer = window.setTimeout(flush, 25);
}

function flush(): void {
  timer = undefined;
  const groups = new Map<string, Entry[]>();
  for (const entry of pending) {
    const key = JSON.stringify([entry.context.owner, entry.context.project]);
    const group = groups.get(key) ?? [];
    group.push(entry);
    groups.set(key, group);
  }
  pending.clear();
  for (const group of groups.values()) {
    for (let offset = 0; offset < group.length; offset += 100) void send(group.slice(offset, offset + 100));
  }
}

async function send(items: Entry[]): Promise<void> {
  const batch: Batch = {entries: items, controller: new AbortController()};
  items.forEach(entry => { entry.batch = batch; });
  const {owner, project} = items[0].context;
  let results: Map<string, ReferenceMetadata> | undefined;
  try {
    const headers: Record<string, string> = {'Content-Type': 'application/json'};
    const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    if (csrf) headers['X-XSRF-TOKEN'] = decodeURIComponent(csrf[1]);
    const response = await fetch(`/api/${encodeURIComponent(owner)}/${encodeURIComponent(project)}/markdown/references/resolve`, {
      method: 'POST', credentials: 'same-origin', headers, signal: batch.controller.signal,
      body: JSON.stringify({items: items.map(({type, value}) => ({type, value}))})
    });
    if (!response.ok) return;
    const body = await response.json();
    if (!Array.isArray(body.items)) return;
    results = new Map();
    for (const item of body.items) {
      if (typeof item.key !== 'string' || typeof item.label !== 'string' || typeof item.href !== 'string') continue;
      // Metadata remains a trust boundary: never turn a response into executable/remote URLs.
      const url = new URL(item.href, location.origin);
      if (!item.href.startsWith('/') || url.origin !== location.origin || url.username || url.password) continue;
      results.set(item.key, item);
    }
  } catch {
    // Network failures and detach cancellation leave the original readable token in place.
  } finally {
    for (const entry of items) {
      const key = entryKey(entry.context, entry.type, entry.value);
      const metadata = results?.get(`${entry.type}:${entry.value}`);
      const result = metadata?.type === entry.type ? metadata : null;
      if (results && !batch.controller.signal.aborted) entry.result = result;
      else if (entries.get(key) === entry) entries.delete(key);
      entry.batch = undefined;
      for (const subscriber of entry.subscribers) {
        subscriber.signal.removeEventListener('abort', subscriber.cancel);
        if (!subscriber.signal.aborted) subscriber.apply(result);
      }
      entry.subscribers.clear();
    }
  }
}
