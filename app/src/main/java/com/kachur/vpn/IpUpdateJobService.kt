package com.kachur.vpn

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object IpUpdateJobs {
    private const val PERIODIC = 0x4B4950
    private const val ON_DEMAND = 0x4B4951

    fun schedule(context: Context, configuration: IpUpdateConfiguration): Boolean {
        val manager = context.getSystemService(JobScheduler::class.java) ?: return false
        return try {
            if (configuration.sources.isEmpty()) manager.cancel(ON_DEMAND)
            if (configuration.schedule == IpUpdateSchedule.MANUAL || configuration.sources.isEmpty()) {
                manager.cancel(PERIODIC)
                return true
            }
            val interval = TimeUnit.DAYS.toMillis(configuration.schedule.days)
            manager.getPendingJob(PERIODIC)?.let { if (it.intervalMillis == interval) return true }
            manager.schedule(job(context, PERIODIC).setPeriodic(interval).setPersisted(true)
                .setRequiresBatteryNotLow(true).build()) == JobScheduler.RESULT_SUCCESS
        } catch (_: RuntimeException) {
            false
        }
    }

    fun updateNow(context: Context): Boolean {
        val manager = context.getSystemService(JobScheduler::class.java) ?: return false
        return try {
            manager.getPendingJob(ON_DEMAND) != null ||
                manager.schedule(job(context, ON_DEMAND).build()) == JobScheduler.RESULT_SUCCESS
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun job(context: Context, id: Int) = JobInfo.Builder(id, ComponentName(context, IpUpdateJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
}

class IpUpdateJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private val running = mutableMapOf<Int, Work>()

    override fun onStartJob(params: JobParameters): Boolean {
        val work = Work()
        running.put(params.jobId, work)?.cancel()
        work.future = executor.submit {
            IpFeedUpdater(applicationContext).refresh(work.cancelled::get)
            main.post {
                if (running[params.jobId] !== work) return@post
                running.remove(params.jobId)
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        running.values.forEach(Work::cancel)
        running.clear()
        super.onDestroy()
    }

    private class Work {
        val cancelled = AtomicBoolean(false)
        var future: Future<*>? = null

        fun cancel() {
            cancelled.set(true)
            future?.cancel(true)
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
