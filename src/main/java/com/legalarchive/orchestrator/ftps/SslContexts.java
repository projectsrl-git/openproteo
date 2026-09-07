package com.legalarchive.orchestrator.ftps;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Builds the SSLContext a target needs: its client certificate, and how it judges the server's. */
public final class SslContexts {

    private SslContexts() {
    }

    public static SSLContext build(FtpsTarget target) throws IOException {
        try {
            KeyManager[] keyManagers = clientKeyManagers(target);
            TrustManager[] trustManagers = trustManagers(target);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, trustManagers, null);
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot build the TLS context for target "
                    + target.getId() + ": " + e, e);
        }
    }

    private static KeyManager[] clientKeyManagers(FtpsTarget target) throws Exception {
        String path = target.getClientCertFile();
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path);
        // Checked here, before any socket is opened, because a wrong path is now the most likely
        // misconfiguration: the target names an absolute path with no base directory to validate
        // it against. Left to the handshake, a missing file presents as a TLS failure, which is an
        // error one goes looking for on the server.
        if (!file.isFile()) {
            throw new IOException("client certificate not found: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IOException("client certificate is not readable: " + file.getAbsolutePath());
        }
        char[] pwd = target.getClientCertPassword().toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(file);
        try {
            ks.load(in, pwd);
        } finally {
            in.close();
        }
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pwd);
        return kmf.getKeyManagers();
    }

    private static TrustManager[] trustManagers(FtpsTarget target) throws Exception {
        switch (target.getTrustMode()) {
            case JVM:
                // null lets JSSE install the default managers over cacerts.
                return null;
            case ANY:
                return new TrustManager[]{TRUST_EVERYTHING};
            case WINDOWS:
                return fromKeyStore(loadWindowsRoot());
            case FILE:
                return fromKeyStore(loadTrustFile(target));
            default:
                return null;
        }
    }

    private static KeyStore loadWindowsRoot() throws Exception {
        try {
            KeyStore ks = KeyStore.getInstance("Windows-ROOT");
            ks.load(null, null);
            return ks;
        } catch (Exception e) {
            throw new IOException("trustMode=WINDOWS needs the SunMSCAPI provider, which exists"
                    + " only on a Windows JVM: " + e, e);
        }
    }

    private static KeyStore loadTrustFile(FtpsTarget target) throws Exception {
        String path = target.getTrustStoreFile();
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("trustMode=FILE but no truststore file is configured");
        }
        File file = new File(path);
        if (!file.isFile()) {
            throw new IOException("truststore not found: " + file.getAbsolutePath());
        }
        String type = path.toLowerCase().endsWith(".p12") || path.toLowerCase().endsWith(".pfx")
                ? "PKCS12" : KeyStore.getDefaultType();
        KeyStore ks = KeyStore.getInstance(type);
        InputStream in = new FileInputStream(file);
        try {
            ks.load(in, target.getTrustStorePassword().toCharArray());
        } finally {
            in.close();
        }
        return ks;
    }

    private static TrustManager[] fromKeyStore(KeyStore ks) throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    private static final X509TrustManager TRUST_EVERYTHING = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
