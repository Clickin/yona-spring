package com.github.yonaprojects.yona.web

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.MutablePropertyValues
import org.springframework.web.bind.WebDataBinder

class IssueFormSpec : DescribeSpec({
    it("distinguishes omitted assignments from an explicitly cleared edit picker") {
        val omitted = IssueForm()
        WebDataBinder(omitted).bind(MutablePropertyValues(mapOf("title" to "Updated")))
        omitted.assigneeLoginIds shouldBe null
        val cleared = IssueForm()
        WebDataBinder(cleared).bind(MutablePropertyValues(mapOf("_assigneeLoginIds" to "on")))
        cleared.assigneeLoginIds shouldBe emptyList()
    }
    it("binds all selected login IDs") {
        val form = IssueForm()
        WebDataBinder(form).bind(MutablePropertyValues(mapOf("assigneeLoginIds" to arrayOf("alice", "bob"))))
        form.assigneeLoginIds shouldBe listOf("alice", "bob")
    }
})
