<script lang="ts">
let nextEditorId = 0;
const editorApi = Symbol('markdown-editor-api');
type EditorApi = {getValue: () => string; setValue: (value: string) => void; ready: Promise<unknown>};
</script>

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, ref, useHost, useShadowRoot} from 'vue';
import TextExpanderElement, {type TextExpanderChangeEvent} from '@github/text-expander-element';
import {loadShadowStyles} from './shadow-styles';
import {emoji} from './emoji';
import './yona-markdown-renderer';

defineOptions({inheritAttrs: false});
type Suggestion = {value: string; label: string; image?: string};
const host = useHost()! as HTMLElement & {[editorApi]?: EditorApi};
const shadow = useShadowRoot()!;
const textarea = host.querySelector('textarea');
const wrapper = host.closest('[data-toggle="markdown-editor"]');
const help = host.querySelector<HTMLElement>('.markdown-help') ?? wrapper?.querySelector<HTMLElement>('.markdown-help');
const notice = host.querySelector<HTMLElement>('.editor-notice-label') ?? wrapper?.querySelector<HTMLElement>('.editor-notice-label');
const clearDraft = host.querySelector<HTMLElement>('.editor-clear-temporary') ?? wrapper?.querySelector<HTMLElement>('.editor-clear-temporary');
const preview = ref(false);
const previewHeight = ref(0);
const editPane = ref<HTMLDivElement>();
const lifetime = new AbortController();
let query: AbortController | undefined;
let expander: TextExpanderElement | undefined;
// Vue retains exposed CE getters across unmounts, so remounts update this same public object.
const exposed = host[editorApi] ??= {getValue, setValue, ready: Promise.resolve()};
Object.assign(exposed, {getValue, setValue});
if (textarea) {
  if (!textarea.id) textarea.id = `yona-markdown-input-${++nextEditorId}`;
  textarea.hidden = false;
  // Turbo clones retain completion attributes, but not the expander's state.
  for (const name of ['aria-expanded', 'aria-haspopup', 'aria-controls', 'aria-activedescendant', 'aria-autocomplete']) {
    textarea.removeAttribute(name);
  }
  if (textarea.getAttribute('role') === 'combobox') textarea.removeAttribute('role');
  expander = new TextExpanderElement();
  expander.setAttribute('keys', ': @ #');
  expander.setAttribute('multiword', ':');
  expander.slot = 'input';
  expander.append(textarea);
  if (help) help.slot = 'help';
  if (notice) notice.slot = 'notice';
  if (clearDraft) clearDraft.slot = 'clear-draft';
  // The original controls remain in light DOM, preserving form/reset and Stimulus ownership.
  host.replaceChildren(expander, ...[help, clearDraft, notice].filter((node): node is HTMLElement => !!node));
}

function dismiss() {
  query?.abort();
  expander?.dismiss();
}

function edit() {
  dismiss();
  preview.value = false;
  if (expander) expander.hidden = false;
}

function getValue() {
  return textarea?.value ?? '';
}

function setValue(value: string) {
  if (!textarea) return;
  edit();
  textarea.value = value;
  textarea.dispatchEvent(new Event('input', {bubbles: true}));
}

// Upgrade values assigned before registration or the first connection, including an empty draft.
const initialValue = Object.getOwnPropertyDescriptor(host, 'value');
if (initialValue && 'value' in initialValue) {
  Reflect.deleteProperty(host, 'value');
  setValue(initialValue.value);
}

function showPreview() {
  if (!textarea || preview.value) return;
  dismiss();
  previewHeight.value = editPane.value!.getBoundingClientRect().height;
  preview.value = true;
  if (expander) expander.hidden = true;
}

function addChecklist() {
  if (!textarea) return;
  edit();
  const position = textarea.selectionStart || textarea.value.length;
  const template = '\n- [ ] Todo A\n- [ ] Todo B\n- [ ] Todo C';
  textarea.setRangeText(template, position, position, 'end');
  textarea.dispatchEvent(new Event('input', {bubbles: true}));
  void nextTick(() => textarea.focus());
}

function previewMode() {
  const mode = host.getAttribute('editor-mode') ?? textarea?.dataset.editorMode;
  return mode === 'wiki-content' || mode === 'readme' ? 'document' : 'comment';
}

function indent(event: KeyboardEvent) {
  if (!textarea || event.target !== textarea || event.key !== 'Tab' || event.defaultPrevented ||
      event.ctrlKey || event.altKey || event.metaKey || expander?.querySelector('[role="listbox"]')) return;
  event.preventDefault();
  const {selectionStart: start, selectionEnd: end} = textarea;
  if (!event.shiftKey && start === end) textarea.setRangeText('\t', start, end, 'end');
  else indentLines(textarea, event.shiftKey);
  textarea.dispatchEvent(new Event('input', {bubbles: true}));
}

function indentLines(input: HTMLTextAreaElement, outdent: boolean) {
  const {selectionStart: start, selectionEnd: end, value} = input;
  const lineStart = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
  // A selection ending after a newline excludes the following line.
  const selectedEnd = end > start && value[end - 1] === '\n' ? end - 1 : end;
  const newline = value.indexOf('\n', selectedEnd);
  const blockEnd = newline < 0 ? value.length : newline;
  const lines = value.slice(lineStart, blockEnd).split('\n');
  let firstDelta = 0;
  let delta = 0;
  const replacement = lines.map((line, index) => {
    const removed = outdent ? (line.match(/^(?:\t| {1,4})/)?.[0].length ?? 0) : 0;
    const change = outdent ? -removed : 1;
    if (index === 0) firstDelta = change;
    delta += change;
    return outdent ? line.slice(removed) : `\t${line}`;
  }).join('\n');
  input.setRangeText(replacement, lineStart, blockEnd, 'preserve');
  input.setSelectionRange(Math.max(lineStart, start + firstDelta), Math.max(lineStart, end + delta));
}

function complete(event: TextExpanderChangeEvent) {
  if (!event.detail) return;
  const {key, text, provide} = event.detail;
  query?.abort();
  query = new AbortController();
  provide(suggestions(key, text, query.signal));
}

async function suggestions(key: string, text: string, signal: AbortSignal) {
  const fragment = document.createElement('ul');
  fragment.className = 'markdown-suggestions';
  fragment.setAttribute('role', 'listbox');
  fragment.setAttribute('aria-label', key === ':' ? 'Emoji' : key === '@' ? 'People' : 'Issues');
  let matches: Suggestion[];
  if (key === ':') {
    const search = text.toLowerCase();
    matches = emoji.filter(item => item.name.toLowerCase().includes(search))
      .sort((a, b) => a.name.toLowerCase().indexOf(search) - b.name.toLowerCase().indexOf(search))
      .slice(0, 10).map(item => ({value: item.content, label: `${item.content} ${item.name}`}));
  } else {
    matches = await mentionSuggestions(key, text, signal);
  }
  fragment.append(...matches.map(suggestionOption));
  return {matched: !signal.aborted && host.isConnected && matches.length > 0, fragment};
}

async function mentionSuggestions(key: string, text: string, signal: AbortSignal): Promise<Suggestion[]> {
  if (key !== '@' && key !== '#') return [];
  const endpoint = host.closest('[data-mention-url]')?.getAttribute('data-mention-url');
  if (!endpoint) return [];
  try {
    const url = new URL(endpoint, document.baseURI);
    if (url.origin !== location.origin || !['http:', 'https:'].includes(url.protocol)) return [];
    url.searchParams.set('query', text);
    url.searchParams.set('mentionType', key === '@' ? 'user' : 'issue');
    const response = await fetch(url, {signal, headers: {Accept: 'application/json'}});
    if (!response.ok) return [];
    const data: unknown = await response.json();
    if (signal.aborted || !data || typeof data !== 'object' || !('result' in data) || !Array.isArray(data.result)) return [];
    const matches: Suggestion[] = [];
    const search = text.toLowerCase();
    const rows: unknown[] = data.result;
    for (const row of rows) {
      const suggestion = mentionSuggestion(row, key, search);
      if (suggestion) matches.push(suggestion);
      if (matches.length === 10) break;
    }
    return matches;
  } catch { return []; }
}

function mentionSuggestion(row: unknown, key: '@' | '#', search: string): Suggestion | undefined {
  if (!row || typeof row !== 'object') return;
  const item = row as Record<string, unknown>;
  if (key === '@') {
    if (typeof item.loginid !== 'string') return;
    const searchText = String(item.searchText ?? `${item.loginid} ${item.name ?? ''}`);
    if (!searchText.toLowerCase().includes(search)) return;
    return {
      value: `@${item.loginid}`, label: `${item.name ?? ''} @${item.loginid}`,
      image: typeof item.image === 'string' ? item.image : undefined,
    };
  }
  if (typeof item.issueNo !== 'number' && typeof item.issueNo !== 'string') return;
  const label = `${item.issueNo} ${item.title ?? ''}`;
  if (!label.toLowerCase().includes(search)) return;
  return {value: `#${item.issueNo}`, label: `#${label}`};
}

/** Labels stay text; avatars accept only HTTP(S), including relative image URLs. */
function suggestionOption({value, label, image}: Suggestion): HTMLLIElement {
  const item = document.createElement('li');
  item.setAttribute('role', 'option');
  item.dataset.value = value;
  if (image) {
    try {
      const url = new URL(image, document.baseURI);
      if (url.protocol === 'https:' || url.protocol === 'http:') {
        const avatar = document.createElement('img');
        avatar.src = url.href;
        avatar.alt = '';
        avatar.width = avatar.height = 20;
        item.append(avatar);
      }
    } catch { /* Invalid avatar URLs do not hide otherwise valid results. */ }
  }
  item.append(document.createTextNode(label));
  return item;
}

onMounted(() => {
  exposed.ready = loadShadowStyles(shadow).then(() => nextTick());
  if (!textarea || !expander) return;
  const options = {signal: lifetime.signal};
  textarea.addEventListener('input', () => query?.abort(), {...options, capture: true});
  textarea.addEventListener('blur', () => query?.abort(), options);
  host.addEventListener('keydown', indent, options);
  // Resolve form ownership at the event, since a same-node move does not remount Vue.
  document.addEventListener('reset', event => {
    if (event.target === textarea.form) edit();
  }, {...options, capture: true});
  expander.addEventListener('text-expander-change', complete, options);
  expander.addEventListener('text-expander-value', event => {
    const detail = (event as CustomEvent<{item: HTMLElement; value: string | null}>).detail;
    detail.value = detail.item.dataset.value ?? null;
  }, options);
  expander.addEventListener('text-expander-committed', () => {
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
  }, options);
});

onBeforeUnmount(() => {
  dismiss();
  lifetime.abort();
});

defineExpose(exposed);
</script>

<template>
  <template v-if="textarea">
    <ul class="nav nav-tabs nm small markdown-editor-controls" role="group" aria-label="Markdown view">
      <li :class="{active: !preview}">
        <a :href="`#${textarea.id}-edit`" role="button" :aria-controls="`${textarea.id}-edit`"
           :aria-pressed="!preview" @click.prevent="edit">{{ host.dataset.editLabel ?? 'Edit' }}</a>
      </li>
      <li :class="{active: preview}">
        <a :href="`#${textarea.id}-preview`" role="button" :aria-controls="`${textarea.id}-preview`"
           :aria-pressed="preview" @click.prevent="showPreview">{{ host.dataset.previewLabel ?? 'Preview' }}</a>
      </li>
      <li>
        <div class="task-list-button">
          <button type="button" class="add-task-list-button ybtn ybtn-small ybtn-danger-no-outline" @click="addChecklist">
            <i class="yobicon-list task-list-icon" aria-hidden="true"></i> {{ host.dataset.checklistLabel ?? 'Add checklist' }}
          </button>
        </div>
      </li>
      <!-- v-pre keeps these native Shadow DOM slots instead of Vue slot outlets. -->
      <li v-if="clearDraft"><slot v-pre name="clear-draft"></slot></li>
      <li v-if="notice"><slot v-pre name="notice"></slot></li>
    </ul>
    <div class="tab-content">
      <slot v-pre name="help"></slot>
      <div :id="`${textarea.id}-edit`" ref="editPane" class="tab-pane" :class="{active: !preview}" :hidden="preview">
        <div class="textarea-box"><slot v-pre name="input"></slot></div>
      </div>
      <div :id="`${textarea.id}-preview`" class="tab-pane markdown-preview" :class="{active: preview}"
           :hidden="!preview" :style="preview ? {height: `${previewHeight}px`} : undefined">
        <yona-markdown-renderer v-if="preview" :sourceElement.prop="textarea" :mode="previewMode()"
                                :owner="host.getAttribute('owner')" :project="host.getAttribute('project')"
                                :ref.attr="host.getAttribute('ref') ?? undefined" :path="host.getAttribute('path')"></yona-markdown-renderer>
      </div>
    </div>
  </template>
</template>

<style>
:host { display: block; }
[hidden] { display: none !important; }
.markdown-editor-controls { color: #333; }
.markdown-editor-controls.nav-tabs.small > li { margin-bottom: -1px; }
.markdown-editor-controls.nav-tabs.small > li > a { padding: 4px 15px; }
.markdown-editor-controls a:focus-visible { outline: 2px solid #2679b5; }
.tab-content { position: relative; overflow: visible; }
.tab-pane.active { display: flow-root; }
.markdown-preview { box-sizing: border-box; overflow: auto; }
.markdown-preview > yona-markdown-renderer { padding: 0 !important; }
</style>
