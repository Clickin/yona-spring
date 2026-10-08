package com.github.yonaprojects.yona.domain.vcs

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "yona.repository-mirror")
class RepositoryMirrorProperties {
    var enabled = false
    var egressRestricted = false
    var writerNodeId = ""
    var nodeId = ""
    var workers = 2
    var batchSize = 50
    var leaseSeconds = 60L
    var syncSeconds = 60L
    var allowedOrigins: List<String> = emptyList()
    var allowedCidrs: List<String> = emptyList()
    var credentials: Map<String, String> = emptyMap()
    var proxyHost = ""
    var proxyPort = 0

    fun requireActivation() {
        if (!enabled) throw MirrorFailure("FEATURE_DISABLED")
        // ponytail: one designated writer and a loopback CONNECT proxy; no automatic failover.
        if (!egressRestricted || nodeId.isBlank() || nodeId != writerNodeId ||
            proxyHost !in setOf("127.0.0.1", "::1") || proxyPort !in 1..65535 ||
            allowedOrigins.isEmpty() || workers !in 1..8 || batchSize !in 1..1000 ||
            leaseSeconds !in 15..3600 || syncSeconds !in 5..86400
        ) throw MirrorFailure("ACTIVATION_REQUIRED")
    }
}
