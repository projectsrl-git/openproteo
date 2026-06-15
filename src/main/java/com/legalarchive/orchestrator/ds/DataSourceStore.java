package com.legalarchive.orchestrator.ds;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.legalarchive.orchestrator.config.AppProperties;

/**
 * Stores datasource definitions in a single readable JSON file
 * (orchestrator.datasources-file, default ./datasources.json), keyed by id.
 * Passwords are stored in clear text: protect the file with filesystem ACLs.
 */
@Component
public class DataSourceStore {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStore.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final File file;
    private final Map<String, DataSourceDef> byId = new LinkedHashMap<String, DataSourceDef>();

    public DataSourceStore(AppProperties props) {
        this.file = new File(props.getDatasourcesFile());
        load();
    }

    public synchronized void load() {
        byId.clear();
        if (!file.exists()) return;
        try {
            DataSourceDef[] arr = mapper.readValue(file, DataSourceDef[].class);
            if (arr != null) for (DataSourceDef d : arr) if (d.id != null) byId.put(d.id, d);
        } catch (Exception e) {
            log.error("Cannot read datasources file {}: {}", file, e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            mapper.writeValue(tmp.toFile(), byId.values().toArray(new DataSourceDef[0]));
            Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Cannot write datasources file {}: {}", file, e.getMessage(), e);
        }
    }

    public synchronized List<DataSourceDef> all() {
        return new ArrayList<DataSourceDef>(byId.values());
    }

    public synchronized DataSourceDef get(String id) {
        return id == null ? null : byId.get(id);
    }

    public synchronized void save(DataSourceDef d) {
        byId.put(d.id, d);
        persist();
    }

    public synchronized boolean delete(String id) {
        boolean removed = byId.remove(id) != null;
        if (removed) persist();
        return removed;
    }
}
