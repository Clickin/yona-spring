package com.github.yonaprojects.yona.domain.vcs

import org.tmatesoft.svn.core.SVNProperties
import org.tmatesoft.svn.core.SVNRevisionProperty
import java.time.Instant
import java.time.format.DateTimeParseException

/** Validate source metadata before publishing a verified revision; never truncate the original author. */
internal fun svnMirrorRevisionDate(properties: SVNProperties): Instant {
    // 255 UTF-16 units fit IssueEvent.senderLoginId across all six supported databases.
    if ((properties.getStringValue(SVNRevisionProperty.AUTHOR)?.length ?: 0) > 255) {
        throw MirrorFailure("AUTHOR_TOO_LONG", attention = true)
    }
    return try {
        Instant.parse(properties.getStringValue(SVNRevisionProperty.DATE)
            ?: throw MirrorFailure("INVALID_REVISION_DATE", attention = true))
    } catch (_: DateTimeParseException) {
        throw MirrorFailure("INVALID_REVISION_DATE", attention = true)
    }
}
