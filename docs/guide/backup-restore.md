# 백업 및 복구

legacy Yona의 `docs/ko/yona-backup-restore.md`를 yona 기준으로 갱신.

## 중앙 큐를 통한 애플리케이션 백업/복원

관리자로 로그인한 뒤 **사이트 관리 → 데이터**에서 제출한다. ZIP에는 manifest,
테이블별 NDJSON, Git/SVN/LFS/첨부 파일과 애플리케이션 파일이 들어간다. 큐 테이블·큐 저장소와
실행 중인 파일 기반 H2 DB는 제외한다. 입력에 해당 운영 상태가 들어 있으면 무시하지 않고 거부한다.
프로젝트 반출은 선택한 프로젝트와 참조 데이터만 포함한다.
프로젝트 반입은 별도 프로젝트를 만들며 이름 충돌 시 이름을 바꾼다. 기존 프로젝트의 이슈를 자동 병합하지 않는다.

CSRF 보호된 native form으로 `POST /site/export`(선택적으로 `project=owner/name`) 또는
multipart `POST /site/import`(`data` 파일)를 제출하면 **303**으로 해당 큐 작업 화면에 이동한다.
GET으로 export를 시작하지 않는다. HTTP 요청에서 ZIP 생성이나 DB 복원을 수행하지 않는다.
import 요청은 업로드와 영속 파일 보관이 끝날 때까지만 기다린다.
큐에서 행/바이트 진행 상태를 확인하고 export 성공 후 결과 링크
(`GET /api/admin/queue/v1/jobs/{id}/result`)로 다운로드한다. 제출 화면을 닫아도 작업은 계속된다.

import는 다른 큐 작업과 겹치지 않게 실행한다. 다만 일반 HTTP 쓰기, Git push,
기존 큐 밖의 background writer까지 중지하지는 않는다. 일관된 백업/복원을 위해 해당 쓰기를 운영 절차로 중지해야 한다.
DB와 파일 교체 전체가 하나의 원자적 트랜잭션은 아니다. 변경 시작 후 중단/실패는 자동 재실행하지 않고
`RECOVERY_REQUIRED`로 남긴다. 이전 handler/process를 중지하고 DB·파일을 대조한 뒤 복구를 확인해야 한다.
배타 작업의 복구 확인이 끝나기 전에는 새 큐 작업 실행도 막는다.

업로드는 `${yona.queue.data-dir}/import-inputs`에 스트리밍·fsync하며, 작업 payload에는
파일 식별자·크기·SHA-256만 저장한다. handler 반환 뒤 큐 완료가 commit되므로 성공한 입력도 자동 삭제하지 않는다.
관리자가 종결 상태를 확인한 뒤 해당 작업이 참조하는 입력을 정리한다.
대기·실행·재시도 가능·미해결 복구 작업의 입력은 삭제하지 않는다.
업로드, 압축 해제 staging, 교체용 백업, 결과 ZIP을 위한 디스크 여유를 확보해야 한다.
애플리케이션 multipart 및 reverse proxy 업로드 한도는 별도로 적용된다.

대용량 이전을 준비할 때는 대상의 업로드 한도를 명시적으로 설정한다. 실제 검증에 사용한 설정 예:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 8GB
      max-request-size: 8GB
```

이는 전역 multipart 한도다. 외부 접근을 막은 maintenance 환경에서 이전하고, reverse proxy 한도와
입력·압축 해제·복원·결과 archive를 위한 디스크 여유도 함께 확인한다. 저장소 Git/SVN/LFS roots는
application-data 밖에 두면 사이트 백업의 중복 파일 수집을 피할 수 있다.

## 아카이브 형식과 1.16 마이그레이션

현재 형식은 **formatVersion=3 / targetVersion="2.0"**인 ZIP이다. 첫 엔트리는
`manifest.json`이며 `format="yona-backup"`, `sourceVersion`, `producer`,
`producerVersion`, `scope`, `project`, UTC `createdAt`, `requiredCapabilities`,
`integrity="sha256-entries-v1"`를 선언한다. 일반 export는 sourceVersion/producerVersion이
`"2.0"`, producer가 `"yona"`, requiredCapabilities가 빈 배열이다.
테이블은 `db/<대상 테이블>.ndjson`, identity 다음 값은 `db/_sequences.json`에 둔다.
파일 루트는 `files/git`, `files/svn`, `files/lfs`, `files/uploads`이며 일반 사이트 백업만
`files/data`도 사용할 수 있다. Wiki는 Git 저장소 `<owner>/<project>.wiki.git`이다.

`integrity.ndjson`에는 manifest와 index 자신을 제외한 **모든** 엔트리의
`{path,size,sha256}`를 기록한다(디렉터리는 0바이트와 빈 내용의 digest).
누락·추가 index 행, 중복 경로/index, 크기·digest 불일치, 경로 이탈과 알 수 없는 파일 루트는 거부한다.
이는 손상 검사이지 서명이나 생산자 신뢰 검증은 아니다. 사이트 아카이브는 비밀번호 해시 등
보안 정보를 포함하므로 비밀 자료로 관리한다.

대상 테이블·컬럼·필수 값·타입·PK/unique/FK, 숫자 사용자 참조, 첨부 컨테이너/파일/크기,
identity 다음 값을 임시 디스크 H2 DB에서 검사한 뒤 복원을 시작한다.
MariaDB/MySQL enum은 대상 메타데이터의 허용 값도 검사한다. 전체 행 관계를 heap에 보관하지 않지만
한 JSON 행과 파일 엔트리 메타데이터는 메모리를 사용한다. 추출본과 검증 DB를 위한 디스크도 확보한다.
PostgreSQL 복원에는 현재 `session_replication_role` 설정 권한이 필요하다.
저장소별 Git/SVN repair를 수행하거나 일반 writer를 막는 기능은 아니므로 운영 중지 절차는 여전히 필요하다.

[yona2-migrator](https://github.com/Clickin/yona2-migrator)가 1.16 스키마를 **2.0 대상 스키마**로
변환한다. 서버 importer는 원본 1.16 SQL/스키마를 해석하지 않는다.
마이그레이션 manifest는 sourceVersion `"1.16"`, producer `"yona2-migrator"`,
producerVersion `"0.1.0"`, requiredCapabilities `["legacy-credentials-sha256-1024"]`,
sourceAuthMode `local|ldap-only|ldap-fallback`, bootstrapSourceLogin `"admin"`을 선언한다.
CLI에서 credential 포함과 admin 매핑을 명시적으로 선택한 뒤 기존 사이트 import 폼으로 제출한다.

대상에는 설정된 bootstrap `admin` 한 명만 있어야 하며 프로젝트·콘텐츠·다른 계정이 없어야 한다.
대상의 초기 역할/설정과 bootstrap 보안 행은 유지한다. 원본 `admin`을 대상 bootstrap에 매핑하지만
**대상 관리자 행 전체(비밀번호·token·잠금·2FA·프로필 포함)**는 그대로 둔다.
원본 admin의 보조 이메일/SSH key/연결 로그인은 새 bootstrap 자격증명으로 가져오지 않는다.
다른 원본 사이트 관리자는 `ACTIVE`로 가져오며 이메일/로그인 ID로 다른 계정을 자동 병합하지 않는다.
원본 `project_user.role_id=3`은 1.16 조회에서 제외되는 가상 사이트 관리자 행이다.
입력 ZIP에는 원본 행을 남기지만 대상의 프로젝트 멤버십으로 적용하지 않는다. 명시적 manager/member 행은 유지한다.
bootstrap ID 충돌은 재채번하고 FK·사용자·첨부 참조가 따라간다.

원본 local password는 Base64 SHA-256/UTF-8 salt/1024회 반복 값을 검증하며 로그인 성공 시 Argon2id로
업그레이드한다. 가져온 일반 계정의 token·remember-me·자동 잠금·2FA 표시는 초기화한다.
sourceAuthMode는 대상 `yona.ldap.enabled` 및 `yona.ldap.fallback-to-local-login`과 정확히 일치해야 한다.
LDAP-only에서는 usable local password를 설치하거나 fallback을 켜지 않는다.
세션·인증 token·2FA·SSO·사이트 설정·감사·큐와 application data는 마이그레이션에서 제외한다.
정확한 manifest/제외 테이블 목록은 [아카이브 계약](../yona-backup-restore.md#archive-compatibility-and-legacy-migration)을 참고한다.

예외적으로 부모 프로젝트가 이미 없어진 원본 메뉴 설정은 integrity 대상
`migration/orphan-project-menu-settings.ndjson`에 원본 공개 컬럼을 보존할 수 있다.
`migrationProvenance`의 고정 format/version/entry 선언과 실제 orphan 여부를 검사한다.
이 provenance는 대상 DB나 파일에 적용하지 않고 큐가 보관한 입력 ZIP에 남긴다.
살아 있는 프로젝트의 메뉴 flag 변환이나 다른 잘못된 FK를 무시하는 예외가 아니다.

사이트 전체 스냅샷은 리소스 삭제 후 남은 알려진 타입의 첨부 기록과 파일도 그대로 보존한다.
없는 컨테이너를 만들거나 기록을 조용히 삭제하지 않는다. 파일 존재·크기·digest 검사는 계속 적용하며,
프로젝트 반입은 참조 컨테이너가 함께 포함되어야 한다. 임시 첨부의 소유자 검증도 별도로 적용한다.

이전 개발 DB에 생성된 `favorite_project.project_id`, `favorite_issue.issue_id`,
`favorite_organization.organization_id`의 단일 리소스 UNIQUE 제약은 사용자별 즐겨찾기와 맞지 않는다.
현재 모델은 ManyToOne이다. `ddl-auto=update`가 기존 UNIQUE를 자동 제거한다고 가정하지 말고,
1.16 이전 검증은 현재 모델로 새로 만든 2.0 DB에서 진행한다.



## DB 자체 백업

애플리케이션 레이어의 export/import와는 별개로, DB 엔진 자체 백업 도구를 쓰는 것이 더 안전하고
표준적이다(대용량/운영 환경 기준).

- MariaDB/MySQL: `mariadb-dump`(`mysqldump`)
- PostgreSQL: `pg_dump`/`pg_dumpall`
- SQL Server, CUBRID: 각 벤더 도구의 백업 절차를 따른다

## 물리 저장소 백업

legacy는 `YONA_DATA` 디렉터리 하나(conf/uploads/repo/logs)를 통째로 압축해두면 됐지만,
yona는 저장 위치가 설정 키별로 분리되어 있다. 백업 대상은 아래 4곳이다
(각 설정 키의 기본값·용도는 [README의 "운영 환경 설정"](../../README.md#운영-환경-설정-특히-windows) 참고):

- `yona.git.base-dir` — Git bare 저장소
- `yona.svn.base-dir` — SVN 저장소
- `yona.lfs.base-dir` — Git LFS 객체
- `yona.upload.base-dir` — 첨부파일 업로드

`application.yml`에 별도 재정의가 없다면 기본값이 `/tmp/yona/...`이므로, 운영 환경에서는
먼저 이 경로들을 영구 보존 디렉터리로 재설정한 다음 그 디렉터리들을 정기적으로 백업해야 한다.
설정(`application.yml` 내용 자체)은 소스/배포 산출물에 포함되므로 별도 백업 대상은 아니지만,
프로덕션 전용으로 오버라이드한 값(DB 비밀번호, OAuth2 client secret 등)이 있다면 그 값도
같이 백업 대상에 포함해야 한다.

## 검증한 실행 경로

약 5GB 규모의 평가 자료로 다음 경로를 로컬 격리 환경에서 검증했다. 입력 데이터·archive·원시 로그는
테스트용으로만 보관하며 저장소나 issue에 공개하지 않는다.

- 1.16의 SELECT-only DB 계정과 읽기 전용 파일 snapshot → CLI 기본 256MiB heap → 2.0 대상 ZIP 생성.
- 실제 관리자 bootstrap/login/upload 폼 → durable queue import 완료.
- 별도 검증기로 지원 테이블의 원본 행 수, 명시적 보안 매핑 후 복원 행/참조/필드, 파일 coverage와 SHA-256 비교.
- 원본 DB·파일 무변경, 대상 bootstrap credential/security 보존.
- 실제 이슈 title/body 렌더링, 원본 native HEAD tree와 Yona/JGit browse 비교, 인증된 첨부 다운로드 byte 비교.
- 사용자 아이콘의 `USER_AVATAR` 첨부를 공통 사용자 조회에서 연결하고, 실제 프로필의 이미지 응답과 복원 파일의 byte 일치를 확인했다.
- 실제 관리자 queue export → 인증된 streaming 결과 다운로드 → 결과 무결성 비교.
- 미지원 형식과 populated target 재이관의 변경 전 거부. 재이관 거부 전후 업무 테이블 checksum 일치.
- 원본 사용자의 비밀번호를 추측하지 않았다. 별도의 합성 1.16 fixture를 CLI부터 UI import까지 이관한 뒤
  잘못된 비밀번호의 무변경과 올바른 로그인 후 Argon2id 전환을 실제 브라우저에서 검증했다.

이 결과는 지정된 2.0 archive 계약과 로컬 실행 경로의 검증이다. 운영 reverse proxy, 실제 LDAP 서비스,
향후 다른 Yona 버전의 upgrade 성공을 주장하지 않는다.
