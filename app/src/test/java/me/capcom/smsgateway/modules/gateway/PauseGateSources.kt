package me.capcom.smsgateway.modules.gateway

import java.io.File

/**
 * V2-L23: the pause gates sit in Android classes (workers, services) that a plain JVM test
 * cannot start. This reads their source so a test fails when a gate is removed or moved
 * after the call it guards.
 */
internal object PauseGateSources {

    private val roots = listOf(
        "src/main/java/me/capcom/smsgateway",
        "app/src/main/java/me/capcom/smsgateway",
    )

    fun read(relativePath: String): String {
        val file = roots.map { File(it, relativePath) }.firstOrNull { it.isFile }
            ?: throw AssertionError("source not found: $relativePath (cwd ${File(".").absoluteFile})")
        return file.readText().replace("\r\n", "\n")
    }

    /** The text from [marker] to the closing brace of its body. */
    fun functionBody(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) throw AssertionError("function not found: $marker")
        val open = source.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced body: $marker")
    }
}
