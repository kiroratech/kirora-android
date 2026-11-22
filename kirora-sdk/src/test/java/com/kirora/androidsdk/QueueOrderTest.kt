package com.kirora.androidsdk

import com.kirora.androidsdk.Kirora.Companion.launchInQueue
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/** The event queue keeps call order even when a task suspends (as Room's DAO calls do). */
internal class QueueOrderTest {

    @Test
    fun aTaskThatSuspendsFinishesBeforeTheNextOneStarts() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(Dispatchers.Default)
        val first = scope.launchInQueue {
            order.add("write starts")
            withContext(Dispatchers.IO) { delay(200) } // as a Room insert on its own executor
            order.add("write ends")
        }
        val second = scope.launchInQueue { order.add("adoption") }
        joinAll(first, second)
        assertEquals(listOf("write starts", "write ends", "adoption"), order.toList())
    }
}
