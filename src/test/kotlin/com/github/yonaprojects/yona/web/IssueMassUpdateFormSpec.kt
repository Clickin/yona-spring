package com.github.yonaprojects.yona.web

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.MutablePropertyValues
import org.springframework.web.bind.WebDataBinder

class IssueMassUpdateFormSpec : DescribeSpec({
    it("omitted assignments preserve the list while the field marker clears it") {
        val omitted = IssueMassUpdateForm()
        WebDataBinder(omitted).bind(MutablePropertyValues(mapOf("state" to "CLOSED")))
        omitted.assigneeIds shouldBe null
        val cleared = IssueMassUpdateForm()
        WebDataBinder(cleared).bind(MutablePropertyValues(mapOf("_assigneeIds" to "on")))
        cleared.assigneeIds shouldBe emptyList()
    }
    it("binds every selected assignee ID") {
        val form = IssueMassUpdateForm()
        WebDataBinder(form).bind(MutablePropertyValues(mapOf("assigneeIds" to arrayOf("12", "34"))))
        form.assigneeIds shouldBe listOf(12L, 34L)
    }
})
