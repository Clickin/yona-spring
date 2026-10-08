import {defineCustomElement} from 'vue';
import Editor from './YonaMarkdownEditor.vue';

export const YonaMarkdownEditor = defineCustomElement(Editor);
export type YonaMarkdownEditor = InstanceType<typeof YonaMarkdownEditor> & {
  value: string;
  getValue?: () => string;
  setValue?: (value: string) => void;
  readonly ready: Promise<unknown>;
};

// Vue exposes methods, not a writable .value accessor. The SFC owns value and input behavior.
Object.defineProperty(YonaMarkdownEditor.prototype, 'value', {
  get(this: YonaMarkdownEditor) { return this.getValue?.() ?? this.querySelector('textarea')?.value ?? ''; },
  set(this: YonaMarkdownEditor, value: string) {
    if (this.setValue) this.setValue(value);
    // Preserve assignments made before connection; setup consumes this own property on upgrade.
    else Object.defineProperty(this, 'value', {configurable: true, writable: true, value});
  },
});

customElements.define('yona-markdown-editor', YonaMarkdownEditor);
