package hu.kal8989.sutoseged

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * A tervezést az Android háttérfeladat-kezelője futtatja. Ha a telefon közben
 * kilövi az appot, a rendszer akkor is befejezi (szükség esetén újraindítja),
 * az eredményt a tárhelyre írja, és értesítést küld.
 */
class PlanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        val store = Store(applicationContext)
        val prompt = inputData.getString(KEY_PROMPT)
        if (prompt.isNullOrBlank()) {
            store.planStatus = "error"
            store.planError = "Hiányzó kérés."
            return ListenableWorker.Result.failure()
        }

        store.planStatus = "running"
        val result = AiClient.plan(store.aiConfig(), prompt)
        val plan = result.getOrNull()

        if (plan != null) {
            store.saveLastPlan(plan)
            store.planError = ""
            store.planStatus = "done"
            Notifications.show(
                applicationContext,
                Notifications.CH_PLAN,
                "Elkészült a sütési terv",
                plan.cim,
                1001
            )
            return ListenableWorker.Result.success()
        }

        val msg = result.exceptionOrNull()?.message ?: "Ismeretlen hiba"
        // Túlterhelés vagy hálózati akadás: a rendszer később magától újrapróbálja.
        val transient = msg.contains("(503)") || msg.contains("(429)") ||
            msg.contains("(500)") || msg.contains("timeout", ignoreCase = true) ||
            msg.contains("Unable to resolve host", ignoreCase = true)
        if (transient && runAttemptCount < MAX_RETRIES) {
            store.planError = "Újrapróbálás… ($msg)"
            return ListenableWorker.Result.retry()
        }

        store.planError = msg
        store.planStatus = "error"
        Notifications.show(
            applicationContext,
            Notifications.CH_PLAN,
            "Nem sikerült a terv",
            msg,
            1002
        )
        return ListenableWorker.Result.failure()
    }

    companion object {
        private const val KEY_PROMPT = "prompt"
        private const val WORK_NAME = "sutoseged_plan"
        private const val MAX_RETRIES = 3

        fun enqueue(context: Context, prompt: String) {
            val store = Store(context)
            store.planError = ""
            store.planStatus = "running"

            val req = OneTimeWorkRequestBuilder<PlanWorker>()
                .setInputData(workDataOf(KEY_PROMPT to prompt))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
