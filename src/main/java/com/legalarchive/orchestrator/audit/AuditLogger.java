package com.legalarchive.orchestrator.audit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Log di audit per feedId: file JSON Lines append-only, una riga per evento,
 * leggibile con qualunque editor di testo, con catena di hash SHA-256.
 *
 * Ogni record contiene:
 *   seq      numero progressivo
 *   ts       timestamp ISO-8601 con offset
 *   feedId, runId, node, event, user
 *   details  mappa chiave/valore con i dettagli dell'evento
 *   prevHash hash del record precedente (GENESIS per il primo)
 *   hash     SHA-256 di "seq|ts|feedId|runId|node|event|user|detailsJson|prevHash"
 *
 * La catena rende il log "audit proof": qualunque modifica, cancellazione o
 * inserimento di una riga invalida tutti gli hash successivi, e la verifica
 * (UI o a mano) lo evidenzia. Il file e' {baseDir}/{feedId}/_logs/audit_{feedId}.jsonl
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Stato per feed: ultimo hash e seq, per non rileggere il file a ogni append. */
    private static class ChainState { long seq = 0; String lastHash = "GENESIS"; }
    private final Map<String, ChainState> chains = new ConcurrentHashMap<String, ChainState>();
    private final Map<String, Object> locks = new ConcurrentHashMap<String, Object>();

    public static class Entry {
        public long seq;
        public String ts;
        public String feedId;
        public String runId;
        public String node;
        public String event;
        public String user;
        public Map<String, String> details = new LinkedHashMap<String, String>();
        public String prevHash;
        public String hash;
    }

    public static class VerifyResult {
        public boolean valid = true;
        public long entries = 0;
        public Long brokenAtSeq;
        public String message = "OK";
    }

    public void log(Path auditFile, String feedId, String runId, String node,
                    String event, String user, Map<String, String> details) {
        Object lock = locks.computeIfAbsent(feedId, k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(auditFile.getParent());
                ChainState st = chains.get(feedId);
                if (st == null) {
                    st = recoverChainState(auditFile);
                    chains.put(feedId, st);
                }
                Entry e = new Entry();
                e.seq = st.seq + 1;
                e.ts = OffsetDateTime.now().format(ISO);
                e.feedId = feedId;
                e.runId = runId;
                e.node = node;
                e.event = event;
                e.user = user;
                if (details != null) e.details.putAll(details);
                e.prevHash = st.lastHash;
                e.hash = computeHash(e);

                BufferedWriter w = Files.newBufferedWriter(auditFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                w.write(mapper.writeValueAsString(e));
                w.newLine();
                w.close();

                st.seq = e.seq;
                st.lastHash = e.hash;
            } catch (Exception ex) {
                // l'audit non deve mai far fallire il run, ma l'errore va nel log applicativo
                log.error("Impossibile scrivere audit {}: {}", auditFile, ex.getMessage(), ex);
            }
        }
    }

    public List<Entry> read(Path auditFile, int lastN) {
        List<Entry> all = new ArrayList<Entry>();
        if (!Files.exists(auditFile)) return all;
        try (BufferedReader r = Files.newBufferedReader(auditFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                all.add(mapper.readValue(line, Entry.class));
            }
        } catch (Exception e) {
            log.error("Errore lettura audit {}: {}", auditFile, e.getMessage());
        }
        if (lastN > 0 && all.size() > lastN) {
            return all.subList(all.size() - lastN, all.size());
        }
        return all;
    }

    /** Verifica l'integrita' dell'intera catena di hash. */
    public VerifyResult verify(Path auditFile) {
        VerifyResult v = new VerifyResult();
        if (!Files.exists(auditFile)) { v.message = "No audit file"; return v; }
        String prev = "GENESIS";
        long expectedSeq = 1;
        try (BufferedReader r = Files.newBufferedReader(auditFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                Entry e = mapper.readValue(line, Entry.class);
                v.entries++;
                if (e.seq != expectedSeq || !prev.equals(e.prevHash) || !computeHash(e).equals(e.hash)) {
                    v.valid = false;
                    v.brokenAtSeq = e.seq;
                    v.message = "Chain broken at record seq=" + e.seq
                            + " (line " + v.entries + "): hash or sequence mismatch";
                    return v;
                }
                prev = e.hash;
                expectedSeq++;
            }
        } catch (Exception ex) {
            v.valid = false;
            v.message = "Verification error: " + ex.getMessage();
            return v;
        }
        v.message = "Chain intact: " + v.entries + " records verified";
        return v;
    }

    private ChainState recoverChainState(Path auditFile) {
        ChainState st = new ChainState();
        if (!Files.exists(auditFile)) return st;
        try (BufferedReader r = Files.newBufferedReader(auditFile, StandardCharsets.UTF_8)) {
            String line, last = null;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) last = line;
            }
            if (last != null) {
                Entry e = mapper.readValue(last, Entry.class);
                st.seq = e.seq;
                st.lastHash = e.hash;
            }
        } catch (Exception ex) {
            log.error("Recupero stato catena fallito per {}: {}", auditFile, ex.getMessage());
        }
        return st;
    }

    private String computeHash(Entry e) throws Exception {
        String detailsJson = mapper.writeValueAsString(e.details);
        String payload = e.seq + "|" + e.ts + "|" + n(e.feedId) + "|" + n(e.runId) + "|"
                + n(e.node) + "|" + n(e.event) + "|" + n(e.user) + "|" + detailsJson + "|" + e.prevHash;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String n(String s) { return s == null ? "" : s; }
}
