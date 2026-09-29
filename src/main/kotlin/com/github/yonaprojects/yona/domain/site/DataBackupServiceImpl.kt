package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.queue.isQueueTable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

/** 대기·실행 중인 작업 큐 작업이 있어 import를 거부할 때 던진다(IllegalArgumentException 서브타입이 아니다). */
class UnfinishedQueueJobsException(message: String) : RuntimeException(message)

/** 백업 아카이브가 yona 백업 형식이 아니거나 손상됐을 때 던진다. */
class BadBackupArchiveException(message: String) : RuntimeException(message)

/**
 * 사이트/프로젝트 백업 — data/DataService.java + exchangers 44개의 범용 대응. yona는 테이블별
 * 전용 Exchanger를 손으로 나열해 유지하지만, 여기서는 DB 메타데이터로 테이블·PK·FK를 스스로
 * 찾아낸다 — 엔티티가 추가돼도 이 서비스는 수정할 필요가 없다.
 *
 * 아카이브는 ZIP 한 개를 스트리밍하며(전체를 메모리에 올리지 않는다) 다음 구조를 갖는다:
 *
 *  - manifest.json        형식 버전/범위(site|project)/생성 시각
 *  - db/<table>.ndjson    테이블별 NDJSON(행당 JSON 객체 한 줄). 큐 테이블은 제외.
 *  - db/_sequences.json   (site 범위만) 테이블별 auto-increment "다음 값"
 *  - files/data/...       yona.data 앱 파일(단, 파일 기반 durable queue 상태와 실행 중 H2 파일 DB는 제외)
 *  - files/git/...        yona.git.base-dir 아래 저장소 디렉터리 전체
 *  - files/lfs/...        yona.lfs.base-dir 아래 LFS 객체
 *  - files/uploads/<hash> yona.data 밖에 설정된 경우의 첨부 파일
 *
 * yona.upload.base-dir가 yona.data 아래면 첨부 파일은 files/data 안에 포함된다.
 *
 * 사이트 import는 모든 테이블을 DELETE 후 다시 INSERT하는 완전 교체다. yona처럼 export 시점의
 * auto-increment "다음 값"을 캡처해 복원 시 그대로 되돌려 ID 갭을 보존한다.
 *
 * 프로젝트 export는 해당 프로젝트 행에서 FK로 도달 가능한 모든 행의 폐쇄곡선(출장지 반출용)이다.
 * attachment는 container_id가 FK가 아니라 문자열 컬럼이라 폐쇄곡선에 안 걸리므로, 내보낸 행의
 * (container_type, id) 조합으로 별도 수집한다.
 *
 * 프로젝트 import(merge)는 본사 사이트에 합치기 위한 것으로, 폐쇄곡선 행의 PK를 대상 사이트에서
 * 새로 채번해 재매핑한다. n4user는 login_id로 기존 사용자와 매칭해 재사용하고(중복 생성 금지),
 * attachment.container_id는 컨테이너 테이블의 재매핑을 따라간다. 이름 충돌 시 `<name>-imported-<ts>`로
 * 별도 프로젝트를 만들고, 미완료 큐 작업이 있으면 모든 import를 거부한다(`UnfinishedQueueJobsException`).
 */
@Service
class DataBackupServiceImpl(
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
    @Value("\${yona.data:data}") private val applicationDataDir: File,
    @Value("\${yona.queue.data-dir:\${yona.data:data}/queue}") private val queueDataDir: File,
    @Value("\${yona.git.base-dir:/tmp/yona/git}") private val gitBaseDir: File,
    @Value("\${yona.lfs.base-dir:/tmp/yona/lfs}") private val lfsBaseDir: File,
    @Value("\${yona.upload.base-dir:/tmp/yona/uploads}") private val uploadBaseDir: File,
) : DataBackupService {

    private val logger = LoggerFactory.getLogger(DataBackupServiceImpl::class.java)
    private val sharedReferenceTables = setOf("n4user", "role", "organization")
    private val jdbcTemplate = JdbcTemplate(dataSource)

    private enum class Dialect { MYSQL_COMPATIBLE, POSTGRES, H2, CUBRID, OTHER }

    override fun exportSite(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            writeManifest(zip, DataBackupService.SCOPE_SITE, null)
            // Preserve intentionally empty roots so a full restore can remove stale destination files.
            val uploadsInsideData = isWithin(uploadBaseDir, applicationDataDir)
            val roots = listOf("git", "lfs", "data") + if (uploadsInsideData) emptyList() else listOf("uploads")
            for (name in roots) {
                zip.putNextEntry(ZipEntry("files/$name/"))
                zip.closeEntry()
            }
            if (uploadsInsideData) {
                zip.putNextEntry(ZipEntry("files/data/uploads/"))
                zip.closeEntry()
            }
            val dialect = detectDialect()
            val sequences = LinkedHashMap<String, Long>()
            // 트랜잭션 안에서 읽어야 MariaDB/MySQL 드라이버가 fetchSize대로 커서 스트리밍한다.
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val tables = listTables(connection).filterNot { isQueueTable(it) }
                for (table in tables) {
                    val key = table.lowercase()
                    nextSequenceValue(connection, table, dialect)?.let { sequences[key] = it }
                    zip.putNextEntry(ZipEntry("db/$key.ndjson"))
                    val statement = connection.createStatement().apply { fetchSize = STREAM_FETCH_SIZE }
                    statement.executeQuery("SELECT * FROM ${exportTableRef(table, dialect)}").use { rs ->
                        val columns = columnLabels(rs)
                        while (rs.next()) {
                            zip.write(objectMapper.writeValueAsBytes(rowMap(rs, columns)))
                            zip.write('\n'.code)
                        }
                    }
                    statement.close()
                    zip.closeEntry()
                }
                zipEntry(zip, "db/_sequences.json") { it.write(objectMapper.writeValueAsBytes(sequences)) }
            }
            addTreeToZip(zip, gitBaseDir, "files/git")
            addTreeToZip(zip, lfsBaseDir, "files/lfs")
            addApplicationDataToZip(zip)
            if (!uploadsInsideData) addTreeToZip(zip, uploadBaseDir, "files/uploads")
        }
        logger.info("사이트 전체 백업 완료")
    }

    override fun exportProject(owner: String, project: String, out: OutputStream) {
        val seed = jdbcTemplate.queryForList(
            "SELECT * FROM project WHERE name = ? AND owner = ?", project, owner
        ).firstOrNull() ?: throw NoSuchElementException("Project not found: $owner/$project")

        val tempRoot = createTempDirectory()
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val rows = foreignKeyClosure(connection, seed, tempRoot)
                addReferencedUsers(connection, rows)
                addContainerAttachments(connection, rows)

                ZipOutputStream(out).use { zip ->
                    writeManifest(zip, DataBackupService.SCOPE_PROJECT, "$owner/$project")
                    for ((table, file) in rows) {
                        zip.putNextEntry(ZipEntry("db/$table.ndjson"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    // 파일들: DB 폐쇄곡선의 attachment 해시 + 이 프로젝트의 git/wiki/LFS 디렉터리.
                    rows["attachment"]?.let { attachmentFile ->
                        forEachNdjsonRow(attachmentFile) { row ->
                            val hash = row.value("hash")?.toString() ?: return@forEachNdjsonRow
                            val file = File(uploadBaseDir, hash)
                            if (file.isFile) addFileToZip(zip, file, "files/uploads/$hash")
                        }
                    }
                    for (repo in listOf("$owner/$project.git", "$owner/$project.wiki.git")) {
                        addTreeToZip(zip, File(gitBaseDir, repo), "files/git/$repo")
                    }
                    addTreeToZip(zip, File(lfsBaseDir, "$owner/$project"), "files/lfs/$owner/$project")
                }
                logger.info("프로젝트 백업 완료: $owner/$project (테이블 ${rows.size}개)")
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    override fun backupScope(input: InputStream): String? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == "manifest.json") {
                    val manifest = objectMapper.readValue(zip.readBytes(), Map::class.java) as Map<String, Any?>
                    return manifest["scope"]?.toString()
                }
                zip.closeEntry()
            }
        }
    }

    @Transactional
    override fun importSite(input: InputStream) {
        val archive = readArchive(input, DataBackupService.SCOPE_SITE)
        try {
            val tables = archive.tables.filterKeys { !isQueueTable(it) }
            restoreTables(tables, archive.sequences)
            swapInImportedFiles(archive)
            logger.info("사이트 전체 복원 완료: 테이블 ${tables.size}개")
        } finally {
            archive.tempRoot.deleteRecursively()
        }
    }

    @Transactional
    override fun importProject(input: InputStream): String? {
        val archive = readArchive(input, DataBackupService.SCOPE_PROJECT)
        try {
            val tables = archive.tables
            val projectFile = tables["project"] ?: throw BadBackupArchiveException("프로젝트 백업에 project 행이 없습니다")
            val projectName = firstNdjsonRow(projectFile)?.value("name")?.toString()
                ?: throw BadBackupArchiveException("프로젝트 백업의 project 행에 name이 없습니다")

            refuseIfQueueBusy()
            val nameExists = (jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project WHERE name = ?", Long::class.java, projectName
            ) ?: 0L) > 0
            val effectiveName = if (nameExists) {
                var suffix = Instant.now().toEpochMilli()
                var candidate: String
                do {
                    val postfix = "-imported-${suffix++}"
                    candidate = projectName.take((250 - postfix.length).coerceAtLeast(1)) + postfix
                } while ((jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM project WHERE name = ?", Long::class.java, candidate
                    ) ?: 0L) > 0)
                logger.info("같은 이름의 프로젝트가 있어 '$projectName'를 '$candidate' 이름으로 가져옵니다")
                candidate
            } else projectName
            archive.project = "${archive.project?.substringBefore('/')}/$effectiveName"

            val metadata = dataSource.connection.use { it.metaData }
            val fks = foreignKeys(metadata, tables.keys)
            val referenceTables = sharedReferenceTables
            val offsets = HashMap<String, Long>()
            val reservedNext = HashMap<String, Long>()
            val referenceIds = HashMap<String, HashMap<String, Any?>>()
            // ponytail: only referenced shared identities remain in RAM; use a DB staging table if contributor count grows unusually large.
            val dialect = detectDialect()

            // Cursor-scan numeric PKs once. A constant table offset removes the O(rows) old→new map.
            for ((table, file) in tables) {
                if (table in referenceTables) continue
                val pk = primaryKeyColumns(metadata, table)
                if (pk.size != 1) continue
                if (!isNumericColumn(metadata, table, pk[0])) {
                    throw BadBackupArchiveException("프로젝트 테이블 $table 에 지원하지 않는 단일 비숫자 PK가 있습니다")
                }
                // A dependent table whose PK is also an FK must inherit its parent's offset.
                if (fks[table].orEmpty().any { fk -> fk.fkColumns.any { it.equals(pk[0], true) } }) continue
                var minimum: Long? = null
                var maximum: Long? = null
                forEachNdjsonRow(file) { row ->
                    val id = row.value(pk[0])?.toString()?.toLongOrNull()
                        ?: throw BadBackupArchiveException("$table.${pk[0]} PK가 정수가 아닙니다")
                    minimum = minimum?.coerceAtMost(id) ?: id
                    maximum = maximum?.coerceAtLeast(id) ?: id
                }
                if (minimum != null) {
                    val targetMax = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(${pk[0]}), 0) FROM $table", Long::class.java
                    ) ?: 0L
                    val nextSequence = dataSource.connection.use { nextSequenceValue(it, table, dialect) }
                    val highWater = maxOf(targetMax, (nextSequence ?: Math.addExact(targetMax, 1L) - 1L))
                    val offset = Math.addExact(Math.subtractExact(highWater, minimum!!), 1L)
                    val next = Math.addExact(Math.addExact(maximum!!, offset), 1L)
                    offsets[table] = offset
                    reservedNext[table] = next
                }
            }
            // Natural-key references are the only old→new id map; it scales with project contributors,
            // not with the number of issues/comments. Numeric project-owned ids use table offsets.
            doInForeignKeysOff(dialect) {
                for (table in listOf("role", "organization", "n4user")) {
                    val file = tables[table] ?: continue
                    val pk = primaryKeyColumns(metadata, table)
                    if (pk.size != 1 || !isNumericColumn(metadata, table, pk[0])) {
                        throw BadBackupArchiveException("${table}에는 숫자 단일 PK가 필요합니다")
                    }
                    val naturalKey = if (table == "n4user") "login_id" else "name"
                    val targetMax = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(${pk[0]}), 0) FROM $table", Long::class.java
                    ) ?: 0L
                    val nextSequence = if (table == "role") null else
                        dataSource.connection.use { nextSequenceValue(it, table, dialect) }
                    var nextId = maxOf(targetMax, (nextSequence ?: Math.addExact(targetMax, 1L) - 1L))
                    val idMap = referenceIds.getOrPut(table) { HashMap() }
                    forEachNdjsonRow(file) { row ->
                        val oldId = row.value(pk[0])?.toString()
                            ?: throw BadBackupArchiveException("$table PK가 없습니다")
                        val naturalValue = row.value(naturalKey)?.toString()
                            ?: throw BadBackupArchiveException("$table.${naturalKey}가 없습니다")
                        val existing = jdbcTemplate.queryForList(
                            "SELECT ${pk[0]} FROM $table WHERE LOWER($naturalKey) = ?", naturalValue.lowercase()
                        ).firstOrNull()
                        if (existing != null) {
                            idMap[oldId] = (existing.values.first() as Number).toLong()
                        } else {
                            nextId = Math.addExact(nextId, 1L)
                            val rowToInsert = HashMap(row).apply { putValue(pk[0], nextId) }
                            insertRow(table, rowToInsert, dateTimeColumns(table))
                            idMap[oldId] = nextId
                        }
                    }
                }

                // Pass 2: each NDJSON row is read, remapped and inserted before moving to the next row.
                for ((table, file) in tables) {
                    if (table in referenceTables) continue
                    val dateTimes = dateTimeColumns(table)
                    val tableFks = fks[table].orEmpty()
                    val pk = primaryKeyColumns(metadata, table)
                    forEachNdjsonRow(file) { row ->
                        val remapped = HashMap(row)
                        for (fk in tableFks) {
                            for (column in fk.fkColumns) {
                                val old = remapped.value(column) ?: continue
                                remapped.putValue(column, remapProjectId(fk.pkTable, old, referenceIds, offsets, true))
                            }
                        }
                        for ((column, value) in remapped.toMap()) {
                            val lower = column.lowercase()
                            val userReference = lower == "user_id" || lower.endsWith("_user_id") ||
                                lower == "author_id" || lower.endsWith("_author_id")
                            if (userReference && tableFks.none { fk -> fk.fkColumns.any { it.equals(column, true) } }) {
                                remapped.putValue(column, remapProjectId("n4user", value, referenceIds, offsets, true))
                            }
                        }
                        if (table == "project") remapped.putValue("name", effectiveName)
                        if (pk.size == 1) offsets[table]?.let { offset ->
                            val old = remapped.value(pk[0])?.toString()?.toLongOrNull()
                                ?: throw BadBackupArchiveException("$table.${pk[0]} PK가 정수가 아닙니다")
                            remapped.putValue(pk[0], Math.addExact(old, offset))
                        }
                        if (table == "attachment") remapAttachmentContainer(remapped, referenceIds, offsets)
                        insertRow(table, remapped, dateTimes)
                    }
                }

                // Explicit inserts do not advance every dialect's generated-id sequence.
                for ((table, reserved) in reservedNext) {
                    val pk = primaryKeyColumns(metadata, table).single()
                    val afterInsert = (jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX($pk), 0) FROM $table", Long::class.java
                    ) ?: 0L) + 1
                    restoreSequence(table, pk, dialect, maxOf(reserved, afterInsert))
                }
                for (table in listOf("n4user", "organization")) {
                    if (tables.containsKey(table)) {
                        val pk = primaryKeyColumns(metadata, table).single()
                        val next = (jdbcTemplate.queryForObject(
                            "SELECT COALESCE(MAX($pk), 0) FROM $table", Long::class.java
                        ) ?: 0L) + 1
                        restoreSequence(table, pk, dialect, next)
                    }
                }
            }
            swapInImportedFiles(archive)
            logger.info("프로젝트 가져오기 완료: 테이블 ${tables.size}개")
            return archive.project
        } finally {
            archive.tempRoot.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- 아카이브 읽기

    private data class ArchiveFile(val entryName: String, val target: File)

    private data class Archive(
        val scope: String,
        val originalProject: String?,
        var project: String?,
        val tables: LinkedHashMap<String, File>,
        val sequences: Map<String, Long>,
        val files: List<ArchiveFile>,
        val tempRoot: File,
    )

    private fun readArchive(input: InputStream, expectedScope: String): Archive {
        val tables = LinkedHashMap<String, File>()
        val files = mutableListOf<ArchiveFile>()
        var sequences: Map<String, Long> = emptyMap()
        var scope: String? = null
        var project: String? = null
        val tempRoot = createTempDirectory()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "manifest.json" -> {
                        val manifest = objectMapper.readValue(zip.readBytes(), Map::class.java) as Map<String, Any?>
                        scope = manifest["scope"]?.toString()
                        project = manifest["project"]?.toString()
                        if (manifest["format"] != DataBackupService.FORMAT || (manifest["formatVersion"] as? Number)?.toInt() != DataBackupService.FORMAT_VERSION) {
                            throw BadBackupArchiveException(
                                "지원하지 않는 백업 형식입니다: ${manifest["format"]}/${manifest["formatVersion"]}"
                            )
                        }
                    }

                    entry.name.startsWith("db/") && entry.name.endsWith(".ndjson") -> {
                        val table = entry.name.removePrefix("db/").removeSuffix(".ndjson").lowercase()
                        if (!table.matches(Regex("[a-z0-9_]+"))) {
                            throw BadBackupArchiveException("잘못된 테이블 이름입니다: $table")
                        }
                        if (tables.containsKey(table)) throw BadBackupArchiveException("중복된 테이블 엔트리입니다: $table")
                        val tableFile = File(tempRoot, "db/$table.ndjson").canonicalFile
                        if (!tableFile.path.startsWith(tempRoot.canonicalFile.path + File.separator)) {
                            throw BadBackupArchiveException("잘못된 테이블 경로입니다: ${entry.name}")
                        }
                        tableFile.parentFile?.mkdirs()
                        tableFile.outputStream().use { zip.copyTo(it) }
                        tables[table] = tableFile
                    }

                    entry.name == "db/_sequences.json" -> {
                        sequences = (objectMapper.readValue(zip.readBytes(), Map::class.java) as Map<String, Any?>)
                            .mapValues { (_, value) -> (value as Number).toLong() }
                    }

                    entry.name.startsWith("files/") -> extractArchiveFile(zip, entry, tempRoot, files)
                }
                zip.closeEntry()
            }
        }
        if (scope == null) throw BadBackupArchiveException("백업 아카이브에 manifest.json이 없습니다")
        if (scope != expectedScope) {
            throw BadBackupArchiveException(
                if (scope == DataBackupService.SCOPE_PROJECT) "프로젝트 백업입니다. 사이트 전체 가져오기로 복원할 수 없습니다."
                else "사이트 전체 백업입니다. 이 화면에서는 프로젝트 백업만 가져올 수 없습니다."
            )
        }
        if (scope == DataBackupService.SCOPE_PROJECT) {
            val parts = project?.split('/')
            if (parts == null || parts.size != 2 || parts.any { segment ->
                    segment.isBlank() || segment == "." || segment == ".." || segment.any { it == '\\' || it == ':' || it == '\u0000' }
                }
            ) {
                throw BadBackupArchiveException("프로젝트 백업 manifest의 owner/name 경로가 잘못됐습니다")
            }
        }
        return Archive(scope, project, project, tables, sequences, files, tempRoot)
    }

    private fun extractArchiveFile(
        zip: ZipInputStream, entry: ZipEntry, tempRoot: File, files: MutableList<ArchiveFile>,
    ) {
        val relative = entry.name.removePrefix("files/")
        val target = File(tempRoot, relative).canonicalFile
        // zip-slip 방지: 임시 루트 밖으로 나가는 경로는 거부한다.
        if (!target.path.startsWith(tempRoot.canonicalFile.path + File.separator)) {
            throw BadBackupArchiveException("백업 아카이브의 파일 경로가 허용 범위를 벗어납니다: ${entry.name}")
        }
        if (entry.isDirectory) {
            target.mkdirs()
            return
        }
        target.parentFile?.mkdirs()
        target.outputStream().use { zip.copyTo(it) }
        files.add(ArchiveFile(relative, target))
    }

    private fun replaceApplicationData(source: File, replace: (File, File) -> Unit) {
        val rootPath = applicationDataDir.canonicalFile.toPath()
        val queuePath = queueDataDir.canonicalFile.toPath()
        val protectedRoots = mutableSetOf("h2") // The running H2 database is restored through logical table rows.
        if (queuePath != rootPath && queuePath.startsWith(rootPath)) {
            protectedRoots += rootPath.relativize(queuePath).getName(0).toString()
        }
        applicationDataDir.mkdirs()
        val incoming = source.listFiles().orEmpty().associateBy { it.name }
        for (current in applicationDataDir.listFiles().orEmpty()) {
            if (current.name !in protectedRoots && current.name !in incoming) current.deleteRecursively()
        }
        for ((name, file) in incoming) {
            if (name !in protectedRoots) replace(file, File(applicationDataDir, name))
        }
    }

    /** 임시 디렉터리에 받둔 파일들을 실제 위치로 옮긴다. site는 루트 통째 교체, project는 해당 프로젝트만. */
    private fun swapInImportedFiles(archive: Archive) {
        if (archive.files.isEmpty() && archive.scope != DataBackupService.SCOPE_SITE) return
        var swapped = 0
        fun replaceDir(source: File, destination: File) {
            val parent = destination.absoluteFile.parentFile ?: throw IllegalStateException("No parent for $destination")
            parent.mkdirs()
            val stage = File(parent, ".${destination.name}.restore-${java.util.UUID.randomUUID()}")
            val backup = File(parent, ".${destination.name}.backup-${java.util.UUID.randomUUID()}")
            if (!source.renameTo(stage)) source.copyRecursively(stage, overwrite = true)
            val hadDestination = destination.exists()
            if (hadDestination && !destination.renameTo(backup)) {
                stage.deleteRecursively()
                throw IllegalStateException("Could not stage existing directory: $destination")
            }
            try {
                if (!stage.renameTo(destination)) throw IllegalStateException("Could not install restored directory: $destination")
            } catch (failure: Throwable) {
                if (hadDestination && backup.exists()) backup.renameTo(destination)
                throw failure
            } finally {
                stage.deleteRecursively()
            }
            backup.deleteRecursively()
        }
        when (archive.scope) {
            DataBackupService.SCOPE_SITE -> {
                for (name in listOf("git", "lfs", "data", "uploads")) {
                    val source = File(archive.tempRoot, name)
                    if (source.exists()) {
                        when (name) {
                            "git" -> replaceDir(source, gitBaseDir)
                            "lfs" -> replaceDir(source, lfsBaseDir)
                            "data" -> replaceApplicationData(source, ::replaceDir)
                            else -> replaceDir(source, uploadBaseDir)
                        }
                        swapped++
                    }
                }
            }

            DataBackupService.SCOPE_PROJECT -> {
                // 아카이브 엔트리는 원본 owner/name으로 들어온다. DB 프로젝트는 이름 충돌 시
                // 실효 이름으로 바뀌므로, 저장소/LFS 목적지도 실효 이름으로 매핑해야 한다 —
                // 그렇지 않으면 충돌 merge가 기존 본사 저장소를 원본 이름으로 덮어써버린다.
                val originalOwner = archive.originalProject?.substringBefore('/')
                val originalName = archive.originalProject?.substringAfter('/')
                val effectiveOwner = archive.project?.substringBefore('/')
                val effectiveName = archive.project?.substringAfter('/')
                if (originalOwner == null || originalName == null || effectiveOwner == null || effectiveName == null) return

                val gitSource = File(archive.tempRoot, "git/$originalOwner")
                if (gitSource.isDirectory) {
                    for (repo in gitSource.listFiles()!!) {
                        // <name>.git / <name>.wiki.git → 실효 이름 기반 목적지로 치환
                        val withoutGit = repo.name.removeSuffix(".git")
                        val isWiki = withoutGit.endsWith(".wiki")
                        val base = if (isWiki) withoutGit.removeSuffix(".wiki") else withoutGit
                        val destinationName = base.replaceFirst(originalName, effectiveName) + (if (isWiki) ".wiki" else "") + ".git"
                        replaceDir(repo, File(gitBaseDir, "$effectiveOwner/$destinationName"))
                        swapped++
                    }
                }
                val lfsSource = File(archive.tempRoot, "lfs/$originalOwner/$originalName")
                if (lfsSource.isDirectory) {
                    replaceDir(lfsSource, File(lfsBaseDir, "$effectiveOwner/$effectiveName"))
                    swapped++
                }
            }
        }
        if (archive.scope == DataBackupService.SCOPE_PROJECT) {
            for (file in archive.files.filter { it.entryName.startsWith("uploads/") }) {
                val destination = File(uploadBaseDir, file.entryName.removePrefix("uploads/"))
                destination.parentFile?.mkdirs()
                // 내용 해시명이라 대상이 이미 있으면 같은 내용이다 — 재사용한다.
                if (destination.isFile || file.target.renameTo(destination)) swapped++
            }
        }
        logger.info("백업 파일 복원 완료: ${swapped}개 항목")
    }

    // ---------------------------------------------------------------- 프로젝트 폐쇄곡선

    private data class Fk(val fkTable: String, val pkTable: String, val fkColumns: List<String>, val pkColumns: List<String>)

    /**
     * 공유 참조 데이터(사용자·역할·조직·사용자 보안 행) — 프로젝트 백업에는 "참조된 행"만 담고
     * 이 테이블들에서 더 탐색하지 않는다. n4user를 통해 project_user → project B(다른 프로젝트)
     * 나 사용자 보안/기기 행 전체가 새어드는 것을 막는다(site 격리의 핵심).
     */
    private val terminalTables = setOf(
        "n4user", "role", "organization", "email", "ssh_key", "gpg_key", "gpg_key_verified_email",
        "api_token", "api_token_scope", "user_known_device", "user_setting", "user_backup_code",
        "user_totp_credential", "user_verification", "user_webauthn_credential",
        "oauth_authorization", "oauth_authorization_consent", "oauth_registered_client",
        "saml2_sso_settings", "oidc_sso_settings", "audit_log", "property",
    )

    /** attachment.container_id(문자열 컬럼, FK 아님)가 가리키는 컨테이너 테이블 매핑. */
    private val containerTables: Map<String, List<String>> = mapOf(
        "project" to listOf("project", "project_setting"),
        "issue" to listOf("issue_post"),
        "posting" to listOf("board_post"),
        "milestone" to listOf("milestone"),
        "issue_comment" to listOf("issue_comment"),
        "posting_comment" to listOf("nonissue_comment"),
        "comment_thread" to listOf("comment_thread"),
        "review_comment" to listOf("review_comment"),
        "issue_label" to listOf("issue_label", "label"),
        "issue_label_category" to listOf("issue_label_category"),
        "webhook" to listOf("webhook"),
        "n4user" to listOf("user", "user_avatar"),
    )

    private fun foreignKeys(metadata: DatabaseMetaData, tables: Collection<String>): Map<String, List<Fk>> {
        data class Part(val seq: Int, val pkTable: String, val fkColumn: String, val pkColumn: String)

        val grouped = HashMap<String, LinkedHashMap<String, MutableList<Part>>>()
        for (table in tables) {
            for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
                metadata.getImportedKeys(null, null, candidate).use { rs ->
                    while (rs.next()) {
                        val pkTable = rs.getString("PKTABLE_NAME")
                        if (isQueueTable(pkTable)) continue
                        val name = rs.getString("FK_NAME") ?: "fk_${rs.getInt("KEY_SEQ")}"
                        grouped.getOrPut(table.lowercase()) { LinkedHashMap() }
                            .getOrPut("$candidate/$name") { mutableListOf() }
                            .add(Part(rs.getInt("KEY_SEQ"), pkTable, rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")))
                    }
                }
            }
        }
        val result = HashMap<String, List<Fk>>()
        for ((table, byName) in grouped) {
            result[table] = byName.values.map { parts ->
                val sorted = parts.sortedBy { it.seq }
                Fk(table.lowercase(), sorted.first().pkTable.lowercase(), sorted.map { it.fkColumn }, sorted.map { it.pkColumn })
            }
        }
        return result
    }
    private fun foreignKeyClosure(
        connection: Connection,
        seed: Map<String, Any?>,
        spoolRoot: File,
    ): LinkedHashMap<String, File> {
        val metadata = connection.metaData
        val allTables = listTables(connection).filterNot { isQueueTable(it) }
        val tableNames = allTables.associateBy { it.lowercase() }
        val fks = foreignKeys(metadata, allTables)
        val files = LinkedHashMap<String, File>()
        val writers = HashMap<String, java.io.BufferedWriter>()
        val seen = HashMap<String, HashSet<String>>()

        fun pkColumnsOf(table: String): List<String> = primaryKeyColumns(metadata, tableNames[table] ?: table)

        fun traversalRow(table: String, row: Map<String, Any?>): Map<String, Any?> {
            val columns = (pkColumnsOf(table) + fks[table].orEmpty().flatMap { it.fkColumns })
                .map { it.lowercase() }.toSet()
            return row.filterKeys { it.lowercase() in columns }
        }

        fun add(table: String, row: Map<String, Any?>): Boolean {
            val key = table.lowercase()
            val identity = pkColumnsOf(key).joinToString("\u0000") { row.value(it)?.toString() ?: "" }
            if (!seen.getOrPut(key) { HashSet() }.add(identity)) return false
            val file = files.getOrPut(key) { File(spoolRoot, "db/$key.ndjson").also { it.parentFile?.mkdirs() } }
            val writer = writers.getOrPut(key) { java.io.FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8) }
            writer.write(objectMapper.writeValueAsString(if (key == "n4user") projectUserReference(row) else row))
            writer.newLine()
            return true
        }

        val queue = ArrayDeque<Pair<String, Map<String, Any?>>>()
        if (add("project", seed)) queue.add("project" to traversalRow("project", seed))

        try {
            while (queue.isNotEmpty()) {
                val (table, row) = queue.removeFirst()
                // 공유 참조 데이터는 행만 포함한다 — 자식/부모 탐색을 계속하면 다른 프로젝트와
                // 사용자 보안 행 전체가 따라 들어온다.
                if (table in terminalTables) continue
                for (fk in fks[table].orEmpty()) {
                    // 나가는 방향: 이 행이 참조하는 부모 행도 함께 내보낸다(작성자 사용자 등).
                    val values = fk.fkColumns.map { row.value(it) }
                    if (values.any { it == null }) continue
                    val identity = values.joinToString("\u0000")
                    if (seen.getOrPut(fk.pkTable) { HashSet() }.contains(identity)) continue
                    val parentTable = tableNames[fk.pkTable] ?: continue
                    val where = fk.pkColumns.joinToString(" AND ") { "$it = ?" }
                    jdbcTemplate.queryForList("SELECT * FROM $parentTable WHERE $where", *values.toTypedArray())
                        .forEach { parent ->
                            if (add(fk.pkTable, parent)) queue.add(fk.pkTable to traversalRow(fk.pkTable, parent))
                        }
                }
                for ((childTable, childFks) in fks) {
                    // 들어오는 방향: 이 행을 참조하는 자식 행도 함께 내보낸다(이슈, 댓글 등).
                    for (child in childFks.filter { it.pkTable == table }) {
                        // project → project 자기 참조(fork)는 별개 프로젝트다 — 폐쇄곡선에 넣지 않는다.
                        if (childTable == "project" && table == "project") continue
                        val childValues = child.pkColumns.map { row.value(it) }
                        if (childValues.any { it == null }) continue
                        val childName = tableNames[childTable] ?: continue
                        val where = child.fkColumns.joinToString(" AND ") { "$it = ?" }
                        connection.prepareStatement("SELECT * FROM $childName WHERE $where").use { statement ->
                            statement.fetchSize = STREAM_FETCH_SIZE
                            childValues.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                            statement.executeQuery().use { rs ->
                                val columns = columnLabels(rs)
                                while (rs.next()) {
                                    val childRow = rowMap(rs, columns)
                                    if (add(childTable, childRow)) {
                                        queue.add(childTable to traversalRow(childTable, childRow))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            writers.values.forEach { it.close() }
        }
        return files
    }
    private fun projectUserReference(row: Map<String, Any?>): Map<String, Any?> = HashMap<String, Any?>().apply {
        row.filterKeys { it.lowercase() in setOf("id", "name", "english_name", "login_id", "email", "lang", "created_date") }
            .forEach { (key, value) -> put(key, value) }
        putValue("state", "ACTIVE")
        putValue("remember_me", false)
        putValue("is_guest", false)
        putValue("failed_login_attempts", 0)
    }

    /** FK 폐쇄곡선만으로는 container_id를 따라가지 못하므로 첨부를 별도로 spool한다. */
    private fun addContainerAttachments(connection: Connection, rows: LinkedHashMap<String, File>) {
        val metadata = connection.metaData
        val existing = HashSet<String>()
        rows["attachment"]?.let { file -> forEachNdjsonRow(file) { it.value("id")?.toString()?.let(existing::add) } }
        for ((table, typeValues) in containerTables) {
            val file = rows[table] ?: continue
            val pkColumn = primaryKeyColumns(metadata, table).firstOrNull() ?: continue
            val ids = LinkedHashSet<String>()
            forEachNdjsonRow(file) { row -> row.value(pkColumn)?.toString()?.let(ids::add) }
            for (chunk in ids.chunked(500)) {
                val typePlaceholders = typeValues.joinToString(",") { "?" }
                val placeholders = chunk.joinToString(",") { "?" }
                val sql = "SELECT * FROM attachment WHERE LOWER(container_type) IN ($typePlaceholders) AND container_id IN ($placeholders)"
                forEachJdbcRow(connection, sql, typeValues + chunk) { row ->
                    val id = row.value("id")?.toString() ?: return@forEachJdbcRow
                    if (existing.add(id)) appendNdjsonRow(rows, "attachment", row)
                }
            }
        }
    }

    /** issue.author_id처럼 FK 제약 없이 비정규화된 사용자 참조도 함께 spool한다. */
    private fun addReferencedUsers(connection: Connection, rows: LinkedHashMap<String, File>) {
        val userRefPattern = Regex(".*_(author_id|user_id)$|^(author_id|user_id)$")
        val ids = LinkedHashSet<String>()
        for ((table, file) in rows) {
            if (table == "n4user") continue
            val pkColumns = primaryKeyColumns(connection.metaData, table).map { it.lowercase() }.toSet()
            forEachNdjsonRow(file) { row ->
                for ((column, value) in row) {
                    val lower = column.lowercase()
                    if (value == null || lower in pkColumns || !userRefPattern.matches(lower)) continue
                    value.toString().toLongOrNull()?.let { ids.add(it.toString()) }
                }
            }
        }
        val owner = rows["project"]?.let { firstNdjsonRow(it)?.value("owner")?.toString() }
            ?.takeIf { it.isNotBlank() }
        val seen = HashSet<String>()
        rows["n4user"]?.let { file -> forEachNdjsonRow(file) { it.value("id")?.toString()?.let(seen::add) } }
        for (chunk in ids.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            forEachJdbcRow(connection, "SELECT * FROM n4user WHERE id IN ($placeholders)", chunk) { row ->
                row.value("id")?.toString()?.let { if (seen.add(it)) appendNdjsonRow(rows, "n4user", row) }
            }
        }
        if (owner != null) {
            forEachJdbcRow(connection, "SELECT * FROM n4user WHERE LOWER(login_id) = ?", listOf(owner.lowercase())) { row ->
                row.value("id")?.toString()?.let { if (seen.add(it)) appendNdjsonRow(rows, "n4user", row) }
            }
        }
    }

    private fun appendNdjsonRow(rows: LinkedHashMap<String, File>, table: String, row: Map<String, Any?>) {
        val directory = rows.values.firstOrNull()?.parentFile ?: File(createTempDirectory(), "db")
        directory.mkdirs()
        val file = rows.getOrPut(table) { File(directory, "$table.ndjson") }
        val archivedRow = if (table == "n4user") projectUserReference(row) else row
        file.appendBytes(objectMapper.writeValueAsBytes(archivedRow) + byteArrayOf('\n'.code.toByte()))
    }

    private fun remapProjectId(
        table: String,
        value: Any?,
        referenceIds: Map<String, Map<String, Any?>>,
        offsets: Map<String, Long>,
        requiredReference: Boolean,
    ): Any? {
        if (value == null) return null
        val key = value.toString()
        if (table in sharedReferenceTables) {
            val mapped = referenceIds[table]?.get(key)
            if (mapped != null) return mapped
            if (requiredReference) throw BadBackupArchiveException("$table 참조 ID가 백업에 없습니다: $key")
            return value
        }
        val offset = offsets[table] ?: return value
        val id = key.toLongOrNull() ?: throw BadBackupArchiveException("$table 참조 ID가 정수가 아닙니다: $key")
        return Math.addExact(id, offset)
    }

    /** merge 시 attachment.container_id를 컨테이너 테이블의 새 식별자로 옮긴다. */
    private fun remapAttachmentContainer(
        row: MutableMap<String, Any?>,
        referenceIds: Map<String, Map<String, Any?>>,
        offsets: Map<String, Long>,
    ) {
        val type = row.value("container_type")?.toString()?.lowercase() ?: return
        val table = containerTables.entries.firstOrNull { type in it.value }?.key ?: return
        val old = row.value("container_id")?.toString() ?: return
        val mapped = remapProjectId(table, old, referenceIds, offsets, requiredReference = false)
        if (mapped.toString() != old) row.putValue("container_id", mapped.toString())
    }

    // ---------------------------------------------------------------- 공용 저수준 유틸

    /** DB별로 대소문자가 다른 컬럼 키를 무시하고 값을 꺼낸다. */
    private fun Map<String, Any?>.value(key: String): Any? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun MutableMap<String, Any?>.putValue(key: String, value: Any?) {
        val existing = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        if (existing != null) existing.setValue(value) else this[key] = value
    }

    private fun forEachJdbcRow(
        connection: Connection,
        sql: String,
        parameters: List<Any?>,
        block: (Map<String, Any?>) -> Unit,
    ) {
        connection.prepareStatement(sql).use { statement ->
            statement.fetchSize = STREAM_FETCH_SIZE
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                val columns = columnLabels(rs)
                while (rs.next()) block(rowMap(rs, columns))
            }
        }
    }
    private fun writeManifest(zip: ZipOutputStream, scope: String, project: String?) {
        zipEntry(zip, "manifest.json") {
            it.write(objectMapper.writeValueAsBytes(mapOf(
                "format" to DataBackupService.FORMAT, "formatVersion" to DataBackupService.FORMAT_VERSION,
                "scope" to scope, "project" to project, "createdAt" to Instant.now().toString(),
            )))
        }
    }

    private fun zipEntry(zip: ZipOutputStream, name: String, write: (OutputStream) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        write(zip)
        zip.closeEntry()
    }

    private fun addTreeToZip(zip: ZipOutputStream, root: File, entryPrefix: String) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeToOrNull(root)?.path ?: return@forEach
            addFileToZip(zip, file, "$entryPrefix/$relative")
        }
    }

    private fun isWithin(child: File, parent: File): Boolean =
        child.canonicalFile.toPath().startsWith(parent.canonicalFile.toPath())

    /** application data는 포함하되 활성 DB 파일과 durable queue의 파일 저장소는 제외한다. */
    private fun addApplicationDataToZip(zip: ZipOutputStream) {
        if (!applicationDataDir.isDirectory) return
        val root = applicationDataDir.canonicalFile.toPath()
        val excluded = buildList {
            val queuePath = queueDataDir.canonicalFile.toPath()
            if (queuePath.startsWith(root)) add(queuePath)
            val h2Path = File(applicationDataDir, "h2").canonicalFile.toPath()
            if (h2Path.startsWith(root)) add(h2Path)
        }
        applicationDataDir.walkTopDown()
            .onEnter { directory ->
                val path = directory.canonicalFile.toPath()
                excluded.none { path == it || path.startsWith(it) }
            }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeToOrNull(applicationDataDir) ?: return@forEach
                addFileToZip(zip, file, "files/data/${relative.path}")
            }
    }
    private fun addFileToZip(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun columnLabels(rs: ResultSet): List<String> {
        val meta = rs.metaData
        return (1..meta.columnCount).map { meta.getColumnLabel(it) }
    }

    private fun rowMap(rs: ResultSet, columns: List<String>): Map<String, Any?> {
        val row = LinkedHashMap<String, Any?>(columns.size)
        for (column in columns) row[column] = rs.getObject(column)
        return row
    }

    private fun restoreTables(tables: Map<String, File>, sequences: Map<String, Long>) {
        refuseIfQueueBusy()
        val dialect = detectDialect()
        doInForeignKeysOff(dialect) {
            for ((table, file) in tables) {
                jdbcTemplate.update("DELETE FROM $table")
                val dateTimeColumns = dateTimeColumns(table)
                forEachNdjsonRow(file) { row -> insertRow(table, row, dateTimeColumns) }
                sequences[table]?.let { restoreSequence(table, "id", dialect, it) }
            }
        }
    }

    private fun firstNdjsonRow(file: File): Map<String, Any?>? {
        var first: Map<String, Any?>? = null
        forEachNdjsonRow(file) { if (first == null) first = it }
        return first
    }

    private fun forEachNdjsonRow(file: File, block: (Map<String, Any?>) -> Unit) {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                block(objectMapper.readValue(line, Map::class.java) as Map<String, Any?>)
            }
        }
    }

    private fun refuseIfQueueBusy() {
        val unfinished = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM queue_job WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT','CANCEL_REQUESTED')",
            Long::class.java
        ) ?: 0L
        if (unfinished > 0) {
            throw UnfinishedQueueJobsException("완료되지 않은 작업 큐 작업이 ${unfinished}건 있어 import할 수 없습니다")
        }
    }

    private fun doInForeignKeysOff(dialect: Dialect, block: () -> Unit) {
        setForeignKeyChecks(dialect, enabled = false)
        try {
            block()
        } catch (e: Throwable) {
            // 복원 실패 원인을 FK 재활성화 실패(예: PostgreSQL 25P02)가 덮어쓰지 않도록 suppressed로 붙인다.
            try {
                setForeignKeyChecks(dialect, enabled = true)
            } catch (restoreFailure: Exception) {
                e.addSuppressed(restoreFailure)
            }
            throw e
        }
        setForeignKeyChecks(dialect, enabled = true)
    }

    // CUBRID JDBC 드라이버는 Connection.getSchema()(JDBC 4.1)를 지원하지 않아 UnsupportedOperationException을
    // (SQLException으로 감싸) 던진다. CUBRID는 접속 사용자가 곧 테이블 소유자(스키마)이므로 사용자 이름으로 대신한다.
    private fun schemaOf(connection: java.sql.Connection): String? =
        try {
            connection.schema
        } catch (e: UnsupportedOperationException) {
            connection.metaData.userName
        } catch (e: java.sql.SQLException) {
            if (e is java.sql.SQLFeatureNotSupportedException || e.cause is UnsupportedOperationException) {
                connection.metaData.userName
            } else {
                throw e
            }
        }

    private fun listTables(connection: Connection): List<String> {
        val tables = mutableListOf<String>()
        connection.metaData.getTables(connection.catalog, schemaOf(connection), "%", arrayOf("TABLE")).use { rs ->
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"))
        }
        return tables.sorted()
    }

    private fun insertRow(table: String, row: Map<String, Any?>, dateTimeColumns: Set<String>) {
        if (row.isEmpty()) return
        val columns = row.keys.toList()
        val placeholders = columns.joinToString(",") { "?" }
        val sql = "INSERT INTO $table (${columns.joinToString(",")}) VALUES ($placeholders)"
        val values = columns.map { column -> coerceForInsert(row[column], column.lowercase() in dateTimeColumns) }
        jdbcTemplate.update(sql, *values.toTypedArray())
    }

    // export가 직렬화한 Instant 값은 import에서 타입 없는 Map으로 역직렬화되어 String이 된다.
    // MariaDB는 ISO-8601('T'/'Z')을 datetime으로 파싱하지 못하므로 TIMESTAMP/DATE/TIME 계열이면
    // String -> Instant -> Timestamp로 되돌려 바인딩한다.
    private fun coerceForInsert(value: Any?, isDateTimeColumn: Boolean): Any? {
        if (!isDateTimeColumn || value !is String) return value
        return try {
            Timestamp.from(Instant.parse(value))
        } catch (e: DateTimeParseException) {
            value
        }
    }

    private fun dateTimeColumns(table: String): Set<String> {
        dataSource.connection.use { connection ->
            val columns = mutableSetOf<String>()
            for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
                connection.metaData.getColumns(connection.catalog, schemaOf(connection), candidate, null).use { rs ->
                    while (rs.next()) {
                        val jdbcType = rs.getInt("DATA_TYPE")
                        if (jdbcType == Types.TIMESTAMP || jdbcType == Types.DATE || jdbcType == Types.TIME ||
                            jdbcType == Types.TIMESTAMP_WITH_TIMEZONE || jdbcType == Types.TIME_WITH_TIMEZONE
                        ) {
                            columns.add(rs.getString("COLUMN_NAME").lowercase())
                        }
                    }
                }
            }
            return columns
        }
    }

    private fun primaryKeyColumns(metadata: DatabaseMetaData, table: String): List<String> {
        // H2는 unquoted 식별자를 대문자로 저장해 소문자 이름으로는 메타데이터가 비어 있다 — 후보 케이스로 재시도한다.
        for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
            metadata.getPrimaryKeys(null, null, candidate).use { rs ->
                val columns = sortedMapOf<Int, String>()
                while (rs.next()) columns[rs.getInt("KEY_SEQ")] = rs.getString("COLUMN_NAME")
                if (columns.isNotEmpty()) return columns.values.toList()
            }
        }
        return emptyList()
    }

    private fun isNumericColumn(metadata: DatabaseMetaData, table: String, column: String): Boolean {
        dataSource.connection.use { connection ->
            for (candidate in linkedSetOf(table, table.uppercase(), table.lowercase())) {
                connection.metaData.getColumns(connection.catalog, schemaOf(connection), candidate, null).use { rs ->
                    while (rs.next()) {
                        if (rs.getString("COLUMN_NAME").equals(column, ignoreCase = true)) {
                            return when (rs.getInt("DATA_TYPE")) {
                                Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT -> true
                                else -> false
                            }
                        }
                    }
                }
            }
            // H2 2.x 등에서 getColumns가 빈 결과를 돌려주는 환경이 있다 — 실제 SELECT의 컬럼 타입으로 판정한다.
            connection.createStatement().use { statement ->
                statement.maxRows = 1
                statement.executeQuery("SELECT $column FROM $table").use { rs ->
                    return when (rs.metaData.getColumnType(1)) {
                        Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT -> true
                        else -> false
                    }
                }
            }
        }
    }

    private fun nextSequenceValue(connection: Connection, table: String, dialect: Dialect): Long? {
        val metadata = connection.metaData
        return when (dialect) {
            Dialect.MYSQL_COMPATIBLE -> jdbcTemplate.queryForObject(
                "SELECT AUTO_INCREMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                Long::class.java, connection.catalog, table
            )?.toLong()
            // 조인 테이블처럼 숫자 id가 없는 테이블에 pg_get_serial_sequence()를 호출하면 예외가 나므로
            // 먼저 PK가 숫자 단일 컬럼인지 확인한다.
            Dialect.POSTGRES -> {
                val pk = primaryKeyColumns(metadata, table)
                if (pk.size != 1 || !isNumericColumn(metadata, table, pk[0])) return null
                val sequenceName = jdbcTemplate.queryForObject(
                    "SELECT pg_get_serial_sequence(?, ?)", String::class.java, table, pk[0]
                ) ?: return null
                jdbcTemplate.queryForObject(
                    "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM $sequenceName",
                    Long::class.java
                )?.toLong()
            }
            // H2 identity 컬럼의 "다음 값"은 표준 조회가 없어 MAX+1로 근사한다(갭 보존 불가 — 문서화된 한계).
            Dialect.H2 -> {
                val pk = primaryKeyColumns(metadata, table)
                if (pk.size != 1 || !isNumericColumn(metadata, table, pk[0])) return null
                jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(${pk[0]}), 0) + 1 FROM $table", Long::class.java
                )?.toLong()
            }
            Dialect.CUBRID, Dialect.OTHER -> null
        }
    }

    private fun restoreSequence(table: String, pkColumn: String, dialect: Dialect, nextValue: Long) {
        when (dialect) {
            Dialect.MYSQL_COMPATIBLE -> jdbcTemplate.execute("ALTER TABLE $table AUTO_INCREMENT = $nextValue")
            Dialect.POSTGRES -> {
                val sequenceName = jdbcTemplate.queryForObject(
                    "SELECT pg_get_serial_sequence(?, ?)", String::class.java, table, pkColumn
                ) ?: return
                // setval(seq, nextValue, false): 다음 nextval()이 정확히 nextValue를 반환한다.
                jdbcTemplate.queryForObject("SELECT setval(?, ?, false)", Long::class.java, sequenceName, nextValue)
            }
            Dialect.H2 -> jdbcTemplate.execute("ALTER TABLE $table ALTER COLUMN $pkColumn RESTART WITH $nextValue")
            Dialect.CUBRID, Dialect.OTHER -> logger.warn("알 수 없는 DB 방언이라 $table 의 auto-increment/시퀀스를 재설정하지 않습니다")
        }
    }

    // role 같은 이름이 CUBRID 예약어라(테이블 이름을 만드는 YonaCubridNamingStrategy 참고) 인용하지 않으면
    // SELECT가 문법 오류가 된다. 다른 DBMS는 기존처럼 이름을 그대로 쓴다.
    private fun exportTableRef(table: String, dialect: Dialect): String =
        if (dialect == Dialect.CUBRID) "\"$table\"" else table

    private fun detectDialect(): Dialect {
        dataSource.connection.use { connection ->
            val product = connection.metaData.databaseProductName ?: ""
            return when {
                product.contains("MySQL", ignoreCase = true) || product.contains("MariaDB", ignoreCase = true) ->
                    Dialect.MYSQL_COMPATIBLE
                product.contains("PostgreSQL", ignoreCase = true) -> Dialect.POSTGRES
                product.contains("H2", ignoreCase = true) -> Dialect.H2
                product.contains("CUBRID", ignoreCase = true) -> Dialect.CUBRID
                else -> Dialect.OTHER
            }
        }
    }

    private fun setForeignKeyChecks(dialect: Dialect, enabled: Boolean) {
        when (dialect) {
            Dialect.MYSQL_COMPATIBLE -> jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = ${if (enabled) 1 else 0}")
            Dialect.POSTGRES -> jdbcTemplate.execute("SET session_replication_role = '${if (enabled) "origin" else "replica"}'")
            // SET REFERENTIAL_INTEGRITY — H2 고유 구문.
            Dialect.H2 -> jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY ${if (enabled) "TRUE" else "FALSE"}")
            Dialect.CUBRID, Dialect.OTHER -> logger.warn("알 수 없는 DB 방언이라 외래키 제약을 토글하지 않습니다")
        }
    }

    private fun createTempDirectory(): File =
        File.createTempFile("yona-import", "").let { file ->
            file.delete()
            file.mkdir()
            file.deleteOnExit()
            file
        }

    companion object {
        private const val STREAM_FETCH_SIZE = 1000
    }
}
