package com.github.yonaprojects.yona.config

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.vcs.RepositoryAccess
import com.github.yonaprojects.yona.domain.vcs.RepositoryService
import com.github.yonaprojects.yona.domain.vcs.RepositoryUnavailableException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RepositoryAccessFilterSpec : DescribeSpec({
    it("gates protocol, browser, api/vcs, and actual code mutation routes through response flush") {
        val projects = mockk<ProjectRepository>()
        val repositories = mockk<RepositoryService>(relaxed = true)
        val project = Project(owner = "owner", name = "repo", vcs = "GIT").apply { id = -201 }
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        every { projects.findById(-201) } returns Optional.of(project)
        val filter = RepositoryAccessFilter(projects, repositories)
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (path in listOf(
                "/git/owner/repo.git/git-receive-pack", "/svn/owner/repo/!svn/rvr/1/file", "/hg/owner/repo",
                "/git-lfs/owner/repo.git/objects/aabb", "/api/vcs/owner/repo/raw",
                "/api/vcs/owner/repo/history", "/owner/repo/code/main/download", "/owner/repo/code/download/main",
                "/owner/repo/rawcode/main/file", "/owner/repo/commits/main", "/owner/repo/code/main/setAsDefault",
                "/owner/repo/tags/tag", "/owner/repo/posts", "/api/v1/projects/owner/repo/tags"
            )) {
                val resetEntered = CountDownLatch(1)
                var reset: java.util.concurrent.Future<*>? = null
                val response = object : MockHttpServletResponse() {
                    override fun flushBuffer() {
                        resetEntered.await(50, TimeUnit.MILLISECONDS) shouldBe false
                        super.flushBuffer()
                    }
                }
                filter.doFilter(MockHttpServletRequest("POST", path), response, FilterChain { _, res ->
                    reset = executor.submit { RepositoryAccess.reset(-201) { resetEntered.countDown() } }
                    res.writer.write("active response")
                })
                reset!!.get(5, TimeUnit.SECONDS)
                response.contentAsString shouldBe "active response"
            }
        } finally {
            executor.shutdownNow()
        }
    }

    it("does not acquire a single-project gate for reset, dual-project PR operations, or unrelated requests") {
        val projects = mockk<ProjectRepository>()
        val repositories = mockk<RepositoryService>()
        val filter = RepositoryAccessFilter(projects, repositories)
        for (path in listOf(
            "/owner/repo/changeVCS", "/owner/repo/issues/1", "/health", "/assets/app.js",
            "/owner/repo/pullrequest/1", "/owner/repo/pullrequests",
            "/api/projects/1/pullrequests/1/merge", "/api/v1/projects/owner/repo/pull-requests",
            "/api/owner/repo/mentionListAtPullRequest", "/api/owner/repo/mentionListAtCommitDiff",
            "/api/projects/1", "/api/v1/projects/owner/repo"
        )) {
            var entered = false
            filter.doFilter(MockHttpServletRequest("POST", path), MockHttpServletResponse(), FilterChain { _, _ ->
                RepositoryAccess.reset(-202) { entered = true }
            })
            entered shouldBe true
        }
        verify { projects wasNot Called }
        verify { repositories wasNot Called }
    }

    it("uses an exclusive outer gate for settings mutation through validation-response flushing") {
        val projects = mockk<ProjectRepository>()
        val repositories = mockk<RepositoryService>(relaxed = true)
        val project = Project(owner = "owner", name = "repo", vcs = "GIT").apply { id = -204 }
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        every { projects.findById(-204) } returns Optional.of(project)
        val executor = Executors.newSingleThreadExecutor()
        val readerEntered = CountDownLatch(1)
        var reader: java.util.concurrent.Future<*>? = null
        val response = object : MockHttpServletResponse() {
            override fun flushBuffer() {
                readerEntered.await(50, TimeUnit.MILLISECONDS) shouldBe false
                super.flushBuffer()
            }
        }
        try {
            RepositoryAccessFilter(projects, repositories).doFilter(
                MockHttpServletRequest("POST", "/owner/repo/setting"), response, FilterChain { _, res ->
                    // The native lifecycle service can acquire exclusive again without upgrading a read lock.
                    RepositoryAccess.exclusive(-204) {
                        RepositoryAccess.read(-204) { res.writer.write("settings validation response") }
                    }
                    reader = executor.submit { RepositoryAccess.read(-204) { readerEntered.countDown() } }
                }
            )
            reader!!.get(5, TimeUnit.SECONDS)
            response.contentAsString shouldBe "settings validation response"
        } finally {
            executor.shutdownNow()
        }
    }

    it("keeps GET and HEAD settings responses under a shared read gate") {
        val projects = mockk<ProjectRepository>()
        val repositories = mockk<RepositoryService>(relaxed = true)
        val project = Project(owner = "owner", name = "repo", vcs = "GIT").apply { id = -205 }
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        every { projects.findById(-205) } returns Optional.of(project)
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (method in listOf("GET", "HEAD")) {
                RepositoryAccessFilter(projects, repositories).doFilter(
                    MockHttpServletRequest(method, "/owner/repo/setting"), MockHttpServletResponse(),
                    FilterChain { _, _ ->
                        executor.submit<Boolean> { RepositoryAccess.read(-205) { true } }.get(5, TimeUnit.SECONDS) shouldBe true
                    }
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    it("rejects a canonical repository escape with a generic error before opening a handler") {
        val projects = mockk<ProjectRepository>()
        val repositories = mockk<RepositoryService>()
        val project = Project(owner = "owner", name = "repo", vcs = "GIT").apply { id = -203 }
        every { projects.findByOwnerAndNameOrPreviousPlace("owner", "repo") } returns Optional.of(project)
        every { projects.findById(-203) } returns Optional.of(project)
        every { repositories.getRepository(project) } throws RepositoryUnavailableException()
        val response = MockHttpServletResponse()
        var opened = false
        RepositoryAccessFilter(projects, repositories).doFilter(
            MockHttpServletRequest("GET", "/owner/repo/rawcode/main/file"), response,
            FilterChain { _, _ -> opened = true }
        )
        response.status shouldBe 404
        response.contentAsString shouldBe ""
        opened shouldBe false
    }
})
