package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.config.security.AccessControl
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.support.MarkdownService
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

class MarkdownPreviewControllerSpec : DescribeSpec({
    val projects = mockk<ProjectRepository>()
    val users = mockk<UserRepository>()
    val access = mockk<AccessControl>()
    val markdown = mockk<MarkdownService>()
    val mvc = MockMvcBuilders.standaloneSetup(
        MarkdownPreviewController(projects, users, access, markdown)
    ).build()

    describe("server preview boundary") {
        it("rejects invalid frame identifiers before rendering") {
            mvc.perform(post("/markdown/owner/project/preview")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("body", "# draft")
                .param("frameId", "markdown-preview-\"><script>"))
                .andExpect(status().isBadRequest)
            verify(exactly = 0) { markdown.render(any(), any(), any()) }
        }
        it("does not render a private project's context for an unauthorized viewer") {
            val project = Project(id = 1L, name = "project", owner = "owner")
            every { projects.findByOwnerAndNameOrPreviousPlace("owner", "project") } returns Optional.of(project)
            every { access.isAllowedToReadProject(null, project) } returns false
            mvc.perform(post("/markdown/owner/project/preview")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("body", "# draft")
                .param("frameId", "markdown-preview-test"))
                .andExpect(status().isForbidden)
            verify(exactly = 0) { markdown.render(any(), any(), any()) }
        }
    }
})
