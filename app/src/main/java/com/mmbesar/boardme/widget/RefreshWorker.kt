package com.mmbesar.boardme.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mmbesar.boardme.BoardMeApplication
import com.mmbesar.boardme.network.ApiException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background job: re-downloads the projects shown by widgets, then redraws them.
 * Android runs it about every 30 minutes while at least one widget exists.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BoardMeApplication
        return try {
            app.repository.refreshForWidgets(WidgetPrefs.allProjectIds(applicationContext))
            BoardWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // Only worth trying again when the problem is the network; a wrong
            // token or a certificate problem will not fix itself.
            if (e.kind == ApiException.Kind.NETWORK) Result.retry() else Result.success()
        } catch (e: Exception) {
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "boardme-widget-refresh"

        /** Safe to call many times: an existing schedule is kept. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
