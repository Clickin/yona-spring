package com.github.yonaprojects.yona.domain.webhook

import java.time.Instant

// The upstream SVN author is not a Yona account or a user pushing to this mirror.
data class SvnMirrorCommit(
    val revision: String,
    val author: String?,
    val created: Instant,
    val message: String
)
