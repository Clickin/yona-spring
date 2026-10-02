package com.github.yonaprojects.yona.domain.webhook

import com.github.yonaprojects.yona.domain.enumeration.ResourceType

/** Only emitted by comment creation; delivery waits for the creating transaction to commit. */
data class CommentCreatedWebhookEvent(
    val resourceType: ResourceType,
    val resourceId: String,
    val senderId: Long?
)
