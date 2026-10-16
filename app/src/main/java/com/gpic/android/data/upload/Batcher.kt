package com.gpic.android.data.upload

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Collects single requests from many upload workers into one call. A batch goes out when it has [maxSize]
 * requests or [maxWaitMs] after its first one, whichever comes first; batches run one after another
 * (Google handles one account's commits serially anyway). [run] returns one result per input, in order.
 */
class Batcher<I, O>(
    scope: CoroutineScope,
    private val maxSize: Int,
    private val maxWaitMs: Long,
    private val run: suspend (List<I>) -> List<Result<O>>,
) {
    private class Request<I, O>(val input: I, val reply: CompletableDeferred<O>)

    private val queue = Channel<Request<I, O>>(Channel.UNLIMITED)

    private val worker: Job = scope.launch {
        while (true) {
            val batch = mutableListOf(queue.receive())
            val deadline = System.currentTimeMillis() + maxWaitMs
            while (batch.size < maxSize) {
                val left = deadline - System.currentTimeMillis()
                // Take whatever is already queued without waiting, then wait out the rest of the window.
                var next = queue.tryReceive().getOrNull()
                if (next == null && left > 0) next = withTimeoutOrNull(left) { queue.receive() }
                batch += next ?: break
            }
            val results = try {
                run(batch.map { it.input }).also { check(it.size == batch.size) { "batch returned ${it.size} results for ${batch.size}" } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                batch.forEach { it.reply.cancel(e) }
                throw e
            } catch (e: Exception) {
                List(batch.size) { Result.failure(e) }
            }
            batch.zip(results).forEach { (r, res) -> res.fold({ r.reply.complete(it) }, { r.reply.completeExceptionally(it) }) }
        }
    }

    suspend fun submit(input: I): O {
        val r = Request<I, O>(input, CompletableDeferred())
        queue.send(r)
        return r.reply.await()
    }

    fun close() {
        worker.cancel()
        queue.close()
    }
}
