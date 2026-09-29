package com.github.yonaprojects.yona.domain.site

import java.io.InputStream
import java.io.OutputStream

/**
 * 사이트/프로젝트 백업. 아카이브는 ZIP 한 개로 스트리밍하며(전체를 메모리에 올리지 않는다)
 * 다음 구조를 갖는다:
 *
 *  - manifest.json        형식 버전/범위(site|project)/생성 시각
 *  - db/<TABLE>.ndjson    테이블별 NDJSON(행당 JSON 객체 한 줄). 큐 테이블은 제외.
 *  - db/_sequences.json   (site 범위만) 테이블별 auto-increment "다음 값"
 *  - files/data/...       yona.data 앱 파일(단, durable queue 상태와 실행 중 H2 파일 DB는 제외)
 *  - files/git/...        yona.git.base-dir 아래 저장소 디렉터리 전체
 *  - files/lfs/...        yona.lfs.base-dir 아래 LFS 객체
 *  - files/uploads/<hash> 별도 yona.upload.base-dir의 첨부 파일(내용 해시명; data 아래면 files/data에 포함)
 */
interface DataBackupService {
    companion object {
        const val FORMAT = "yona-backup"
        const val FORMAT_VERSION = 2
        const val SCOPE_SITE = "site"
        const val SCOPE_PROJECT = "project"
    }

    /** 사이트 전체(DB + 저장소/LFS/첨부 파일)를 out으로 내보낸다. */
    fun exportSite(out: OutputStream)

    /** 프로젝트 하나와 참조 행/파일만 내보낸다(출장지 반출용). */
    fun exportProject(owner: String, project: String, out: OutputStream)

    /** 사이트 백업은 완전 교체 복원, 프로젝트 백업은 별도 프로젝트로 추가한다(기존 프로젝트 이슈 merge는 아님). */
    fun importSite(input: InputStream)
    fun importProject(input: InputStream): String?

    /** 아카이브 전체를 읽지 않고 manifest의 scope만 확인한다(site|project, 판별 불가면 null). */
    fun backupScope(input: InputStream): String?
}
