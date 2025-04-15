package com.duckduckgo.app.dns.socket_pool;

import android.os.Build;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class SocketClientJava {

    private static final Logger LOGGER = Logger.getLogger(SocketClientJava.class.getName());

    private SSLSocket client;
    private DataInputStream input;
    private DataOutputStream output;

    private static final int TIMEOUT_IN_MS = 2 * 1000;

    public SocketClientJava(String host, int port, String dnsHostName) {
        this.create(host, port, dnsHostName);
    }

    private void create(String host, int port, String dnsHostName) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SSLSocketFactory socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            Future<?> future = executor.submit(() -> {
                try {
                    client = (SSLSocket) socketFactory.createSocket(host, port);
                } catch (Exception e) {
                    throw new RuntimeException("Socket creation failed", e);
                }
            });

            try {
                // Wait for the socket creation to complete, with a timeout
                future.get(1500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true); // Attempt to interrupt the task
                throw new Exception("Socket creation timed out", e);
            } catch (Exception e) {
                throw new Exception("Socket creation thread interrupted", e);
            }

            configureSocket(dnsHostName);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        } finally {
            executor.shutdownNow(); // Ensure the thread is stopped
        }
    }

    private void configureSocket(String dnsHostName) throws IOException {
        this.client.setSoTimeout(TIMEOUT_IN_MS);
        this.client.setTcpNoDelay(true);
        this.client.setKeepAlive(true);
        this.client.setSSLParameters(getTLSHeader(dnsHostName));

        this.input = new DataInputStream(this.client.getInputStream());
        this.output = new DataOutputStream(this.client.getOutputStream());
        this.output.flush();
    }

    private SSLParameters getTLSHeader(String dnsHostName) {
        final SSLParameters parameters = new SSLParameters();
        parameters.setServerNames(Collections.singletonList(new SNIHostName(dnsHostName)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
        } else {
            try {
                Method method = this.getClass().getMethod("setApplicationProtocols", String[].class);
                method.invoke(this, (Object) new String[]{"http/1.1"});
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error setting application protocols: %s", e.getMessage());
            }
        }

        return parameters;
    }

    public byte[] execute(byte[] message) {
        try {
            this.output.writeShort(message.length);
            this.output.write(message);
            this.output.flush();

            byte[] responseBytes = new byte[this.input.readUnsignedShort()];
            input.readFully(responseBytes);
            return responseBytes;
        } catch (Exception ioex) {
            LOGGER.log(Level.SEVERE, "Error sending message to socket", ioex);
            return null;
        }
    }

    public void close() {
        if (this.client != null) {
            try {
                this.client.close();
            } catch (Exception ioex) {
                LOGGER.log(Level.WARNING, "The socket client could not be closed.", ioex);
            } finally {
                this.client = null;
            }
        }
    }

    public boolean isValid() {
        if (this.client != null) {
            return this.client.isClosed();
        }
        return false;
    }

}
