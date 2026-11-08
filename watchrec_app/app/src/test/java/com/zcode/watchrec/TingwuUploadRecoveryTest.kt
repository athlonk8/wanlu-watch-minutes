package com.zcode.watchrec

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TingwuUploadRecoveryTest {
    @Test fun dnsFailureBeforeSendingCanRetryWithoutCloudReview() {
        val job = JSONObject().put("phase", "creating").put("taskId", "synthetic")
        val failure = TingwuNetworkErrors.failure(UnknownHostException("https://secret.invalid/?token=hidden"), requestNotSent = true)
        TingwuUploadRecovery.recover(job, failure, creatingRequestAttempted = true)
        assertFalse(job.has("phase"))
        assertFalse(job.has("taskId"))
        assertFalse(job.optBoolean("needsReview"))
        assertTrue(TingwuUploadRecovery.retryable(job, failure))
        assertFalse(failure.message.orEmpty().contains("hidden"))
    }

    @Test fun timeoutAfterRequestWasSentNeverCreatesAnotherTaskAutomatically() {
        val job = JSONObject().put("phase", "creating").put("taskId", "same-task")
        val failure = TingwuNetworkErrors.failure(SocketTimeoutException())
        TingwuUploadRecovery.recover(job, failure)
        assertEquals("creating", job.getString("phase"))
        assertEquals("same-task", job.getString("taskId"))
        assertTrue(job.getBoolean("needsReview"))
        assertFalse(TingwuUploadRecovery.retryable(job, failure))
    }

    @Test fun interruptedStorageUploadKeepsCloudIdentityForRetry() {
        val job = JSONObject().put("phase", "uploading").put("transId", "existing").put("putLink", "synthetic-link")
        val failure = TingwuNetworkErrors.failure(SocketTimeoutException())
        TingwuUploadRecovery.recover(job, failure)
        assertTrue(TingwuUploadRecovery.retryable(job, failure))
        assertEquals("existing", job.getString("transId"))
        assertEquals("synthetic-link", job.getString("putLink"))
    }

    @Test fun explicitCreationRejectionDoesNotRequireRebuildingUnknownTask() {
        for (failure in listOf(TingwuFailure("expired", loginRequired = true), TingwuFailure("quota", serviceCode = "TRS.TransTime"))) {
            val job = JSONObject().put("phase", "creating")
            TingwuUploadRecovery.recover(job, failure, creatingRequestAttempted = true)
            assertFalse(job.has("phase"))
            assertFalse(job.optBoolean("needsReview"))
            assertFalse(TingwuUploadRecovery.retryable(job, failure))
        }
    }

    @Test fun confirmedAndUncertainStagesDoNotBlindlyReplayMutations() {
        val failure = TingwuNetworkErrors.failure(SocketTimeoutException())
        for (phase in listOf("uploaded", "synced", "done", "creating")) {
            assertFalse(TingwuUploadRecovery.retryable(JSONObject().put("phase", phase), failure))
        }
        assertTrue(TingwuUploadRecovery.retryable(JSONObject().put("phase", "starting"), failure))
    }

    @Test fun preflightLoginFailureNeverClearsAnOlderUncertainCreation() {
        val job = JSONObject().put("phase", "creating").put("taskId", "older-task")
        TingwuUploadRecovery.recover(job, TingwuFailure("expired", loginRequired = true))
        assertEquals("creating", job.getString("phase"))
        assertEquals("older-task", job.getString("taskId"))
        assertTrue(job.getBoolean("needsReview"))
    }
}
