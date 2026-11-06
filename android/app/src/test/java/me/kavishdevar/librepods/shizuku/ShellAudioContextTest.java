package me.kavishdevar.librepods.shizuku;

import android.app.Application;
import android.content.Context;
import android.media.session.MediaSessionManager;
import android.os.Process;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {37}, application = Application.class)
public class ShellAudioContextTest {
    @Test public void typedMediaLookupPreparesTheRuntimeBeforeDelegating() {
        int before = Process.myUid();
        ShadowProcess.setUid(0);
        try (MockedStatic<RootAppProcess> runtime = mockStatic(RootAppProcess.class)) {
            Context base = mock(Context.class);
            MediaSessionManager manager = mock(MediaSessionManager.class);
            AtomicBoolean prepared = new AtomicBoolean(false);
            runtime.when(RootAppProcess::prepareMedia).thenAnswer(call -> { prepared.set(true); return null; });
            when(base.getSystemServiceName(MediaSessionManager.class)).thenReturn(Context.MEDIA_SESSION_SERVICE);
            when(base.getSystemService(Context.MEDIA_SESSION_SERVICE)).thenAnswer(call -> {
                assertTrue(prepared.get()); return manager;
            });
            assertSame(manager, new ShellAudioContext(base).getSystemService(MediaSessionManager.class));
            runtime.verify(RootAppProcess::prepareMedia);
        } finally { ShadowProcess.setUid(before); }
    }

    @Test public void unrelatedAudioLookupNeverInitializesTheMediaModule() {
        int before = Process.myUid();
        ShadowProcess.setUid(2000);
        try (MockedStatic<RootAppProcess> runtime = mockStatic(RootAppProcess.class)) {
            Context base = mock(Context.class);
            Object audio = new Object();
            when(base.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audio);
            assertSame(audio, new ShellAudioContext(base).getSystemService(Context.AUDIO_SERVICE));
            runtime.verifyNoInteractions();
        } finally { ShadowProcess.setUid(before); }
    }

    @Test public void unverifiedMediaInitializationCannotReachTheFrameworkLookup() {
        int before = Process.myUid();
        ShadowProcess.setUid(0);
        try (MockedStatic<RootAppProcess> runtime = mockStatic(RootAppProcess.class)) {
            Context base = mock(Context.class);
            runtime.when(RootAppProcess::prepareMedia).thenThrow(
                new MediaRuntimeUnavailableException(new IllegalStateException("private runtime detail")));
            assertThrows(MediaRuntimeUnavailableException.class,
                () -> new ShellAudioContext(base).getSystemService(Context.MEDIA_SESSION_SERVICE));
            verify(base, never()).getSystemService(anyString());
        } finally { ShadowProcess.setUid(before); }
    }
}
