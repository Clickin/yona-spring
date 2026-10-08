# 마크다운 지원 방식

브라우저 Markdown은 main repository `frontend/src/markdown`의 Vue 3 컴포넌트를 실제 open Shadow DOM에 mount하며, `micromark + GFM → DOMPurify → DOM plugin → 비동기 enhancement` 순서로 처리한다. Thymeleaf 페이지 구조와 서버 CommonMark의 메일·번역·Wiki REST 응답은 유지한다.

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

공통 `markdownEditor` fragment의 상단 버튼과 탭 영역은 Vue가 Shadow DOM에서 렌더링한다. 원래 textarea와 text-expander는 named slot의 light DOM에 남으므로 `value`·`defaultValue`·selection·validation·폼 제출/reset과 첨부파일·자동저장 selector를 유지한다. 중복 hidden input이나 값 동기화는 없다. JavaScript가 없어도 서버 폼을 사용할 수 있다.

Yona 1.x의 편집/미리보기 탭, 체크리스트 추가 버튼, 임시저장 표시와 Markdown 도움말을 기존 CSS로 표시한다. 버튼 문구는 Thymeleaf 메시지에서 받는다. 체크리스트는 해당 에디터의 textarea에 기존 3개 항목 템플릿을 삽입하며, 미리보기 중이면 편집으로 돌아온다. 추가했던 서식 toolbar와 별도 Help 버튼은 제거했다.

상단 UI 기준은 `upstream/master`가 아닌 정확한 `v1.16.0` 태그다. 체크리스트 버튼의 `yobicon-list task-list-icon`, 탭 padding `4px 15px`, 목록 하단 margin `-1px`를 에디터 범위에서 복원했다. 한국어 버튼은 기존 104.09×24px에서 120.48×25px로 맞췄고, 넓어진 탭 때문에 오른쪽으로 60px 밀린 배치도 복원했다. 태그의 HTML·Less·Bootstrap·원본 아이콘 폰트를 이용해 1440px/390px의 편집·미리보기 4개 상태에서 6개 요소를 비교했다. 크기·위치는 1 CSS px 이내, 색·글꼴·여백은 일치했다. 실행 중인 Play 앱 전체가 아닌 정적 기준 화면과의 비교다.

도움말은 `help/markdown :: markdown` Thymeleaf fragment의 light DOM을 `help` slot으로 배치한다. 기존 document-root Stimulus controller와 공통 CSS가 그대로 적용된다. 임시저장 표시도 같은 이유로 원래 노드를 slot에 유지한다.

자동완성에는 GitHub text-expander를 사용한다. `@/#`는 기존 `mentionList` endpoint에 취소 가능한 요청을 보내고, `:`는 기존 65개 로컬 emoji에서 검색한다. 결과 label은 HTML이 아닌 text로 삽입한다. Tab/Shift+Tab, 첨부파일 삽입, draft 복구/삭제는 같은 textarea와 editor `.value` 인터페이스를 사용한다.

Preview 진입마다 textarea를 한 번 읽는 renderer를 새로 mount하고 Edit 복귀 시 제거한다. 입력 중 파싱/live preview는 없다. `.value` setter와 form reset은 stale preview를 종료한다. Wiki preview는 document 모드다. Inline review도 기존 vanilla CodeCommentBox와 SSR form을 사용한다.

Preview는 진입 직전 편집 영역의 실제 높이(하단 margin 포함)를 border-box 높이로 유지하며, 긴 본문은 내부에서 스크롤한다. Edit로 돌아오면 같은 textarea와 사용자가 조절한 높이가 유지된다. 활성 탭의 `flow-root`가 margin을 영역 안에 포함해 아래 요소가 움직이지 않게 한다. 공통 `autosize()`는 Markdown editor textarea를 제외하고 다른 textarea에만 적용한다. Chromium의 1366px·390px 화면에서 module 지연 중 기본·조절한 크기 유지와 짧은·긴 preview 왕복을 회귀 검증했다.

CM6·Vue Markdown editor/review-form bundle·Marked·전역 highlighter·서버 preview controller와 사용하지 않는 서버 상대경로 helper는 제거했다. 다른 Vue 위젯과 server-only renderer/cache, 호환성 API 응답은 유지한다.

### Shadow DOM 경계와 도움말 소유권

사용처는 이슈 3곳 외에도 게시판 3곳, Wiki 1곳, 마일스톤 2곳, PR 3곳, 코드 diff/compare/SVN 3곳, 공통 댓글·수정·스레드·리뷰 partial 4곳으로 총 19개 템플릿이다. 모두 같은 도움말 fragment를 받고, 도움말만 따로 포함하는 곳은 없다. CodeCommentBox는 같은 `#review-form` DOM을 `appendChild`로 옮겨 재사용하므로 이슈 폼 전용 동작으로 두지 않는다.

`frontend/src/markdown/yona-markdown-help.ts`는 `/javascripts/markdown/yona-markdown-help.js`로 전역 로드하며, Stimulus Application 하나에 로컬 `MarkdownHelpController`를 `markdown-help`로 등록한다. fragment root의 선언은 다음과 같다.

```html
<div class="markdown-help" data-controller="markdown-help"
    data-action="click->markdown-help#toggle keydown.enter->markdown-help#toggle keydown.space->markdown-help#toggle">
  <!-- 기존 .help-nav[data-target] 항목과 HTML 예시 panel. -->
</div>
```

[`data-action`과 keyboard filter](https://stimulus.hotwired.dev/reference/actions#keyboardevent-filter)가 click/Enter/Space를 `toggle(Event)`에 연결한다. 도움말 항목의 기본 동작만 막고, controller 범위의 `tab`/`panel` target으로 `active`·`aria-expanded`·`hidden`을 변경한다. 기존 `data-target`의 panel class 이름은 유지한다. `data-toggle="markdown-help"`, 에디터의 도움말 signal/listener는 없다. 열린 상태는 DOM에 저장하며 `connect()`에서 초기화하지 않는다.

Vue는 shadow toolbar와 preview를 소유하고 slot의 도움말·임시저장 표시·textarea 내부는 변경하지 않는다. 따라서 Stimulus가 document query로 도움말을 찾고 같은 노드 이동과 Turbo clone의 열린 패널 상태도 유지한다. Shadow root는 기존 Bootstrap·아이콘·Yona·highlight stylesheet를 캐시에서 재사용한다.

렌더러의 출력도 실제 shadow root 안에 있다. heading fragment 이동은 명시적으로 처리하고, tasklist는 host의 권한·폼 문맥을 유지한 채 shadow checkbox를 찾는다. 사용자 popover는 출력 subtree를 기준으로 초기화한다. Turbo 원문 snapshot은 비활성 light-DOM template에 보존한다. `.ready`는 shadow stylesheet와 동기 초기화 완료를 기다리며 `markdown-rendered`는 composed event다. 아래 측정치는 Vue 전환 이전 기록이다.

## 보안과 비동기 처리

- DOMPurify HTML profile을 사용하되 form, inline style, 이벤트 handler, SVG/MathML, 위험한 protocol은 허용하지 않는다. structural plugin은 안전한 DOM node/text를 생성한다.
- reference 자동 링크는 기존 서버 `AutoLinkRenderer`의 5단계(`경로#N`, `#N`, `경로@sha`, `sha`, `@user|@org|@owner/project`)를 같은 순서로 재현한다. fork 축약형(`owner#N`, `owner@sha`), ASCII 기준 단어 경계(`이슈#3`의 `#3`도 링크), `@`가 있을 때만 프로젝트 링크, 번역된 이슈 상태, 사용자 hover popover(`이름 로그인ID`)를 포함한다.
- reference는 프로젝트별 25ms 고정 window로 batch/dedupe하고 page memory에 cache한다. 서버 `POST /api/{owner}/{project}/markdown/references/resolve`는 최대 100개, token당 200자까지 받으며 대상 프로젝트·이슈 READ와 member-only code 권한을 검사한다. HTML 대신 metadata만 반환한다. disconnect된 subscriber는 취소한다.
- 외부 링크는 `noopener`, `application.noreferrer=true`일 때 `noreferrer`도 적용한다.
- highlight core/grammar는 해당 fence에서만 lazy-load한다. 1.x의 59개 언어와 alias, 기존 2.0 언어를 합친 66개 grammar fixture를 유지한다. 모르는 언어는 원문 코드로 표시하며 자동 언어 추측은 하지 않는다.
- Mermaid는 24ms 수집 후 전역 queue에서 하나씩 처리하고 작업 사이에 yield한다. viewport 우선순위, strict mode, 50,000자/500 edge 제한과 별도 SVG sanitizer를 적용한다. 오류 시 원본 code block이 남는다.
- 이미지 Viewer는 renderer가 생성·해제한다. 전역 DOMContentLoaded scan을 사용하지 않는다.

## 빌드와 검증

Gradle `processResources`/`bootJar`는 `pnpmInstall`의 `pnpm install --frozen-lockfile --ignore-scripts`와 esbuild를 실행한다. ESM/chunk 결과물은 `build/generated/frontend/web`에 생성하며 Git에 vendoring하지 않는다. 기존 Turbo build는 별도로 유지하고 Windows에서는 `pnpm.cmd`를 선택한다.

실행 명령, 상세 정책, 이전 CM6/Marked/highlight gzip·Brotli baseline은 [영문 기술 문서](../../technical/markdown.md)를 참고한다. `frontend/scripts/check-markdown-{structure,enhancements}.mjs`는 기존 E2E Playwright로 Chromium/Firefox/WebKit을 검증한다.

Vue/Shadow DOM 전환(2026-10-08): 통합 브랜치의 Gradle `processResources testFrontend`·strict TypeScript·단위 테스트 15개를 통과했다. 이 브랜치의 템플릿과 빌드 자산을 사용하는 격리 H2 앱에서 Chromium component/editor/attachment 검사 17개가 통과했다. Shadow DOM 격리, native FormData, 업로드·삭제, paste/drop, 표 붙여넣기와 Turbo 복원을 포함한다. 실제 브라우저에서도 이미지 업로드 → 미리보기 → 이슈 폼 제출 → 저장된 이미지 조회를 확인했다. Markdown 단독 브랜치의 지연 초기화 및 Chromium·Firefox·WebKit structure/enhancement 검사도 통과했다. 단독 브랜치 demo의 HEAD/corpus 부재로 실패한 문서 loading 검사 4개는 통과로 기록하지 않는다.

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
