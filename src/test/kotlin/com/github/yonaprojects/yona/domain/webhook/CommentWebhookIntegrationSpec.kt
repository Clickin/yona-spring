package com.github.yonaprojects.yona.domain.webhook

import com.github.yonaprojects.yona.AbstractIntegrationTest
import com.github.yonaprojects.yona.domain.board.Posting
import com.github.yonaprojects.yona.domain.board.PostingRepository
import com.github.yonaprojects.yona.domain.board.PostingCommentRepository
import com.github.yonaprojects.yona.domain.comment.CommentService
import com.github.yonaprojects.yona.domain.enumeration.ResourceType
import com.github.yonaprojects.yona.domain.enumeration.WebhookType
import com.github.yonaprojects.yona.domain.issue.Issue
import com.github.yonaprojects.yona.domain.issue.IssueRepository
import com.github.yonaprojects.yona.domain.issue.IssueCommentRepository
import com.github.yonaprojects.yona.domain.notification.NotificationEventRepository
import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.project.ProjectRepository
import com.github.yonaprojects.yona.domain.project.ProjectScope
import com.github.yonaprojects.yona.domain.pullrequest.*
import com.github.yonaprojects.yona.domain.user.User
import com.github.yonaprojects.yona.domain.user.UserRepository
import com.github.yonaprojects.yona.domain.support.CodeRange
import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CommentWebhookIntegrationSpec @Autowired constructor(
    private val comments: CommentService,
    private val reviews: CodeReviewService,
    private val users: UserRepository,
    private val projects: ProjectRepository,
    private val issues: IssueRepository,
    private val postings: PostingRepository,
    private val pullRequests: PullRequestRepository,
    private val issueComments: IssueCommentRepository,
    private val postingComments: PostingCommentRepository,
    private val reviewComments: ReviewCommentRepository,
    private val commitComments: CommitCommentRepository,
    private val commentThreads: CommentThreadRepository,
    private val webhooks: WebhookRepository,
    private val notifications: NotificationEventRepository,
    transactionManager: PlatformTransactionManager
) : AbstractIntegrationTest() {
    init { describe("Comment webhooks") {
        it("committed comments with no personal receivers deliver once to their selected hook, never on rollback") {
            val tx = TransactionTemplate(transactionManager)
            val received = LinkedBlockingQueue<Pair<String, String>>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.use {
                    received.add(exchange.requestURI.path to exchange.requestBody.bufferedReader().readText())
                    exchange.sendResponseHeaders(204, -1)
                }
            }
            server.start()
            val suffix = System.nanoTime().toString()
            val author = users.save(User(loginId = "hook-$suffix", name = "Comment author", email = "hook-$suffix@example.com"))
            val project = projects.save(Project(owner = author.loginId, name = "hook-$suffix", projectScope = ProjectScope.PRIVATE))
            val issue = issues.save(Issue(project = project, number = 11, title = "Issue parent", authorId = author.id))
            val posting = postings.save(Posting(project = project, number = 12, title = "Posting parent", authorId = author.id))
            val pr = pullRequests.save(PullRequest(toProject = project, fromProject = project, contributor = author, number = 13, title = "PR parent", body = "Not the review body"))
            val hookIds = mutableListOf<Long>()
            val issueIds = mutableListOf<Long>()
            val postingIds = mutableListOf<Long>()
            val reviewIds = mutableListOf<Long>()
            val commitIds = mutableListOf<Long>()
            val threadIds = mutableListOf<Long>()
            try {
                for (kind in listOf("issue", "posting", "review", "commit")) {
                    hookIds += webhooks.save(Webhook(
                        project = project, payloadUrl = "http://127.0.0.1:${server.address.port}/$kind", webhookType = WebhookType.JSON,
                        issueComment = kind == "issue", postingComment = kind == "posting",
                        reviewComment = kind == "review", commitComment = kind == "commit"
                    )).id!!
                }
                tx.executeWithoutResult {
                    val comment = comments.createIssueComment(issue.id!!, "Issue comment body", author, null)
                    issueIds += comment.id!!
                    val postComment = comments.createPostingComment(posting.id!!, "Posting comment body", author, null)
                    postingIds += postComment.id!!
                    val review = reviews.createReviewComment(project, pr, null, "PR review body", null, null, author)
                    reviewIds += review.id!!
                    threadIds += review.thread!!.id!!
                    val commitReview = reviews.createReviewComment(project, null, "abc123", "Commit review body", null, null, author)
                    reviewIds += commitReview.id!!
                    threadIds += commitReview.thread!!.id!!
                    val commit = reviews.createCommitComment(project, "abc123", "Commit comment body", null, null, null, author)
                    commitIds += commit.id!!
                    val ranged = reviews.createReviewComment(project, pr, null, "PR diff review body",
                        CodeRange(path = "file.txt", startLine = 0, endLine = 0, startSide = CodeRange.Side.B, endSide = CodeRange.Side.B), null, author)
                    reviewIds += ranged.id!!
                    threadIds += ranged.thread!!.id!!
                }
                val expected = mapOf(
                    "Issue comment body" to Triple("issue", "ISSUE_POST", "issue/11"),
                    "Posting comment body" to Triple("posting", "BOARD_POST", "post/12"),
                    "PR review body" to Triple("review", "PULL_REQUEST", "pull/13"),
                    "PR diff review body" to Triple("review", "PULL_REQUEST", "pull/13"),
                    "Commit review body" to Triple("commit", "COMMIT", "commit/abc123"),
                    "Commit comment body" to Triple("commit", "COMMIT", "commit/abc123")
                )
                val bodies = mutableSetOf<String>()
                repeat(6) {
                    val (path, payload) = received.poll(15, TimeUnit.SECONDS).shouldNotBeNull()
                    val json = ObjectMapper().readTree(payload)
                    val comment = json.path("comment")
                    val body = comment.path("body").asText()
                    val (kind, parentType, parentPath) = expected[body].shouldNotBeNull()
                    bodies.add(body) shouldBe true
                    path shouldBe "/$kind"
                    json.path("action").asText() shouldBe "created"
                    comment.path("author").path("id").asLong() shouldBe author.id
                    comment.path("author").path("login").asText() shouldBe author.loginId
                    val parent = json.path("parent")
                    parent.path("resourceType").asText() shouldBe parentType
                    when (parentType) {
                        "ISSUE_POST" -> {
                            parent.path("id").asLong() shouldBe issue.id
                            parent.path("number").asLong() shouldBe 11L
                            parent.path("title").asText() shouldBe "Issue parent"
                        }
                        "BOARD_POST" -> {
                            parent.path("id").asLong() shouldBe posting.id
                            parent.path("number").asLong() shouldBe 12L
                            parent.path("title").asText() shouldBe "Posting parent"
                        }
                        "PULL_REQUEST" -> {
                            parent.path("id").asLong() shouldBe pr.id
                            parent.path("number").asLong() shouldBe 13L
                            parent.path("title").asText() shouldBe "PR parent"
                        }
                        "COMMIT" -> parent.path("id").asText() shouldBe "abc123"
                    }
                    parent.path("url").asText().endsWith("/${project.owner}/${project.name}/$parentPath") shouldBe true
                    val changesPath = if (parentType == "PULL_REQUEST") "/changes" else ""
                    val anchor = if (json.path("resourceType").asText() == "COMMIT_COMMENT") "commit-comment-" else "comment-"
                    comment.path("url").asText() shouldBe "${parent.path("url").asText()}$changesPath#$anchor${comment.path("id").asText()}"
                    val type = ResourceType.valueOf(json.path("resourceType").asText())
                    notifications.findFirstByResourceTypeAndResourceIdAndCreatedAfterOrderByIdDesc(type, comment.path("id").asText(), Instant.EPOCH) shouldBe null
                }
                bodies shouldBe expected.keys
                tx.executeWithoutResult { status ->
                    comments.createIssueComment(issue.id!!, "Rolled back", author, null)
                    status.setRollbackOnly()
                }
                received.poll(1, TimeUnit.SECONDS) shouldBe null
            } finally {
                server.stop(0)
                tx.executeWithoutResult {
                    webhooks.deleteAllById(hookIds)
                    issueComments.deleteAllById(issueIds)
                    postingComments.deleteAllById(postingIds)
                    reviewComments.deleteAllById(reviewIds)
                    commitComments.deleteAllById(commitIds)
                    commentThreads.deleteAllById(threadIds)
                    pullRequests.deleteById(pr.id!!)
                    issues.deleteById(issue.id!!)
                    postings.deleteById(posting.id!!)
                    projects.deleteById(project.id!!)
                    users.deleteById(author.id!!)
                }
            }
        }
    } }
}
