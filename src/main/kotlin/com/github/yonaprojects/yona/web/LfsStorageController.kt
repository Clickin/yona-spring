package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.domain.vcs.RepositoryWriteGuard
import com.github.yonaprojects.yona.domain.vcs.RepositoryKind
import org.springframework.security.access.AccessDeniedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import java.io.File
import java.io.FileOutputStream

@RestController
@RequestMapping("/git-lfs")
class LfsStorageController(
    @Value("\${yona.lfs.base-dir:/tmp/yona/lfs}")
    private val lfsBaseDir: String,
    private val writeGuard: RepositoryWriteGuard
) {

    @GetMapping("/{owner}/{project}/objects/{oid}")
    fun downloadObject(
        @PathVariable owner: String,
        @PathVariable project: String,
        @PathVariable oid: String
    ): ResponseEntity<Resource> {
        if (oid.length < 4) {
            return ResponseEntity.badRequest().build()
        }
        val file = getObjectFile(owner, project, oid)
        if (!file.exists() || !file.isFile) {
            return ResponseEntity.notFound().build()
        }

        val resource = FileSystemResource(file)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$oid\"")
            .body(resource)
    }

    @PutMapping("/{owner}/{project}/objects/{oid}")
    fun uploadObject(
        @PathVariable owner: String,
        @PathVariable project: String,
        @PathVariable oid: String,
        request: HttpServletRequest
    ): ResponseEntity<Unit> {
        try {
            val kind = if (project.endsWith(".wiki")) RepositoryKind.WIKI else RepositoryKind.CODE
            writeGuard.requireWritable(owner, project.removeSuffix(".wiki"), kind)
        } catch (_: AccessDeniedException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        if (oid.length < 4) {
            return ResponseEntity.badRequest().build()
        }
        val file = getObjectFile(owner, project, oid)
        file.parentFile.mkdirs()

        try {
            request.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            return ResponseEntity.status(HttpStatus.CREATED).build()
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
    }

    private fun getObjectFile(owner: String, project: String, oid: String): File {
        val d1 = oid.substring(0, 2)
        val d2 = oid.substring(2, 4)
        return File(lfsBaseDir, "$owner/$project/objects/$d1/$d2/$oid")
    }
}
