# 웹훅 기능의 내부 동작

legacy Yona의 `docs/ko/technical/webhook-server-internal.md`를 옮김. 웹훅 등록/삭제 URL
경로와 payload 검증 규칙(2000자/250자 제한)까지 `WebhookController`에 그대로 남아 있고,
push 이벤트 payload JSON 구조도 legacy 예시와 필드 구성이 거의 동일하다 — 코드로 확인했다.

## 개요

웹훅은 프로젝트의 push, 이슈·게시글·PR 알림과 새 댓글 이벤트를 HTTP 요청으로 전달한다.

웹훅의 등록/삭제는 `WebhookController`가 처리한다.

```
GET     /projects/{owner}/{projectName}/webhooks              등록된 웹훅 목록
POST    /projects/{owner}/{projectName}/webhooks              웹훅 등록
DELETE  /projects/{owner}/{projectName}/webhooks/{id}          웹훅 삭제
```

**legacy 대비 바뀐 점**: URL에 `/projects` 접두사가 붙었고(legacy는 `/:user/:project/webhooks`),
삭제는 POST가 아니라 표준 HTTP `DELETE` 메서드를 쓴다.

## 웹훅 등록/삭제

등록된 웹훅은 해당 프로젝트 안에서만 확인·관리할 수 있으며, 그 프로젝트에서 발생하는
이벤트에만 반응한다.

새 웹훅 등록은 `POST .../webhooks`로 하며, 다음 값을 폼으로 받는다.

- **Payload URL** — 웹훅이 동작할 때 HTTP 요청을 보낼 주소. 최대 2000자.
- **Secret** — 요청을 받는 서버가 그 요청을 구분하기 위한 비밀 토큰. 최대 250자, 선택 사항.
- **webhookType** — `SIMPLE`, `DETAIL_SLACK`, `DETAIL_HANGOUT_CHAT`, `JSON`.
- **gitPush** — push 이벤트 선택. 기존 동작대로 JSON 포맷은 이 값과 무관하게 push를 받는다.
- **issueComment / postingComment / reviewComment / commitComment** — 새 이슈 댓글, 게시글 댓글,
  PR 리뷰 댓글, 커밋 댓글·리뷰 선택. 기본값은 모두 `true`이며, 폼에서 “전송 안 함”을 선택하면
  해당 종류의 새 댓글을 보내지 않는다. 기존 등록의 선택을 변경하려면 삭제 후 다시 등록한다.

`GET /api/v1/projects/{owner}/{project}/webhooks`는 적용 중인 선택을 JSON으로 반환하고,
같은 URL의 `POST`는 위 필드를 JSON으로 받아 등록한다. 웹 화면과 동일한 프로젝트 관리 권한이 필요하다.

두 제한을 넘으면 `400 Bad Request`로 거부된다(legacy와 동일한 2000/250 제한을 그대로
유지했다).

삭제는 `DELETE .../webhooks/{id}`로 한다.

## 웹훅 동작

새 댓글은 개인 알림 수신자가 없어도 생성 트랜잭션이 커밋된 뒤 해당 프로젝트의 선택된 웹훅으로
한 번 발행한다. 롤백된 댓글은 보내지 않는다. 이슈·게시글, PR 일반·코드 리뷰, 커밋 댓글·리뷰를
지원한다. 개인 알림 이벤트와 별도로 처리하므로 같은 댓글을 두 경로에서 중복 전송하지 않는다.

댓글 JSON은 기존 `event`, `sender`, `project`, `resourceId`, `resourceType`을 유지하고
`action: "created"`, `comment: {id, body, url, author: {id, login, name}}`,
`parent: {resourceType, id, url, number?, title?}`를 추가한다. 커밋 부모의 ID는 커밋 해시이며
이슈/PR 번호·제목은 없다. Slack 상세 포맷도 부모 PR 본문 대신 실제 댓글 본문을 담는다.
PR 댓글 링크는 댓글이 표시되는 `/pull/{number}/changes`로 연결하며, 특정 커밋 리뷰는 해당
changes 경로로 연결한다. Git/Hg의 일반 커밋 댓글은 리뷰 댓글 ID와 충돌하지 않는
`#commit-comment-{id}` 앵커를 사용한다.

댓글 수정·삭제 이벤트의 신규 계약, 전송 이력·재전송은 이 범위에 포함하지 않는다. HTTP 전송은
기존 비동기 best-effort 방식이므로 네트워크 수준의 exactly-once 보장이 아니다.

push 이벤트는 `GitPushHooks`와 post-receive 이벤트 처리 경로에서 `WebhookServiceImpl`로 전달한다.

push의 JSON payload는 아래 구조다.

```json
{
    "ref": ["refs/heads/master"],
    "commits": [
        {
            "id": "c2f9f27ea16004020d1f4e846217c2825d217a12",
            "message": "test\n",
            "timestamp": "2015-06-12T04:41:21+0900",
            "url": "http://localhost:8080/dddeeee/commit/c2f9f27ea16004020d1f4e846217c2825d217a12",
            "author": {"name": "hello", "email": "hello@hello.com"},
            "committer": {"name": "hello", "email": "hello@hello.com"}
        }
    ],
    "head_commit": { "...": "commits[0]과 동일 구조" },
    "sender": {
        "login": "hello",
        "id": 2,
        "avatar_url": "/assets/images/default-avatar-128.png",
        "type": "User",
        "site_admin": false
    },
    "pusher": {"name": "hello", "email": "hello@hello.com"},
    "repository": {
        "id": 33,
        "name": "dddeeee",
        "owner": "hello",
        "html_url": "/hello/dddeeee",
        "overview": "eee",
        "private": false
    }
}
```

스키마는 기존 Hibernate `ddl-auto: update` 방식으로 네 개의 nullable boolean 컬럼을 추가한다.
기존 행의 null은 `true`로 해석하여 종전의 새 댓글 구독을 유지한다.
