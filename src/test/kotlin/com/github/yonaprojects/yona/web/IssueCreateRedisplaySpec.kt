package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.attachment.Attachment
import com.github.yonaprojects.yona.domain.attachment.AttachmentRepository
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.issue.*
import com.github.yonaprojects.yona.domain.milestone.Milestone
import com.github.yonaprojects.yona.domain.milestone.MilestoneRepository
import com.github.yonaprojects.yona.domain.project.*
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.YonaUserDetails
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

@Transactional
class IssueCreateRedisplaySpec @Autowired constructor(
    context: WebApplicationContext,
    users: UserRepository,
    projects: ProjectRepository,
    projectUsers: ProjectUserRepository,
    roles: RoleRepository,
    issues: IssueRepository,
    milestones: MilestoneRepository,
    categories: IssueLabelCategoryRepository,
    labels: IssueLabelRepository,
    attachments: AttachmentRepository
) : AbstractIntegrationTest() {
    private val mvc by lazy {
        MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    }

    init {
        it("renders a recoverable default form after template deletion and retains all submitted state") {
            val author = users.save(User(loginId = "redisplay-author", name = "Author", email = "redisplay@example.test"))
            val project = projects.save(Project(owner = author.loginId, name = "redisplay", projectScope = ProjectScope.PUBLIC))
            val role = roles.findById(RoleType.MEMBER.roleType).orElseGet {
                roles.save(Role(id = RoleType.MEMBER.roleType, name = "MEMBER"))
            }
            projectUsers.save(ProjectUser(project = project, user = author, role = role))
            val parent = issues.save(Issue(project = project, title = "Parent", number = 1L, authorId = author.id))
            val milestone = milestones.save(Milestone(project = project, title = "Release"))
            val category = categories.save(IssueLabelCategory(project = project, name = "Type"))
            val selectedLabels = labels.saveAll(listOf(
                IssueLabel(project = project, category = category, name = "Bug", color = "#ff0000"),
                IssueLabel(project = project, category = category, name = "UI", color = "#00ff00")
            ))
            val own = attachments.save(Attachment(name = "own.txt", ownerLoginId = author.loginId))
            val foreign = attachments.save(Attachment(name = "foreign.txt", ownerLoginId = "other-user"))
            val attached = attachments.save(Attachment(name = "already-attached.txt", ownerLoginId = author.loginId,
                containerType = ResourceType.ISSUE_POST, containerId = parent.id.toString()))
            val details = YonaUserDetails(id = author.id!!, loginId = author.loginId, passwordVal = "h", passwordSalt = "s",
                authoritiesVal = AuthorityUtils.createAuthorityList("ROLE_ACTIVE"))
            val base = "/${project.owner}/${project.name}"

            // An absent/deleted selection is a normal GET of the default form, not an exception.
            val defaultHtml = mvc.perform(get("$base/issueform").param("templateId", "deleted").with(user(details)))
                .andExpect(status().isOk).andReturn().response.contentAsString
            Jsoup.parse(defaultHtml).select("#issue-form input[name=templateId]").size shouldBe 0

            val response = mvc.perform(post("$base/issues").with(user(details)).with(csrf())
                .param("templateId", "deleted").param("answer.steps", "<script>keep this literally</script>")
                .param("title", "Keep title").param("body", "Keep **body**")
                .param("assigneeLoginId", author.loginId).param("milestoneId", milestone.id.toString())
                .param("labelIds", *selectedLabels.map { it.id.toString() }.toTypedArray())
                .param("dueDate", "2027-04-05").param("isDraft", "true")
                .param("parentIssueId", parent.id.toString()).param("targetProjectId", project.id.toString())
                .param("isFromGlobalMenuNew", "true").param("referCommentId", "123")
                .param("temporaryUploadFiles", "${own.id},${foreign.id},${attached.id},999999999"))
                .andExpect(status().isBadRequest).andReturn().response
            val html = Jsoup.parse(response.contentAsString)
            fun value(selector: String) = html.selectFirst(selector)!!.attr("value")
            value("#title") shouldBe "Keep title"
            value("#assignee") shouldBe author.loginId
            value("#milestoneId option[selected]") shouldBe milestone.id.toString()
            html.select("#labelIds option[selected]").map { it.attr("value") }.toSet() shouldBe selectedLabels.map { it.id.toString() }.toSet()
            value("#issueDueDate") shouldBe "2027-04-05"
            value("#isDraft") shouldBe "true"
            value("#targetProjectId option[selected]") shouldBe project.id.toString()
            value("#parentId option[selected]") shouldBe parent.id.toString()
            value("#issue-form input[name=referCommentId]") shouldBe "123"
            value("#issue-form input[name=isFromGlobalMenuNew]") shouldBe "true"
            html.select("#issue-form input[name=templateId]").size shouldBe 0
            val body = html.selectFirst("yona-markdown-editor[name=body]")!!.wholeText()
            body shouldContain "Keep **body**"
            body shouldContain "    <script>keep this literally</script>"
            html.selectFirst("#upload")!!.attr("data-temporary-upload-files") shouldBe own.id.toString()
            html.select("#upload .attached-file-marker").map { it.attr("data-id") } shouldBe listOf(own.id.toString())
            own.containerType shouldBe ResourceType.NOT_A_RESOURCE
            foreign.containerType shouldBe ResourceType.NOT_A_RESOURCE
            attached.containerId shouldBe parent.id.toString()

            // Retry the recovered ordinary form: only the retained temporary upload moves.
            val saved = mvc.perform(post("$base/issues").with(user(details)).with(csrf())
                .param("title", value("#title")).param("body", body).param("isDraft", value("#isDraft"))
                .param("assigneeLoginId", value("#assignee")).param("milestoneId", value("#milestoneId option[selected]"))
                .param("labelIds", *selectedLabels.map { it.id.toString() }.toTypedArray())
                .param("dueDate", value("#issueDueDate")).param("parentIssueId", parent.id.toString())
                .param("temporaryUploadFiles", html.selectFirst("#upload")!!.attr("data-temporary-upload-files")))
                .andExpect(status().is3xxRedirection).andReturn().response.redirectedUrl!!
            val issue = issues.findByProjectAndNumber(project, saved.substringAfterLast('/').toLong())!!
            issue.body shouldBe body
            issue.state shouldBe State.DRAFT
            issue.isDraft shouldBe true
            attachments.findById(own.id!!).get().containerId shouldBe issue.id.toString()
            attachments.findById(foreign.id!!).get().containerType shouldBe ResourceType.NOT_A_RESOURCE
            attachments.findById(attached.id!!).get().containerId shouldBe parent.id.toString()
        }
    }
}
