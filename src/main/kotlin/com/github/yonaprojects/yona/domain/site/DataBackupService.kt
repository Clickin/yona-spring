package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.queue.TaskContext
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 사이트/프로젝트 백업. 아카이브는 ZIP 한 개로 스트리밍하며(전체를 메모리에 올리지 않는다)
 * 다음 구조를 갖는다:
 *
 *  - manifest.json        형식 버전/범위(site|project)/생성 시각
 *  - db/<TABLE>.ndjson    테이블별 NDJSON(행당 JSON 객체 한 줄). 큐 테이블은 제외.
 *  - db/_sequences.json   (site 범위만) 테이블별 auto-increment "다음 값"
 *  - files/data/...       yona.data 앱 파일(단, durable queue 상태와 실행 중 H2 파일 DB는 제외)
 *  - files/svn/...        yona.svn.base-dir 아래 SVN 저장소
 *  - files/git/...        yona.git.base-dir 아래 저장소 디렉터리 전체
 *  - files/lfs/...        yona.lfs.base-dir 아래 LFS 객체
 *  - files/uploads/<hash> 별도 yona.upload.base-dir의 첨부 파일(내용 해시명; data 아래면 files/data에 포함)
 *  - integrity.ndjson     manifest/index 제외 모든 엔트리의 path/size/SHA-256
 */
interface DataBackupService {
    companion object {
        const val FORMAT = "yona-backup"
        const val FORMAT_VERSION = 3
        const val TARGET_VERSION = "2.0"
        const val INTEGRITY = "sha256-entries-v1"
        const val LEGACY_CREDENTIALS = "legacy-credentials-sha256-1024"
        const val SCOPE_SITE = "site"
        const val SCOPE_PROJECT = "project"
    }

    /** 사이트 전체(DB + 저장소/LFS/첨부 파일)를 out으로 내보낸다. */
    fun exportSite(out: OutputStream, execution: DataBackupExecution? = null)

    /** 프로젝트 하나와 참조 행/파일만 내보낸다(출장지 반출용). */
    fun exportProject(owner: String, project: String, out: OutputStream, execution: DataBackupExecution? = null)

    /** 사이트 백업은 완전 교체 복원, 프로젝트 백업은 별도 프로젝트로 추가한다(기존 프로젝트 이슈 merge는 아님). */
    fun importSite(input: InputStream, execution: DataBackupExecution? = null)
    fun importProject(input: InputStream, execution: DataBackupExecution? = null): String?
}

/** Per-invocation state; synchronous archive callers have no queue execution context. */
class DataBackupExecution internal constructor(
    private val context: TaskContext,
    private val checkpoints: TransactionTemplate,
) {
    internal val jobId: Long get() = context.jobId
    internal var mutationStarted = false
        private set
    private var stage = "archive"
    private var rows = 0L
    private var bytes = 0L
    private var lastCheckpoint = 0L

    internal fun stage(value: String) {
        stage = value
        checkpoint()
    }

    internal fun row() {
        rows++
        if (rows % 256L == 0L || checkpointDue()) checkpoint()
    }

    internal fun copied(count: Int) {
        val previous = bytes
        bytes += count
        if (bytes / 1_048_576L != previous / 1_048_576L || checkpointDue()) checkpoint()
    }

    internal fun beginMutation() {
        checkpoint()
        mutationStarted = true
    }

    internal fun checkpoint() {
        // Restore transactions must not retain queue row locks and prevent heartbeats/cancellation.
        checkpoints.executeWithoutResult {
            context.checkpoint()
            context.progress(stage, mapOf("rows" to rows, "bytes" to bytes))
        }
        lastCheckpoint = System.nanoTime()
    }

    private fun checkpointDue() = System.nanoTime() - lastCheckpoint >= TimeUnit.SECONDS.toNanos(1)
}

internal fun copyArchiveBytes(input: InputStream, output: OutputStream, execution: DataBackupExecution?) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        execution?.copied(count)
    }
}
