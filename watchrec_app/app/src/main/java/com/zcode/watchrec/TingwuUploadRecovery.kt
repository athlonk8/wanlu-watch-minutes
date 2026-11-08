package com.zcode.watchrec

import org.json.JSONObject

/** Only retry operations whose existing cloud identity is known, or never sent. */
internal object TingwuUploadRecovery {
    fun replaceConfirmedExpiredUpload(job: JSONObject, response: JSONObject): Boolean {
        if (job.optString("phase") != "uploading" || job.optString("transId").isBlank() || response.optString("code") !in setOf("0", "200")) return false
        val records = response.optJSONArray("data") ?: return false
        val id = job.getString("transId")
        val expired = (0 until records.length()).any { index ->
            val record = records.optJSONObject(index)
            record?.optString("transId") == id && record.optInt("status", -1) == 2 && record.optString("statusMsg") == "TRS.UploadingExpired"
        }
        if (!expired) return false
        job.put("expiredTransId", id)
        listOf("phase", "taskId", "transId", "putLink", "getLink", "sts", "uploadMode", "serviceCode", "awaitingAcceptance", "confirmationChecks").forEach(job::remove)
        job.put("progress", 0).put("needsReview", false)
        return true
    }

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
            if (failure.serviceCode == "-1" && failure.retryable) job.put("awaitingAcceptance", true)
            else job.put("phase", "synced").put("needsReview", false)
        }
        if (job.optString("phase") == "uploading" && failure?.message?.contains("过期") == true) job.put("needsReview", true)
    }

    fun retryable(job: JSONObject, error: Exception): Boolean =
        error is TingwuFailure && error.retryable && !error.loginRequired && !job.optBoolean("needsReview") &&
            job.optString("phase") in setOf("", "uploading", "starting")
}
