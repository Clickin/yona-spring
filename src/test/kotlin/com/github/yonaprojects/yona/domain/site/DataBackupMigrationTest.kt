package com.github.yonaprojects.yona.domain.site

import com.github.yonaprojects.yona.domain.user.PasswordEncodingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.util.UUID

/** Synthetic consumer-visible archive checks: no fixture database or private paths are used. */
class DataBackupMigrationTest {
    @Test
    fun migrationRetainsBootstrapSecurityAndRemapsCollidingIdentityWithoutPromotingSourceAdministrators() {
        Harness().use { h ->
            val before = h.jdbc.queryForMap("SELECT * FROM n4user WHERE id=1")
            h.service.importSite(h.archive().inputStream())
            assertEquals(before, h.jdbc.queryForMap("SELECT * FROM n4user WHERE id=1"))
            assertEquals("retained-security", h.jdbc.queryForObject("SELECT secret FROM user_totp_credential WHERE user_id=1", String::class.java))
            val imported = h.jdbc.queryForMap("SELECT * FROM n4user WHERE login_id='member'")
            assertEquals(11L, (imported.value("id") as Number).toLong())
            assertEquals("ACTIVE", imported.value("state"))
            assertNull(imported.value("token"))
            assertEquals(false, imported.value("is_two_factor_enabled"))
            assertTrue(PasswordEncodingService().matches("legacy-password", imported.value("password") as String, imported.value("password_salt") as String))
            assertEquals(11L, h.jdbc.queryForObject("SELECT author_id FROM issue WHERE id=7", Long::class.java))
            assertEquals(1L, h.jdbc.queryForObject("SELECT author_id FROM issue WHERE id=8", Long::class.java))
            assertEquals(1, h.jdbc.queryForObject("SELECT COUNT(*) FROM n4user WHERE state='SITE_ADMIN'", Int::class.java))
            assertEquals("ref: refs/heads/main\n", h.root.resolve("git/member/example.git/HEAD").toFile().readText())
            h.jdbc.update("INSERT INTO n4user(login_id,name,email,state) VALUES ('after','After','after@example.invalid','ACTIVE')")
            assertTrue(h.jdbc.queryForObject("SELECT id FROM n4user WHERE login_id='after'", Long::class.java)!! > 11)
        }
    }

    @Test
    fun providerIdentifiersAreNotNumericUserReferencesEvenWhenTheirTextLooksLikeAnId() {
        Harness().use { h ->
            val links = listOf(
                mapOf("id" to 1, "user_id" to 1, "provider_key" to "synthetic", "provider_user_id" to "1"),
                mapOf("id" to 2, "user_id" to 1, "provider_key" to "synthetic", "provider_user_id" to "external-identity"),
            )
            h.service.importSite(h.archive(extraTables = mapOf("linked_account" to links)).inputStream())
            assertEquals(listOf("1", "external-identity"), h.jdbc.queryForList("SELECT provider_user_id FROM linked_account ORDER BY id", String::class.java))
            assertEquals(listOf(11L, 11L), h.jdbc.queryForList("SELECT user_id FROM linked_account ORDER BY id", Long::class.java))
        }
    }

    @Test
    fun incompatibleCapabilitiesVersionsIntegrityAndSchemaFailBeforeRowsOrFilesChange() {
        Harness().use { h ->
            val inputs = listOf(
                h.archive(overrides = mapOf("targetVersion" to "3.0")),
                h.archive(overrides = mapOf("formatVersion" to 2)),
                h.archive(overrides = mapOf("requiredCapabilities" to listOf("unknown"))),
                h.archive(corruptIntegrity = true),
                h.archive(extraUser = mapOf("unknown_column" to "reject")),
                h.archive(extraUser = mapOf("id" to 10)),
                h.archive(extraTables = mapOf("unknown_table" to listOf(mapOf("id" to 1)))),
                h.archive(extraTables = mapOf("user_totp_credential" to listOf(mapOf("user_id" to 1, "secret" to "injected")))),
                h.archive(extraTables = mapOf("attachment" to listOf(mapOf("id" to 1, "hash" to "missing", "container_type" to "ISSUE_POST", "container_id" to "7", "size" to 4)))),
                h.archive(issueAuthor = 999),
                h.archive(missingRepository = true),
                h.archive(extraFiles = mapOf("files/data/queue/replace-me" to byteArrayOf(1))),
                h.archive(extraFiles = mapOf("files/git/../escape" to byteArrayOf(1))),
            )
            for (input in inputs) {
                assertThrows(Exception::class.java) { h.service.importSite(input.inputStream()) }
                assertEquals(listOf("admin"), h.jdbc.queryForList("SELECT login_id FROM n4user", String::class.java))
                assertEquals(0, h.jdbc.queryForObject("SELECT COUNT(*) FROM issue", Int::class.java))
                assertEquals("target-file", h.root.resolve("git/marker").toFile().readText())
                assertEquals("queue-file", h.root.resolve("queue/marker").toFile().readText())
            }
        }
    }

    @Test
    fun freshTargetAndExplicitSourceAdministratorAreRequiredRatherThanMergingAccounts() {
        Harness().use { h ->
            assertThrows(BadBackupArchiveException::class.java) {
                h.service.importSite(h.archive(overrides = mapOf("bootstrapSourceLogin" to null)).inputStream())
            }
            h.jdbc.update("INSERT INTO n4user(id,login_id,name,email,state) VALUES (2,'existing','Existing','existing@example.invalid','ACTIVE')")
            assertThrows(BadBackupArchiveException::class.java) { h.service.importSite(h.archive().inputStream()) }
            assertEquals(2, h.jdbc.queryForObject("SELECT COUNT(*) FROM n4user", Int::class.java))
        }
    }

    @Test
    fun ldapOnlyDoesNotImportUsableLocalCredentialsOrPermitLocalFallback() {
        Harness(ldap = true, fallback = false).use { h ->
            assertThrows(BadBackupArchiveException::class.java) { h.service.importSite(h.archive(mode = "ldap-fallback").inputStream()) }
            h.service.importSite(h.archive(mode = "ldap-only").inputStream())
            assertNull(h.jdbc.queryForObject("SELECT password FROM n4user WHERE login_id='member'", String::class.java))
            assertNull(h.jdbc.queryForObject("SELECT password_salt FROM n4user WHERE login_id='member'", String::class.java))
            assertEquals("bootstrap-hash", h.jdbc.queryForObject("SELECT password FROM n4user WHERE login_id='admin'", String::class.java))
        }
        Harness().use { h ->
            assertThrows(BadBackupArchiveException::class.java) { h.service.importSite(h.archive(mode = "ldap-only").inputStream()) }
        }
    }

    @Test
    fun svnRepositoryAndGitWikiAreRestoredToTheTargetNativeLayout() {
        Harness().use { h ->
            val files = mapOf(
                "files/svn/member/example/format" to "5\n".toByteArray(),
                "files/svn/member/example/db/current" to "0\n".toByteArray(),
                "files/git/member/example.wiki.git/HEAD" to "ref: refs/heads/main\n".toByteArray(),
            )
            h.service.importSite(h.archive(vcs = "Subversion", missingRepository = true, extraFiles = files).inputStream())
            assertEquals("0\n", h.root.resolve("svn/member/example/db/current").toFile().readText())
            assertEquals("ref: refs/heads/main\n", h.root.resolve("git/member/example.wiki.git/HEAD").toFile().readText())
        }
    }

    @Test
    fun mappedSourceAdministratorCannotInstallNewBootstrapSshCredentials() {
        Harness().use { h ->
            h.jdbc.update("INSERT INTO ssh_key VALUES (1,1,'target-bootstrap-key')")
            val rows = listOf(
                mapOf("id" to 1, "user_id" to 1, "public_key" to "source-member-key"),
                mapOf("id" to 2, "user_id" to 10, "public_key" to "source-admin-key"),
            )
            h.service.importSite(h.archive(extraTables = mapOf("ssh_key" to rows)).inputStream())
            assertEquals("target-bootstrap-key", h.jdbc.queryForObject("SELECT public_key FROM ssh_key WHERE id=1 AND user_id=1", String::class.java))
            assertEquals(11L, h.jdbc.queryForObject("SELECT user_id FROM ssh_key WHERE public_key='source-member-key'", Long::class.java))
            assertEquals(0, h.jdbc.queryForObject("SELECT COUNT(*) FROM ssh_key WHERE public_key='source-admin-key'", Int::class.java))
        }
    }

    @Test
    fun normalFormatThreeExportsRoundTripWithoutMigrationCapabilities() {
        Harness().use { h ->
            val binary = byteArrayOf(0, 1, 127, -128, -1)
            h.jdbc.update("INSERT INTO archive_binary_material VALUES (1, ?)", binary)
            val bytes = DataBackupArchiveTestSupport.exportSiteToBytes(h.service)
            h.jdbc.update("UPDATE n4user SET name='Changed' WHERE id=1")
            h.jdbc.update("UPDATE archive_binary_material SET bytes_value = ?", byteArrayOf(42))
            h.service.importSite(bytes.inputStream())
            assertEquals("Bootstrap", h.jdbc.queryForObject("SELECT name FROM n4user WHERE id=1", String::class.java))
            assertEquals("queue-file", h.root.resolve("queue/marker").toFile().readText())
            assertTrue(DataBackupArchiveTestSupport.readEntryNames(bytes).contains("integrity.ndjson"))
            assertArrayEquals(binary, h.jdbc.queryForObject("SELECT bytes_value FROM archive_binary_material WHERE id=1", ByteArray::class.java))
        }
    }

    @Test
    fun duplicateZipPathsAreRejectedBeforeBootstrapOrFilesystemReplacement() {
        Harness().use { h ->
            val first = "files/git/member/example.git/one"
            val second = "files/git/member/example.git/two"
            val bytes = h.archive(extraFiles = mapOf(first to byteArrayOf(1), second to byteArrayOf(2)))
            val from = second.toByteArray()
            val to = first.toByteArray()
            // ZIP stores entry names outside compressed data; equal-length replacement keeps the archive readable.
            for (offset in 0..bytes.size - from.size) {
                if (from.indices.all { bytes[offset + it] == from[it] }) to.copyInto(bytes, offset)
            }
            assertThrows(BadBackupArchiveException::class.java) { h.service.importSite(bytes.inputStream()) }
            assertEquals(listOf("admin"), h.jdbc.queryForList("SELECT login_id FROM n4user", String::class.java))
            assertEquals("target-file", h.root.resolve("git/marker").toFile().readText())
        }
    }

    @Test
    fun declaredOrphanMenuProvenanceIsIntegrityCheckedWithoutInventingTargetProjects() {
        val path = "migration/orphan-project-menu-settings.ndjson"
        val metadata = mapOf("format" to "yona-legacy-provenance", "formatVersion" to 1, "entries" to listOf(path))
        val row = mapOf("id" to 9, "project_id" to 999, "code" to true, "issue" to false,
            "pull_request" to true, "review" to false, "milestone" to true, "board" to false)
        Harness().use { h ->
            val raw = (h.mapper.writeValueAsString(row) + "\n").toByteArray()
            h.service.importSite(h.archive(overrides = mapOf("migrationProvenance" to metadata), extraFiles = mapOf(path to raw)).inputStream())
            assertEquals(listOf(5L), h.jdbc.queryForList("SELECT id FROM project", Long::class.java))
            assertFalse(h.root.resolve(path).toFile().exists())
        }
        Harness().use { h ->
            val live = (h.mapper.writeValueAsString(row + ("project_id" to 5)) + "\n").toByteArray()
            assertThrows(BadBackupArchiveException::class.java) {
                h.service.importSite(h.archive(overrides = mapOf("migrationProvenance" to metadata), extraFiles = mapOf(path to live)).inputStream())
            }
            assertThrows(BadBackupArchiveException::class.java) {
                h.service.importSite(h.archive(overrides = mapOf("migrationProvenance" to metadata)).inputStream())
            }
            assertEquals(listOf("admin"), h.jdbc.queryForList("SELECT login_id FROM n4user", String::class.java))
        }
    }

    @Test
    fun fullSiteSnapshotsPreserveUnboundAttachmentRecordsAndBytesWithoutInventingContainers() {
        Harness().use { h ->
            val bytes = "retained attachment".toByteArray()
            val attachment = mapOf("id" to 42, "hash" to "retained-upload", "container_type" to "ISSUE_POST",
                "container_id" to "999", "size" to bytes.size)
            h.service.importSite(h.archive(extraTables = mapOf("attachment" to listOf(attachment)),
                extraFiles = mapOf("files/uploads/retained-upload" to bytes)).inputStream())
            val backup = DataBackupArchiveTestSupport.exportSiteToBytes(h.service)
            h.service.importSite(backup.inputStream())
            assertEquals("999", h.jdbc.queryForObject("SELECT container_id FROM attachment WHERE id=42", String::class.java))
            assertEquals(0, h.jdbc.queryForObject("SELECT COUNT(*) FROM issue WHERE id=999", Int::class.java))
            assertArrayEquals(bytes, h.root.resolve("uploads/retained-upload").toFile().readBytes())
        }
    }

    @Test
    fun virtualSiteManagerRowsDoNotBecomeProjectMembershipsOrHideExplicitProjectRoles() {
        Harness().use { h ->
            val grants = listOf(
                mapOf("id" to 3, "user_id" to 1, "project_id" to 5, "role_id" to 3),
                mapOf("id" to 4, "user_id" to 1, "project_id" to 5, "role_id" to 1),
                mapOf("id" to 5, "user_id" to 10, "project_id" to 5, "role_id" to 3),
            )
            val roles = listOf(mapOf("id" to 1), mapOf("id" to 3))
            h.service.importSite(h.archive(extraTables = mapOf("role" to roles, "project_user" to grants)).inputStream())
            assertEquals(listOf(4L), h.jdbc.queryForList("SELECT id FROM project_user", Long::class.java))
            assertEquals(11L, h.jdbc.queryForObject("SELECT user_id FROM project_user WHERE id=4", Long::class.java))
            assertEquals(1L, h.jdbc.queryForObject("SELECT role_id FROM project_user WHERE id=4", Long::class.java))
        }
    }

    private class Harness(ldap: Boolean = false, fallback: Boolean = false) : AutoCloseable {
        val root = Files.createTempDirectory("yona-synthetic-migration")
        val mapper = JsonMapper.builder().build()
        private val source = DriverManagerDataSource("jdbc:h2:mem:migration-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        val jdbc = JdbcTemplate(source)
        val service: DataBackupService = DataBackupServiceImpl(source, mapper, root.resolve("data").toFile(), root.resolve("queue").toFile(),
            root.resolve("git").toFile(), root.resolve("lfs").toFile(), root.resolve("uploads").toFile(), ldap, fallback, root.resolve("svn").toFile())

        init {
            jdbc.execute("CREATE TABLE n4user(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,login_id VARCHAR(255) NOT NULL UNIQUE,name VARCHAR(255) NOT NULL,email VARCHAR(255) NOT NULL,password VARCHAR(255),password_salt VARCHAR(255),state VARCHAR(20) NOT NULL,token VARCHAR(255),remember_me BOOLEAN DEFAULT FALSE,is_guest BOOLEAN DEFAULT FALSE,failed_login_attempts INTEGER DEFAULT 0,locked_until TIMESTAMP,is_two_factor_enabled BOOLEAN DEFAULT FALSE)")
            jdbc.execute("CREATE TABLE project(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,name VARCHAR(255) NOT NULL,owner VARCHAR(255) NOT NULL,vcs VARCHAR(255))")
            jdbc.execute("CREATE TABLE issue(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,project_id BIGINT NOT NULL REFERENCES project(id),author_id BIGINT REFERENCES n4user(id),title VARCHAR(255) NOT NULL)")
            jdbc.execute("CREATE TABLE user_totp_credential(user_id BIGINT PRIMARY KEY REFERENCES n4user(id),secret VARCHAR(255) NOT NULL)")
            jdbc.execute("CREATE TABLE queue_job(id BIGINT PRIMARY KEY,status VARCHAR(50) NOT NULL)")
            jdbc.execute("CREATE TABLE archive_binary_material(id BIGINT PRIMARY KEY,bytes_value VARBINARY(100) NOT NULL)")
            jdbc.execute("CREATE TABLE attachment(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,hash VARCHAR(255) NOT NULL,container_type VARCHAR(255) NOT NULL,container_id VARCHAR(255) NOT NULL,size BIGINT NOT NULL,owner_login_id VARCHAR(255))")
            jdbc.execute("CREATE TABLE ssh_key(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES n4user(id),public_key VARCHAR(255) NOT NULL)")
            jdbc.execute("CREATE TABLE linked_account(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES n4user(id),provider_key VARCHAR(255) NOT NULL,provider_user_id VARCHAR(255) NOT NULL)")
            jdbc.execute("CREATE TABLE role(id BIGINT PRIMARY KEY)")
            jdbc.execute("CREATE TABLE project_user(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES n4user(id),project_id BIGINT NOT NULL REFERENCES project(id),role_id BIGINT NOT NULL REFERENCES role(id))")
            jdbc.update("INSERT INTO n4user(id,login_id,name,email,password,password_salt,state,token,remember_me,is_two_factor_enabled,failed_login_attempts) VALUES (1,'admin','Bootstrap','bootstrap@example.invalid','bootstrap-hash',NULL,'SITE_ADMIN','bootstrap-token',TRUE,TRUE,3)")
            jdbc.update("INSERT INTO user_totp_credential VALUES (1,'retained-security')")
            for ((path, value) in mapOf("git/marker" to "target-file", "queue/marker" to "queue-file")) {
                root.resolve(path).toFile().apply { parentFile.mkdirs(); writeText(value) }
            }
        }

        fun archive(mode: String = "local", overrides: Map<String, Any?> = emptyMap(), corruptIntegrity: Boolean = false,
            extraUser: Map<String, Any?> = emptyMap(), issueAuthor: Long = 1, missingRepository: Boolean = false,
            extraFiles: Map<String, ByteArray> = emptyMap(), vcs: String = "GIT",
            extraTables: Map<String, List<Map<String, Any?>>> = emptyMap()): ByteArray {
            val password = PasswordEncodingService.legacyHash("legacy-password", "legacy-salt")
            val tables = mapOf(
                "n4user" to listOf(
                    mapOf("id" to 10,"login_id" to "admin","name" to "Source Admin","email" to "source-admin@example.invalid","password" to password,"password_salt" to "legacy-salt","state" to "SITE_ADMIN"),
                    mapOf("id" to 1,"login_id" to "member","name" to "Member","email" to "member@example.invalid","password" to password,"password_salt" to "legacy-salt","state" to "SITE_ADMIN","token" to "source-token","is_two_factor_enabled" to true) + extraUser,
                ),
                "project" to listOf(mapOf("id" to 5,"name" to "example","owner" to "member","vcs" to vcs)),
                "issue" to listOf(mapOf("id" to 7,"project_id" to 5,"author_id" to issueAuthor,"title" to "Member issue"),
                    mapOf("id" to 8,"project_id" to 5,"author_id" to 10,"title" to "Admin issue")),
            ) + extraTables
            val manifest = mapOf("sourceVersion" to "1.16","producer" to "yona2-migrator","producerVersion" to "0.1.0",
                "requiredCapabilities" to listOf(DataBackupService.LEGACY_CREDENTIALS),"sourceAuthMode" to mode,"bootstrapSourceLogin" to "admin") + overrides
            val files = (if (missingRepository) emptyMap() else mapOf("files/git/member/example.git/HEAD" to "ref: refs/heads/main\n".toByteArray())) + extraFiles
            return DataBackupArchiveTestSupport.buildSiteArchive(mapper, tables, mapOf("n4user" to 11,"project" to 6,"issue" to 9), manifest, files, corruptIntegrity)
        }

        override fun close() {
            jdbc.execute("DROP ALL OBJECTS")
            jdbc.execute("SHUTDOWN")
            root.toFile().deleteRecursively()
        }
    }
}
