# 마크다운 지원 방식

브라우저 Markdown의 기준 구현은 main repository `frontend/src/markdown`의 Lit 3 Web Component이며, `micromark + GFM → DOMPurify → DOM plugin → 비동기 enhancement` 순서로 처리한다. 서버 CommonMark는 메일·번역 응답·Wiki REST `renderedHtml`용으로 유지한다. API 필드를 제거하지 않는다.

## 조회 화면

```html
<yona-markdown-renderer mode="comment" owner="owner" project="project"
    th:text="${issue.body}"></yona-markdown-renderer>
```

원문은 반드시 `th:text`로 escape한다. Markdown을 큰 HTML 속성이나 `th:utext`로 전달하지 않는다. `comment` 모드는 soft break를 보존하고, `document` 모드는 CommonMark 문서 의미와 GitHub-style heading slug를 사용한다. 저장소 파일에는 `ref`와 전체 파일 `path`를 전달하고 Wiki에는 ref 없이 페이지 path를 전달한다.

source는 최초 mount에서 한 번만 읽는다. 다른 source를 표시하려면 새 element를 만든다. Turbo가 DOM을 `cloneNode(true)`로 캐시하므로 원문 snapshot을 비활성 `<template>`에 보존한다. 같은 element의 reconnect는 재파싱하지 않는다. source 감시용 MutationObserver나 renderer 내부 Turbo 의존성은 없다.

## 보안과 비동기 처리

- DOMPurify HTML profile을 사용하되 form, inline style, 이벤트 handler, SVG/MathML, 위험한 protocol은 허용하지 않는다. structural plugin은 안전한 DOM node/text를 생성한다.
- reference는 프로젝트별 25ms 고정 window로 batch/dedupe하고 page memory에 cache한다. 서버 `POST /api/{owner}/{project}/markdown/references/resolve`는 최대 100개, token당 200자까지 받으며 대상 프로젝트·이슈 READ와 member-only code 권한을 검사한다. HTML 대신 metadata만 반환한다. disconnect된 subscriber는 취소한다.
- 외부 링크는 `noopener`, `application.noreferrer=true`일 때 `noreferrer`도 적용한다.
- highlight core/grammar는 해당 fence에서만 lazy-load한다. 1.x의 59개 언어와 alias, 기존 2.0 언어를 합친 66개 grammar fixture를 유지한다. 모르는 언어는 원문 코드로 표시하며 자동 언어 추측은 하지 않는다.
- Mermaid는 24ms 수집 후 전역 queue에서 하나씩 처리하고 작업 사이에 yield한다. viewport 우선순위, strict mode, 50,000자/500 edge 제한과 별도 SVG sanitizer를 적용한다. 오류 시 원본 code block이 남는다.
- 이미지 Viewer는 renderer가 생성·해제한다. 전역 DOMContentLoaded scan을 사용하지 않는다.

## 빌드와 검증

Gradle `processResources`/`bootJar`가 pinned lockfile의 `npm ci --ignore-scripts --no-audit --no-fund`와 esbuild를 실행한다. ESM/chunk 결과물은 `build/generated/frontend/markdown`에만 생성하며 Git에 vendoring하지 않는다. 기존 Turbo build는 별도로 유지하고 Windows에서는 `npm.cmd`를 선택한다.

실행 명령, 상세 정책, 이전 CM6/Marked/highlight gzip·Brotli baseline은 [영문 기술 문서](../../technical/markdown.md)를 참고한다. `frontend/scripts/check-markdown-{structure,enhancements}.mjs`는 기존 E2E Playwright로 Chromium/Firefox/WebKit을 검증한다.

조회 renderer 전환은 editor 교체보다 먼저 별도 commit으로 수행한다. 문서화되지 않은 옛 Marked quirk와 `yb-header-*` ID는 복원하지 않는다.
