package com.legalarchive.orchestrator.ftps;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.legalarchive.orchestrator.config.AppProperties;

/**
 * Stores FTPS target definitions in a single readable JSON file
 * (orchestrator.ftp-targets-file, default ./ftp-targets.json), keyed by id.
 *
 * <p>It is deliberately not a DataSourceDef with type "ftps". The field sets are disjoint, and the
 * datasources page offers a test that runs a query - a button that could not mean anything for an
 * FTPS entry.
 *
 * <p>Passwords are stored in clear text, as datasources.json already does: protect the file with
 * filesystem ACLs. Neither the account password nor the certificate password is ever logged.
 */
@Component
public class FtpTargetStore {

    private static final Logger log = LoggerFactory.getLogger(FtpTargetStore.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final File file;
    private final Map<String, FtpsTarget> byId = new LinkedHashMap<String, FtpsTarget>();

    public FtpTargetStore(AppProperties props) {
        this.file = new File(props.getFtpTargetsFile());
        load();
    }

    public synchronized void load() {
        byId.clear();
        if (!file.exists()) {
            return;
        }
        try {
            FtpsTarget[] arr = mapper.readValue(file, FtpsTarget[].class);
            if (arr != null) {
                for (FtpsTarget t : arr) {
                    if (t.getId() != null) {
                        byId.put(t.getId(), t);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Cannot read FTPS targets file {}: {}", file, e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            mapper.writeValue(tmp.toFile(), byId.values().toArray(new FtpsTarget[0]));
            Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Cannot write FTPS targets file {}: {}", file, e.getMessage(), e);
        }
    }

    public synchronized List<FtpsTarget> all() {
        return new ArrayList<FtpsTarget>(byId.values());
    }

    public synchronized FtpsTarget get(String id) {
        return id == null ? null : byId.get(id);
    }

    public synchronized void save(FtpsTarget t) {
        byId.put(t.getId(), t);
        persist();
    }

    public synchronized boolean delete(String id) {
        boolean removed = byId.remove(id) != null;
        if (removed) {
            persist();
        }
        return removed;
    }

    /** The file this store reads and writes, for a diagnostic message. */
    public File file() {
        return file;
    }
}
