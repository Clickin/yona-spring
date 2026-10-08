package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.config.svn.SvnAuthorizationFilter
import com.github.yonaprojects.yona.config.vcs.RepoAccessPolicy
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.RepositoryMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException

class RepositoryMirrorReadinessSpec : DescribeSpec({
    describe("mirror preparation is not an empty completed repository") {
        it("requires the fixed target to be verified and indexed even when paused or disabled") {
            val mirrors = mockk<RepositoryMirrorRepository>()
            val project = Project(id = 7, owner = "owner", name = "mirror", vcs = "SUBVERSION", repositoryMode = RepositoryMode.MIRROR)
            val mirror = RepositoryMirror(project = project)
            every { mirrors.findByProjectId(7) } returns mirror
            val readiness = RepositoryMirrorReadiness(mirrors)
            readiness.isReady(Project()) shouldBe true
            shouldThrow<ResponseStatusException> { readiness.requireReady(project) }.statusCode.value() shouldBe 503
            mirror.initialImportTargetRevision = 3
            mirror.lastVerifiedRevision = 3
            mirror.lastIndexedRevision = 2
            mirror.enabled = false
            readiness.isReady(project) shouldBe false
            mirror.lastIndexedRevision = 3
            readiness.isReady(project) shouldBe true
            mirror.status = RepositoryMirrorStatus.NEEDS_ATTENTION
            readiness.isReady(project) shouldBe false
        }
        it("responds preparing on DAV reads while mutation remains read-only forbidden") {
            val mirrors = mockk<RepositoryMirrorRepository>()
            val policy = mockk<RepoAccessPolicy>()
            val guard = mockk<RepositoryWriteGuard>()
            val project = Project(id = 7, owner = "owner", name = "mirror", vcs = "SUBVERSION", repositoryMode = RepositoryMode.MIRROR)
            every { mirrors.findByProjectId(7) } returns RepositoryMirror(project = project)
            every { policy.findProject("owner", "mirror") } returns project
            every { policy.requiresAuth(project, false) } returns false
            every { guard.isWritable(project, any()) } returns false
            val filter = SvnAuthorizationFilter(policy, guard, RepositoryMirrorReadiness(mirrors))
            var continued = false
            val chain = FilterChain { _, _ -> continued = true }
            val read = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest("PROPFIND", "/svn/owner/mirror"), read, chain)
            read.status shouldBe 503
            continued shouldBe false
            val write = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest("PROPPATCH", "/svn/owner/mirror"), write, chain)
            write.status shouldBe 403
            continued shouldBe false
        }
    }
})
