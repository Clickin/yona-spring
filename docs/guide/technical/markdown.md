# 마크다운 지원 방식

브라우저 Markdown의 기준 구현은 main repository `frontend/src/markdown`의 Lit 3 Web Component이며, `micromark + GFM → DOMPurify → DOM plugin → 비동기 enhancement` 순서로 처리한다. 서버 CommonMark는 메일·번역 응답·Wiki REST `renderedHtml`용으로 유지한다. API 필드를 제거하지 않는다.

## 조회 화면

```html
<yona-markdown-renderer mode="comment" owner="owner" project="project"
    th:text="${issue.body}"></yona-markdown-renderer>
```

원문은 반드시 `th:text`로 escape한다. Markdown을 큰 HTML 속성이나 `th:utext`로 전달하지 않는다. `comment` 모드는 soft break를 보존하고, `document` 모드는 CommonMark 문서 의미와 GitHub-style heading slug를 사용한다. 저장소 파일에는 `ref`와 전체 파일 `path`를 전달하고 Wiki에는 ref 없이 페이지 path를 전달한다.

source는 최초 mount에서 한 번만 읽는다. 다른 source를 표시하려면 새 element를 만든다. Turbo가 DOM을 `cloneNode(true)`로 캐시하므로 원문 snapshot을 비활성 `<template>`에 보존한다. 같은 element의 reconnect는 재파싱하지 않는다. source 감시용 MutationObserver나 renderer 내부 Turbo 의존성은 없다.

초기 module 로딩 중에는 head에서 먼저 적용한 CSS가 raw Markdown을 가리고 `Loading Markdown…` 안내를 표시한다. `display:none`으로 접지 않고 같은 글꼴·padding과 `pre-wrap` 줄바꿈으로 원문 길이와 화면 너비에 비례하는 공간을 예약한다. 동기 렌더링과 structural plugin이 끝난 뒤 `data-markdown-ready`를 설정해 결과를 표시한다. 고정된 픽셀 높이는 남기지 않아 이후 창 크기 변경 시 빈 공간이 유지되지 않는다.

200개 문단/1280px viewport의 로컬 확인에서는 약 8,478px를 예약하고 실제 결과가 약 7,448px였다. 이는 근사치이며 크기 정보가 없는 이미지와 비동기 Mermaid의 최종 높이까지 보장하지는 않는다. JavaScript를 끄거나 module 다운로드가 실패한 경우에는 원문을 읽을 수 있게 복원한다. `markdown-loading.spec.ts`가 module 전달을 의도적으로 지연해 원문 비노출·높이 예약·resize·Turbo clone·실패 fallback을 검증한다.

## 편집기

공통 `markdownEditor` fragment가 `<yona-markdown-editor>` 안에 실제 `<textarea>`를 서버 렌더링한다. JavaScript는 이 노드를 유지하며 `value`·`defaultValue`·selection·form reset을 그대로 사용한다. hidden textarea나 별도 editor document를 만들지 않는다.

GitHub Markdown toolbar/text-expander를 사용한다. `@/#`는 기존 `mentionList` endpoint에 취소 가능한 요청을 보내고, `:`는 기존 65개 로컬 emoji에서 검색한다. 결과 label은 HTML이 아닌 text로 삽입한다. Tab/Shift+Tab, 첨부파일 삽입, draft 복구/삭제는 같은 textarea와 editor `.value` 계약을 사용한다.

Preview 진입마다 textarea를 한 번 읽는 renderer를 새로 mount하고 Edit 복귀 시 제거한다. 입력 중 파싱/live preview는 없다. `.value` setter와 form reset은 stale preview를 종료한다. Wiki preview는 document 모드다. Inline review도 기존 vanilla CodeCommentBox와 SSR form을 사용한다.

CM6·Vue Markdown editor/review-form bundle·Marked·전역 highlighter·서버 preview controller와 사용하지 않는 서버 상대경로 helper는 제거했다. 다른 Vue 위젯과 server-only renderer/cache, 호환성 API 응답은 유지한다.

## 보안과 비동기 처리

- DOMPurify HTML profile을 사용하되 form, inline style, 이벤트 handler, SVG/MathML, 위험한 protocol은 허용하지 않는다. structural plugin은 안전한 DOM node/text를 생성한다.
- reference 자동 링크는 기존 서버 `AutoLinkRenderer`의 5단계(`경로#N`, `#N`, `경로@sha`, `sha`, `@user|@org|@owner/project`)를 같은 순서로 재현한다. fork 축약형(`owner#N`, `owner@sha`), ASCII 기준 단어 경계(`이슈#3`의 `#3`도 링크), `@`가 있을 때만 프로젝트 링크, 번역된 이슈 상태, 사용자 hover popover(`이름 로그인ID`)를 포함한다.
- reference는 프로젝트별 25ms 고정 window로 batch/dedupe하고 page memory에 cache한다. 서버 `POST /api/{owner}/{project}/markdown/references/resolve`는 최대 100개, token당 200자까지 받으며 대상 프로젝트·이슈 READ와 member-only code 권한을 검사한다. HTML 대신 metadata만 반환한다. disconnect된 subscriber는 취소한다.
- 외부 링크는 `noopener`, `application.noreferrer=true`일 때 `noreferrer`도 적용한다.
- highlight core/grammar는 해당 fence에서만 lazy-load한다. 1.x의 59개 언어와 alias, 기존 2.0 언어를 합친 66개 grammar fixture를 유지한다. 모르는 언어는 원문 코드로 표시하며 자동 언어 추측은 하지 않는다.
- Mermaid는 24ms 수집 후 전역 queue에서 하나씩 처리하고 작업 사이에 yield한다. viewport 우선순위, strict mode, 50,000자/500 edge 제한과 별도 SVG sanitizer를 적용한다. 오류 시 원본 code block이 남는다.
- 이미지 Viewer는 renderer가 생성·해제한다. 전역 DOMContentLoaded scan을 사용하지 않는다.

## 빌드와 검증

Gradle `processResources`/`bootJar`가 pinned lockfile의 `npm ci --ignore-scripts --no-audit --no-fund`와 esbuild를 실행한다. ESM/chunk 결과물은 `build/generated/frontend/markdown`에만 생성하며 Git에 vendoring하지 않는다. 기존 Turbo build는 별도로 유지하고 Windows에서는 `npm.cmd`를 선택한다.

실행 명령, 상세 정책, 이전 CM6/Marked/highlight gzip·Brotli baseline은 [영문 기술 문서](../../technical/markdown.md)를 참고한다. `frontend/scripts/check-markdown-{structure,enhancements}.mjs`는 기존 E2E Playwright로 Chromium/Firefox/WebKit을 검증한다.

`e2e/specs/05-code/markdown-documents.spec.ts`는 실제 로컬 Git endpoint로 대표 원문 corpus를 넣고 README → 하위 `.md` → README 이동과 상대 이미지 로딩을 검증한다. 한글/중복 heading, GFM, safe HTML, Kotlin code, 200개 문단을 세 브라우저에서 확인했다. 저장소 ref는 Thymeleaf 예약 속성인 `th:ref`가 아니라 `th:attr`로 전달한다.

조회 renderer 전환은 editor 교체보다 먼저 별도 commit으로 수행한다. 문서화되지 않은 옛 Marked quirk와 `yb-header-*` ID는 복원하지 않는다.

## 검증 기록과 한계

- Renderer 정적 dependency closure: gzip 50,383 bytes. Editor+renderer closure: gzip 61,381 bytes. 둘은 shared chunk를 공유하므로 합산하지 않는다.
- 같은 Markdown을 표시하는 cold-context Chromium Issue 페이지의 JavaScript transfer는 upstream `a71722f`의 2,765,215 bytes에서 1,581,994 bytes로 줄었다. 로컬 HTTP 비압축 측정이며 gzip 수치와는 다른 지표다.
- Chromium/Firefox/WebKit에서 component·XSS·자동완성·native reset·clone·Viewer dispose, reference batch/취소, task PATCH, 162개 language identifier/alias와 Mermaid 공격·크기/edge 제한 검증을 통과했다. 100개 renderer는 각각 22.8/41.0/36.0ms에 mount했고 reference 요청은 1회였다.
- 실제 Turbo detail을 20/40/60회 교체한 뒤 Chromium GC 기준 retained renderer는 8/6/7개, document에 mount된 renderer는 계속 2개였다.
- 선택한 최종 JVM 묶음 66개 테스트, strict TypeScript 검사, `processResources`/`bootJar`를 통과했다.

전체 application E2E가 모두 green인 것은 아니다. 기존 Turbo의 Back/history 및 검색 navigation 실패를 수정하지 않은 upstream worktree에서도 재현했다. 해당 테스트를 숨기거나 기대값을 변경하지 않았다. Firefox/WebKit은 각각 86개 통과·Turbo 1개 실패, Chromium 한 실행은 83개 통과·history 1개 실패였다. 이 문제와 Markdown component 수명주기 검증을 구분한다.

실제 운영 Yona 1.x export archive는 제공되지 않아 archive 업로드/import 검증을 수행했다고 주장하지 않는다. 원문 Markdown 의미와 legacy 언어 fixture는 브라우저에서 검증했다. 자세한 측정 조건과 결과는 영문 문서를 참고한다.
