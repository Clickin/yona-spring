package com.github.yonaprojects.yona.queue.acceptance

import com.github.yonaprojects.yona.YonaApplication
import com.github.yonaprojects.yona.queue.QueueControl
import com.github.yonaprojects.yona.queue.QueueWorkerRuntime
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/** Test-classpath process entrypoint; boots the real Yona app with no web listener. */
object QueueWorkerProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val application = SpringApplicationBuilder(
            YonaApplication::class.java,
            QueueAcceptanceWorkerProcessConfiguration::class.java,
        ).web(WebApplicationType.NONE).run(*args)
        val runtime = application.getBean(QueueWorkerRuntime::class.java)
        val queueControl = application.getBean(QueueControl::class.java)
        val instanceId = application.environment.getRequiredProperty("yona.queue.instance-id")
        System.out.println("QUEUE_ACCEPTANCE_READY:$instanceId")
        System.out.flush()
        try {
            while (true) {
                val line = readLine() ?: break
                when {
                    line == "stop-claiming" -> {
                        runtime.stopClaimingNewWork()
                        System.out.println("QUEUE_ACCEPTANCE_STOPPED:$instanceId")
                    }
                    line == "close" -> {
                        runtime.stopClaimingNewWork()
                        System.out.println("QUEUE_ACCEPTANCE_SHUTDOWN_STARTED:$instanceId")
                        System.out.flush()
                        application.close()
                        System.out.println("QUEUE_ACCEPTANCE_CLOSED:$instanceId")
                        System.out.flush()
                        return
                    }
                    line.startsWith("cancel\t") -> {
                        val fields = line.split('\t')
                        val commandId = fields[2]
                        try {
                            val result = queueControl.cancel(fields[1].toLong(), commandId, fields[3].toLong())
                            System.out.println("QUEUE_ACCEPTANCE_CONTROL:$commandId:${result.status}")
                        } catch (failure: Exception) {
                            System.out.println("QUEUE_ACCEPTANCE_CONTROL_ERROR:$commandId:${failure.message ?: failure.javaClass.name}")
                        }
                    }
                }
                System.out.flush()
            }
        } finally {
            if (application.isActive) application.close()
        }
    }
}
