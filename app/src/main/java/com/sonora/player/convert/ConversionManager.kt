package com.sonora.player.convert

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class ConversionStatus { QUEUED, RUNNING, DONE, FAILED }

class ConversionJob(val id: Long, val name: String, val source: Uri) {
    var status by mutableStateOf(ConversionStatus.QUEUED)
    var progress by mutableFloatStateOf(0f)

    /** Where the MP3 was saved when DONE, or the error text when FAILED. */
    var message by mutableStateOf("")
}

/**
 * Runs MP3 conversions one at a time in the background and exposes their progress as
 * Compose state. Lives for the whole app process, so rotating the screen doesn't cancel work.
 */
object ConversionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(1)
    private var nextId = 0L

    val jobs = mutableStateListOf<ConversionJob>()

    /** Increases every time a file finishes, so the UI knows to rescan the library. */
    var completedCount by mutableIntStateOf(0)
        private set

    fun enqueue(context: Context, items: List<Pair<Uri, String>>, bitrateKbps: Int) {
        val app = context.applicationContext
        for ((uri, name) in items) {
            val job = ConversionJob(nextId++, name, uri)
            jobs.add(job)
            scope.launch { gate.withPermit { run(app, job, bitrateKbps) } }
        }
    }

    fun clearFinished() {
        jobs.removeAll { it.status == ConversionStatus.DONE || it.status == ConversionStatus.FAILED }
    }

    private suspend fun run(context: Context, job: ConversionJob, bitrateKbps: Int) {
        job.status = ConversionStatus.RUNNING
        try {
            val saved = withContext(Dispatchers.IO) {
                Mp3Converter.convert(context, job.source, job.name, bitrateKbps) { job.progress = it }
            }
            job.message = saved
            job.progress = 1f
            job.status = ConversionStatus.DONE
            completedCount++
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            job.message = t.message ?: t.javaClass.simpleName
            job.status = ConversionStatus.FAILED
        }
    }
}
