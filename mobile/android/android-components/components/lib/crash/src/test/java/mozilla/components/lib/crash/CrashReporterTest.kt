/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.crash

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.lib.crash.db.CrashDao
import mozilla.components.lib.crash.db.CrashDatabase
import mozilla.components.lib.crash.db.CrashEntity
import mozilla.components.lib.crash.db.CrashType
import mozilla.components.lib.crash.service.CrashReporterService
import mozilla.components.lib.crash.service.CrashTelemetryService
import mozilla.components.support.test.any
import mozilla.components.support.test.eq
import mozilla.components.support.test.expectException
import mozilla.components.support.test.ext.joinBlocking
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.rule.MainCoroutineRule
import mozilla.components.support.test.rule.runTestOnMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.Thread.sleep
import java.lang.reflect.Modifier

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class CrashReporterTest {

    @get:Rule
    val coroutinesTestRule = MainCoroutineRule()
    private val scope = coroutinesTestRule.scope

    private lateinit var db: CrashDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(testContext, CrashDatabase::class.java).build()
        CrashReporter.reset()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `Calling install() will setup uncaught exception handler`() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        CrashReporter(
            context = testContext,
            services = listOf(mock()),
        ).install(testContext)

        val newHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(newHandler)

        assertNotEquals(defaultHandler, newHandler)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CrashReporter throws if no service is defined`() {
        CrashReporter(
            context = testContext,
            services = emptyList(),
        ).install(testContext)
    }

    @Test
    fun `GIVEN a CrashReporter initialized with useLegacyReporting=false and shouldPrompt=NEVER WHEN it receives a crash THEN sendCrashReport is no longer called`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
                scope = scope,
                useLegacyReporting = false,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter, never()).showPromptOrNotification(testContext, crash)
    }

    @Test
    fun `GIVEN a CrashReporter initialized with useLegacyReporting=false and usePrompt=ALWAYS WHEN it receives a crash THEN showPromptOrNotification is no longer called`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
                scope = scope,
                useLegacyReporting = false,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).showPromptOrNotification(testContext, crash)
    }

    @Test
    fun `CrashReporter will submit report immediately if setup with Prompt-NEVER`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.NEVER,
                scope = scope,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter).sendCrashReport(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))
    }

    @Test
    @Config(sdk = [28])
    fun `CrashReporter will show prompt if setup with Prompt-ALWAYS on SDK 28 and below`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
                scope = scope,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter).showPrompt(any(), eq(crash))
        verify(reporter, never()).showNotification(any(), eq(crash))
    }

    @Test
    fun `CrashReporter will show notification if setup with Prompt-ALWAYS`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
                scope = scope,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))
        verify(reporter).showNotification(any(), eq(crash))
    }

    @Test
    fun `CrashReporter will submit report immediately for non native crash and with setup Prompt-ONLY_NATIVE_CRASH`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ONLY_NATIVE_CRASH,
                scope = scope,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter).sendCrashReport(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))
    }

    @Test
    @Config(sdk = [28])
    fun `CrashReporter will show prompt for main process native crash and with setup Prompt-ONLY_NATIVE_CRASH for SDK 28 and below`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ONLY_NATIVE_CRASH,
                scope = scope,
            ).install(testContext),
        )

        val crash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_MAIN,
            processType = "main",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter).showPrompt(any(), eq(crash))
        verify(reporter, never()).showNotification(any(), eq(crash))

        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(service, never()).report(crash)
    }

    @Test
    fun `CrashReporter will show notification for main process native crash and with setup Prompt-ONLY_NATIVE_CRASH`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ONLY_NATIVE_CRASH,
                scope = scope,
            ).install(testContext),
        )

        val crash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_MAIN,
            processType = "main",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))
        verify(reporter).showNotification(any(), eq(crash))

        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(service, never()).report(crash)
    }

    @Test
    @Config(sdk = [28])
    fun `CrashReporter will submit crash telemetry through prompt even if crash report requires prompt on SDK 28 and below`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter).showPrompt(any(), eq(crash))
        verify(reporter, never()).showNotification(testContext, crash)
    }

    @Test
    fun `CrashReporter will submit crash telemetry through notification even if crash report requires prompt`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))
        verify(reporter).showNotification(testContext, crash)
    }

    @Test
    fun `CrashReporter will not prompt the user if there is no crash services`() {
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))
    }

    @Test
    @Config(sdk = [28])
    fun `CrashReporter will not send crash telemetry if there is no telemetry service and show prompt on SDK 28 and below`() {
        val service: CrashReporterService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter, never()).sendCrashTelemetry(testContext, crash)
        verify(reporter).showPrompt(any(), eq(crash))
        verify(reporter, never()).showNotification(any(), any())
    }

    @Test
    fun `CrashReporter will not send crash telemetry if there is no telemetry service and show notification `() {
        val service: CrashReporterService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
            ).install(testContext),
        )

        val crash: Crash.UncaughtExceptionCrash = createUncaughtExceptionCrash()

        reporter.onCrash(testContext, crash)

        verify(reporter, never()).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).showPrompt(any(), any())
        verify(reporter).showNotification(any(), any())
    }

    @Test
    fun `Calling install() with no crash services or telemetry crash services will throw exception`() {
        var exceptionThrown = false

        try {
            CrashReporter(
                context = testContext,
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
            ).install(testContext)
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
        }

        assert(exceptionThrown)
    }

    @Test
    fun `Calling install() with at least one crash service or telemetry crash service will not throw exception`() {
        var exceptionThrown = false

        try {
            CrashReporter(
                context = testContext,
                services = listOf(mock()),
            ).install(testContext)
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
        }
        assert(!exceptionThrown)

        try {
            CrashReporter(
                context = testContext,
                telemetryServices = listOf(mock()),
            ).install(testContext)
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
        }
        assert(!exceptionThrown)
    }

    @Test
    fun `CrashReporter is enabled by default`() {
        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(mock()),
                shouldPrompt = CrashReporter.Prompt.ONLY_NATIVE_CRASH,
            ).install(testContext),
        )

        assertTrue(reporter.enabled)
    }

    @Test
    fun `CrashReporter will not prompt and not submit report if not enabled`() {
        val service: CrashReporterService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.ALWAYS,
                scope = scope,
            ).install(testContext),
        )

        reporter.enabled = false

        val crash: Crash.UncaughtExceptionCrash = mock()
        reporter.onCrash(testContext, crash)

        verify(reporter, never()).sendCrashReport(testContext, crash)
        verify(reporter, never()).sendCrashTelemetry(testContext, crash)
        verify(reporter, never()).showPrompt(any(), eq(crash))

        verify(service, never()).report(crash)
    }

    @Test
    fun `CrashReporter sends telemetry`() {
        val crash = createUncaughtExceptionCrash()

        val service = mock<CrashReporterService>()
        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.NEVER,
                scope = scope,
            ).install(testContext),
        )

        reporter.onCrash(testContext, crash)
        verify(reporter, never()).sendCrashTelemetry(testContext, crash)
    }

    @Test
    fun `CrashReporter forwards uncaught exception crashes to service`() {
        var exceptionCrash = false

        val service = object : CrashReporterService {
            override val id: String = "test"

            override val name: String = "TestReporter"

            override fun createCrashReportUrl(identifier: String): String? = null

            override fun report(crash: Crash.UncaughtExceptionCrash): String? {
                exceptionCrash = true
                return null
            }

            override fun report(crash: Crash.NativeCodeCrash): String? = null

            override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? = null
        }

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.NEVER,
            ).install(testContext),
        )

        reporter.submitReport(
            Crash.UncaughtExceptionCrash(0, RuntimeException(), arrayListOf()),
        ).joinBlocking()
        assertTrue(exceptionCrash)
    }

    @Test
    fun `CrashReporter forwards native crashes to service`() {
        var nativeCrash = false

        val service = object : CrashReporterService {
            override val id: String = "test"

            override val name: String = "TestReporter"

            override fun createCrashReportUrl(identifier: String): String? = null

            override fun report(crash: Crash.UncaughtExceptionCrash): String? = null

            override fun report(crash: Crash.NativeCodeCrash): String? {
                nativeCrash = true
                return null
            }

            override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? = null
        }

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.NEVER,
            ).install(testContext),
        )

        reporter.submitReport(
            Crash.NativeCodeCrash(
                0,
                "",
                "",
                Crash.NativeCodeCrash.PROCESS_VISIBILITY_FOREGROUND_CHILD,
                processType = "content",
                breadcrumbs = arrayListOf(),
                remoteType = null,
            ),
        ).joinBlocking()
        assertTrue(nativeCrash)
    }

    @Test
    fun `CrashReporter forwards caught exception crashes to service`() = runTestOnMain {
        val testMessage = "test_Message"
        val testData = hashMapOf("1" to "one", "2" to "two")
        val testCategory = "testing_category"
        val testLevel = Breadcrumb.Level.CRITICAL
        val testType = Breadcrumb.Type.USER
        var exceptionCrash = false
        var exceptionThrowable: Throwable? = null
        var exceptionBreadcrumb: ArrayList<Breadcrumb>? = null
        val service = object : CrashReporterService {
            override val id: String = "test"

            override val name: String = "TestReporter"

            override fun createCrashReportUrl(identifier: String): String? = null

            override fun report(crash: Crash.UncaughtExceptionCrash): String? = null

            override fun report(crash: Crash.NativeCodeCrash): String? = null

            override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? {
                exceptionCrash = true
                exceptionThrowable = throwable
                exceptionBreadcrumb = breadcrumbs
                return null
            }
        }

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.NEVER,
                scope = scope,
            ).install(testContext),
        )

        val throwable = RuntimeException()
        val breadcrumb = Breadcrumb(
            testMessage,
            testData,
            testCategory,
            testLevel,
            testType,
        )
        reporter.recordCrashBreadcrumb(breadcrumb)
        advanceUntilIdle()

        reporter.submitCaughtException(throwable).joinBlocking()

        assertTrue(exceptionCrash)
        assert(exceptionThrowable == throwable)
        assert(exceptionBreadcrumb?.get(0) == breadcrumb)
    }

    @Test
    fun `Caught exception with no stack trace should be reported as CrashReporterException`() = runTestOnMain {
        val testMessage = "test_Message"
        val testData = hashMapOf("1" to "one", "2" to "two")
        val testCategory = "testing_category"
        val testLevel = Breadcrumb.Level.CRITICAL
        val testType = Breadcrumb.Type.USER
        var exceptionCrash = false
        var exceptionThrowable: Throwable? = null
        var exceptionBreadcrumb: ArrayList<Breadcrumb>? = null
        val service = object : CrashReporterService {
            override val id: String = "test"

            override val name: String = "TestReporter"

            override fun createCrashReportUrl(identifier: String): String? = null

            override fun report(crash: Crash.UncaughtExceptionCrash): String? = null

            override fun report(crash: Crash.NativeCodeCrash): String? = null

            override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? {
                exceptionCrash = true
                exceptionThrowable = throwable
                exceptionBreadcrumb = breadcrumbs
                return null
            }
        }

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                shouldPrompt = CrashReporter.Prompt.NEVER,
                scope = scope,
            ).install(testContext),
        )

        val throwable = RuntimeException()
        throwable.stackTrace = emptyArray()
        val breadcrumb = Breadcrumb(
            testMessage,
            testData,
            testCategory,
            testLevel,
            testType,
        )
        reporter.recordCrashBreadcrumb(breadcrumb)
        advanceUntilIdle()

        reporter.submitCaughtException(throwable).joinBlocking()

        assertTrue(exceptionCrash)
        assert(exceptionThrowable is CrashReporterException.UnexpectedlyMissingStacktrace)
        assert(exceptionThrowable?.cause is java.lang.RuntimeException)
        assertEquals(exceptionBreadcrumb?.get(0), breadcrumb)
    }

    @Test
    fun `CrashReporter forwards native crashes to telemetry service`() {
        var nativeCrash = false

        val telemetryService = object : CrashTelemetryService {
            override fun record(crash: Crash.UncaughtExceptionCrash) = Unit

            override fun record(crash: Crash.NativeCodeCrash) {
                nativeCrash = true
            }

            override fun record(throwable: Throwable) = Unit
        }

        val reporter = spy(
            CrashReporter(
                context = testContext,
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.NEVER,
            ).install(testContext),
        )

        reporter.submitCrashTelemetry(
            Crash.NativeCodeCrash(
                0,
                "",
                "",
                Crash.NativeCodeCrash.PROCESS_VISIBILITY_FOREGROUND_CHILD,
                processType = "content",
                breadcrumbs = arrayListOf(),
                remoteType = null,
            ),
        ).joinBlocking()
        assertTrue(nativeCrash)
    }

    @Test
    fun `Internal reference is set after calling install`() {
        expectException<IllegalStateException> {
            CrashReporter.requireInstance
        }

        val reporter = CrashReporter(
            context = testContext,
            services = listOf(mock()),
        )

        expectException<IllegalStateException> {
            CrashReporter.requireInstance
        }

        reporter.install(testContext)

        assertNotNull(CrashReporter.requireInstance)
    }

    @Test
    fun `CrashReporter invokes PendingIntent if provided for foreground child process crashes`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()

        val intent = Intent("action")
        val pendingIntent = spy(PendingIntent.getActivity(context, 0, intent, 0))

        val reporter = CrashReporter(
            context = testContext,
            shouldPrompt = CrashReporter.Prompt.ALWAYS,
            services = listOf(mock()),
            nonFatalCrashIntent = pendingIntent,
        ).install(testContext)

        val nativeCrash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_FOREGROUND_CHILD,
            processType = "content",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )
        reporter.onCrash(context, nativeCrash)

        verify(pendingIntent).send(eq(context), eq(0), any(), eq(null), eq(null), eq(null))

        val receivedIntent = shadowOf(context).nextStartedActivity

        val receivedCrash = Crash.fromIntent(receivedIntent) as? Crash.NativeCodeCrash
            ?: throw AssertionError("Expected NativeCodeCrash instance")

        assertEquals(nativeCrash, receivedCrash)
        assertEquals("dump.path", receivedCrash.minidumpPath)
        assertEquals("extras.path", receivedCrash.extrasPath)
        assertEquals(false, receivedCrash.isFatal)
        assertEquals(Crash.NativeCodeCrash.PROCESS_VISIBILITY_FOREGROUND_CHILD, receivedCrash.processVisibility)
    }

    @Test
    fun `CrashReporter does not invoke PendingIntent if provided for main process crashes`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()

        val intent = Intent("action")
        val pendingIntent = spy(PendingIntent.getActivity(context, 0, intent, 0))

        val reporter = CrashReporter(
            context = testContext,
            shouldPrompt = CrashReporter.Prompt.ALWAYS,
            services = listOf(mock()),
            nonFatalCrashIntent = pendingIntent,
        ).install(testContext)

        val nativeCrash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_MAIN,
            processType = "main",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )
        reporter.onCrash(context, nativeCrash)

        verify(pendingIntent, never()).send(eq(context), eq(0), any())
    }

    @Test
    fun `CrashReporter does not invoke PendingIntent if provided for background child process crashes`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()

        val intent = Intent("action")
        val pendingIntent = spy(PendingIntent.getActivity(context, 0, intent, 0))

        val reporter = CrashReporter(
            context = testContext,
            shouldPrompt = CrashReporter.Prompt.ALWAYS,
            services = listOf(mock()),
            nonFatalCrashIntent = pendingIntent,
        ).install(context)

        val nativeCrash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_BACKGROUND_CHILD,
            processType = "gpu",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )
        reporter.onCrash(context, nativeCrash)

        verify(pendingIntent, never()).send(eq(context), eq(0), any())
    }

    @Test
    fun `CrashReporter sends telemetry but don't send native crash if the crash is in foreground child process and nonFatalPendingIntent is not null`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.NEVER,
                nonFatalCrashIntent = mock(),
                scope = scope,
            ).install(testContext),
        )

        val nativeCrash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_FOREGROUND_CHILD,
            processType = "content",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )
        reporter.onCrash(testContext, nativeCrash)

        verify(reporter, never()).sendCrashReport(testContext, nativeCrash)
        verify(reporter, times(1)).sendCrashTelemetry(testContext, nativeCrash)
        verify(reporter, never()).showPrompt(any(), eq(nativeCrash))
    }

    @Test
    fun `CrashReporter sends telemetry and crash if the crash is in foreground child process and nonFatalPendingIntent is null`() {
        val service: CrashReporterService = mock()
        val telemetryService: CrashTelemetryService = mock()

        val reporter = spy(
            CrashReporter(
                context = testContext,
                services = listOf(service),
                telemetryServices = listOf(telemetryService),
                shouldPrompt = CrashReporter.Prompt.NEVER,
                scope = scope,
            ).install(testContext),
        )

        val nativeCrash = Crash.NativeCodeCrash(
            0,
            "dump.path",
            "extras.path",
            processVisibility = Crash.NativeCodeCrash.PROCESS_VISIBILITY_FOREGROUND_CHILD,
            processType = "content",
            breadcrumbs = arrayListOf(),
            remoteType = null,
        )
        reporter.onCrash(testContext, nativeCrash)

        verify(reporter, times(1)).sendCrashReport(testContext, nativeCrash)
        verify(reporter, times(1)).sendCrashTelemetry(testContext, nativeCrash)
        verify(reporter, never()).showPrompt(any(), eq(nativeCrash))
    }

    @Test
    fun `CrashReporter instance writes are visible across threads`() {
        val instanceField = CrashReporter::class.java.getDeclaredField("instance")
        assertTrue(Modifier.isVolatile(instanceField.modifiers))
    }

    @Test
    fun `Breadcrumbs stores only max number of breadcrumbs`() = runTestOnMain {
        val testMessage = "test_Message"
        val testData = hashMapOf("1" to "one", "2" to "two")
        val testCategory = "testing_category"
        val testLevel = Breadcrumb.Level.CRITICAL
        val testType = Breadcrumb.Type.USER

        var crashReporter = CrashReporter(
            context = testContext,
            services = listOf(mock()),
            maxBreadCrumbs = 5,
            scope = scope,
        )

        repeat(10) {
            crashReporter.recordCrashBreadcrumb(Breadcrumb(testMessage, testData, testCategory, testLevel, testType))
        }
        advanceUntilIdle()
        assertEquals(crashReporter.crashBreadcrumbsCopy().size, 5)

        crashReporter = CrashReporter(
            context = testContext,
            services = listOf(mock()),
            maxBreadCrumbs = 5,
            scope = scope,
        )
        repeat(15) {
            crashReporter.recordCrashBreadcrumb(Breadcrumb(testMessage, testData, testCategory, testLevel, testType))
        }
        advanceUntilIdle()
        assertEquals(crashReporter.crashBreadcrumbsCopy().size, 5)
    }

    @Test
    fun `Breadcrumb priority queue stores the latest breadcrumbs`() = runTestOnMain {
        val testMessage = "test_Message"
        val testData = hashMapOf("1" to "one", "2" to "two")
        val testCategory = "testing_category"
        val testType = Breadcrumb.Type.USER
        val maxNum = 10

        val crashReporter = CrashReporter(
            context = testContext,
            services = listOf(mock()),
            maxBreadCrumbs = maxNum,
            scope = scope,
        )

        repeat(maxNum) {
            crashReporter.recordCrashBreadcrumb(
                Breadcrumb(testMessage, testData, testCategory, Breadcrumb.Level.CRITICAL, testType),
            )
            sleep(10) // make sure time elapsed
        }
        advanceUntilIdle()

        crashReporter.crashBreadcrumbsCopy().let {
            for (i in 0 until maxNum) {
                assertEquals(it.elementAt(i).level, Breadcrumb.Level.CRITICAL)
            }

            var time = it[0].date
            for (i in 1 until it.size) {
                assertTrue(time.before(it[i].date))
                time = it[i].date
            }
        }

        repeat(maxNum) {
            crashReporter.recordCrashBreadcrumb(
                Breadcrumb(testMessage, testData, testCategory, Breadcrumb.Level.DEBUG, testType),
            )
            sleep(10) // make sure time elapsed
        }
        advanceUntilIdle()

        crashReporter.crashBreadcrumbsCopy().let {
            for (i in 0 until maxNum) {
                assertEquals(it.elementAt(i).level, Breadcrumb.Level.DEBUG)
            }

            var time = it[0].date
            for (i in 1 until it.size) {
                assertTrue(time.before(it[i].date))
                time = it[i].date
            }
        }
    }

    @Test
    fun `GIVEN the crash reporter has unsent crashes WHEN calling hasUnsentCrashReports THEN return true`() = runTestOnMain {
        val database: CrashDatabase = mock()
        val crashDao: CrashDao = mock()
        val timestamp = 10_000L

        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { database },
        )

        `when`(database.crashDao()).thenReturn(crashDao)
        `when`(crashDao.numberOfUnsentCrashesSince(timestamp)).thenReturn(1)

        assertTrue(crashReporter.hasUnsentCrashReportsSince(timestamp))
    }

    @Test
    fun `GIVEN the crash reporter has no crashes WHEN calling hasUnsentCrashReports THEN return false`() = runTestOnMain {
        val database: CrashDatabase = mock()
        val crashDao: CrashDao = mock()
        val timestamp = 10_000L

        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { database },
        )

        `when`(database.crashDao()).thenReturn(crashDao)
        `when`(crashDao.numberOfUnsentCrashesSince(timestamp)).thenReturn(0)

        assertFalse(crashReporter.hasUnsentCrashReportsSince(timestamp))
    }

    @Test
    fun `GIVEN the crash reporter has unsent crashes WHEN calling unsentCrashReports THEN return list of unsent crashes`() = runTestOnMain {
        val database: CrashDatabase = mock()
        val crashDao: CrashDao = mock()
        val timestamp = 10_000L

        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { database },
        )

        val crashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "6b6aea3f-55f1-46b2-a875-6c15530ed36e",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = null,
            processVisibility = null,
            processType = null,
            extrasPath = null,
            remoteType = null,
        )
        `when`(database.crashDao()).thenReturn(crashDao)
        `when`(crashDao.getCrashesWithoutReportsSince(timestamp)).thenReturn(listOf(crashEntity))

        assertEquals(crashReporter.unsentCrashReportsSince(timestamp).first().uuid, "6b6aea3f-55f1-46b2-a875-6c15530ed36e")
    }

    @Test
    fun `GIVEN the crash reporter has old unsent crashes WHEN querying for newer crashes THEN only return the crashes newer than the timestamp`() = runTestOnMain {
        val olderTimestamp = 5_000L
        val baseTimestamp = 10_000L
        val newerTimestamp = 15_000L

        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { db },
        )

        val oldCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "old uuid",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = olderTimestamp,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = null,
            processVisibility = null,
            processType = null,
            extrasPath = null,
            remoteType = null,
        )
        val newCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "new uuid",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = newerTimestamp,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = null,
            processVisibility = null,
            processType = null,
            extrasPath = null,
            remoteType = null,
        )

        val result = withContext(Dispatchers.IO) {
            db.crashDao().insertCrash(oldCrashEntity)
            db.crashDao().insertCrash(newCrashEntity)
            crashReporter.unsentCrashReportsSince(baseTimestamp)
        }

        assertEquals(1, result.size)
        assertEquals("new uuid", result.first().uuid)
    }

    @Test
    fun `GIVEN the crash reporter has old and new unsent crashes WHEN querying whether newer crashes exist THEN result is true`() = runTestOnMain {
        val olderTimestamp = 5_000L
        val baseTimestamp = 10_000L
        val newerTimestamp = 15_000L

        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { db },
        )

        val oldCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "old uuid",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = olderTimestamp,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = null,
            processVisibility = null,
            processType = null,
            extrasPath = null,
            remoteType = null,
        )
        val newCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "new uuid",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = newerTimestamp,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = null,
            processVisibility = null,
            processType = null,
            extrasPath = null,
            remoteType = null,
        )

        val result = withContext(Dispatchers.IO) {
            db.crashDao().insertCrash(oldCrashEntity)
            db.crashDao().insertCrash(newCrashEntity)
            crashReporter.hasUnsentCrashReportsSince(baseTimestamp)
        }

        assertEquals(true, result)
    }

    @Test
    fun `GIVEN the crash reporter has only old unsent crashes WHEN querying whether newer crashes exist THEN result is false`() = runTestOnMain {
        val olderTimestamp = 5_000L
        val baseTimestamp = 10_000L

        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { db },
        )

        val oldCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "old uuid",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = olderTimestamp,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = null,
            processVisibility = null,
            processType = null,
            extrasPath = null,
            remoteType = null,
        )

        val result = withContext(Dispatchers.IO) {
            db.crashDao().insertCrash(oldCrashEntity)
            crashReporter.hasUnsentCrashReportsSince(baseTimestamp)
        }

        assertEquals(false, result)
    }

    @Test
    fun `Breadcrumb priority queue output list result is sorted by time`() = runTestOnMain {
        val testMessage = "test_Message"
        val testData = hashMapOf("1" to "one", "2" to "two")
        val testCategory = "testing_category"
        val testType = Breadcrumb.Type.USER
        val maxNum = 10

        val crashReporter = CrashReporter(
            context = testContext,
            services = listOf(mock()),
            maxBreadCrumbs = 5,
            scope = scope,
        )

        repeat(maxNum) {
            crashReporter.recordCrashBreadcrumb(
                Breadcrumb(testMessage, testData, testCategory, Breadcrumb.Level.DEBUG, testType),
            )
            sleep(10) // make sure time elapsed
        }
        advanceUntilIdle()

        crashReporter.crashBreadcrumbsCopy().let {
            var time = it[0].date
            for (i in 1 until it.size) {
                assertTrue(time.before(it[i].date))
                time = it[i].date
            }
        }

        repeat(maxNum / 2) {
            crashReporter.recordCrashBreadcrumb(
                Breadcrumb(testMessage, testData, testCategory, Breadcrumb.Level.INFO, testType),
            )
            sleep(10) // make sure time elapsed
        }
        advanceUntilIdle()

        crashReporter.crashBreadcrumbsCopy().let {
            var time = it[0].date
            for (i in 1 until it.size) {
                assertTrue(time.before(it[i].date))
                time = it[i].date
            }
        }
    }

    @Test
    fun `GIVEN the crash reporter has unsent crashes WHEN calling findCrashReports WITH specific crashID that do not exists THEN return empty list`() = runTestOnMain {
        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { db },
        )

        val oldCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "53a63dcb-c450-44a0-940c-e809c7fad474",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/46c43391-3e08-4222-a334-80fe13e0433b.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )
        val newCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "cc698820-06e6-45e1-932a-94e29dcd280c",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/1a64d53e-7d34-416a-9679-ffdc2c6e0ba8.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )

        val crashIDs = arrayOf("b0cbe510-4bc0-4f2e-b561-b496351e316b")
        val result = withContext(Dispatchers.IO) {
            db.crashDao().insertCrash(oldCrashEntity)
            db.crashDao().insertCrash(newCrashEntity)
            crashReporter.findCrashReports(crashIDs)
        }

        assertEquals(result.size, 0)
    }

    @Test
    fun `GIVEN the crash reporter has unsent crashes WHEN calling findCrashReports WITH specific crashID THEN return list of this crash`() = runTestOnMain {
        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { db },
        )

        val oldCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "53a63dcb-c450-44a0-940c-e809c7fad474",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/46c43391-3e08-4222-a334-80fe13e0433b.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )
        val newCrashEntity = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "cc698820-06e6-45e1-932a-94e29dcd280c",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/1a64d53e-7d34-416a-9679-ffdc2c6e0ba8.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )

        val crashIDs = arrayOf("/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/1a64d53e-7d34-416a-9679-ffdc2c6e0ba8.dmp")

        val result = withContext(Dispatchers.IO) {
            db.crashDao().insertCrash(oldCrashEntity)
            db.crashDao().insertCrash(newCrashEntity)
            crashReporter.findCrashReports(crashIDs)
        }

        assertEquals(result.size, 1)
        assertEquals(crashReporter.findCrashReports(crashIDs).first().uuid, newCrashEntity.uuid)
    }

    @Test
    fun `GIVEN the crash reporter has unsent crashes WHEN calling findCrashReports WITH specific crashID THEN return list of those crashes`() = runTestOnMain {
        val crashReporter = CrashReporter(
            services = listOf(mock()),
            scope = scope,
            databaseProvider = { db },
        )

        val crashEntity1 = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "53a63dcb-c450-44a0-940c-e809c7fad474",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/46c43391-3e08-4222-a334-80fe13e0433b.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )
        val crashEntity2 = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "cc698820-06e6-45e1-932a-94e29dcd280c",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/1a64d53e-7d34-416a-9679-ffdc2c6e0ba8.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )
        val crashEntity3 = CrashEntity(
            crashType = CrashType.NATIVE,
            uuid = "68fe1af8-2008-4aa4-9ff4-b23aecf9cb7d",
            runtimeTags = mapOf(),
            breadcrumbs = listOf(),
            createdAt = 0L,
            stacktrace = "<native crash>",
            throwableData = null,
            minidumpPath = "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/4b8c7669-8bee-4785-b87d-5c58dbb27e8e.dmp",
            processType = null,
            processVisibility = null,
            extrasPath = null,
            remoteType = null,
        )

        val crashIDs = arrayOf("/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/46c43391-3e08-4222-a334-80fe13e0433b.dmp", "/data/data/org.mozilla.fenix.debug/files/mozilla/Crash Reports/pending/4b8c7669-8bee-4785-b87d-5c58dbb27e8e.dmp")

        val result = withContext(Dispatchers.IO) {
            db.crashDao().insertCrash(crashEntity1)
            db.crashDao().insertCrash(crashEntity2)
            db.crashDao().insertCrash(crashEntity3)
            crashReporter.findCrashReports(crashIDs)
        }

        assertEquals(result.size, 2)
        assertEquals(crashReporter.findCrashReports(crashIDs).get(0).uuid, crashEntity1.uuid)
        assertEquals(crashReporter.findCrashReports(crashIDs).get(1).uuid, crashEntity3.uuid)
    }
}

private fun createUncaughtExceptionCrash(): Crash.UncaughtExceptionCrash {
    return Crash.UncaughtExceptionCrash(
        0,
        RuntimeException(),
        ArrayList(),
    )
}
