package com.redhat.rhjmc.containerjfr.net;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Path;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openjdk.jmc.rjmx.services.jfr.IFlightRecorderService;
import org.openjdk.jmc.rjmx.services.jfr.IRecordingDescriptor;

import com.redhat.rhjmc.containerjfr.TestBase;
import com.redhat.rhjmc.containerjfr.core.net.JFRConnection;
import com.redhat.rhjmc.containerjfr.core.sys.Environment;
import fi.iki.elonen.NanoHTTPD;

@ExtendWith(MockitoExtension.class)
class RecordingExporterTest extends TestBase {

    RecordingExporter exporter;
    @Mock
    Path recordingsPath;
    @Mock
    Environment env;
    @Mock
    JFRConnection connection;
    @Mock
    IFlightRecorderService service;
    @Mock
    NetworkResolver resolver;
    @Mock
    NanoHTTPD server;

    @BeforeEach
    void setup() {
        exporter = new RecordingExporter(recordingsPath, env, mockClientWriter, resolver, server);
    }

    @Test
    void shouldDoNothingOnInit() {
        verifyZeroInteractions(connection);
        verifyZeroInteractions(service);
        verifyZeroInteractions(server);
        verifyZeroInteractions(resolver);
    }

    @Test
    void shouldSuccessfullyInstantiateWithDefaultServer() {
        when(env.getEnv(Mockito.eq("CONTAINER_JFR_DOWNLOAD_PORT"), Mockito.anyString())).thenReturn("1234");
        assertDoesNotThrow(() -> new RecordingExporter(recordingsPath, env, mockClientWriter, resolver));
    }

    @Test
    void shouldRestartOnConnectionChange() throws Exception {
        when(connection.getService()).thenReturn(service);
        when(server.isAlive()).thenReturn(true).thenReturn(false);

        exporter.connectionChanged(connection);

        verify(connection).getService();
        verify(service).getAvailableRecordings();
        InOrder inOrder = inOrder(server);
        inOrder.verify(server).isAlive();
        inOrder.verify(server).stop();
        inOrder.verify(server).isAlive();
        inOrder.verify(server).start();

        verifyNoMoreInteractions(server);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(service);
    }

    @Test
    void shouldStopOnDisconnect() throws Exception {
        when(server.isAlive()).thenReturn(true);

        exporter.connectionChanged(null);

        InOrder inOrder = inOrder(server);
        inOrder.verify(server).isAlive();
        inOrder.verify(server).stop();

        verifyNoMoreInteractions(server);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(service);
    }

    @Test
    void shouldThrowExceptionIfServerCannotStart() {
        Exception e = assertThrows(RuntimeException.class, () -> {
            when(connection.getService()).thenReturn(service);
            when(server.isAlive()).thenReturn(true).thenReturn(false);
            doThrow(IOException.class).when(server).start();

            exporter.connectionChanged(connection);
        });
        MatcherAssert.assertThat(e.getMessage(), Matchers.equalTo("java.io.IOException"));
    }

    @Test
    void shouldStartEvenWhileDisconnectedFromTarget() throws Exception {
        when(server.isAlive()).thenReturn(false);

        exporter.start();

        verify(server).start();
    }

    @Test
    void shouldDoNothingIfStartedWhileRunning() throws Exception {
        when(connection.getService()).thenReturn(service);
        when(server.isAlive()).thenReturn(true).thenReturn(false).thenReturn(true);

        exporter.connectionChanged(connection);

        verify(connection).getService();
        verify(service).getAvailableRecordings();
        InOrder inOrder = inOrder(server);
        inOrder.verify(server).isAlive();
        inOrder.verify(server).stop();
        inOrder.verify(server).isAlive();
        inOrder.verify(server).start();

        verifyNoMoreInteractions(server);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(service);

        exporter.start();

        verifyNoMoreInteractions(server);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(service);
    }

    @Test
    void shouldDoNothingIfStartedWhileDisconnected() throws Exception {
        when(server.isAlive()).thenReturn(true);

        exporter.connectionChanged(null);

        InOrder inOrder = inOrder(server);
        inOrder.verify(server).isAlive();
        inOrder.verify(server).stop();

        verifyNoMoreInteractions(server);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(service);

        exporter.start();

        verifyNoMoreInteractions(server);
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(service);
    }

    @Test
    void shouldInitializeDownloadCountsToZero() throws Exception {
        IRecordingDescriptor descriptor = mock(IRecordingDescriptor.class);
        when(descriptor.getName()).thenReturn("foo");

        exporter.addRecording(descriptor);

        MatcherAssert.assertThat(exporter.getDownloadCount("foo"), Matchers.equalTo(0));

        verifyZeroInteractions(server);
        verifyZeroInteractions(connection);
        verifyZeroInteractions(service);
    }

    @Test
    void shouldReportNegativeDownloadsForUnknownRecordings() throws Exception {
        MatcherAssert.assertThat(exporter.getDownloadCount("foo"), Matchers.lessThan(0));

        verifyZeroInteractions(server);
        verifyZeroInteractions(connection);
        verifyZeroInteractions(service);
    }

    @Test
    void shouldAllowRemovingRecordings() throws Exception {
        IRecordingDescriptor descriptor = mock(IRecordingDescriptor.class);
        when(descriptor.getName()).thenReturn("foo");

        exporter.addRecording(descriptor);
        MatcherAssert.assertThat(exporter.getDownloadCount("foo"), Matchers.equalTo(0));

        exporter.removeRecording(descriptor);
        MatcherAssert.assertThat(exporter.getDownloadCount("foo"), Matchers.lessThan(0));

        verifyZeroInteractions(server);
        verifyZeroInteractions(connection);
        verifyZeroInteractions(service);
    }

    @Test
    void shouldProvideDefaultHostUrl() throws Exception {
        when(env.getEnv(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> (String) invocation.getArguments()[1]);
        when(resolver.getHostAddress()).thenReturn("foo");

        MatcherAssert.assertThat(exporter.getHostUrl(), Matchers.equalTo(new URL("http", "foo", Integer.valueOf(RecordingExporter.DEFAULT_PORT), "")));
    }

    @Test
    void shouldProvideCustomizedHostUrl() throws Exception {
        when(env.getEnv(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String arg = (String) invocation.getArguments()[0];
            if (arg.equals(RecordingExporter.HOST_VAR)) {
                return "bar-host";
            } else {
                return (String) invocation.getArguments()[1];
            }
        });
        when(resolver.getHostAddress()).thenReturn("foo");

        MatcherAssert.assertThat(exporter.getHostUrl(), Matchers.equalTo(new URL("http", "bar-host", Integer.valueOf(RecordingExporter.DEFAULT_PORT), "")));
    }

    @Test
    void shouldProvideCustomizedPortUrl() throws Exception {
        when(env.getEnv(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String arg = (String) invocation.getArguments()[0];
            if (arg.equals(RecordingExporter.HOST_VAR)) {
                return (String) invocation.getArguments()[1];
            } else {
                return "1234";
            }
        });
        when(resolver.getHostAddress()).thenReturn("foo");

        MatcherAssert.assertThat(exporter.getHostUrl(), Matchers.equalTo(new URL("http", "foo", 1234, "")));
    }

    @Test
    void shouldProvideCustomizedUrl() throws Exception {
        when(env.getEnv(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String arg = (String) invocation.getArguments()[0];
            if (arg.equals(RecordingExporter.HOST_VAR)) {
                return "example";
            } else {
                return "9876";
            }
        });
        when(resolver.getHostAddress()).thenReturn("foo");

        MatcherAssert.assertThat(exporter.getHostUrl(), Matchers.equalTo(new URL("http", "example", 9876, "")));
    }

    @ParameterizedTest()
    @ValueSource(strings = {
        "foo",
        "bar.jfr",
        "some-recording.jfr",
        "another_recording",
        "alpha123"
    })
    void shouldProvideDownloadUrl(String recordingName)
            throws UnknownHostException, MalformedURLException, SocketException {
        String hostUrl = "example.com";
        when(env.getEnv(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenAnswer(invocation ->
            (String) invocation.getArguments()[1]
        );
        when(resolver.getHostAddress()).thenReturn(hostUrl);

        MatcherAssert.assertThat(exporter.getDownloadURL(recordingName), Matchers.equalTo("http://example.com:8181/" + recordingName));
    }

    @ParameterizedTest()
    @ValueSource(strings = {
        "foo",
        "bar.jfr",
        "some-recording.jfr",
        "another_recording",
        "alpha123"
    })
    void shouldProvideReportUrl(String recordingName)
            throws UnknownHostException, MalformedURLException, SocketException {
        String hostUrl = "example.com";
        when(env.getEnv(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenAnswer(invocation ->
            (String) invocation.getArguments()[1]
        );
        when(resolver.getHostAddress()).thenReturn(hostUrl);

        MatcherAssert.assertThat(exporter.getReportURL(recordingName), Matchers.equalTo("http://example.com:8181/reports/" + recordingName));
    }

}
