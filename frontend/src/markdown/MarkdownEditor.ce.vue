<script setup lang="ts">
import {nextTick, ref} from 'vue';
import type TextExpanderElement from '@github/text-expander-element';

// Vue props are shallow: the native controls and host never become reactive state.
const props = defineProps<{
  host: HTMLElement;
  textarea?: HTMLTextAreaElement;
  expander?: TextExpanderElement;
  hasClearDraft: boolean;
  hasNotice: boolean;
  dismiss: () => void;
}>();
const preview = ref(false);
const previewHeight = ref(0);
const editPane = ref<HTMLDivElement>();

function edit() {
  props.dismiss();
  preview.value = false;
  if (props.expander) props.expander.hidden = false;
}

function showPreview() {
  if (!props.textarea || preview.value) return;
  props.dismiss();
  previewHeight.value = editPane.value!.getBoundingClientRect().height;
  preview.value = true;
  if (props.expander) props.expander.hidden = true;
}

function addChecklist() {
  const textarea = props.textarea;
  if (!textarea) return;
  edit();
  const position = textarea.selectionStart || textarea.value.length;
  const template = '\n- [ ] Todo A\n- [ ] Todo B\n- [ ] Todo C';
  textarea.setRangeText(template, position, position, 'end');
  textarea.dispatchEvent(new Event('input', {bubbles: true}));
  void nextTick(() => textarea.focus());
}

function previewMode() {
  const mode = props.host.getAttribute('editor-mode') ?? props.textarea?.dataset.editorMode;
  return mode === 'wiki-content' || mode === 'readme' ? 'document' : 'comment';
}

defineExpose({edit});
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
      <li v-if="hasClearDraft"><slot v-pre name="clear-draft"></slot></li>
      <li v-if="hasNotice"><slot v-pre name="notice"></slot></li>
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
