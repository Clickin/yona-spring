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
            if (!this.editing()) return;
            event.preventDefault();
            this.resume = event.detail.resume;
            this.status.textContent = '입력 중입니다. 새 서버 조회 결과의 표시를 보류합니다.';
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
            this.status.textContent = '실시간 연결 끊김 — 마지막 조회 결과입니다. 재연결 후 갱신합니다.';
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
        if (this.frame.contains(document.activeElement)) return true;
        return [...this.frame.querySelectorAll('.queue-command input:not([type=hidden])')].some(input =>
            input.type === 'checkbox' ? input.checked : input.value.length > 0);
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
            this.status.textContent = '새 변경 사항이 있습니다. 입력과 키보드 초점을 보호하기 위해 갱신을 보류합니다.';
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
            this.status.textContent = this.failed
                ? '화면 갱신 실패 — 마지막 조회 결과입니다. 새로고침하여 권한과 연결을 확인하세요.'
                : this.source?.readyState === EventSource.OPEN
                    ? '실시간 연결됨 — 최신 서버 조회 결과입니다.'
                    : '실시간 연결 끊김 — 마지막 조회 결과입니다.';
        } catch (_) {
            this.status.textContent = '화면 갱신 실패 — 마지막 조회 결과입니다. 새로고침해 주세요.';
        } finally {
            this.busy = false;
            this.schedule();
        }
    }
}

customElements.define('yona-queue-events', QueueEvents);
