import {Application, Controller} from '@hotwired/stimulus';

/** The server owns help HTML; this controller owns only its open/closed state. */
class MarkdownHelpController extends Controller<HTMLElement> {
  static targets = ['tab', 'panel'];
  declare readonly tabTargets: HTMLElement[];
  declare readonly panelTargets: HTMLElement[];

  toggle(event: Event) {
    const tab = event.target instanceof Element
      ? event.target.closest<HTMLElement>('[data-markdown-help-target~="tab"]') : null;
    const target = tab?.dataset.target;
    if (!tab || !target) return;
    const tabs = this.tabTargets;
    if (!tabs.includes(tab)) return;
    event.preventDefault();
    const open = !tab.classList.contains('active');
    for (const item of tabs) {
      const selected = open && item === tab;
      item.classList.toggle('active', selected);
      item.setAttribute('aria-expanded', String(selected));
    }
    for (const panel of this.panelTargets) {
      const selected = open && panel.classList.contains(target);
      panel.classList.toggle('active', selected);
      panel.hidden = !selected;
    }
  }
}

const application = Application.start();
application.register('markdown-help', MarkdownHelpController);
