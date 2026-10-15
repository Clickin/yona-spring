import {defineCustomElement} from 'vue';
import YonaAttachmentsComponent from './YonaAttachments.vue';

export type AttachmentOptions = {
  textarea?: HTMLTextAreaElement | null; uploadURL?: string; listURL?: string; resourceType?: string; resourceId?: string;
};

export const YonaAttachments = defineCustomElement(YonaAttachmentsComponent);

// Legacy callers configure before mounting, and Vue remounts after disconnects.
// A declared prop keeps that public method synchronous without retaining a stale exposed setup function.
Object.defineProperty(YonaAttachments.prototype, 'configure', {
  value(this: HTMLElement & {configuration?: AttachmentOptions}, options: AttachmentOptions = {}) {
    this.configuration = {
      ...options,
      uploadURL: options.uploadURL ?? this.configuration?.uploadURL,
      listURL: options.listURL ?? this.configuration?.listURL,
    };
  },
});

if (!customElements.get('yona-attachments')) customElements.define('yona-attachments', YonaAttachments);
