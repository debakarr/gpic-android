package com.gpic.android.data.upload

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatcherTest {
    @Test fun groupsUpToMaxSizeAndKeepsOrder() = runBlocking {
        val sizes = mutableListOf<Int>()
        coroutineScope {
            val b = Batcher<Int, Int>(this, maxSize = 10, maxWaitMs = 200) { xs -> sizes += xs.size; xs.map { Result.success(it * 2) } }
            val out = (1..25).map { i -> async { b.submit(i) } }.awaitAll()
            assertEquals((1..25).map { it * 2 }, out)
            b.close()
        }
        assertEquals(25, sizes.sum())
        assertTrue("batches $sizes", sizes.all { it <= 10 } && sizes.size <= 4)
    }

    @Test fun perItemAndWholeBatchFailures() = runBlocking {
        coroutineScope {
            var calls = 0
            val b = Batcher<Int, Int>(this, maxSize = 5, maxWaitMs = 100) { xs ->
                if (++calls == 1) xs.map { if (it == 2) Result.failure(IllegalStateException("refused")) else Result.success(it) }
                else throw java.io.IOException("network")
            }
            val first = (1..3).map { i -> async { runCatching { b.submit(i) } } }.awaitAll()
            assertEquals(listOf(1, null, 3), first.map { it.getOrNull() })
            assertTrue(first[1].exceptionOrNull() is IllegalStateException)
            val second = runCatching { b.submit(9) }
            assertTrue(second.exceptionOrNull() is java.io.IOException)
            b.close()
        }
    }
}
