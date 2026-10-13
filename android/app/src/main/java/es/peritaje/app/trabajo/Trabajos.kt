package es.peritaje.app.trabajo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.peritaje.app.PeritajeApp
import es.peritaje.app.data.ResultadoEnvio
import es.peritaje.app.data.SinConfiguracion
import java.util.concurrent.TimeUnit

private val Context.repo get() = (applicationContext as PeritajeApp).contenedor.repositorio

/** Trae el índice de citas (de Notion o del PC) y las que falten. */
class SincronizarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.repo.sincronizar()
        // Por si alguna visita finalizada se quedó sin encolar (p. ej. tras reinstalar).
        applicationContext.repo.idsVisitadas().forEach { Planificador.enviarAvance(applicationContext, it) }
        Result.success()
    } catch (e: SinConfiguracion) {
        Result.failure()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

/** Manda el avance de una visita. Solo se da por enviado cuando el destino confirma "ok". */
class EnviarAvanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(CLAVE_ID) ?: return Result.failure()
        return when (applicationContext.repo.enviarAvance(id)) {
            ResultadoEnvio.Enviado -> Result.success()
            is ResultadoEnvio.Reintentar -> Result.retry()
        }
    }

    companion object {
        const val CLAVE_ID = "id_encargo"
    }
}

/** Borra de la base local las citas enviadas hace más de 7 días (las fotos de la galería no se tocan). */
class PurgaWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        applicationContext.repo.purgar()
        return Result.success()
    }
}

object Planificador {
    const val SYNC_AHORA = "sync-ahora"
    private const val SYNC_PERIODICA = "sync-periodica"
    private const val PURGA = "purga"

    private val conRed = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun programarPeriodicos(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            SYNC_PERIODICA, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SincronizarWorker>(1, TimeUnit.HOURS).setConstraints(conRed).build(),
        )
        wm.enqueueUniquePeriodicWork(
            PURGA, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PurgaWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    /** Botón Sincronizar: intento inmediato (o en cuanto haya red). */
    fun sincronizarAhora(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_AHORA, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SincronizarWorker>()
                .setConstraints(conRed)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun enviarAvance(context: Context, idEncargo: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "avance-$idEncargo", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<EnviarAvanceWorker>()
                .setConstraints(conRed)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(EnviarAvanceWorker.CLAVE_ID to idEncargo))
                .build(),
        )
    }
}
