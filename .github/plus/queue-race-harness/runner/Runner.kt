package runner

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/** Runs each scenario in its own class loader: a fresh process with its own singletons. */
fun main(args: Array<String>) {
    val classpath = args[0].split(":").map { File(it).toURI().toURL() }.toTypedArray()
    val plan = listOf(
        listOf("r1_switchDuringMetaFetch"),
        listOf("r1b_abaDuringMetaFetch"),
        listOf("r2_switchDuringResolveOfChosenEpisode"),
        listOf("r3_switchDuringResolveOfQueuedEpisode"),
        listOf("r4_abaLateOldAnswerAfterNewSearch"),
        listOf("r5_abaWithoutMainRunningBetween"),
        listOf("r6_abaOldAnswerFirst"),
        listOf("r7_removeThenLateAnswer"),
        listOf("r8_restartPhase1", "r8_restartPhase2"),
    )
    var failed = 0
    for (steps in plan) {
        val store = Files.createTempDirectory("nuvio-store").toString()
        System.setProperty("harness.store", store)
        for (step in steps) {
            val loader = URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())
            val out = try {
                loader.loadClass("harness.Scenarios").getMethod(step).invoke(null) as String
            } catch (t: Throwable) {
                "FAIL $step | crashed: ${(t.cause ?: t)}"
            }
            if (out.startsWith("FAIL")) failed++
            println(out)
            loader.close()
        }
    }
    println(if (failed == 0) "ALL PASS" else "$failed FAILED")
    if (failed != 0) kotlin.system.exitProcess(1)
}
