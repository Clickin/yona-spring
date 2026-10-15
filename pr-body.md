> 이 영문 초안은 대체되었습니다. 최종 제목·본문은 `docs/pr/fix-svn-hg-readme-edit-guard.md`을 사용합니다.

## Summary

Align unsupported online README entry points with the existing Git-only README writer. SVN README write support is a separate future feature; this change does not implement `SvnRepository.commitTextFile`.

The limited fix hides unsupported README links, rejects direct README form/API/service writes before mutations, and preserves existing README-marked posts when an edit omits or clears the flag. SVN/SUBVERSION and HG/MERCURIAL aliases are rejected; the repository resolver's existing null/unknown default-to-Git behavior is preserved. No schema/data migration, generic capability layer, issue-template writer changes, or SVN commit implementation.

Related discussion: https://github.com/yona-projects/yona/issues/493 (closed; not a closing reference).

## Actual baseline reproduction

Reproduced on `1b6b6e04761cc390c242c183d3a31a533ea75001` using Java 21.0.6, pnpm 12.3.4, SVN 1.14.5, H2 and real headless Chromium. No external notifications were enabled. Dependencies: `pnpm --dir frontend install --ignore-scripts --lockfile=false`. Started `./gradlew bootRun -x npmCi` with h2 profile, localhost port 19202, disposable data/git/svn/hg/lfs directories, `yona.analytics.send-usage=false` and `yona.notification.bymail.enabled=false`. `copyTurbo`/`processResources` remained enabled. Baseline startup logged an unrelated H2 `property.value` DDL warning; the tested routes worked.

1. Created a disposable administrator through `/bootstrap-setup`, logged in and created a Subversion project through `/projectform`.
2. Empty SVN repository home displayed **create README** linking to `postform?readme=true`. The page rendered a normal post form titled `Update README.md`, with **no `readme` field**.
3. Entered `README browser baseline content` and clicked Save. Browser navigated to `post/1`, board count became 1 and displayed that content. `svnlook youngest` remained **0**, tree contained only `/`.
4. Used `svn checkout file://...`, added README.md and `svn commit`: revision **1**, contents `# SVN README baseline\n\nCommitted using the SVN client.\n`.
5. Home displayed the actual README and an **edit** link to the same form. Form prefilled the SVN contents but still had no `readme` field. Submitting its actual FormData using same-origin browser fetch (body replaced) followed a redirect to `post/2`, final HTTP **200**; `readme` was absent.
6. Same form submitted directly with `readme=true` followed a success-looking redirect to project home (final **200**) and created README-marked post 3, while SVN remained revision 1.
7. Authenticated browser API `POST /api/projects/1/posts` with `readme=true` returned **201**, created post 4 with `readme:true`. `PUT .../3` with `readme:false` returned **200**, silently converted the existing marked post to an ordinary post. `PATCH .../4/content` returned **200** and changed its body.
8. After all writes, `svnlook youngest` still returned **1** and `svnlook cat ... README.md` returned the original SVN-client content.

Expected: unsupported README creation/edit requests are rejected before posting/repository side effects; no misleading README editing links. Existing repository README reading and normal board posts remain available.

## Validation

### Actual post-fix browser and repository checks

- Existing SVN README still renders, with no edit link. Empty SVN home has no create link.
- Direct README GET, existing marked-post edit GET, direct README POST, and marked-post edit with `readme=false` all returned **400**, no redirect, and the localized explanatory page.
- API create/PUT/PATCH returned **400** with the explicit explanation. Existing marked post 4 retained `readme:true` and `API PATCH baseline`; rejected calls left the four baseline posts unchanged.
- The next ordinary SVN UI post received number **5**, demonstrating rejected writes did not consume numbers. Ordinary SVN API create returned **201**, PUT and body PATCH **200**.
- Empty SVN direct README POST returned **400** and retained **0** posts and counter **0**.
- Mercurial home has no README link; direct README GET/API create returned **400**, while ordinary create/PUT/PATCH returned **201/200/200**.
- Git README checkbox/link remained visible; editing through the actual visible Markdown editor succeeded and `git show HEAD:README.md` returned `# Git README updated control`. Ordinary Git API posting returned **201**.
- Unauthenticated requests retained the existing permission behavior (HTML forbidden page, API POST **403**), not an unsupported-writer message.
- `svnlook youngest` stayed **1**, with original SVN-client README contents. Offline H2 inspection after stopping the application showed counters SVN=6, Git=2, Hg=1, empty SVN=0; the existing SVN README flag remained true; attachments, notification events, and notification mails were all **0**.

The actual browser run caught an intermediate error-rendering failure that mocked view-name tests missed: exception handling discarded global template attributes and returned 500. The final view path sets HTTP 400 while retaining the original model; an actual Thymeleaf-rendering regression now covers that path. No global model defaults were added to mask it.

For the final isolated restart, the H2 URL included `NON_KEYWORDS=VALUE` and SSH relay was disabled. No production schema change was made. A live H2 AUTO_SERVER inspection could not connect to its advertised address; final database inspection was performed locally after stopping the disposable application.

### Automated checks

Focused H2 regression: **382 tests, 0 failures, 0 errors, 0 skipped** across BoardViewControllerSpec, BoardControllerSpec, ProjectViewControllerSpec, PostingServiceSpec, and ErrorPageTemplateRenderingSpec.

```sh
./gradlew test -x npmCi -Dyona.it.db=h2 \
  --tests 'com.github.yonaprojects.yona.web.BoardViewControllerSpec' \
  --tests 'com.github.yonaprojects.yona.web.BoardControllerSpec' \
  --tests 'com.github.yonaprojects.yona.web.ProjectViewControllerSpec' \
  --tests 'com.github.yonaprojects.yona.domain.board.PostingServiceSpec' \
  --tests 'com.github.yonaprojects.yona.web.ErrorPageTemplateRenderingSpec'
```

Full H2 suite (`./gradlew test -x npmCi -Dyona.it.db=h2`): **465 suites, 6,756 tests, 0 failures, 0 errors, 2 skipped**, completed in 3m29s. Other database profiles were not run for this change.
