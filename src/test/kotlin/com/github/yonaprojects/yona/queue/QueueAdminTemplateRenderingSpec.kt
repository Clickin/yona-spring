package com.github.yonaprojects.yona.queue

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.user.UserState
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

class QueueAdminTemplateRenderingSpec @Autowired constructor(
    private val wac: WebApplicationContext,
    private val users: UserRepository,
) : AbstractIntegrationTest() {
    override fun extensions() = listOf(SpringExtension)
    private lateinit var mvc: MockMvc

    init {
        beforeSpec {
            users.save(User(loginId = "queue-template-admin", name = "Queue administrator",
                email = "queue-template-admin@example.com", state = UserState.SITE_ADMIN))
            mvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
        }

        describe("Queue page localization") {
            for (language in listOf("en", "ja", "ru", "uz")) {
                it("renders English queue messages for $language without leaking Korean text") {
                    val html = mvc.perform(get(QUEUE_PAGE).header("Accept-Language", language)
                        .with(user("queue-template-admin").roles("SITE_ADMIN")))
                        .andExpect(status().isOk).andReturn().response.contentAsString
                    val doc = Jsoup.parse(html)
                    doc.select("#queue-heading").text() shouldBe "Job queue"
                    Regex("[가-힣]").containsMatchIn(doc.select(".queue-admin").text()) shouldBe false
                    doc.select(".site-setting-nav li").last()!!.text() shouldBe "Job queue"
                    if (language == "en") Regex("[가-힣]").containsMatchIn(doc.body().text()) shouldBe false
                }
            }

            for ((method, request) in listOf(
                "GET" to get(QUEUE_PAGE).param("selected", "invalid"),
                "POST" to post("$QUEUE_PAGE/jobs/invalid/cancel")
                    .contentType("application/x-www-form-urlencoded").with(csrf()),
            )) {
                it("renders $method errors with the shared layout and current administrator") {
                    val result = mvc.perform(request.header("Accept-Language", "en")
                        .with(user("queue-template-admin").roles("SITE_ADMIN")))
                        .andExpect(status().isBadRequest).andReturn()
                    val doc = Jsoup.parse(result.response.contentAsString)
                    doc.select("[role=alert]").text() shouldBe "The request is invalid. (INVALID_REQUEST)"
                    (result.modelAndView!!.model["currentUser"] as User).loginId shouldBe "queue-template-admin"
                }
            }
        }
    }
}
