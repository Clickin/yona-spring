/** Text rules shared with legacy yona.Attachments/yona.Files; kept DOM-free for unit tests. */
export type LinkSource = {name: string; url?: string; mimeType?: string};

const videoTypes = ['video/mp4', 'video/ogg', 'video/webm'];
export const isVideo = (mimeType = '') => videoTypes.includes(mimeType.trim().toLowerCase());

const escapeText = (value: string) => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const escapeAttribute = (value: string) => value.replace(/&/g, '&amp;').replace(/"/g, '&quot;');

/** The Markdown inserted for an uploaded file; videos keep the legacy video.js wrapper. */
export function linkText({name, url = '', mimeType = ''}: LinkSource): string {
  const link = `[${name}](${url}) `;
  if (mimeType.startsWith('image')) return `!${link}`;
  if (isVideo(mimeType)) {
    return `<video class="video-js" data-setup="{}" controls="controls"><source src="${escapeAttribute(url)}" ` +
      `type="${escapeAttribute(mimeType)}"></video>${escapeText(link)}`;
  }
  return link;
}

export const uploadMarker = (key: string) => `<!--_${key}_-->`;

export function hasTextAndImage(items: ArrayLike<{kind: string; type: string}>): boolean {
  let text = false;
  let image = false;
  if (items.length > 1) {
    for (const item of Array.from(items)) {
      if (item.kind === 'string' && /^text\/plain/.test(item.type)) text = true;
      else if (item.kind === 'file' && /^image\//.test(item.type)) image = true;
    }
  }
  return text && image;
}

/** Spreadsheet cells to a Markdown table, as copy-excel-paste-markdown in legacy yona.Files. */
export function markdownTable(data: string): string {
  const rows = data.split(/[\u0085\u2028\u2029]|\r\n?/g).map(row => row.replace('\n', ' ').split('\t'));
  const alignments: string[] = [];
  const widths = rows[0].map((column, index) => {
    const match = column.match(/^(\^[lcr])/i);
    const alignment = match ? match[1][1].toLowerCase() : 'l';
    alignments.push(alignment);
    rows[0][index] = column.replace(/^(\^[lcr])/i, '');
    return Math.max(...rows.map(row => `${row[index]}`.length));
  });
  const lines = rows.map(row => `| ${row.map((column, index) => column + ' '.repeat(widths[index] - column.length)).join(' | ')} |`);
  lines.splice(1, 0, `|${widths.map((width, index) => {
    const alignment = alignments[index];
    const adjust = alignment === 'r' ? 1 : alignment === 'c' ? 2 : 0;
    return (alignment === 'c' ? ':' : '') + '-'.repeat(width + 2 - adjust) + (alignment === 'l' ? '' : ':');
  }).join('|')}|`);
  return lines.join('\n');
}

/** Legacy visible name for pasted images: seconds + milliseconds + "-Y-M-D-H-m" (not unique). */
export function legacyUploadName(date = new Date()): string {
  return `${date.getSeconds()}${date.getMilliseconds()}-${date.getFullYear()}-${date.getMonth() + 1}-` +
    `${date.getDate()}-${date.getHours()}-${date.getMinutes()}`;
}

export function readableSize(bytes: number): string {
  if (!bytes || bytes < 1024) return `${bytes || 0}B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = bytes / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(1)}${units[unit]}`;
}

type RandomSource = Pick<Crypto, 'getRandomValues'> & Partial<Pick<Crypto, 'randomUUID'>>;

/** Unique per upload; randomUUID is unavailable on insecure (plain http) origins, so fall back. */
export function uploadKey(source: RandomSource = crypto): string {
  if (source.randomUUID) return source.randomUUID();
  const bytes = source.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('').replace(/^(.{8})(.{4})(.{4})(.{4})/, '$1-$2-$3-$4-');
}
