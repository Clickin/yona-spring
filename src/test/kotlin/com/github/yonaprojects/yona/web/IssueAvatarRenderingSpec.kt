package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.attachment.Attachment
import com.github.yonaprojects.yona.domain.attachment.AttachmentRepository
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueComment
import com.github.yonaprojects.yona.domain.issue.IssueCommentRepository
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@Transactional
class IssueAvatarRenderingSpec @Autowired constructor(
    private val context: WebApplicationContext,
    private val users: UserRepository,
    private val projects: ProjectRepository,
    private val issues: IssueRepository,
    private val comments: IssueCommentRepository,
    private val attachments: AttachmentRepository,
    private val entityManager: EntityManager
) : AbstractIntegrationTest() {
    init {
        it("renders each author's avatar on issue pages and refreshed comment timelines") {
            val author = users.save(User(loginId = "avatar-author", name = "Author"))
            val commenter = users.save(User(loginId = "avatar-commenter", name = "Commenter"))
            val project = projects.save(Project(owner = "avatar-owner", name = "avatar-project", projectScope = ProjectScope.PUBLIC))
            val authorAvatar = attachments.save(Attachment(name = "author.png", hash = "avatar-author", containerType = ResourceType.USER_AVATAR, containerId = author.id.toString()))
            val commentAvatar = attachments.save(Attachment(name = "comment.png", hash = "avatar-comment", containerType = ResourceType.USER_AVATAR, containerId = commenter.id.toString()))
            val first = issues.save(Issue(project = project, number = 1L, title = "First", body = "First body", state = State.OPEN, authorId = author.id, authorLoginId = author.loginId))
            val second = issues.save(Issue(project = project, number = 2L, title = "Second", body = "Second body", state = State.OPEN, authorId = commenter.id, authorLoginId = commenter.loginId))
            val comment = comments.save(IssueComment(issue = first, contents = "Reply", authorId = commenter.id, authorLoginId = commenter.loginId))
            val missingAuthor = comments.save(IssueComment(issue = first, contents = "Deleted author", authorId = Long.MAX_VALUE))
            entityManager.flush()
            entityManager.clear()
            val mvc = MockMvcBuilders.webAppContextSetup(context).build()
            val route = "/${project.owner}/${project.name}/issue"
            fun render(path: String) = Jsoup.parse(mvc.perform(get(path)).andExpect(status().isOk).andReturn().response.contentAsString)
            val page = render("$route/1")
            page.selectFirst(".author-info .avatar-wrap img")!!.attr("src") shouldBe "/files/${authorAvatar.id}"
            page.selectFirst("#comment-${comment.id} .comment-avatar img")!!.attr("src") shouldBe "/files/${commentAvatar.id}"
            page.selectFirst("#comment-${missingAuthor.id} .comment-avatar img")!!.attr("src") shouldBe "/assets/images/default-avatar-128.png"
            val timeline = render("$route/1/timeline")
            timeline.selectFirst("#comment-${comment.id} .comment-avatar img")!!.attr("src") shouldBe "/files/${commentAvatar.id}"
            render("$route/2").selectFirst(".author-info .avatar-wrap img")!!.attr("src") shouldBe "/files/${commentAvatar.id}"
        }
    }
}
