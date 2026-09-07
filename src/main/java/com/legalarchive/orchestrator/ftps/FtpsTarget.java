package com.legalarchive.orchestrator.ftps;

/**
 * One FTPS destination. A plain bean, the way DataSourceDef is, so the store in a later batch can
 * serialise it without a mapping layer.
 *
 * <p>Two defaults depart from the house rule that a new behaviour is born off, and the departure is
 * deliberate: ignorePasvAddress and reuseTlsSession are both ON. The 227 reply from this estate has
 * been observed to carry an address that is not routable from where the client runs, and the client
 * that works has data-channel session reuse enabled. A default known to be wrong for both servers
 * in scope is not conservative, it is broken. Both remain switchable per target.
 */
public class FtpsTarget {

    private String id;
    private String name;
    private String host;
    private int port = 2121;

    private String username;
    private String password = "";

    /** Absolute path to the PKCS#12 file. There is no configured base directory, by decision. */
    private String clientCertFile;
    private String clientCertPassword = "";

    private TrustMode trustMode = TrustMode.WINDOWS;
    private String trustStoreFile;
    private String trustStorePassword = "";

    private String minTls = "TLSv1.2";
    private String maxTls = "TLSv1.3";

    private boolean passive = true;
    private boolean ignorePasvAddress = true;
    private boolean reuseTlsSession = true;

    private int connectTimeoutSec = 30;
    private int dataTimeoutSec = 300;

    private String controlEncoding = "UTF-8";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password == null ? "" : password;
    }

    public String getClientCertFile() {
        return clientCertFile;
    }

    public void setClientCertFile(String clientCertFile) {
        this.clientCertFile = clientCertFile;
    }

    public String getClientCertPassword() {
        return clientCertPassword;
    }

    public void setClientCertPassword(String clientCertPassword) {
        this.clientCertPassword = clientCertPassword == null ? "" : clientCertPassword;
    }

    public TrustMode getTrustMode() {
        return trustMode;
    }

    public void setTrustMode(TrustMode trustMode) {
        this.trustMode = trustMode == null ? TrustMode.WINDOWS : trustMode;
    }

    public String getTrustStoreFile() {
        return trustStoreFile;
    }

    public void setTrustStoreFile(String trustStoreFile) {
        this.trustStoreFile = trustStoreFile;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword == null ? "" : trustStorePassword;
    }

    public String getMinTls() {
        return minTls;
    }

    public void setMinTls(String minTls) {
        this.minTls = minTls;
    }

    public String getMaxTls() {
        return maxTls;
    }

    public void setMaxTls(String maxTls) {
        this.maxTls = maxTls;
    }

    public boolean isPassive() {
        return passive;
    }

    public void setPassive(boolean passive) {
        this.passive = passive;
    }

    public boolean isIgnorePasvAddress() {
        return ignorePasvAddress;
    }

    public void setIgnorePasvAddress(boolean ignorePasvAddress) {
        this.ignorePasvAddress = ignorePasvAddress;
    }

    public boolean isReuseTlsSession() {
        return reuseTlsSession;
    }

    public void setReuseTlsSession(boolean reuseTlsSession) {
        this.reuseTlsSession = reuseTlsSession;
    }

    public int getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    public void setConnectTimeoutSec(int connectTimeoutSec) {
        this.connectTimeoutSec = connectTimeoutSec;
    }

    public int getDataTimeoutSec() {
        return dataTimeoutSec;
    }

    public void setDataTimeoutSec(int dataTimeoutSec) {
        this.dataTimeoutSec = dataTimeoutSec;
    }

    public String getControlEncoding() {
        return controlEncoding;
    }

    public void setControlEncoding(String controlEncoding) {
        this.controlEncoding = controlEncoding;
    }

    /**
     * The TLS protocol names to enable, narrowed to the configured window.
     *
     * <p>maxTls exists as a lever rather than as decoration. Data-channel reuse is the session-ID
     * mechanism, which TLS 1.3 replaced with tickets; if the data connection is refused while
     * everything else works, capping at TLSv1.2 is the first thing to try.
     */
    public String[] enabledProtocols() {
        String[] all = {"TLSv1.2", "TLSv1.3"};
        java.util.List<String> out = new java.util.ArrayList<String>();
        boolean started = false;
        for (String p : all) {
            if (p.equals(minTls)) {
                started = true;
            }
            if (started) {
                out.add(p);
            }
            if (p.equals(maxTls)) {
                break;
            }
        }
        if (out.isEmpty()) {
            out.add("TLSv1.2");
        }
        return out.toArray(new String[out.size()]);
    }
}
