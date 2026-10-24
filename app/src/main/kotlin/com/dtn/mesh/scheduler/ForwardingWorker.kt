package com.dtn.mesh.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.dao.SeenBundleDao
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.service.DtnOrchestrator
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager job — HOUSEKEEPING ONLY.
 *
 * Runs every 15 minutes (WorkManager minimum) and performs:
 * 1. TTL expiry & garbage collection
 * 2. Revert timed-out FORWARDING → BUFFERED
 * 3. Periodic routing aging (both strategies)
 * 4. Collect pending Q-updates and route to orchestrator (for thread safety)
 * 5. Buffer pressure enforcement / drop policy
 *
 * Real-time forwarding is handled by [DtnOrchestrator] on encounter events.
 * This worker NEVER calls transport.sendMessage() or qEngine.update() directly.
 */
@HiltWorker
class ForwardingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val queueManager: MessageQueueManager,
    private val strategySelector: StrategySelector,
    private val decisionDao: ForwardingDecisionDao,
    private val orchestrator: DtnOrchestrator,
    private val seenBundleDao: SeenBundleDao,
    private val lifecycle: MessageLifecycleLog,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ForwardingWorker"
        const val WORK_NAME = "dtn_housekeeping_cycle"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Housekeeping cycle starting")

        // Phase 1: TTL expiry. Log each message about to expire BEFORE the DB marks it, so the
        // lifecycle feed / buffer-history show why it left the buffer. Both steps use one clock, so
        // exactly the journaled rows turn EXPIRED, and an EXPIRED row is never listed again: one
        // journal entry per message. (This used to read getAllBuffered(), which already leaves
        // expired rows out, so nothing was ever journaled and no expiry reward was ever set.)
        val now = System.currentTimeMillis()
        runCatching {
            queueManager.getExpiring(now)
                .forEach { m ->
                    // Only user messages belong in the message journal; announces and receipts
                    // (our own group receipts too, DATA on the wire) expire constantly by design
                    // and would bury real events.
                    if (m.messageType == DtnMessageType.DATA && m.portNum != DtnMessage.PORT_GROUP_RECEIPT) {
                        lifecycle.log(LifecycleEvent.EXPIRED, m.id,
                            origin = m.originNodeId.value, dest = m.destinationNodeId.value,
                            hopCount = m.hopCount, extra = "TTL reached before delivery")
                    } else {
                        val kind = if (m.portNum == DtnMessage.PORT_GROUP_RECEIPT) "GROUP-RECEIPT" else m.messageType.name
                        lifecycle.console("CTRL $kind ${m.id.take(8)} expired")
                    }
                    // Q-learning credit: decisions on a message that expired undelivered get a
                    // negative reward so the engine learns to avoid whatever led here.
                    runCatching { decisionDao.setRewardByMessageId(m.id, DtnOrchestrator.REWARD_EXPIRED) }
                }
        }
        val expired = queueManager.expireMessages(now)
        if (expired > 0) Log.d(TAG, "Expired $expired messages")

        // Phase 2: Revert timed-out forwards
        queueManager.revertTimedOutForwards()

        // Phase 3: Purge old terminal messages
        queueManager.purgeOldMessages()

        // Phase 3b: Purge dedup tombstones whose retention window has elapsed (safe to forget).
        runCatching { seenBundleDao.purgeExpired(System.currentTimeMillis()) }
            .onSuccess { if (it > 0) Log.d(TAG, "Purged $it expired tombstones") }

        // Phase 4: Periodic routing aging (both strategies)
        strategySelector.onPeriodicAge()

        // Phase 5: Collect pending Q-updates and route to orchestrator
        // (Q-engine mutations ONLY happen on orchestrator's single-threaded dispatcher)
        val pendingUpdates = decisionDao.getPendingUpdates()
        if (pendingUpdates.isNotEmpty()) {
            orchestrator.enqueueBatchQUpdates(pendingUpdates)
            Log.d(TAG, "Routed ${pendingUpdates.size} Q-updates to orchestrator")
        }

        // Phase 6: Buffer pressure enforcement
        queueManager.enforceBufferPressure(strategySelector)

        // NOTE: a former "Phase 7" proactively DROPPED any buffered message whose destination
        // P-value was below pMinThreshold after a 2-minute grace period. That was fundamentally
        // wrong for a delay-tolerant network: a message to a node we have not encountered yet has
        // P = 0.0 by definition, so store-carry-forward bundles (and anything a data mule is
        // carrying) were deleted after ~2 minutes even though their TTL is hours. A message's
        // lifetime is governed ONLY by its TTL (Phase 1 expiry) and, when storage is scarce, by
        // buffer-pressure eviction (Phase 6). Low predictability means "keep carrying and wait
        // for a better contact", never "delete". The phase was removed deliberately.

        Log.d(TAG, "Housekeeping cycle complete")
        return Result.success()
    }
}
