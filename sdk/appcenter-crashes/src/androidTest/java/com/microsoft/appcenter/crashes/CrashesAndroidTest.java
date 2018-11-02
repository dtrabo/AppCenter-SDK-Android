package com.microsoft.appcenter.crashes;

import android.annotation.SuppressLint;
import android.app.Application;
import android.support.test.InstrumentationRegistry;

import com.microsoft.appcenter.AppCenter;
import com.microsoft.appcenter.AppCenterPrivateHelper;
import com.microsoft.appcenter.Constants;
import com.microsoft.appcenter.channel.Channel;
import com.microsoft.appcenter.crashes.ingestion.models.ErrorAttachmentLog;
import com.microsoft.appcenter.crashes.ingestion.models.ManagedErrorLog;
import com.microsoft.appcenter.crashes.model.ErrorReport;
import com.microsoft.appcenter.crashes.model.NativeException;
import com.microsoft.appcenter.crashes.utils.ErrorLogHelper;
import com.microsoft.appcenter.ingestion.Ingestion;
import com.microsoft.appcenter.ingestion.models.Log;
import com.microsoft.appcenter.utils.HandlerUtils;
import com.microsoft.appcenter.utils.UUIDUtils;
import com.microsoft.appcenter.utils.async.AppCenterConsumer;
import com.microsoft.appcenter.utils.storage.FileManager;
import com.microsoft.appcenter.utils.storage.SharedPreferencesManager;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileFilter;
import java.io.FileWriter;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static com.microsoft.appcenter.Flags.DEFAULT_FLAGS;
import static com.microsoft.appcenter.Flags.PERSISTENCE_CRITICAL;
import static com.microsoft.appcenter.test.TestUtils.TAG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

@SuppressWarnings("unused")
public class CrashesAndroidTest {

    @SuppressLint("StaticFieldLeak")
    private static Application sApplication;

    private static Thread.UncaughtExceptionHandler sDefaultCrashHandler;

    private Channel mChannel;

    /* Filter out the minidump folder. */
    private FileFilter mMinidumpFilter = new FileFilter() {

        @Override
        public boolean accept(File file) {
            return !file.isDirectory();
        }
    };

    @BeforeClass
    public static void setUpClass() {
        sDefaultCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        sApplication = (Application) InstrumentationRegistry.getContext().getApplicationContext();
        FileManager.initialize(sApplication);
        SharedPreferencesManager.initialize(sApplication);
        Constants.loadFromContext(sApplication);
    }

    @Before
    public void setUp() {
        Thread.setDefaultUncaughtExceptionHandler(sDefaultCrashHandler);
        SharedPreferencesManager.clear();
        for (File logFile : ErrorLogHelper.getErrorStorageDirectory().listFiles()) {
            if (logFile.isDirectory()) {
                for (File dumpDir : logFile.listFiles()) {
                    for (File dumpFile : dumpDir.listFiles()) {
                        assertTrue(dumpFile.delete());
                    }
                }
            } else {
                assertTrue(logFile.delete());
            }
        }
        mChannel = mock(Channel.class);
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(sDefaultCrashHandler);
    }

    private void startFresh(CrashesListener listener) {

        /* Configure new instance. */
        AppCenterPrivateHelper.unsetInstance();
        Crashes.unsetInstance();
        AppCenter.setLogLevel(android.util.Log.VERBOSE);
        AppCenter.configure(sApplication, "a");

        /* Clean logs. */
        AppCenter.setEnabled(false);
        AppCenter.setEnabled(true).get();

        /* Replace channel. */
        AppCenter.getInstance().setChannel(mChannel);
        /* Set listener. */
        Crashes.setListener(listener);

        /* Start crashes. */
        AppCenter.start(Crashes.class);

        /* Wait for start. */
        assertTrue(Crashes.isEnabled().get());
    }

    @Test
    public void getLastSessionCrashReportSimpleException() throws Exception {

        /* Null before start. */
        Crashes.unsetInstance();
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());

        /* Crash on 1st process. */
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        startFresh(null);
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());
        final RuntimeException exception = new IllegalArgumentException();
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();

        /* Get last session crash on 2nd process. */
        startFresh(null);
        ErrorReport errorReport = Crashes.getLastSessionCrashReport().get();
        assertNotNull(errorReport);
        Throwable lastThrowable = errorReport.getThrowable();
        assertTrue(lastThrowable instanceof IllegalArgumentException);
        assertTrue(Crashes.hasCrashedInLastSession().get());
    }

    @Test
    public void getLastSessionCrashReportStackOverflowException() throws Exception {

        /* Null before start. */
        Crashes.unsetInstance();
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());

        /* Crash on 1st process. */
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        startFresh(null);
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());
        final Error exception = generateStackOverflowError();
        assertTrue(exception.getStackTrace().length > ErrorLogHelper.FRAME_LIMIT);
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();

        /* Get last session crash on 2nd process. */
        startFresh(null);
        ErrorReport errorReport = Crashes.getLastSessionCrashReport().get();
        assertNotNull(errorReport);
        Throwable lastThrowable = errorReport.getThrowable();
        assertTrue(lastThrowable instanceof StackOverflowError);
        assertEquals(ErrorLogHelper.FRAME_LIMIT, lastThrowable.getStackTrace().length);
        assertTrue(Crashes.hasCrashedInLastSession().get());
    }

    @Test
    public void getLastSessionCrashReportExceptionWithHugeFramesAndHugeCauses() throws Exception {

        /* Null before start. */
        Crashes.unsetInstance();
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());

        /* Crash on 1st process. */
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        startFresh(null);
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());
        final RuntimeException exception = generateHugeException(300, 300);
        assertTrue(exception.getStackTrace().length > ErrorLogHelper.FRAME_LIMIT);
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();

        /* Get last session crash on 2nd process. */
        startFresh(null);
        ErrorReport errorReport = Crashes.getLastSessionCrashReport().get();
        assertNotNull(errorReport);

        /* The client side throwable failed to save as huge so will be null. */
        assertNull(errorReport.getThrowable());
        assertTrue(Crashes.hasCrashedInLastSession().get());

        /* Check managed error was sent as truncated. */
        ArgumentCaptor<ManagedErrorLog> errorLog = ArgumentCaptor.forClass(ManagedErrorLog.class);
        verify(mChannel).enqueue(errorLog.capture(), eq(Crashes.ERROR_GROUP));
        assertNotNull(errorLog.getValue());
        assertNotNull(errorLog.getValue().getException());
        assertNotNull(errorLog.getValue().getException().getFrames());
        assertEquals(ErrorLogHelper.FRAME_LIMIT, errorLog.getValue().getException().getFrames().size());
        int causesCount = 0;
        com.microsoft.appcenter.crashes.ingestion.models.Exception e = errorLog.getValue().getException();
        while (e.getInnerExceptions() != null && (e = e.getInnerExceptions().get(0)) != null) {
            causesCount++;
        }
        assertEquals(ErrorLogHelper.CAUSE_LIMIT, causesCount + 1);
    }

    @Test
    public void getLastSessionCrashReportNative() throws Exception {

        /* Null before start. */
        Crashes.unsetInstance();
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());
        assertNull(Crashes.getMinidumpDirectory().get());

        /* Simulate we have a minidump. */
        File newMinidumpDirectory = ErrorLogHelper.getNewMinidumpDirectory();
        File minidumpFile = new File(newMinidumpDirectory, "minidump.dmp");
        FileManager.write(minidumpFile, "mock minidump");

        /* Start crashes now. */
        startFresh(null);

        /* We can access directory now. */
        assertEquals(newMinidumpDirectory.getAbsolutePath(), Crashes.getMinidumpDirectory().get());
        ErrorReport errorReport = Crashes.getLastSessionCrashReport().get();
        assertNotNull(errorReport);
        assertTrue(Crashes.hasCrashedInLastSession().get());
        assertTrue(errorReport.getThrowable() instanceof NativeException);

        /* File has been deleted. */
        assertFalse(minidumpFile.exists());

        /* After restart, it's processed. */
        Crashes.unsetInstance();
        startFresh(null);
        assertNull(Crashes.getLastSessionCrashReport().get());
        assertFalse(Crashes.hasCrashedInLastSession().get());
    }

    @Test
    public void failedToMoveMinidump() throws Exception {

        /* Simulate we have a minidump. */
        File newMinidumpDirectory = ErrorLogHelper.getNewMinidumpDirectory();
        File minidumpFile = new File(newMinidumpDirectory, "minidump.dmp");
        FileManager.write(minidumpFile, "mock minidump");

        /* Make moving fail. */
        assertTrue(ErrorLogHelper.getPendingMinidumpDirectory().delete());

        /* Start crashes now. */
        try {
            startFresh(null);

            /* If failed to process minidump, delete entire crash. */
            assertNull(Crashes.getLastSessionCrashReport().get());
            assertFalse(Crashes.hasCrashedInLastSession().get());
            assertFalse(minidumpFile.exists());
        } finally {
            assertTrue(ErrorLogHelper.getPendingMinidumpDirectory().mkdir());
        }
    }

    @Test
    public void clearInvalidFiles() throws Exception {
        File invalidFile1 = new File(ErrorLogHelper.getErrorStorageDirectory(), UUIDUtils.randomUUID() + ErrorLogHelper.ERROR_LOG_FILE_EXTENSION);
        File invalidFile2 = new File(ErrorLogHelper.getErrorStorageDirectory(), UUIDUtils.randomUUID() + ErrorLogHelper.ERROR_LOG_FILE_EXTENSION);
        assertTrue(invalidFile1.createNewFile());
        new FileWriter(invalidFile2).append("fake_data").close();
        assertEquals(2, ErrorLogHelper.getStoredErrorLogFiles().length);

        /* Invalid files should be cleared. */
        startFresh(null);
        assertTrue(Crashes.isEnabled().get());
        assertEquals(0, ErrorLogHelper.getStoredErrorLogFiles().length);
    }

    @Test
    public void testNoDuplicateCallbacksOrSending() throws Exception {

        /* Crash on 1st process. */
        Crashes.unsetInstance();
        assertFalse(Crashes.hasCrashedInLastSession().get());
        android.util.Log.i(TAG, "Process 1");
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        CrashesListener crashesListener = mock(CrashesListener.class);

        /* While testing should process, call methods that require the handler to test we avoid a dead lock and run directly. */
        when(crashesListener.shouldProcess(any(ErrorReport.class))).thenAnswer(new Answer<Boolean>() {

            @Override
            public Boolean answer(InvocationOnMock invocationOnMock) {
                assertNotNull(AppCenter.getInstallId().get());
                return AppCenter.isEnabled().get() && Crashes.isEnabled().get();
            }
        });
        when(crashesListener.shouldAwaitUserConfirmation()).thenReturn(true);
        startFresh(crashesListener);
        final RuntimeException exception = new RuntimeException();
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        verify(uncaughtExceptionHandler).uncaughtException(thread, exception);
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
        verifyZeroInteractions(crashesListener);

        /* Second process: enqueue log but network is down... */
        android.util.Log.i(TAG, "Process 2");
        startFresh(crashesListener);

        /* Check last session error report. */
        Crashes.getLastSessionCrashReport().thenAccept(new AppCenterConsumer<ErrorReport>() {

            @Override
            public void accept(ErrorReport errorReport) {
                assertNotNull(errorReport);
                Throwable lastThrowable = errorReport.getThrowable();
                assertTrue(lastThrowable instanceof RuntimeException);
            }
        });
        assertTrue(Crashes.hasCrashedInLastSession().get());

        /* Wait U.I. thread callback (shouldAwaitUserConfirmation). */
        final Semaphore semaphore = new Semaphore(0);
        HandlerUtils.runOnUiThread(new Runnable() {

            @Override
            public void run() {
                semaphore.release();
            }
        });
        semaphore.acquire();

        /* Waiting user confirmation so no log sent yet. */
        ArgumentMatcher<Log> matchCrashLog = new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object o) {
                return o instanceof ManagedErrorLog;
            }
        };
        verify(mChannel, never()).enqueue(argThat(matchCrashLog), anyString(), anyInt());
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
        verify(crashesListener).shouldProcess(any(ErrorReport.class));
        verify(crashesListener).shouldAwaitUserConfirmation();
        verifyNoMoreInteractions(crashesListener);

        /* Confirm to resume processing. */
        final AtomicReference<Log> log = new AtomicReference<>();
        doAnswer(new Answer() {

            @Override
            public Object answer(InvocationOnMock invocationOnMock) {
                log.set((Log) invocationOnMock.getArguments()[0]);
                return null;
            }
        }).when(mChannel).enqueue(argThat(matchCrashLog), anyString(), anyInt());
        Crashes.notifyUserConfirmation(Crashes.ALWAYS_SEND);
        assertTrue(Crashes.isEnabled().get());
        verify(mChannel).enqueue(argThat(matchCrashLog), anyString(), eq(PERSISTENCE_CRITICAL));
        assertNotNull(log.get());
        assertEquals(1, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);

        verify(crashesListener).getErrorAttachments(any(ErrorReport.class));
        verifyNoMoreInteractions(crashesListener);

        /* Third process: sending succeeds. */
        android.util.Log.i(TAG, "Process 3");
        mChannel = mock(Channel.class);
        ArgumentCaptor<Channel.GroupListener> groupListener = ArgumentCaptor.forClass(Channel.GroupListener.class);
        startFresh(crashesListener);
        verify(mChannel).addGroup(anyString(), anyInt(), anyInt(), anyInt(), isNull(Ingestion.class), groupListener.capture());
        groupListener.getValue().onBeforeSending(log.get());
        groupListener.getValue().onSuccess(log.get());

        /* Wait callback to be processed in background thread (file manipulations) then called back in UI. */

        /*
         * Wait background thread to process the 2 previous commands,
         * to do we check if crashed in last session, since we restarted process again after crash,
         * it's false even if we couldn't send the log yet.
         */
        assertFalse(Crashes.hasCrashedInLastSession().get());
        assertNull(Crashes.getLastSessionCrashReport().get());

        /* Wait U.I. thread callbacks. */
        HandlerUtils.runOnUiThread(new Runnable() {

            @Override
            public void run() {
                semaphore.release();
            }
        });
        semaphore.acquire();

        assertEquals(0, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
        verify(mChannel, never()).enqueue(argThat(matchCrashLog), anyString(), anyInt());
        verify(crashesListener).onBeforeSending(any(ErrorReport.class));
        verify(crashesListener).onSendingSucceeded(any(ErrorReport.class));
        verifyNoMoreInteractions(crashesListener);
    }

    @Test
    public void processingWithMinidump() throws Exception {

        /* Simulate we have a minidump. */
        File newMinidumpDirectory = ErrorLogHelper.getNewMinidumpDirectory();
        File minidumpFile = new File(newMinidumpDirectory, "minidump.dmp");
        FileManager.write(minidumpFile, "mock minidump");

        /* Set up crash listener. */
        CrashesListener crashesListener = mock(CrashesListener.class);
        when(crashesListener.shouldProcess(any(ErrorReport.class))).thenReturn(true);
        when(crashesListener.shouldAwaitUserConfirmation()).thenReturn(true);
        ErrorAttachmentLog textAttachment = ErrorAttachmentLog.attachmentWithText("Hello", "hello.txt");
        when(crashesListener.getErrorAttachments(any(ErrorReport.class))).thenReturn(Collections.singletonList(textAttachment));
        startFresh(crashesListener);

        /* Check last session error report. */
        assertTrue(Crashes.hasCrashedInLastSession().get());

        /* Wait U.I. thread callback (shouldAwaitUserConfirmation). */
        final Semaphore semaphore = new Semaphore(0);
        HandlerUtils.runOnUiThread(new Runnable() {

            @Override
            public void run() {
                semaphore.release();
            }
        });
        semaphore.acquire();

        /* Waiting user confirmation so no log sent yet. */
        ArgumentMatcher<Log> matchCrashLog = new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object o) {
                return o instanceof ManagedErrorLog;
            }
        };
        verify(mChannel, never()).enqueue(argThat(matchCrashLog), anyString(), anyInt());
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
        verify(crashesListener).shouldProcess(any(ErrorReport.class));
        verify(crashesListener).shouldAwaitUserConfirmation();
        verifyNoMoreInteractions(crashesListener);

        /* Confirm to resume processing. */
        final AtomicReference<Log> log = new AtomicReference<>();
        doAnswer(new Answer() {

            @Override
            public Object answer(InvocationOnMock invocationOnMock) {
                log.set((Log) invocationOnMock.getArguments()[0]);
                return null;
            }
        }).when(mChannel).enqueue(argThat(matchCrashLog), anyString(), anyInt());
        Crashes.notifyUserConfirmation(Crashes.SEND);
        assertTrue(Crashes.isEnabled().get());
        verify(mChannel).enqueue(argThat(matchCrashLog), anyString(), eq(PERSISTENCE_CRITICAL));
        assertNotNull(log.get());
        assertEquals(1, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
        verify(crashesListener).getErrorAttachments(any(ErrorReport.class));
        verifyNoMoreInteractions(crashesListener);

        /* Verify automatic minidump attachment. */
        verify(mChannel).enqueue(argThat(new ArgumentMatcher<Log>() {

            @Override
            public boolean matches(Object argument) {
                if (argument instanceof ErrorAttachmentLog) {
                    ErrorAttachmentLog log = (ErrorAttachmentLog) argument;
                    return "application/octet-stream".equals(log.getContentType()) && "minidump.dmp".equals(log.getFileName());
                }
                return false;
            }
        }), anyString(), eq(DEFAULT_FLAGS));

        /* Verify custom text attachment. */
        verify(mChannel).enqueue(eq(textAttachment), anyString(), eq(DEFAULT_FLAGS));
    }

    @Test
    public void cleanupFilesOnDisable() throws Exception {

        /* Crash. */
        android.util.Log.i(TAG, "Process 1");
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        startFresh(null);
        assertTrue(Crashes.isEnabled().get());
        final RuntimeException exception = new RuntimeException();
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        verify(uncaughtExceptionHandler).uncaughtException(thread, exception);
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);

        /* Disable, test waiting for disable to finish. */
        Crashes.setEnabled(false).get();
        assertFalse(Crashes.isEnabled().get());
        assertEquals(0, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
    }

    @Test
    public void setEnabledWhileAlreadyEnabledShouldNotDuplicateCrashReport() throws Exception {

        /* Test the fix of the duplicate crash sending bug. */
        android.util.Log.i(TAG, "Process 1");
        Thread.UncaughtExceptionHandler uncaughtExceptionHandler = mock(Thread.UncaughtExceptionHandler.class);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
        startFresh(null);
        Crashes.setEnabled(true);
        assertTrue(Crashes.isEnabled().get());
        final RuntimeException exception = new RuntimeException();
        final Thread thread = new Thread() {

            @Override
            public void run() {
                throw exception;
            }
        };
        thread.start();
        thread.join();
        verify(uncaughtExceptionHandler).uncaughtException(thread, exception);

        /* Check there are only 2 files: the throwable and the json one. */
        assertEquals(2, ErrorLogHelper.getErrorStorageDirectory().listFiles(mMinidumpFilter).length);
    }

    private static Error generateStackOverflowError() {
        try {
            return generateStackOverflowError();
        } catch (StackOverflowError error) {
            return error;
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static RuntimeException generateHugeException(int stacktraceIncrease, int causes) {
        if (stacktraceIncrease > 0) {
            try {
                return generateHugeException(stacktraceIncrease - 1, causes);
            } catch (StackOverflowError ignore) {
            }
        }
        Exception e = new Exception();
        for (int i = 0; i < causes; i++) {
            e = new Exception(Integer.valueOf(i).toString(), e);
        }
        return new RuntimeException(e);
    }
}
