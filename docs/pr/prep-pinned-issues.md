# 프로젝트 공용 이슈 고정 준비

- 브랜치: `prep/pinned-issues`
- 준비 기준: upstream/next `6dae7982a242f672d3132feeeb32a369d21e8d1f`
- 상태: 구현, 아래 H2 회귀 검증 및 실제 브라우저 DOM/폼 smoke 완료. 로컬 준비 브랜치이며 이슈 승인, 최신 upstream 재베이스, 비-H2 검증은 남아 있다. push, PR 생성, 이슈 댓글은 하지 않았다.

## 구현 범위

프로젝트 매니저는 이슈 상세 화면의 **프로젝트에 고정 / 프로젝트 고정 해제** 버튼으로 팀 공용 고정을 변경한다. 개인 별표 즐겨찾기와 데이터·권한이 분리되어 있다. 로그인 사용자라도 해당 프로젝트 매니저가 아니면 변경할 수 없다. 프로젝트 및 이슈 읽기 권한도 확인한다.

이슈 목록 상단은 현재 상태·검색어·작성자·담당자·마일스톤·댓글 작성자·라벨·마감일 조건에 맞는 고정 이슈를 별도 구역에 표시한다. 일반 목록에서는 고정 이슈를 제외하여 같은 이슈를 중복 표시하거나 페이지 수에 두 번 포함하지 않는다. 고정 구역의 건수와 **나머지 검색 결과** 건수를 별도로 표시하고, 페이지 나누기는 나머지 이슈에만 적용한다. 열린/닫힌 상태 탭의 기존 프로젝트 전체 건수는 고정을 포함한다. 엑셀 조회는 고정 여부와 무관하게 해당 필터에 맞는 전체 이슈를 한 번씩 포함한다.

고정은 `Issue.pinnedAt`에 저장한다. 프로젝트 간 이동 시 이슈와 함께 이동하는 하위 이슈의 고정을 모두 해제한다. 이슈 삭제 시 별도 고정 관계를 정리할 필요가 없다. 초안은 고정할 수 없고 고정 목록에서 제외된다. 이미 고정된 이슈의 반복 고정 요청은 기존 시간을 유지한다. 고정 쓰기의 프로젝트 조건은 이전 프로젝트 대상으로 들어온 요청이 이동 후의 이슈를 고정하지 못하게 한다.

### API

```http
PUT /api/v1/projects/{owner}/{project}/issues/{number}/pin
Content-Type: application/json

{"pinned": true}
```

해제는 `false`. 성공 시 `200 {"pinned":true|false}`. 미인증 `401`, 읽을 수 있는 프로젝트의 비매니저 `403`, 읽을 수 없거나 존재하지 않는 프로젝트/이슈 `404`, 초안 `400`. 기존 API 토큰의 이슈 쓰기 스코프를 사용한다. 일반 이슈 응답에도 `pinnedAt`이 추가된다. 웹 폼은 CSRF가 적용되는 `POST /{owner}/{project}/issue/{number}/pin`을 사용하고 성공 시 상세 화면으로 `303` 이동한다.

새 고정 API는 인증된 세션으로 호출할 때 CSRF 토큰을 요구한다. 웹 폼에도 같은 세션 경계를 적용하여 가짜 `Yona-Token` 또는 `Authorization: token` 헤더가 CSRF 예외가 되지 않게 한다. 세션 없는 정상 PAT/OAuth Bearer 요청은 기존처럼 허용하고, 다른 기존 API의 CSRF 정책은 변경하지 않는다.

### 스키마

이 저장소의 기존 Hibernate `ddl-auto=update` 방식대로 nullable `issue.pinned_at` 컬럼을 추가한다. 기존 행은 `NULL`(고정 안 됨)이며 별도 마이그레이션 도구·새 테이블·기본값 backfill은 추가하지 않는다. H2 외 지원 DB의 기존 스키마 업그레이드는 별도 검증 대상이다.

## 아직 승인되지 않은 제품 결정

- 프로젝트의 직접 매니저만 고정 변경 가능하다. 사이트/조직 관리자라는 이유만으로 별도 우회 권한을 추가하지 않는다.
- 닫힌 이슈의 고정은 유지되며 닫힌 상태 필터에서 보인다.
- 고정 구역은 모든 페이지에서 보이고 현재 목록의 정렬을 따른다. 고정 개수 상한, 드래그 정렬, 별도 알림은 추가하지 않는다.
- 고정 구역에도 현재 검색 필터를 적용한다. 필터에 맞지 않는 공지를 강제로 노출하지 않는다.

## 검증

2026-10-02 독점 검증 슬롯에서 JDK 21/H2로 아래 6개 스펙을 실행했다. **173 tests, 0 failures, 0 errors; BUILD SUCCESSFUL in 36s**. 첫 실행의 목록 테스트는 사용자 없는 fixture가 초기 관리자 설정으로 `302` 이동하여 실패했고, 실제 사이트 전제인 사용자 fixture를 추가한 뒤 6개 스펙 전체가 통과했다. 전체 프로젝트 테스트 및 비-H2 DB 검증은 실행하지 않았다.

작성된 `PinnedIssueSpec`은 실제 DB와 MVC/Thymeleaf 경계에서 다음을 확인한다.

- 매니저의 고정/반복 고정/웹 해제 영속화와 다른 사용자에게 공유되는 목록
- 일반 멤버이자 작성자의 고정 거부, 미인증 거부, 초안 거부, 비공개 프로젝트 변경 거부
- 필터 및 2페이지에 걸친 고정/일반 목록 분리와 정확한 일반 페이지 수, 비공개/초안/다른 상태 제외
- 부모·하위 이슈 이동 시 해제, 이전 프로젝트 조건의 쓰기 거부, 고정된 이슈 삭제

`PinnedIssueSecuritySpec`도 실제 보안 필터 체인에서 통과했다. 세션 CSRF 누락, 가짜 토큰 헤더, 인코딩된 `/%70in` 경로의 변경을 차단하고, 정상 세션 CSRF 요청과 세션 없는 이슈 쓰기 PAT 및 실제 서명된 OAuth Bearer 요청은 성공했다.

```sh
JAVA_HOME=/Users/senghyunjo/.sdkman/candidates/java/21.0.6-tem \
  ./gradlew test -Dyona.it.db=h2 --no-daemon --max-workers=1 \
  --tests com.github.yonaprojects.yona.web.PinnedIssueSpec \
  --tests com.github.yonaprojects.yona.web.PinnedIssueSecuritySpec \
  --tests com.github.yonaprojects.yona.web.IssueViewControllerSpec \
  --tests com.github.yonaprojects.yona.web.IssueListTemplateRenderingSpec \
  --tests com.github.yonaprojects.yona.web.IssueDraftListTemplateRenderingSpec \
  --tests com.github.yonaprojects.yona.domain.issue.IssueServiceSpec
```

실제 headless Chromium에서 포트 `18106`으로 다음을 확인했다. H2/업로드와 Git/SVN/Hg를 모두 `/tmp/yona-prep-pinned-issues-smoke-20261002` 아래 독립 경로로 두고 `--yona.ssh.relay.enabled=false`로 실행했다. 완료 후 소유한 앱·브라우저를 종료하고 검증 슬롯을 반환했다.

- 매니저의 실제 상세 폼으로 고정 → 버튼이 해제로 바뀜 → 해제 → 새로고침 후 다시 고정 버튼 확인.
- 별도 가입·로그인한 두 번째 사용자에게 공용 고정이 표시됨. 그 사용자에게 변경 폼이 없고 유효 CSRF를 포함한 변경 API도 `403`.
- `filter=needle&itemsPerPage=1`에서 고정 1건/나머지 2건/2페이지. 1페이지의 일반 행은 `#2`, 2페이지는 `#3`, 두 페이지 모두 고정 `#1`이 별도 구역에 한 번만 표시됨.
- 실제 브라우저 세션의 CSRF 누락 및 가짜 `Yona-Token` 요청 모두 `403`.
- 비공개 프로젝트의 고정 제목이 두 번째 사용자에게 노출되지 않고 권한 오류 페이지로 표시됨.
- 닫힌 이슈의 고정은 닫힌 상태 탭에서 유지되고 나머지 결과 0건으로 표시됨.
- 고정 이슈 이동 API `200`, 응답 `pinnedAt=null`, 대상 목록에서 일반 이슈로 보이며 고정 구역 없음.
- 대상에서 다시 고정 후 삭제 `200`, 재조회 `404`, 목록에서 고정 구역과 삭제 제목 모두 없음.

화면의 실제 DOM, 접근성 관찰 및 폼 클릭/페이지 이동으로 확인했다. 다른 준비 브랜치들에서 반복 확인된 screenshot 도구 timeout은 재시도하지 않았으므로 픽셀 이미지 기반 시각 검증은 수행하지 않았다. 기존 데이터베이스 업그레이드와 H2 외 DB 검증은 미실행이다.

## 이슈 승인 후 PR 준비

승인 당시의 최신 upstream/next에 다시 맞춘다. 이 문서의 기준 커밋을 최신이라고 가정하지 않는다.

```sh
git fetch upstream next
git rebase upstream/next
```

충돌을 해결한 후 위 회귀 검증과 실제 웹 smoke를 다시 실행하고, 당시 변경된 권한·스키마·API 계약도 확인한다. 검증 결과를 이 문서에 기록한 뒤 커밋 및 PR 준비를 진행한다. 지금은 push, PR, 이슈 댓글을 작성하지 않는다.
