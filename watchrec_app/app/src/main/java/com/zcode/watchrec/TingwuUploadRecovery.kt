package com.zcode.watchrec

import org.json.JSONObject

/** Only retry operations whose existing cloud identity is known, or never sent. */
internal object TingwuUploadRecovery {
    fun recover(job: JSONObject, error: Exception, creatingRequestAttempted: Boolean = false) {
        val failure = error as? TingwuFailure
        if (failure?.serviceCode?.isNotBlank() == true) job.put("serviceCode", failure.serviceCode)
        if (job.optString("phase") == "creating") {
            if (creatingRequestAttempted && failure != null && (failure.requestNotSent || failure.loginRequired || failure.serviceCode.isNotBlank())) {
                job.remove("phase")
                job.remove("taskId")
                job.put("needsReview", false)
            } else job.put("needsReview", true)
        }
        if (job.optString("phase") == "starting" && failure?.serviceCode?.isNotBlank() == true) {
            job.put("phase", "synced").put("needsReview", false)
        }
        if (job.optString("phase") == "uploading" && failure?.message?.contains("过期") == true) job.put("needsReview", true)
    }

    fun retryable(job: JSONObject, error: Exception): Boolean =
        error is TingwuFailure && error.retryable && !error.loginRequired && !job.optBoolean("needsReview") &&
            job.optString("phase") in setOf("", "uploading", "starting")
}
