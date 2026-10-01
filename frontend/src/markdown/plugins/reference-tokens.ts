export type ReferenceType = 'issue' | 'user' | 'project' | 'commit';
export type Candidate = {type: ReferenceType; value: string};
export type Piece = string | (Candidate & {text: string});
/** true: resolves, false: known not to resolve, undefined: not looked up yet (treated as unresolved). */
export type Lookup = (candidate: Candidate) => boolean | undefined;

// Mirrors the server AutoLinkRenderer: Java's [a-zA-Z0-9-_.가-힣/] path class and ASCII \w boundaries.
const path = '[a-zA-Z0-9\\-_.가-힣/]+';
const sha = '[a-f0-9]{7,40}';
const word = /\w/;
type Pass = {pattern: RegExp; candidate: (match: RegExpMatchArray) => Candidate};
const passes: Pass[] = [
  {pattern: new RegExp(`@?(${path})#(\\d+)`, 'g'), candidate: m => ({type: 'issue', value: `${m[1]}#${m[2]}`})},
  {pattern: /#(\d+)/g, candidate: m => ({type: 'issue', value: `#${m[1]}`})},
  {pattern: new RegExp(`(${path})@?(${sha})`, 'g'), candidate: m => ({type: 'commit', value: `${m[1]}@${m[2]}`})},
  {pattern: new RegExp(`@?(${sha})`, 'g'), candidate: m => ({type: 'commit', value: m[1]})},
  {pattern: new RegExp(`@(${path})`, 'g'),
    candidate: m => m[1].includes('/') ? {type: 'project', value: m[1]} : {type: 'user', value: `@${m[1]}`}},
];

/**
 * Applies the legacy passes in order. Text linked by an earlier pass is not rescanned; an unresolved
 * match stays text that later passes may still link. Repeat with fresh lookups until `unknown` is empty.
 */
export function linkLegacyReferences(text: string, lookup: Lookup): {pieces: Piece[]; unknown: Candidate[]} {
  let pieces: Piece[] = [text];
  const unknown = new Map<string, Candidate>();
  for (const pass of passes) {
    pieces = pieces.flatMap(piece => {
      if (typeof piece !== 'string') return [piece];
      const out: Piece[] = [];
      let offset = 0;
      for (const match of piece.matchAll(pass.pattern)) {
        const start = match.index!;
        const end = start + match[0].length;
        if ((start > 0 && word.test(piece[start - 1])) || (end < piece.length && word.test(piece[end]))) continue;
        const candidate = pass.candidate(match);
        const known = lookup(candidate);
        if (known === undefined) unknown.set(`${candidate.type}:${candidate.value}`, candidate);
        if (!known) continue;
        if (start > offset) out.push(piece.slice(offset, start));
        out.push({...candidate, text: match[0]});
        offset = end;
      }
      if (offset < piece.length) out.push(piece.slice(offset));
      return out;
    });
  }
  return {pieces, unknown: [...unknown.values()]};
}
