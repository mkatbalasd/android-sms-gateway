package me.capcom.smsgateway.modules.gateway.workers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker maps "no more retries" to Result.success(), never Result.failure(): a failed link on
 * the per-id unique chain would mark the reports queued behind it as failed without an attempt.
 * That mapping is the `else` branch of this decision, so a first report that runs out of retries
 * does not stop a queued second one.
 */
class SendStateWorkerRetryTest {
    @Test
    fun retriesWhileAttemptsRemain() {
        assertTrue(SendStateWorker.shouldRetry(0))
        assertTrue(SendStateWorker.shouldRetry(9))
    }

    @Test
    fun givesUpAfterTheLastAttemptSoTheWorkerReturnsSuccess() {
        assertFalse(SendStateWorker.shouldRetry(10))
        assertFalse(SendStateWorker.shouldRetry(11))
    }
}
