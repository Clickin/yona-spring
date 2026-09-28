package com.github.yonaprojects.yona.web

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.enumeration.State
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.project.ProjectUser
import com.github.yonaprojects.yona.domain.project.ProjectUserRepository
import com.github.yonaprojects.yona.domain.pullrequest.PullRequest
import com.github.yonaprojects.yona.domain.pullrequest.PullRequestRepository
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.role.RoleType
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.YonaUserDetails
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// PR changes renders the permission boundary and the actual inline-review POST form.
class PullRequestInlineReviewCommentWiringTemplateRenderingSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val projectUserRepository: ProjectUserRepository,
    private val roleRepository: RoleRepository,
    private val pullRequestRepository: PullRequestRepository
) : AbstractIntegrationTest() {

    override fun extensions() = listOf(SpringExtension)

    private lateinit var mockMvc: MockMvc

    init {
        beforeSpec {
            mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
                .build()
        }

        describe("PR changes 탭의 인라인 리뷰 댓글 작성 배선") {
            val member = userRepository.findByLoginId("prdiffui-member").orElseGet {
                userRepository.save(User(loginId = "prdiffui-member", name = "PR인라인멤버", email = "prdiffui-member@yona.io"))
            }
            val roleMember = roleRepository.findById(RoleType.MEMBER.roleType).orElseGet {
                roleRepository.save(Role(id = RoleType.MEMBER.roleType, name = "MEMBER"))
            }
            val project = projectRepository.findAll().find { it.name == "prdiffui-proj" } ?: projectRepository.save(
                Project(name = "prdiffui-proj", owner = "prdiffui-owner", projectScope = ProjectScope.PUBLIC, vcs = "GIT")
            )
            if (!projectUserRepository.existsByProjectIdAndUserId(project.id!!, member.id!!)) {
                projectUserRepository.save(ProjectUser(project = project, user = member, role = roleMember))
            }
            val memberDetails = YonaUserDetails(
                id = member.id!!,
                loginId = member.loginId,
                passwordVal = "hashed",
                passwordSalt = "salt",
                authoritiesVal = AuthorityUtils.createAuthorityList("ROLE_ACTIVE")
            )
            val pr = pullRequestRepository.findAll().find { it.title == "인라인UI테스트PR" } ?: pullRequestRepository.save(
                PullRequest(
                    title = "인라인UI테스트PR", body = "본문", toProject = project, fromProject = project,
                    toBranch = "master", fromBranch = "feature-inline", contributor = member, state = State.OPEN, number = 1L
                )
            )

            it("리뷰 댓글 작성 권한이 있는 멤버에게는 #changes에 canReviewComment=true와 POST URL이 내려가고 CodeCommentBox 팝업이 포함돼야 한다") {
                val result = mockMvc.perform(
                    get("/${project.owner}/${project.name}/pull/${pr.number}/changes").with(SecurityMockMvcRequestPostProcessors.user(memberDetails))
                ).andReturn()

                val body = result.response.contentAsString
                val doc = Jsoup.parse(body)

                doc.select("#changes").attr("data-can-review-comment") shouldBe "true"
                doc.select("#changes").attr("data-review-comment-post-url") shouldBe
                    "/${project.owner}/${project.name}/pullRequest/${pr.id}/comments"

                doc.select("#review-form form").size shouldBe 1
            }

            it("새 라인/범위 댓글은 서버 렌더링된 contents textarea와 CSRF 필드로 제출할 수 있어야 한다") {
                val result = mockMvc.perform(
                    get("/${project.owner}/${project.name}/pull/${pr.number}/changes").with(SecurityMockMvcRequestPostProcessors.user(memberDetails))
                ).andReturn()

                val body = result.response.contentAsString
                val doc = Jsoup.parse(body)
                val reviewForm = doc.select("#review-form form")

                reviewForm.attr("action") shouldBe
                    "/${project.owner}/${project.name}/pullRequest/${pr.id}/comments"
                reviewForm.attr("method") shouldBe "post"
                reviewForm.attr("enctype") shouldBe "multipart/form-data"
                reviewForm.select("textarea[name=contents]").size shouldBe 1
                reviewForm.select("textarea[name=contents]").hasAttr("disabled") shouldBe false
                reviewForm.select("input[name=contents]").size shouldBe 0
                reviewForm.select("input[type=hidden][name=_csrf]").size shouldBe 1
                reviewForm.select("input[name=_csrf]").`val`().isBlank() shouldBe false
            }
        }
    }
}
