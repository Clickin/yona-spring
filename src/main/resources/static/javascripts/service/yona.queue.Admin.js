import * as Turbo from '../turbo/turbo.es2017-esm.js';

Turbo.session.drive = false;
Turbo.config.forms.mode = 'off';

// Native navigation owns URLs/history/forms; Turbo only replaces bounded server HTML.
class QueueEvents extends HTMLElement {
    connectedCallback() {
        this.frame = this.querySelector('turbo-frame');
        this.status = this.querySelector('[data-queue-connection]');
        this.listeners = new AbortController();
        const options = { signal: this.listeners.signal };
        this.querySelector('[data-queue-refresh]').addEventListener('click', event => {
            if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            this.pending = true;
            this.schedule();
        }, options);
        this.addEventListener('focusout', () => this.schedule(), options);
        this.addEventListener('input', () => this.schedule(), options);
        this.addEventListener('change', () => this.schedule(), options);
        this.addEventListener('turbo:before-fetch-response', event => {
            if (!event.detail.fetchResponse.response.ok) this.failed = true;
        }, options);
        this.addEventListener('turbo:before-frame-render', event => {
            this.focusedJob = document.activeElement?.closest('a')?.closest('[data-job-id]')?.dataset.jobId;
            if (!this.editing()) return;
            event.preventDefault();
            this.resume = event.detail.resume;
            this.status.textContent = window.Messages('queue.admin.js.renderDeferred');
        }, options);
        this.addEventListener('turbo:fetch-request-error', () => {
            this.failed = true;
        }, options);
        this.addEventListener('turbo:frame-missing', event => {
            event.preventDefault(); // Keep the last snapshot, never inject an authorization/login response.
            this.failed = true;
        }, options);
        window.addEventListener('pagehide', () => this.stop(), options);
        window.addEventListener('pageshow', () => this.start(), options);
        this.start();
    }

    disconnectedCallback() {
        this.stop();
        this.listeners.abort();
        this.resume?.();
        this.resume = null;
    }

    start() {
        if (this.source || !this.isConnected) return;
        this.source = new EventSource(this.getAttribute('src'));
        const invalidate = () => {
            this.pending = true;
            this.schedule();
        };
        this.source.addEventListener('reset', invalidate);
        this.source.addEventListener('changed', invalidate);
        this.source.addEventListener('error', () => {
            this.status.textContent = window.Messages('queue.admin.js.reconnecting');
            if (this.source?.readyState === EventSource.CLOSED && !this.reconnectTimer) {
                // Non-200 responses (including a temporary stream quota) do not retry natively.
                this.reconnectTimer = setTimeout(() => {
                    this.reconnectTimer = null;
                    this.source = null;
                    this.start();
                }, 15000);
            }
        });
    }

    stop() {
        this.source?.close();
        this.source = null;
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
        clearTimeout(this.timer);
        this.timer = null;
    }

    editing() {
        return this.frame.contains(document.activeElement) &&
            document.activeElement.matches('input:not([type=hidden]), textarea, select');
    }

    schedule() {
        if (this.resume) {
            // Let focusout finish before inspecting the new active element.
            clearTimeout(this.timer);
            this.timer = setTimeout(() => {
                this.timer = null;
                if (!this.editing()) {
                    this.resume?.();
                    this.resume = null;
                }
            }, 0);
            return;
        }
        if (!this.pending || this.busy || this.timer || !this.isConnected) return;
        this.timer = setTimeout(() => {
            this.timer = null;
            this.refresh();
        }, 200);
    }

    async refresh() {
        if (!this.source || !this.pending) return;
        if (this.editing()) {
            this.status.textContent = window.Messages('queue.admin.js.editing');
            return;
        }
        this.pending = false;
        this.busy = true;
        this.failed = false;
        try {
            if (this.frame.src) this.frame.reload();
            else this.frame.src = this.querySelector('[data-queue-refresh]').href;
            await this.frame.loaded;
            if (!this.isConnected) return;
            if (this.focusedJob && !this.failed) {
                this.frame.querySelector(`tr[data-job-id="${CSS.escape(this.focusedJob)}"] a`)?.focus();
            }
            this.focusedJob = null;
            this.status.textContent = this.failed
                ? window.Messages('queue.admin.js.refreshFailed')
                : this.source?.readyState === EventSource.OPEN
                    ? window.Messages('queue.admin.js.connected')
                    : window.Messages('queue.admin.js.disconnected');
        } catch (_) {
            this.status.textContent = window.Messages('queue.admin.js.refreshFailed');
        } finally {
            this.busy = false;
            this.schedule();
        }
    }
}

customElements.define('yona-queue-events', QueueEvents);
