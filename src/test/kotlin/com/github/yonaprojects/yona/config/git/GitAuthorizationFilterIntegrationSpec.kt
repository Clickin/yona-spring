package com.github.yonaprojects.yona.config.git

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.project.*
import com.github.yonaprojects.yona.domain.role.Role
import com.github.yonaprojects.yona.domain.role.RoleRepository
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.servlet.Filter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.*

class GitAuthorizationFilterIntegrationSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val projectUserRepository: ProjectUserRepository,
    private val roleRepository: RoleRepository
) : AbstractIntegrationTest() {

    override fun extensions() = listOf(SpringExtension)

    private lateinit var mockMvc: MockMvc
    private var reachedApplication = false

    companion object {
        private val lfsBaseDir = Files.createTempDirectory("repository-access-it-").toFile()

        @JvmStatic
        @DynamicPropertySource
        fun storageProperties(registry: DynamicPropertyRegistry) {
            registry.add("yona.lfs.base-dir") { lfsBaseDir.absolutePath }
        }
    }

    init {
        beforeSpec {
            val securityFilter = wac.getBean("springSecurityFilterChain", Filter::class.java)
            mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .addFilters<DefaultMockMvcBuilder>(securityFilter, Filter { request, response, chain ->
                    reachedApplication = true
                    chain.doFilter(request, response)
                })
                .build()
        }

        afterSpec { lfsBaseDir.deleteRecursively() }

        describe("Git HTTP 인증/인가 통합 테스트") {
            beforeEach {
                projectUserRepository.deleteAll()
                projectRepository.deleteAll()
                userRepository.deleteAll()
                roleRepository.deleteAll()
            }

            it("repository access validation case 01") {
                val member = userRepository.save(User(
                    loginId = "member", email = "member@example.com",
                    password = hashPassword("pass123", "saltsalt"), passwordSalt = "saltsalt"
                ))
                val role = roleRepository.save(Role(id = 1L, name = "manager", active = true))
                val project = projectRepository.save(Project(
                    owner = "owner", name = "repo", previousOwnerLoginId = "old-owner", previousName = "old-repo",
                    vcs = "GIT", projectScope = ProjectScope.PUBLIC
                ))
                projectUserRepository.save(ProjectUser(user = member, project = project, role = role))
                val auth = "Basic " + Base64.getEncoder().encodeToString("member:pass123".toByteArray())
                val oid = "a".repeat(64)
                val objectFile = File(lfsBaseDir, "owner/repo.git/objects/aa/aa/$oid")
                objectFile.parentFile.mkdirs()
                objectFile.writeText("existing object")

                listOf("SVN", "SUBVERSION", "HG", "MERCURIAL").forEach { vcs ->
                    project.vcs = vcs
                    projectRepository.saveAndFlush(project)
                    listOf(null, auth).forEach { credentials ->
                        val requests = listOf(
                            get("/git/owner/repo.git/info/refs").param("service", "git-upload-pack"),
                            get("/git/old-owner/old-repo/info/refs").param("service", "git-receive-pack"),
                            post("/git/owner/repo.git/git-upload-pack"),
                            post("/git/owner/repo.git/git-receive-pack"),
                            post("/git/owner/repo.git/info/lfs/objects/batch")
                                .contentType("application/vnd.git-lfs+json").content("""{"operation":"upload","objects":[]}"""),
                            get("/git-lfs/owner/repo.git/objects/$oid"),
                            put("/git-lfs/owner/repo.git/objects/$oid").with(csrf()).content("replacement"),
                            put("/git-lfs/owner/repo/objects/$oid").with(csrf()).content("new object")
                        )
                        requests.forEach { request ->
                            if (credentials != null) request.header(HttpHeaders.AUTHORIZATION, credentials)
                            reachedApplication = false
                            mockMvc.perform(request).andReturn().response.status shouldBe 404
                            reachedApplication shouldBe false
                        }
                    }
                    objectFile.readText() shouldBe "existing object"
                    File(lfsBaseDir, "owner/repo").exists() shouldBe false
                }

                project.vcs = "GIT"
                projectRepository.saveAndFlush(project)
                reachedApplication = false
                val response = mockMvc.perform(get("/git-lfs/owner/repo.git/objects/$oid")).andReturn().response
                response.status shouldBe 200
                response.contentAsString shouldBe "existing object"
                reachedApplication shouldBe true
                mockMvc.perform(
                    put("/git-lfs/owner/repo.git/objects/$oid").with(csrf())
                        .header(HttpHeaders.AUTHORIZATION, auth).content("updated object")
                ).andReturn().response.status shouldBe 201
                objectFile.readText() shouldBe "updated object"
            }

            it("repository access validation case 02") {
                projectRepository.save(Project(owner = "owner", name = "repo", vcs = "GIT", projectScope = ProjectScope.PUBLIC))
                val project = projectRepository.save(Project(
                    owner = "new-owner", name = "new-repo", previousOwnerLoginId = "old-owner", previousName = "old-repo",
                    vcs = "GIT", projectScope = ProjectScope.PUBLIC
                ))
                listOf("/git/owner/repo", "/git/old-owner/old-repo.git").forEach { path ->
                    reachedApplication = false
                    mockMvc.perform(get("$path/info/refs").param("service", "git-upload-pack"))
                    reachedApplication shouldBe true
                }
                listOf("GIT", "SVN", "SUBVERSION", "HG", "MERCURIAL").forEach { vcs ->
                    project.vcs = vcs
                    projectRepository.saveAndFlush(project)
                    reachedApplication = false
                    mockMvc.perform(get("/git/old-owner/old-repo.wiki.git/info/refs").param("service", "git-upload-pack"))
                    reachedApplication shouldBe true
                }
            }

            it("존재하지 않는 프로젝트 접근 시 404 Not Found를 응답해야 한다") {
                mockMvc.perform(get("/git/owner/non-exist.git"))
                    .andExpect { result ->
                        result.response.status shouldBe 404
                    }
            }

            it("PUBLIC 프로젝트의 clone(GET) 요청은 익명 사용자도 통과되어야 한다") {
                // Given
                projectRepository.save(
                    Project(
                        name = "public-repo",
                        owner = "gildong",
                        projectScope = ProjectScope.PUBLIC,
                        isCodeAccessibleMemberOnly = false,
                        createdDate = Instant.now()
                    )
                )

                // When & Then
                mockMvc.perform(get("/git/gildong/public-repo.git/info/refs?service=git-upload-pack"))
                    .andExpect { result ->
                        result.response.status shouldNotBe 401
                        result.response.status shouldNotBe 403
                    }
            }

            it("PUBLIC 프로젝트라도 push(POST) 요청 시 익명 사용자는 401 Unauthorized를 응답해야 한다") {
                // Given
                projectRepository.save(
                    Project(
                        name = "public-repo",
                        owner = "gildong",
                        projectScope = ProjectScope.PUBLIC,
                        isCodeAccessibleMemberOnly = false,
                        createdDate = Instant.now()
                    )
                )

                // When & Then
                mockMvc.perform(post("/git/gildong/public-repo.git/git-receive-pack"))
                    .andExpect { result ->
                        result.response.status shouldBe 401
                        result.response.getHeader("WWW-Authenticate") shouldBe "Basic realm=\"Git Repository\""
                    }
            }

            it("PRIVATE 프로젝트의 clone 요청 시 익명 사용자는 401 Unauthorized를 응답해야 한다") {
                // Given
                projectRepository.save(
                    Project(
                        name = "private-repo",
                        owner = "gildong",
                        projectScope = ProjectScope.PRIVATE,
                        createdDate = Instant.now()
                    )
                )

                // When & Then
                mockMvc.perform(get("/git/gildong/private-repo.git/info/refs?service=git-upload-pack"))
                    .andExpect { result ->
                        result.response.status shouldBe 401
                    }
            }

            it("PRIVATE 프로젝트 요청 시 멤버가 아닌 로그인 유저는 403 Forbidden을 응답해야 한다") {
                // Given
                projectRepository.save(
                    Project(
                        name = "private-repo",
                        owner = "gildong",
                        projectScope = ProjectScope.PRIVATE,
                        createdDate = Instant.now()
                    )
                )

                // 철수 계정 생성
                val salt = "saltsalt"
                val hashedPw = hashPassword("pass123", salt)
                userRepository.save(
                    User(
                        loginId = "chulsoo",
                        name = "김철수",
                        email = "chulsoo@example.com",
                        password = hashedPw,
                        passwordSalt = salt,
                        createdDate = Instant.now()
                    )
                )

                val basicHeader = "Basic " + Base64.getEncoder().encodeToString("chulsoo:pass123".toByteArray())

                // When & Then
                mockMvc.perform(
                    get("/git/gildong/private-repo.git/info/refs?service=git-upload-pack")
                        .header(HttpHeaders.AUTHORIZATION, basicHeader)
                )
                    .andExpect { result ->
                        result.response.status shouldBe 403
                    }
            }

            it("PRIVATE 프로젝트 요청 시 멤버인 로그인 유저는 접근이 허용되어야 한다") {
                // Given
                val role = roleRepository.save(Role(id = 1L, name = "manager", active = true))
                val member = userRepository.save(
                    User(
                        loginId = "chulsoo",
                        name = "김철수",
                        email = "chulsoo@example.com",
                        password = hashPassword("pass123", "saltsalt"),
                        passwordSalt = "saltsalt",
                        createdDate = Instant.now()
                    )
                )
                val project = projectRepository.save(
                    Project(
                        name = "private-repo",
                        owner = "gildong",
                        projectScope = ProjectScope.PRIVATE,
                        createdDate = Instant.now()
                    )
                )
                projectUserRepository.save(ProjectUser(user = member, project = project, role = role))

                val basicHeader = "Basic " + Base64.getEncoder().encodeToString("chulsoo:pass123".toByteArray())

                // When & Then
                mockMvc.perform(
                    get("/git/gildong/private-repo.git/info/refs?service=git-upload-pack")
                        .header(HttpHeaders.AUTHORIZATION, basicHeader)
                )
                    .andExpect { result ->
                        result.response.status shouldNotBe 401
                        result.response.status shouldNotBe 403
                    }
            }
        }
    }

    private fun hashPassword(password: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.reset()
        digest.update(salt.toByteArray(StandardCharsets.UTF_8))
        var hashed = digest.digest(password.toByteArray(StandardCharsets.UTF_8))
        for (i in 1 until 1024) {
            digest.reset()
            hashed = digest.digest(hashed)
        }
        return Base64.getEncoder().encodeToString(hashed)
    }
}
