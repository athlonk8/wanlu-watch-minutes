package com.zcode.watchrec

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Drives the real recording-finalization callback using synthetic files, never a microphone or cloud call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AutomaticUploadTest {
    private lateinit var context: Context
    private val completed = mutableListOf<String>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TingwuSession.accountKey.value = "synthetic-test-account"
        TingwuSession.connected.value = true
        TingwuUploads.prefs(context).edit().clear().putBoolean("automatic", true).commit()
        RecordingStorage.recordings(context).forEach { it.delete() }
        RecorderState.isRecording.value = false
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : Worker(appContext, workerParameters) {
                    override fun doWork(): Result {
                        completed.add(inputData.getString("name").orEmpty())
                        return Result.success()
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
    }

    private fun stop(file: File) {
        val service = Robolectric.buildService(RecorderService::class.java).create().get()
        RecorderService::class.java.getDeclaredField("outFile").apply { isAccessible = true }.set(service, file)
        RecorderState.isRecording.value = true
        val finish = RecorderService::class.java.getDeclaredMethod("finishRecording").apply { isAccessible = true }
        finish.invoke(service)
        finish.invoke(service) // onDestroy follows stop in normal service lifetime.
    }

    @Test fun autoUploadOnlyEnqueuesTheJustSavedRecordingOnce() {
        val old = File(context.filesDir, "REC_old.m4a").apply { writeText("synthetic old audio") }
        val fresh = File(context.filesDir, "REC_new.m4a").apply { writeText("synthetic new audio") }
        stop(fresh)
        val work = WorkManager.getInstance(context).getWorkInfosByTag(TingwuUploads.TAG).get()
        assertEquals(1, work.size)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.single().id)
        assertEquals(listOf(fresh.name), completed)
        assertEquals("synthetic old audio", old.readText())
        assertEquals("synthetic new audio", fresh.readText())
    }

    @Test fun disablingAutomaticUploadKeepsFileWithoutSchedulingCloudWork() {
        TingwuUploads.prefs(context).edit().putBoolean("automatic", false).commit()
        val file = File(context.filesDir, "REC_manual.m4a").apply { writeText("synthetic") }
        stop(file)
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(TingwuUploads.TAG).get().isEmpty())
        assertTrue(file.isFile)
    }

    @Test fun emptyRecordingIsNeverAutomaticallyUploaded() {
        val file = File(context.filesDir, "REC_empty.m4a").apply { writeBytes(byteArrayOf()) }
        stop(file)
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(TingwuUploads.TAG).get().isEmpty())
        assertTrue(file.isFile)
    }
}
