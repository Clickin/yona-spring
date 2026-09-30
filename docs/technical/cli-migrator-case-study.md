# CLI migrator 사례조사: Yona 1.16 → 2.0

조사일: 2026-09-30. 공식 문서와 공개 소스를 비교했다. 외부 도구 설치·실행이나 실제 migration 성능 측정은 하지 않았다. 아래 명령은 공식 사용법 예시이지 실행 결과가 아니다. Yona 구현과 issue 댓글 게시도 이번 조사에 포함하지 않는다.

## 결론

**원본 변환 CLI → 이관 archive → 대상 서버의 비동기 import**가 Yona의 기존 제안과 가장 잘 맞는다. CLI와 queue는 상호 배타적인 방식이 아니다. CLI는 source 스키마/파일 변환을, queue는 target 검증·반영·상태 조회를 담당할 수 있다. Mattermost의 `mmetl` + `mmctl import`가 가까운 선례다. 다만 이 사례들이 모든 도구의 일반적 관행을 통계적으로 증명하는 것은 아니다.

서로 다른 역할을 구분해야 한다.

- **동일 제품 backup/restore**: Gitea dump, GitLab instance backup. 다른 스키마로의 변환기를 대체하지 않는다.
- **논리적 cross-product/schema migration**: Mattermost ETL, GitHub Enterprise Importer. 지원되는 데이터·identity 매핑·누락 항목을 명시한다.
- **DB schema upgrade**: 동일 앱의 schema migration. Yona 1.16의 관계·파일·계정을 2.0으로 옮기는 작업과는 별개다.

## 현재 Yona와의 연결

[#828](https://github.com/yona-projects/yona/issues/828)에서 원래 제시한 안은 (1) 서버 archive+queue, (2) JDBC DB-to-DB CLI, (3) streaming API+CLI다. 이후 사용자 댓글과 [migrator #1](https://github.com/Clickin/yona2-migrator/issues/1)은 **중지된 1.16의 read-only DB와 파일 snapshot에서 archive만 생성하는 CLI**를 제안한다. Maintainer는 세 대안을 검토하겠다고 답했으며, 특정 안 승인이나 공개 archive API 확정을 의미하지 않는다.

이 보고서는 그 후속 제안을 기준으로 비교한다. **target DB에 직접 쓰는 JDBC CLI는 별도 설계**다. archive 생성용 JDBC 읽기를 DB-to-DB 이관이라고 부르지 않는다.

로컬 코드·문서에서 확인한 현재 상태:

- [`DataBackupService.kt`](../../src/main/kotlin/com/github/yonaprojects/yona/domain/site/DataBackupService.kt): `yona-backup`, format version 2, ZIP + `manifest.json`, 테이블별 `db/<table>.ndjson`, 파일 roots.
- [`DataBackupServiceImpl.kt`](../../src/main/kotlin/com/github/yonaprojects/yona/domain/site/DataBackupServiceImpl.kt): 현재 2.0 DB 메타데이터 기반 백업. site import는 완전 교체, project import는 PK 재매핑과 `login_id` 기반 사용자 재사용. 이것만으로 1.16 변환·명시적 계정 매핑이 구현됐다고 볼 수 없다.
- [`backup-restore.md`](../guide/backup-restore.md): 제출 후 queue에서 처리하지만 업로드 자체는 HTTP 요청 안에서 완료한다. 일반 HTTP 쓰기·Git push·큐 밖 writer는 운영 절차로 중지해야 한다. DB와 파일 전체의 원자성은 보장하지 않는다.
- [`durable-queue.md`](../guide/technical/durable-queue.md): job/attempt 상태와 진행률, 결과 artifact, 변경 후 실패의 `RECOVERY_REQUIRED` 경로가 있다. queue의 재시도 기능은 archive import의 안전한 replay를 자동으로 보장하지 않는다.
- 추적 issue는 tarball을 말하지만 현재 코드는 ZIP이다. 구현 전에 공통 계약을 맞춰야 한다. 현재 구현을 활용한다면 ZIP 하나가 가장 작은 변경이며, tar/ZIP 양쪽 지원은 이 조사로 필요성이 입증되지 않았다. 계약이 바뀌면 공개 issue 설명도 함께 갱신해야 한다.

## 가장 가까운 사례: Mattermost ETL

[`mmetl` 공식 README](https://github.com/mattermost/mmetl)는 Slack ZIP 또는 RocketChat `mongodump`를 Mattermost bulk-import JSONL과 attachments directory로 변환한다고 명시한다. 변환기와 대상 import 도구가 분리되어 있다. [공식 Slack migration guide](https://docs.mattermost.com/administration-guide/onboard/migrate-from-slack)는 export → transform → upload/process → 검증 순서를 설명한다.

공식 README의 예시:

```sh
mmetl transform slack --team myteam --file export.zip --dry-run
mmetl transform slack --team myteam --file export.zip --output mm_export.jsonl
```

- `--dry-run`은 출력 없이 문제를 검사하고 문제 발견 시 non-zero로 끝난다. README는 실제 변환이 skip할 missing attachment도 dry-run에서 찾는다고 설명한다. Yona에서는 누락 파일을 성공으로 넘기지 않는 정책이 필요하다. [근거](https://github.com/mattermost/mmetl)
- guest를 일반 사용자로 바꾸면 권한이 넓어지며, skip하면 작성 게시물·멤버십 등도 빠질 수 있다. identity/권한 정책을 단순한 이름 치환으로 다루면 안 된다. Yona에서는 기존 issue의 계정 상태·bootstrap 관리자 보호 요구를 유지한다. [근거](https://github.com/mattermost/mmetl), [Yona 계약](https://github.com/Clickin/yona2-migrator/issues/1)
- **JSONL ≠ bounded-memory**. 공개 [`ParseSlackExportFile`](https://github.com/mattermost/mmetl/blob/master/services/slack/parse.go)은 사용자를 slice로 decode하고 게시물을 `map[string][]SlackPost`에 누적한다. 출력 형식의 장점과 converter 전체의 메모리 상한은 별개다. 이 구현을 Yona 대용량 처리의 증거로 사용할 수 없다.

## 기존 yona-export와의 차이

[공식 README](https://github.com/yona-projects/yona-export)는 source/target URL·token을 설정하고 프로젝트 JSON + 첨부파일을 export/import하는 흐름을 제공한다. [파일 명세](https://github.com/yona-projects/yona-export/blob/master/docs/export-file-spec.md)는 사용자 관계, 이슈·댓글·게시글·라벨·마일스톤 등을 하나의 중첩 JSON에 표현한다. [`YonaExport.js`](https://github.com/yona-projects/yona-export/blob/master/app/YonaExport.js)는 첨부 업로드 후 target API로 글을 전송한다.

따라서 기존 도구는 **target API를 호출하는 프로젝트 이관기**이며, 새 제안의 offline DB/files → versioned archive converter와 책임 범위가 다르다. 기존 도구의 대용량 timeout은 [#828의 사용자 관측](https://github.com/yona-projects/yona/issues/828)이며 이번 조사에서 재현하지 않았다.

## Yona 적용안 — 조사에 따른 제안, 구현 완료 아님

```text
중지된 1.16 / 일관된 read-only DB + 파일 snapshot
  → CLI: 지원 스키마 확인 → 관계/파일 검증 → bounded 변환
  → 공통 versioned archive (완료된 결과만 공개)
  → 2.0 관리자: 업로드 → queue: 사전 검증 → 명시적 identity 충돌 해결
  → maintenance 상태에서 반영 → 데이터/파일/로그인 검증
```

1. **하나의 target importer**: UI export archive와 legacy CLI archive가 명시된 동일 계약을 따르게 한다. source 스키마 변환은 CLI에 두고 target restore를 중복 구현하지 않는다. 단, 현재 backup v2가 migration capability를 충분히 표현하는지는 별도로 확정해야 한다.
2. **snapshot이 먼저**: DB의 consistent read만으로 Git·첨부파일까지 동일 시점이 되지는 않는다. source 쓰기를 멈추거나 일관된 snapshot을 확보한다. target queue의 배타성도 일반 HTTP/Git writer 중지의 대체물이 아니다.
3. **사전 검증과 실제 반영 분리**: version/capability, 관계, 파일 크기/digest, source 인증 모드, 계정 mapping을 확인한 후 쓰기 시작. 손상·미지원·충돌을 조용히 skip하거나 자동 병합하지 않는다. `--dry-run`이라는 이름보다 실제 무변경 보장이 중요하다.
4. **재실행 범위를 좁게**: CLI는 immutable snapshot에서 archive를 처음부터 다시 생성할 수 있게 하고 미완성 결과를 최종 파일로 공개하지 않는다. 최소안은 임의 offset resume를 추가하지 않는 것. target import 변경 후 실패는 자동 retry하지 않고 기존 `RECOVERY_REQUIRED`로 확인한다. job 조회 재개, 새 job retry, 데이터 offset resume는 서로 다른 기능이다.
5. **대용량은 측정으로 증명**: JDBC driver별 cursor/fetch 동작과 keyset iteration, 관계 mapping의 메모리/임시 디스크 상한을 검증한다. 한 줄이 큰 경우까지 고려한다. ZIP/JSONL만 채택했다고 메모리 상한을 주장하지 않는다.
6. **보안·계정은 일반 backup과 구별**: hash/salt를 명시적 migration capability로만 취급. LDAP-only 계정을 local 계정으로 바꾸지 않고 target 관리자 credential을 덮어쓰지 않는다. 외부 사례의 password reset 정책이 Yona credential 보존의 증거는 아니다.
7. **queue가 해결하지 않는 업로드 구간**: 수 GiB 업로드의 proxy/multipart 제한·시간·디스크를 별도 검증한다. 이번 최소안은 수동 업로드를 유지한다. 실패가 확인되면 별도 서버측 파일 인수 경로 등을 검토하되 조사만으로 새 upload 서비스나 object storage를 추가하지 않는다.

### Maintainer에게 보여줄 두 데모

- **Queue 데모**: 2.0 관리자 화면에서 export 제출 → 진행/결과 다운로드 → archive import → 복원 검증. 브라우저를 닫아도 작업 지속. 실패 상태와 복구 필요 상태가 구별됨.
- **CLI 데모**: 실제 1.16 fixture의 read-only DB/files → archive 생성 → 같은 관리자 import/queue로 반영. CLI에 target DB credential이 없어도 동작. 원본 불변, 사용자·권한·작성자 관계·저장소·첨부파일 보존을 검증.

공통 완료 증거는 [migrator #1](https://github.com/Clickin/yona2-migrator/issues/1)의 조건을 유지한다: 독립 archive fixture, SELECT-only와 읽기 전용 파일시스템, 5 GiB 이상/제한 heap, row/관계/file digest 비교, 올바른 로그인 후 hash 전환, 잘못된 로그인·잠금 계정 보호, 관리자 충돌, 중단·재실행·손상 archive, secret 비노출. 이 보고서는 해당 E2E 완료를 주장하지 않는다.

## 서버 job을 사용하는 CLI: Mattermost mmctl

`mmetl`은 converter지만 `mmctl`은 API client다. `mmctl export create`는 서버 background job을 만들고, import는 **upload → available 파일 확인 → process job 생성 → 상태 조회**로 분리한다. 업로드 완료는 DB 반영 완료가 아니다. [공식 절차](https://docs.mattermost.com/administration-guide/manage/cloud-data-export), [bulk import](https://docs.mattermost.com/administration-guide/onboard/bulk-loading-data)

명령 예시(실행 안 함; 로그인/권한 설정 후):

```sh
mmctl import upload ./data.zip
mmctl import list available
mmctl import process UPLOAD_SESSION_ID_data.zip
mmctl import job show IMPORT_JOB_ID
```

- bulk import 문서는 반복 입력을 interruptible/idempotent라고 설명한다. 그러나 기존 값과 두 실행 사이의 사용자 편집을 덮어쓸 수 있다. **중복 생성 방지와 무변경 보장은 다르다.** Yona importer가 이 보장을 이미 갖는다는 뜻은 아니다. [문서](https://docs.mattermost.com/administration-guide/onboard/bulk-loading-data#about-the-bulk-import-command)
- upload resume는 `UploadSession.FileOffset`부터 전송을 다시 시작하는 별도 기능이다. 현재 CLI 소스는 파일 크기를 검사하지만 같은 내용의 파일임을 checksum으로 입증하지 않는다. [고정 revision 소스](https://github.com/mattermost/mattermost/blob/cbdd84fbfba4ccd6324f0d335a66d07a9cfbf530/server/cmd/mmctl/commands/import.go#L207-L268)
- 개발 소스 `cbdd84f`에는 동일 파일의 최근 failed/stale job에서 line checkpoint를 찾아 terminal prompt로 재개하는 코드도 있다. 10분 idle은 job 종료의 증거가 아니며 살아 있는 job과 중복될 수 있다고 소스가 경고한다. 특정 출시 버전의 보장이나 Yona에 복사할 안전한 복구 규칙으로 일반화하지 않는다. [소스](https://github.com/mattermost/mattermost/blob/cbdd84fbfba4ccd6324f0d335a66d07a9cfbf530/server/cmd/mmctl/commands/import.go#L397-L531)
- v9.5부터 local mode는 `mmctl import process --bypass-upload FILE.zip --local`로 서버가 로컬 파일을 직접 인수할 수 있다. 큰 업로드를 피하는 선례지만 별도 인증/파일 권한 경계가 필요하다. 지금 Yona 구현에 이 경로가 있다고 주장하지 않는다. [문서](https://docs.mattermost.com/administration-guide/onboard/bulk-loading-data#using-mmctl-local-mode)
- self-hosted→Cloud 절차는 email 로그인 사용자의 password reset을 요구하고 Channels 외 Playbooks 데이터는 제외한다. 사용자·게시물 export 성공을 credential 또는 모든 제품 데이터 보존과 동일시하지 않는다. [범위/인증 제약](https://docs.mattermost.com/administration-guide/manage/cloud-data-export#migrate-from-self-hosted-to-cloud)
- 최신 문서의 일부 export flag와 개발 소스가 다르므로 실제 데모는 client/server 버전을 고정해야 한다. 위 import 예시는 확인된 공통 절차만 사용한다. [export 문서](https://docs.mattermost.com/administration-guide/manage/bulk-export-tool), [export 소스](https://github.com/mattermost/mattermost/blob/cbdd84fbfba4ccd6324f0d335a66d07a9cfbf530/server/cmd/mmctl/commands/export.go)

## API-first migration CLI: GitHub Enterprise Importer

GHES→GHEC는 Git source와 metadata archive 생성 → storage 업로드 → archive URL로 target migration enqueue → migration ID 조회 순서다. `gh gei`는 이 과정을 orchestration하며 target DB에 직접 쓰지 않는다. [공식 절차](https://docs.github.com/en/migrations/using-github-enterprise-importer/migrating-between-github-products/migrating-repositories-from-github-enterprise-server-to-github-enterprise-cloud)

명령 예시(실행 안 함; GEI 설치, source/target PAT, GHES/storage 설정 전제):

```sh
gh gei migrate-repo \
  --github-source-org SOURCE --source-repo REPO \
  --github-target-org DESTINATION --target-repo NEW_REPO \
  --ghes-api-url https://ghes.example.com/api/v3 --queue-only
gh gei wait-for-migration --migration-id MIGRATION_ID
gh gei download-logs --github-target-org DESTINATION \
  --target-repo NEW_REPO --migration-log-file migration.log
```

- `--queue-only`는 **export/upload/enqueue까지 수행하고 완료 대기만 생략**한다. archive-only 옵션이 아니다. `wait-for-migration` 재실행은 기존 작업 관찰 재개이지 실패한 데이터 적재의 checkpoint resume가 아니다. [CLI 소스](https://github.com/github/gh-gei/blob/b9b3fa175b9bf99b429f3a628b65fd6846459aee/src/gei/Commands/MigrateRepo/MigrateRepoCommand.cs), [wait 소스](https://github.com/github/gh-gei/blob/b9b3fa175b9bf99b429f3a628b65fd6846459aee/src/Octoshift/Commands/WaitForMigration/WaitForMigrationCommandHandler.cs)
- 성공한 migration에도 일부 이슈/댓글 등이 빠진 warning이 있을 수 있다. 로그와 대상 데이터를 별도로 확인해야 하며 download logs는 완료 후 24시간 제공된다. [공식 logs 문서](https://docs.github.com/en/migrations/using-github-enterprise-importer/completing-your-migration-with-github-enterprise-importer/accessing-your-migration-logs-for-github-enterprise-importer)
- 실패 대상의 unlock은 data loss 위험이 있고 삭제 후 retry가 안내된다. 같은 대상 무조건 재실행의 idempotence는 확인되지 않았다. delta migration도 지원하지 않는다. [실패 대응](https://docs.github.com/en/migrations/troubleshooting/troubleshooting-your-migration-with-github-enterprise-importer), [작업 중지 권고](https://docs.github.com/en/migrations/using-github-enterprise-importer/migrating-between-github-products/migrating-repositories-from-github-enterprise-server-to-github-enterprise-cloud#prerequisites)
- commit 이외 활동은 mannequin으로 귀속하고 대상 사용자에게 별도 reclaim한다. 활동 귀속과 repository 접근 권한은 별개다. Git LFS objects·secrets·사용자 repository 접근 권한 등은 GHES repository migration 제외 목록에 있다. **이 구조는 identity 충돌을 명시적으로 처리하는 선례이지 Yona의 사용자/권한/LFS 보존 요구를 줄일 근거가 아니다.** [매핑](https://docs.github.com/en/migrations/using-github-enterprise-importer/completing-your-migration-with-github-enterprise-importer/reclaiming-mannequins-for-github-enterprise-importer), [지원/제외 목록](https://docs.github.com/en/migrations/using-github-enterprise-importer/migrating-between-github-products/about-migrations-between-github-products)

## 파일 이관과 직접 실행의 공존: GitLab / Gitea

| 사례 | CLI의 역할·산출물 | 서버 처리·운영 제약 | Yona에 주는 근거 |
|---|---|---|---|
| Gitea `dump` | 중지된 설치를 ZIP으로 묶음: SQL, repositories, data/config | 공식 restore 명령 없이 수동 파일 배치와 SQL 복원 | offline archive 생성과 DB+파일 동결의 선례. 새 Yona schema로 변환하는 도구는 아님 |
| GitLab instance backup | `gitlab-backup create`로 전체 backup tar 생성 | 정확히 같은 버전·CE/EE에서 restore. DB를 덮어씀 | backup과 migration을 구별하고 version·secret·파일 무결성을 검사 |
| GitLab project export/import | Rake CLI 또는 UI/API로 `.tar.gz`: NDJSON tree, Git bundle, uploads/LFS 등 | API는 비동기 상태 조회. 대형 import Rake CLI는 같은 Sidekiq 작업을 CLI 프로세스 안에서 동기 실행 | queue UI와 CLI 직접 실행이 함께 존재하는 선례. standalone raw DB converter와는 다름 |

근거: [Gitea backup/restore](https://docs.gitea.com/administration/backup-and-restore/), [GitLab instance restore](https://docs.gitlab.com/administration/backup_restore/restore_gitlab/), [GitLab project 형식/범위](https://docs.gitlab.com/user/project/settings/import_export/), [Rake CLI](https://docs.gitlab.com/administration/raketasks/project_import_export/), [ImportTask 소스](https://gitlab.com/gitlab-org/gitlab/-/blob/master/lib/gitlab/import_export/project/import_task.rb)

공식 명령 예시(실행 안 함; 서비스 중지·권한·경로 등 각 문서의 사전조건 필요):

```sh
# Gitea: source archive 생성, 별도 수동 복원
./gitea dump -c /path/to/app.ini

# GitLab: source에서 프로젝트 archive 생성
gitlab-rake "gitlab:import_export:export[root,group/subgroup,project,/backup/project-export.tar.gz]"

# GitLab: target의 애플리케이션 환경에서 직접 import 실행
gitlab-rake "gitlab:import_export:import[root,group/subgroup,imported-project,/backup/project-export.tar.gz]"
```

### 구체적인 운영상 차이

- Gitea는 DB와 Git/file 복사의 시점 차이를 이유로 백업 동안 서버 중지를 요구한다. XORM SQL dump의 복원 문제 가능성 때문에 native DB dump도 안내한다. ZIP 생성 성공만으로 restore 가능성을 증명하지 않는다. [문서](https://docs.gitea.com/administration/backup-and-restore/)
- GitLab 전체 backup의 `STRATEGY=copy`는 변경 중 파일의 tar 읽기 오류를 피하지만 추가 디스크가 필요하다. DB·Git·files 전체의 단일 시점 snapshot 보장으로 해석하면 안 된다. secrets/config, Redis/Sidekiq jobs, 설치 형태에 따라 object-storage blobs 등은 별도 대상이다. [전략/제외 목록](https://docs.gitlab.com/administration/backup_restore/backup_gitlab/)
- GitLab project 파일은 동일 버전 또는 target보다 최대 두 minor 이전 버전에서 export한 파일을 지원한다. 전체 backup의 exact-version 계약과 다르다. 프로젝트 이관은 전체 인스턴스 백업이 아니며 CI artifacts/variables, webhooks 등 제외 항목이 있다. [호환/포함 범위](https://docs.gitlab.com/user/project/settings/import_export/)
- 대형 프로젝트 Rake import는 object-storage upload를 끄고 Sidekiq 작업을 프로세스 내부에서 실행하여 불필요한 업로드/재다운로드와 timeout을 피한다. **CLI 직접 target import를 추가하려는 경우** 이 선례가 있다. 다만 기존 Yona archive-only CLI 약속과는 다른 기능이며 이번 조사로 범위에 추가하지 않는다. [큰 프로젝트](https://docs.gitlab.com/administration/raketasks/project_import_export/#import-large-projects), [실행 구조](https://gitlab.com/gitlab-org/gitlab/-/blob/master/lib/gitlab/import_export/project/import_task.rb)
- GitLab import API는 `finished`여도 `failed_relations`를 반환할 수 있다. 단일 NDJSON record가 50 MB를 넘는 오류도 문서화되어 있다. 성공 상태와 완전성, 행 단위 포맷과 메모리 상한을 구별해야 한다. [API](https://docs.gitlab.com/api/project_import_export/#retrieve-the-status-of-a-project-import), [큰 record 오류](https://docs.gitlab.com/user/project/settings/import_export_troubleshooting/#error-json-exceeds-50-mb-limit)
- GitLab 프로젝트 import의 작성자 보존은 target 사용자 존재·이메일·관리자 조건 등에 의존하고, 조건이 맞지 않으면 importing user로 귀속될 수 있다. 전체 계정/비밀번호 이전을 제공하는 근거가 아니다. Yona는 이러한 자동 대체 대신 기존 명시적 identity mapping 요구를 유지한다. [기여자 보존](https://docs.gitlab.com/user/project/settings/import_export/#preserving-user-contributions)

## Issue 댓글 초안 — 미게시

> CLI migrator 사례를 먼저 조사했습니다. 가장 가까운 구조는 Mattermost의 [`mmetl`](https://github.com/mattermost/mmetl) 변환기와 [`mmctl import`](https://docs.mattermost.com/administration-guide/onboard/bulk-loading-data)의 조합입니다. 원본을 portable JSONL/첨부파일로 변환한 뒤 업로드와 서버의 비동기 import를 분리합니다. [GitHub Enterprise Importer](https://docs.github.com/en/migrations/using-github-enterprise-importer/migrating-between-github-products/migrating-repositories-from-github-enterprise-server-to-github-enterprise-cloud)도 archive 생성/전송 후 migration ID로 서버 작업을 조회합니다.
>
> Queue UI와 CLI 직접 실행의 공존 사례로는 [GitLab project export/import](https://docs.gitlab.com/administration/raketasks/project_import_export/)가 있습니다. UI/API는 비동기로 처리하고 큰 프로젝트용 Rake CLI는 애플리케이션의 import 작업을 프로세스 내부에서 실행합니다. 다만 [GitLab 전체 backup](https://docs.gitlab.com/administration/backup_restore/restore_gitlab/)은 정확히 같은 버전 복원이므로 1.16→2.0 schema migration과 구분해야 합니다.
>
> Yona에서는 앞서 제안한 **중지된 1.16의 read-only DB/files → CLI archive 생성 → 관리자 업로드 → 2.0 queue 검증·복원** 흐름을 기준으로, queue UI와 legacy CLI의 두 데모를 준비하려 합니다. CLI는 target DB에 직접 쓰거나 자동 업로드하지 않습니다. 같은 archive 계약/importer를 사용하되 source schema 변환과 사용자·권한·작성자 mapping을 명시합니다.
>
> 아직 완료된 migration을 주장하는 단계는 아닙니다. JSONL/NDJSON 자체가 bounded-memory를 보장하지 않으므로 5 GiB 이상 제한 heap 검증과 파일 digest/관계/로그인 검증을 포함하겠습니다. 실패 후 job 재조회·upload resume·import 재실행은 서로 다른 기능으로 다루고, target 변경 후 불확실한 실패는 자동 retry하지 않겠습니다. 현재 로컬 importer의 ZIP 형식과 기존 댓글의 tarball 표기도 공개 계약 확정 시 일치시키겠습니다.

