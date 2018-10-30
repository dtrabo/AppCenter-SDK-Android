package com.microsoft.appcenter;

import com.microsoft.appcenter.utils.AppCenterLog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.verifyStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(AppCenterLog.class)
public class FlagsTest {

    @Before
    public void setUp() {
        mockStatic(AppCenterLog.class);
    }

    @Test
    public void persistenceNone() {
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(0, false));
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(0, true));
        verifyStatic(never());
        AppCenterLog.warn(anyString(), anyString());
    }

    @Test
    public void persistenceNormal() {
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(Flags.PERSISTENCE_NORMAL, false));
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(Flags.PERSISTENCE_NORMAL, true));
        verifyStatic(never());
        AppCenterLog.warn(anyString(), anyString());
    }

    @Test
    public void persistenceCritical() {
        assertEquals(Flags.PERSISTENCE_CRITICAL, Flags.getPersistencePriority(Flags.PERSISTENCE_CRITICAL, false));
        assertEquals(Flags.PERSISTENCE_CRITICAL, Flags.getPersistencePriority(Flags.PERSISTENCE_CRITICAL, true));
        verifyStatic(never());
        AppCenterLog.warn(anyString(), anyString());
    }

    @Test
    public void persistenceDefaults() {
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(Flags.DEFAULT_FLAGS, false));
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(Flags.DEFAULT_FLAGS, true));
        verifyStatic(never());
        AppCenterLog.warn(anyString(), anyString());
    }

    @Test
    public void persistenceCriticalPlusOtherFlag() {
        assertEquals(Flags.PERSISTENCE_CRITICAL, Flags.getPersistencePriority(Flags.PERSISTENCE_CRITICAL | 0x0100, false));
        assertEquals(Flags.PERSISTENCE_CRITICAL, Flags.getPersistencePriority(Flags.PERSISTENCE_CRITICAL | 0x0200, true));
        verifyStatic(never());
        AppCenterLog.warn(anyString(), anyString());
    }

    @Test
    public void persistenceInvalidFlag() {

        /* Fallback without warning. */
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(0x09, false));
        verifyStatic(never());
        AppCenterLog.warn(anyString(), anyString());

        /* Fallback with warning. */
        assertEquals(Flags.PERSISTENCE_NORMAL, Flags.getPersistencePriority(0x09, true));
        verifyStatic();
        AppCenterLog.warn(anyString(), anyString());
    }
}
