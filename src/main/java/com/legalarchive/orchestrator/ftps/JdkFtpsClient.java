package com.legalarchive.orchestrator.ftps;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * An FTPS client built on the JDK alone: explicit TLS on the control channel, passive data
 * connections, binary STOR and SIZE. Not a general-purpose FTP client and not trying to be.
 *
 * <p>It exists rather than a library because Maven Central is unreachable from the environment
 * where this code is written and exercised, so an implementation importing one could not be
 * compiled there, let alone driven through a conversation, and would reach the target having never
 * run. The scope that makes that defensible is narrow: upload files to two known servers.
 */
public final class JdkFtpsClient implements FtpsTransport {

    @Override
    public FtpsSession open(FtpsTarget target) throws IOException {
        return new Session(target);
    }

    private static final class Session implements FtpsSession {

        private final FtpsTarget target;
        private final SSLContext sslContext;
        private final SSLSocketFactory factory;
        private final List<String> trace = new ArrayList<String>();

        private Socket control;
        private BufferedReader in;
        private BufferedWriter out;
        private TransferMode currentType;
        private String currentDir = "";
        private javax.net.ssl.SSLSession controlSession;
        private boolean reuseUnavailableReported;
        private String dataHost;
        private int dataPort;

        Session(FtpsTarget target) throws IOException {
            this.target = target;
            this.sslContext = SslContexts.build(target);
            this.factory = sslContext.getSocketFactory();
            if (target.getTrustMode() == TrustMode.ANY) {
                trace.add("WARN trustMode=ANY: the server certificate is not being checked");
            }
            connectAndLogIn();
        }

        // ---------- connection ----------

        private void connectAndLogIn() throws IOException {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(target.getHost(), target.getPort()),
                    target.getConnectTimeoutSec() * 1000);
            plain.setSoTimeout(target.getDataTimeoutSec() * 1000);
            this.control = plain;
            bind(plain);

            expect(readReply(), 220, "welcome");

            send("AUTH TLS");
            FtpReply auth = readReply();
            if (!auth.isPositiveCompletion() && !auth.isPositiveIntermediate()) {
                throw new FtpProtocolException("AUTH TLS refused: " + auth.text());
            }

            SSLSocket secure = (SSLSocket) factory.createSocket(
                    plain, target.getHost(), target.getPort(), true);
            secure.setUseClientMode(true);
            secure.setEnabledProtocols(target.enabledProtocols());
            secure.startHandshake();
            this.control = secure;
            bind(secure);
            this.controlSession = secure.getSession();
            trace.add("TLS control " + secure.getSession().getProtocol()
                    + " " + secure.getSession().getCipherSuite());

            send("USER " + target.getUsername());
            FtpReply user = readReply();
            if (user.isPositiveIntermediate()) {
                // 331: a password is wanted. Sent, and never traced.
                sendSecret("PASS " + target.getPassword(), "PASS ******");
                expect(readReply(), 230, "login");
            } else if (!user.isPositiveCompletion()) {
                throw new FtpProtocolException("login refused: " + user.text());
            }
            // A 230 straight after USER is the certificate-only login, which is how both servers in
            // scope behave. Sending PASS into it would be answered 503 at best.

            send("PBSZ 0");
            expect(readReply(), 200, "PBSZ");
            send("PROT P");
            expect(readReply(), 200, "PROT");
        }

        private void bind(Socket s) throws IOException {
            this.in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), target.getControlEncoding()));
            this.out = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), target.getControlEncoding()));
        }

        // ---------- commands ----------

        @Override
        public void site(String command) throws IOException {
            String c = command == null ? "" : command.trim();
            if (c.isEmpty()) {
                return;
            }
            send(c.toUpperCase().startsWith("SITE ") ? c : "SITE " + c);
            FtpReply r = readReply();
            if (!r.isPositiveCompletion()) {
                throw new FtpProtocolException("SITE refused: " + r.text());
            }
        }

        @Override
        public long store(File local, String remoteDir, String remoteName, TransferMode mode)
                throws IOException {
            if (mode == TransferMode.ASCII) {
                // Refused rather than approximated. In ASCII the protocol requires CRLF on the
                // wire, and the files this would carry end their lines with LF alone; sending the
                // bytes verbatim would produce a file that arrives, reports the right size and is
                // wrong. Deferred with the z/OS target, where it belongs with SITE and dataset
                // names.
                throw new FtpProtocolException(
                        "ASCII transfer is not implemented yet: it is deferred with the z/OS"
                                + " target, and sending bytes unchanged in ASCII mode would"
                                + " silently corrupt record boundaries");
            }
            if (!local.isFile()) {
                throw new IOException("file to send does not exist: " + local.getAbsolutePath());
            }
            setType(mode);
            changeDir(remoteDir);

            Socket data = openPassiveSocket();
            long written = 0;
            try {
                send("STOR " + remoteName);
                FtpReply started = readReply();
                if (!started.isPositivePreliminary() && !started.isPositiveCompletion()) {
                    throw new FtpProtocolException("STOR refused: " + started.text());
                }
                // The TLS handshake on the data channel happens HERE, after the transfer command
                // and its 1xx, and not when the socket was opened. A server accepts the data
                // connection only once it knows what it is for, so handshaking earlier leaves the
                // client waiting for a server hello that cannot arrive until the client sends the
                // command it is blocked from sending. Found by the transport tests, which
                // deadlocked exactly this way.
                data = secureData(data);
                OutputStream os = data.getOutputStream();
                InputStream is = new FileInputStream(local);
                try {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        written += n;
                    }
                    os.flush();
                } finally {
                    is.close();
                }
            } finally {
                closeQuietly(data);
            }
            FtpReply done = readReply();
            if (!done.isPositiveCompletion()) {
                throw new FtpProtocolException("transfer not confirmed: " + done.text());
            }
            trace.add("STOR " + remoteName + " bytes=" + written);
            return written;
        }

        @Override
        public long size(String remoteDir, String remoteName) throws IOException {
            changeDir(remoteDir);
            send("SIZE " + remoteName);
            FtpReply r = readReply();
            if (!r.isPositiveCompletion()) {
                throw new FtpProtocolException("SIZE failed for " + remoteName + ": " + r.text()
                        + " - a transfer that cannot be verified is not a transfer that succeeded."
                        + " If this account is allowed to store but not to stat, or the file is"
                        + " collected as soon as it lands, set Verify to NONE on the target and the"
                        + " server's 226 becomes the confirmation");
            }
            String line = r.lastLine();
            int sp = line.indexOf(' ');
            String value = sp < 0 ? "" : line.substring(sp + 1).trim();
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new FtpProtocolException("SIZE reply is not a number: " + line);
            }
        }

        private void setType(TransferMode mode) throws IOException {
            if (mode == currentType) {
                return;
            }
            send("TYPE " + mode.typeCode());
            expect(readReply(), 200, "TYPE");
            currentType = mode;
        }

        private void changeDir(String dir) throws IOException {
            String d = dir == null ? "" : dir.trim();
            if (d.isEmpty() || d.equals(currentDir)) {
                return;
            }
            send("CWD " + d);
            FtpReply r = readReply();
            if (!r.isPositiveCompletion()) {
                throw new FtpProtocolException("cannot change to remote directory " + d + ": "
                        + r.text());
            }
            currentDir = d;
        }

        // ---------- data channel ----------

        private Socket openPassiveSocket() throws IOException {
            if (!target.isPassive()) {
                throw new FtpProtocolException("active mode is not implemented");
            }
            send("PASV");
            FtpReply r = readReply();
            if (!r.isPositiveCompletion()) {
                throw new FtpProtocolException("PASV refused: " + r.text());
            }
            PasvReply pasv = PasvReply.parse(r.lastLine());

            String host = target.getHost();
            if (!target.isIgnorePasvAddress()) {
                host = pasv.advertisedHost();
            } else if (!pasv.advertisedHost().equals(target.getHost())) {
                // Logged rather than silently swallowed: against the UAT estate the server answers
                // with an address that is not routable from the client, and a step log that does
                // not say which address was discarded turns a diagnosable failure into a mystery.
                trace.add("PASV advertised " + pasv.advertisedHost()
                        + ", using the control host " + host + " instead");
            }

            this.dataHost = host;
            this.dataPort = pasv.port();

            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, pasv.port()),
                    target.getConnectTimeoutSec() * 1000);
            plain.setSoTimeout(target.getDataTimeoutSec() * 1000);
            return plain;
        }

        private SSLSocket secureData(Socket plain) throws IOException {
            if (target.isReuseTlsSession()) {
                installControlSessionForDataEndpoint();
            }
            SSLSocket secure = (SSLSocket) factory.createSocket(plain, dataHost, dataPort, true);
            secure.setUseClientMode(true);
            secure.setEnabledProtocols(target.enabledProtocols());
            secure.startHandshake();
            boolean resumed = controlSession != null
                    && java.util.Arrays.equals(controlSession.getId(), secure.getSession().getId());
            trace.add("TLS data " + secure.getSession().getProtocol()
                    + (resumed ? " resumed the control session"
                               : " NEW session, the control session was not resumed"));
            return secure;
        }

        /**
         * Puts the control session into the client session cache under the data endpoint's key, so
         * that the handshake about to happen resumes it.
         *
         * <p>The published alternative - wrapping the data socket while naming the control host and
         * port, so that JSSE looks the session up under the key the control handshake populated -
         * was measured and does not work. The cache key follows the socket's real peer port, not
         * the one passed to createSocket, so naming the control endpoint finds nothing and the
         * client offers an empty session id. Measured on both Java 8, the production runtime, and
         * Java 21; the injection below was measured to resume on Java 8.
         *
         * <p>It reaches into JSSE internals, so it is wrapped in a catch-everything: on a JDK where
         * the field is absent or encapsulated the transfer proceeds with a fresh session and the
         * trace says so, rather than the whole delivery failing over a performance-shaped
         * optimisation. Whether a fresh data session is acceptable is the server's decision, and
         * the trace is what tells an operator which of the two happened.
         */
        private void installControlSessionForDataEndpoint() {
            if (controlSession == null) {
                return;
            }
            try {
                Object context = controlSession.getSessionContext();
                java.lang.reflect.Field field =
                        context.getClass().getDeclaredField("sessionHostPortCache");
                field.setAccessible(true);
                Object cache = field.get(context);
                java.lang.reflect.Method put =
                        cache.getClass().getDeclaredMethod("put", Object.class, Object.class);
                put.setAccessible(true);
                String key = (dataHost + ":" + dataPort).toLowerCase(java.util.Locale.ROOT);
                put.invoke(cache, key, controlSession);
            } catch (Throwable t) {
                if (!reuseUnavailableReported) {
                    reuseUnavailableReported = true;
                    trace.add("TLS session reuse is not available on this JVM (" + t
                            + "); data connections will negotiate a fresh session");
                }
            }
        }

        // ---------- plumbing ----------

        private void send(String command) throws IOException {
            trace.add("> " + command);
            writeLine(command);
        }

        private void sendSecret(String command, String traceAs) throws IOException {
            trace.add("> " + traceAs);
            writeLine(command);
        }

        private void writeLine(String command) throws IOException {
            out.write(command);
            out.write("\r\n");
            out.flush();
        }

        private FtpReply readReply() throws IOException {
            FtpReply reply = FtpReplyReader.read(new FtpReplyReader.LineSource() {
                @Override
                public String readLine() throws IOException {
                    return in.readLine();
                }
            });
            trace.add("< " + reply.text());
            return reply;
        }

        private void expect(FtpReply reply, int code, String what) throws IOException {
            if (reply.code() != code) {
                throw new FtpProtocolException(what + " expected " + code + " but the server said: "
                        + reply.text());
            }
        }

        @Override
        public List<String> trace() {
            return Collections.unmodifiableList(trace);
        }

        @Override
        public void close() {
            try {
                if (control != null && !control.isClosed()) {
                    writeLine("QUIT");
                }
            } catch (IOException ignored) {
                // Closing is best effort: the transfers are already done and verified, and a
                // failure here must not turn a good delivery into a reported one.
            }
            closeQuietly(control);
        }

        private static void closeQuietly(Socket s) {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // as above
                }
            }
        }
    }
}
