package com.github.yonaprojects.yona.domain.issue

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

/**
 * 외부 검색 엔진 컨테이너. Testcontainers는 클래스를 읽는 것만으로 Docker 탐색을 시작하므로
 * 외부 엔진 테스트를 켠 경우에만 이 객체를 건드린다.
 * 한국어 분석(`analysis-nori`) 플러그인은 시작할 때 설치한다.
 */
internal object SearchEngineContainers {
    private val started = mutableMapOf<String, GenericContainer<Nothing>>()

    private fun container(image: String, install: String, boot: String, environment: Map<String, String>) =
        GenericContainer<Nothing>(image).apply {
            environment.forEach { (key, value) -> withEnv(key, value) }
            withExposedPorts(9200)
            withCreateContainerCmdModifier { it.withEntrypoint("sh", "-c", "$install && exec $boot") }
            waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(5)))
        }

    /** [backend]는 `opensearch` 또는 `elasticsearch`. 반환값은 REST 기본 주소. */
    @Synchronized
    fun url(backend: String): String {
        val running = started.getOrPut(backend) {
            when (backend) {
                SearchBackends.OPENSEARCH -> container("opensearchproject/opensearch:2.19.1",
                    "bin/opensearch-plugin install --batch analysis-nori", "./opensearch-docker-entrypoint.sh",
                    mapOf("discovery.type" to "single-node", "DISABLE_SECURITY_PLUGIN" to "true",
                        "DISABLE_INSTALL_DEMO_CONFIG" to "true", "OPENSEARCH_JAVA_OPTS" to "-Xms512m -Xmx512m"))
                SearchBackends.ELASTICSEARCH -> container("docker.elastic.co/elasticsearch/elasticsearch:8.17.0",
                    "bin/elasticsearch-plugin install --batch analysis-nori",
                    "/bin/tini -- /usr/local/bin/docker-entrypoint.sh eswrapper",
                    mapOf("discovery.type" to "single-node", "xpack.security.enabled" to "false", "ES_JAVA_OPTS" to "-Xms512m -Xmx512m"))
                else -> error("unsupported backend: $backend")
            }.also { it.start() }
        }
        return "http://${running.host}:${running.getMappedPort(9200)}"
    }

    @Synchronized
    fun stopAll() {
        started.values.forEach { it.stop() }
        started.clear()
    }
}
