package com.zcode.watchrec

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaRecorder

/** Uses Robolectric's recorder: checks encoder configuration without microphone or upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecordingQualityTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(RecordingSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        TingwuUploads.prefs(context).edit().putBoolean("automatic", false).commit()
        RecorderState.isRecording.value = false
        RecorderState.recordingQuality.value = null
    }

    private fun start(): Pair<ServiceController<RecorderService>, ShadowMediaRecorder> {
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(context, RecorderService::class.java), 0, 1)
        val recorder = RecorderService::class.java.getDeclaredField("recorder").apply { isAccessible = true }.get(service) as MediaRecorder
        return controller to Shadow.extract(recorder)
    }

    @Test fun existingInstallWithoutAQualityPreferenceRecordsAt96Kbps() {
        val (service, recorder) = start()
        try {
            assertEquals(96000, recorder.audioEncodingBitRate)
            assertEquals(RecordingQuality.HIGH, RecorderState.recordingQuality.value)
            assertEquals(ShadowMediaRecorder.STATE_RECORDING, recorder.state)
        } finally { service.destroy() }
    }

    @Test fun allSelectionsReachTheEncoderAcrossServiceRecreation() {
        for (quality in RecordingQuality.entries) {
            assertTrue(RecordingSettings.select(context, quality))
            val (service, recorder) = start()
            try {
                assertEquals(quality.kbps * 1000, recorder.audioEncodingBitRate)
                assertEquals(44100, recorder.audioSamplingRate)
                assertEquals(1, recorder.audioChannels)
                assertEquals(MediaRecorder.AudioEncoder.AAC, recorder.audioEncoder)
                assertEquals(RecordingQuality.valueOf(quality.name), RecorderState.recordingQuality.value)
            } finally { service.destroy() }
            assertNull(RecorderState.recordingQuality.value)
        }
    }

    @Test fun changingThePreferenceDoesNotChangeTheCurrentRecording() {
        val (service, recorder) = start()
        try {
            assertTrue(RecordingSettings.select(context, RecordingQuality.HIGHER))
            assertEquals(96000, recorder.audioEncodingBitRate)
            assertEquals(RecordingQuality.HIGH, RecorderState.recordingQuality.value)
        } finally { service.destroy() }
        val (nextService, nextRecorder) = start()
        try { assertEquals(128000, nextRecorder.audioEncodingBitRate) }
        finally { nextService.destroy() }
    }

    @Test fun invalidOrWrongTypedPreferenceSafelyUses96Kbps() {
        val prefs = context.getSharedPreferences(RecordingSettings.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(RecordingSettings.QUALITY_KEY, 123).commit()
        val (service, recorder) = start()
        try { assertEquals(96000, recorder.audioEncodingBitRate) }
        finally { service.destroy() }
        prefs.edit().putString(RecordingSettings.QUALITY_KEY, "corrupt").commit()
        assertEquals(RecordingQuality.HIGH, RecordingSettings.quality(context))
    }
}
