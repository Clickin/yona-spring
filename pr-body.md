> 이 영문 초안은 대체되었습니다. 최종 제목·본문은 `docs/pr/fix-vcs-reset-server-confirmation.md`을 사용합니다.

## Summary

Refs #ISSUE_02_NUMBER

- Retain the existing code/history deletion warning, agreement checkbox, confirmation dialog, and manager authorization.
- Explain in English and Korean that this is a repository reset, not history conversion; child forks are disconnected while their repositories, the project ID, issues, boards, and collaboration data remain.
- Require JSON confirmation containing the actual project ID, exact current project name, expected current VCS, and explicit consent. Recheck a freshly reloaded, locked project immediately before mutation.
- Reject incomplete/incorrect confirmation with 400 and stale VCS with 409. Existing authentication/authorization/not-found responses remain.
- Normalize null GIT and existing SVN/HG aliases consistently between the preview and server comparison. Remove the confirmation-free service call.

## Compatibility and scope

Old bodyless POST requests to `/{owner}/{projectName}/changeVCS` intentionally fail. Updated clients must send `{"projectId":123,"projectName":"example","expectedVcs":"GIT","accepted":true}` as JSON.

This first PR retains the existing physical repository deletion/create order. It does **not** provide archive recovery. The separate stacked archive PR addresses filesystem preservation and compensation. No database schema changes or MIRROR code.

## Regression coverage

Controller confirmation/status cases; service revalidation before repository/fork mutation; VCS cycle and null/alias behavior; preserved checkbox/dialog/template elements; actual disposable-project browser reset preserving project ID, issue, and board content.

## Verification

- Parent H2 focused validation: `ProjectServiceImplSpec`, `ProjectChangeVcsConfirmationIntegrationSpec`, and `ProjectViewControllerSpec`: 3 suites, 304 tests, 0 failures/errors/skips.
- Earlier template/controller/service run: 23 suites, 517 tests, 0 failures/errors/skips. The browser subsequently exposed a Hibernate refresh crash not exercised there; the new real Spring/JPA integration spec now covers the fresh locked reload, stale persistence context, and concurrently added fork relationship.
- Actual Chromium smoke against isolated H2/filesystem roots at port 19203: setup plus both VCS-change specs, 3 passed. Bodyless/wrong-name/no-consent requests returned 400, stale VCS returned 409, valid reset returned 204; project ID, issue body and board body remained.
- Parent full H2 JVM suite on frozen PR1: 466 suites, 6,755 tests, 0 failures/errors, 2 skipped (3m45s).
- Full browser suite and supported-DB matrix remain parent-coordinated publication gates; no broader pass is claimed here.

The issue-number placeholder must be replaced with the supplied public issue number before human publication; no issue number has been invented.
