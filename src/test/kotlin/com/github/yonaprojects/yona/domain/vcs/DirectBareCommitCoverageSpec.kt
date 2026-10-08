package com.github.yonaprojects.yona.domain.vcs

import com.github.yonaprojects.yona.domain.project.Project
import com.github.yonaprojects.yona.domain.user.User
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

class DirectBareCommitCoverageSpec : DescribeSpec({
    describe("direct BareCommit lifecycle") {
        it("reopens a scoped repository for successive commits without leaving scratch files") {
            val base = Files.createTempDirectory("yona-barecommit-direct").toFile()
            try {
                val directory = File(base, "tester/repo.git")
                Git.init().setDirectory(directory).setBare(true).setInitialBranch("main").call().close()
                val project = Project(id = 1L, owner = "tester", name = "repo")
                val user = User(id = 1L, loginId = "tester", name = "Tester", email = "tester@example.com")
                val bare = BareCommit(project, user, base.absolutePath)
                bare.setRefName("refs/heads/main")

                val first = bare.commitTextFile("test.txt", "first", "first commit")
                val second = bare.commitTextFile("main", "nested/test2.txt", "second", "second commit")

                first shouldNotBe null
                second shouldNotBe first
                val repository = GitRepository("tester", "repo", base.absolutePath, { _, _ -> user })
                repository.getRawFile("main", "test.txt").toString(Charsets.UTF_8) shouldBe "first\n"
                repository.getRawFile("main", "nested/test2.txt").toString(Charsets.UTF_8) shouldBe "second"
                File(directory, "nested/test2.txt").exists() shouldBe false
            } finally {
                base.deleteRecursively()
            }
        }
    }
})
