<script setup lang="ts">
import {watchEffect} from 'vue';
import {isVideo, readableSize} from './attachment-text';
import type {AttachmentItem} from './yona-attachments';

const props = defineProps<{
  state: {items: AttachmentItem[]; dragging: boolean};
  shell: HTMLElement;
}>();
const emit = defineEmits<{
  insert: [item: AttachmentItem];
  delete: [item: AttachmentItem];
}>();
const insertLabel = props.shell.dataset.insertLabel ?? '';
const deleteLabel = props.shell.dataset.deleteLabel ?? '';

// The localized shell is server-rendered; Vue owns its list and visibility state.
const list = props.shell.querySelector<HTMLElement>('.attached-files');
const help = props.shell.querySelector<HTMLElement>(':scope > p.help');
const dropper = props.shell.querySelector<HTMLElement>(':scope > .upload-drop-here');
watchEffect(() => {
  const visible = props.state.items.length > 0 ? 'block' : 'none';
  if (list) list.style.display = visible;
  if (help) help.style.display = visible;
  if (dropper) dropper.style.display = props.state.dragging ? 'block' : 'none';
});
</script>

<template>
  <li
    v-for="item in state.items"
    :key="item.key"
    class="attached-file"
    :class="item.id ? 'complete' : 'temporary'"
    :id="item.id ? undefined : item.key"
    :data-id="item.id"
    :data-name="item.name"
    :data-href="item.url"
    :data-mime="item.mimeType"
    @click="item.id && emit('insert', item)"
  >
    <i class="yobicon-supportrequest" aria-hidden="true"></i>
    <i class="mimetype" :class="{'yobicon-video2': isVideo(item.mimeType)}" aria-hidden="true"></i>
    <strong class="name">{{ item.name }}</strong>
    <span class="size">{{ readableSize(item.size) }}</span>
    <div
      class="progress upload-progress"
      role="progressbar"
      :aria-label="item.name"
      :aria-valuemin="0"
      :aria-valuemax="100"
      :aria-valuenow="item.progress"
    >
      <div class="bar orange" :style="{width: `${item.progress}%`}"></div>
    </div>
    <button
      type="button"
      class="btn-transparent btn-delete"
      :disabled="!item.id"
      :aria-label="`${deleteLabel}: ${item.name}`"
      @click.stop="emit('delete', item)"
    >×</button>
    <button type="button" class="nbtn small white btn-insert" :disabled="!item.id">{{ insertLabel }}</button>
  </li>
</template>

<style>
:host { display: block; color: inherit; font: inherit; }
button, input { font: inherit; }
button { cursor: pointer; }
button:focus-visible, .fake-file-wrap:focus-within { outline: 2px solid #3a7ee5; outline-offset: 2px; }
.upload-wrap { position: relative; padding: 10px; background: #f5f5f5; border-radius: 5px; }
.comment-upload { background: #efefef; margin-bottom: 10px; border-radius: 0 0 5px 5px; }
.attach-wrap { text-align: center; }
.attach-wrap .btn-wrap { display: inline-block; margin: 0 5px; vertical-align: top; }
.attach-wrap .plain { display: inline-block; line-height: 30px; }
.nbtn { display: inline-block; border: 0; border-radius: 2px; color: #222; background: #fff;
  font-size: 11px; font-weight: bold; line-height: 18px; text-align: center; white-space: nowrap;
  box-shadow: inset 0 -1px 1px #0005; margin-right: 5px; }
.nbtn.medium { padding: 6px 20px; }
.nbtn:hover { background: #e6e6e6; color: #f36c22; }
.fake-file-wrap { position: relative; overflow: hidden; cursor: pointer; }
.fake-file-wrap .file { position: absolute; inset: 0; width: 100%; height: 100%; opacity: 0; cursor: pointer; }
.attached-files { list-style: none; margin: 15px 0 0; padding: 15px 0; border-top: 1px solid #e0e0e0; }
.attached-file { display: inline-flex; align-items: center; gap: 3px; max-width: calc(100% - 30px);
  height: 30px; line-height: 30px; border: 1px solid #ccc; background: #fafafa;
  padding: 0 10px; margin: 5px 4px; cursor: pointer; }
.attached-file:hover { border-color: #f36c22; }
.attached-file i { display: none; color: #3a7ee5; }
.attached-file.temporary i { display: inline-block; }
.attached-file .name { min-width: 0; max-width: 250px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attached-file .size { font-size: 11px; white-space: nowrap; }
.upload-progress { width: 100px; height: 7px; overflow: hidden; background: #f0f0f0; border: 1px solid #e0e0e0; }
.upload-progress .bar { height: 100%; background: #f36c22; }
.btn-delete { display: none; flex-shrink: 0; width: 30px; height: 30px; border: 0; padding: 0;
  background: transparent; font-size: 1.5em; font-weight: bold; }
.btn-delete:hover { color: #f36c22; }
.btn-insert { display: none; padding: 1px 10px; height: 24px; line-height: 20px; box-shadow: none; }
.attached-file.complete .progress { display: none; }
.attached-file.complete .btn-delete, .attached-file.complete .btn-insert { display: inline-block; }
.right-txt { text-align: right; }
p.help { margin: 0 0 10px; }
.upload-drop-here { position: absolute; inset: 2px; border: 3px dashed #ffb23d;
  background: #fffc; z-index: 1; pointer-events: none; }
.msg-wrap { display: flex; align-items: center; justify-content: center; height: 100%; }
.msg { color: #999; font-size: 26px; text-align: center; }
i { font-family: yobicon; font-style: normal; font-weight: normal; }
.yobicon-upload::before { content: "\e4bd"; }
.yobicon-supportrequest::before { content: "\e203"; }
.yobicon-video2::before { content: "\e19e"; }
</style>
