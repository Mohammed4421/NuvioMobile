@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package harness

import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * Dispatchers.Main for the tests: a queue the test thread runs by hand, so the order of every step on
 * the main thread (where the app runs the queue and selectProfile) is decided by the test.
 */
object TestMain : MainCoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    override val immediate: MainCoroutineDispatcher get() = this
    override fun isDispatchNeeded(context: CoroutineContext) = true
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }

    /** Runs main-thread work until nothing is left (waiting briefly for Dispatchers.Default hops). */
    fun drain() {
        var idle = 0
        while (idle < 5) {
            val task = tasks.poll()
            if (task != null) { task.run(); idle = 0 } else { Thread.sleep(20); idle++ }
        }
    }
}

class TestMainFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = TestMain
}
