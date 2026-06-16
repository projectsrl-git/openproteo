package com.legalarchive.orchestrator.mask;

/**
 * Deterministic masking strategies that need pools, free text, or CID modes (Batch 2/3).
 * Format-preserving numeric/alphanumeric live in {@link MaskEngine}; this class composes
 * the engine's deterministic RNG with the value pools.
 *
 * Determinism: every choice is driven by MaskEngine.stream(group, normalizedValue), so the
 * same input always yields the same output (joins survive). consistencyGroup defaults to the
 * anonType.
 */
public final class MaskGenerators {

    private final MaskEngine engine;
    private final MaskPools pools;

    // config
    public int localePercentIt = 100;          // legacy: kept harmless; file selection below now drives locale
    // Per-pool file names (selectable from the mask step; mix freely, e.g. IT animals + intl colors)
    public String firstNameFile      = "firstnames_it.txt";
    public String lastNameFile       = "lastnames_it.txt";
    public String cityFile           = "cities_it.txt";
    public String streetFile         = "streets_it.txt";
    public String companyAnimalsFile = "company_animals_it.txt";
    public String companyColorsFile  = "company_colors_it.txt";
    public String companyActionsFile = "company_actions_it.txt";
    public String companySuffixesFile = "company_suffixes_it.txt";
    public String cidMode = "formatPreserving"; // formatPreserving | partial | hash
    public int cidMaskPercent = 60;             // for partial: % of length masked in the MIDDLE
    public int cidHashLen = 12;                 // for hash: hex length
    public int personVsCompanyPercent = 70;     // customerDescription: % person (rest company)
    public boolean numericPreserveSeparators = true;
    public String normMode = "trimUpper";

    private static final String LOREM = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua ut enim ad minim veniam quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat ";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public MaskGenerators(MaskEngine engine, MaskPools pools) {
        this.engine = engine;
        this.pools = pools;
    }

    private String norm(String v) {
        if (v == null) return "";
        if ("none".equalsIgnoreCase(normMode)) return v;
        String t = v.trim();
        if ("trim".equalsIgnoreCase(normMode)) return t;
        return t.toUpperCase();
    }

    private static String pick(String[] pool, MaskEngine.Stream s, String fallback) {
        if (pool == null || pool.length == 0) return fallback;
        return pool[s.nextInt(pool.length)];
    }

    private boolean useItalian(MaskEngine.Stream s) {
        int p = localePercentIt;
        if (p >= 100) return true;
        if (p <= 0) return false;
        return s.nextInt(100) < p;
    }

    public String firstName(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        return pick(pools.get(firstNameFile), s, value);
    }

    public String lastName(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        return pick(pools.get(lastNameFile), s, value);
    }

    public String fullName(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        String fn = pick(pools.get(firstNameFile), s, "Mario");
        String ln = pick(pools.get(lastNameFile), s, "Rossi");
        return fn + " " + ln;
    }

    public String city(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        return pick(pools.get(cityFile), s, value);
    }

    /** plausible structure only (no CAP/city coherence by design): "Via <name> <number>". */
    public String address(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        String street = pick(pools.get(streetFile), s, "Roma");
        int num = 1 + s.nextInt(250);
        return "Via " + street + " " + num;
    }

    public String company(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        String a = pick(pools.get(companyAnimalsFile), s, "Leone");
        String c = pick(pools.get(companyColorsFile), s, "Dorato");
        String act = pick(pools.get(companyActionsFile), s, "Volante");
        String suf = pick(pools.get(companySuffixesFile), s, "S.r.l.");
        return a + " " + c + " " + act + " " + suf;
    }

    /** person full name OR synthetic company, by personVsCompanyPercent. */
    public String customerDescription(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        boolean person = s.nextInt(100) < personVsCompanyPercent;
        if (person) {
            String fn = pick(pools.get(firstNameFile), s, "Mario");
            String ln = pick(pools.get(lastNameFile), s, "Rossi");
            return fn + " " + ln;
        } else {
            String a = pick(pools.get(companyAnimalsFile), s, "Leone");
            String c = pick(pools.get(companyColorsFile), s, "Dorato");
            String act = pick(pools.get(companyActionsFile), s, "Volante");
            String suf = pick(pools.get(companySuffixesFile), s, "S.r.l.");
            return a + " " + c + " " + act + " " + suf;
        }
    }

    /** lorem ipsum filled/truncated to the exact original character length. */
    public String freeText(String value) {
        if (value == null || value.isEmpty()) return value;
        int len = value.length();
        StringBuilder sb = new StringBuilder(len);
        while (sb.length() < len) sb.append(LOREM);
        return sb.substring(0, len);
    }

    // ---- CID modes ----
    public String cid(String value, String group) {
        if (value == null || value.isEmpty()) return value;
        if ("partial".equalsIgnoreCase(cidMode)) return cidPartial(value);
        if ("hash".equalsIgnoreCase(cidMode)) return cidHash(value, group);
        return engine.alphanumericFixed(value, group, norm(value));   // formatPreserving (default)
    }

    /** keep head and tail visible, replace the MIDDLE with '-' (cidMaskPercent of the length). */
    private String cidPartial(String value) {
        int len = value.length();
        int pct = Math.max(0, Math.min(100, cidMaskPercent));
        int maskCount = (int) Math.round(len * (pct / 100.0));
        if (maskCount <= 0) return value;
        if (maskCount >= len) {
            StringBuilder all = new StringBuilder(len);
            for (int i = 0; i < len; i++) all.append('-');
            return all.toString();
        }
        int head = (len - maskCount) / 2;
        int tail = len - maskCount - head;
        StringBuilder sb = new StringBuilder(len);
        sb.append(value, 0, head);
        for (int i = 0; i < maskCount; i++) sb.append('-');
        sb.append(value, len - tail, len);
        return sb.toString();
    }

    /** deterministic HMAC-derived hex string, truncated to cidHashLen. */
    private String cidHash(String value, String group) {
        MaskEngine.Stream s = engine.stream(group, norm(value));
        int n = Math.max(1, cidHashLen);
        StringBuilder sb = new StringBuilder(n);
        while (sb.length() < n) {
            int b = s.nextByte();
            sb.append(HEX[(b >>> 4) & 0xF]);
            if (sb.length() < n) sb.append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** Full dispatch for an anonType (pool/text/cid). Returns null if not handled here. */
    public String apply(String anonType, String value) {
        if (anonType == null) return null;
        if (value == null || value.isEmpty()) return value;   // empty stays empty for every strategy
        String at = anonType.trim();
        String group = at;   // consistencyGroup default = anonType
        if ("firstName".equals(at)) return firstName(value, group);
        if ("lastName".equals(at)) return lastName(value, group);
        if ("fullName".equals(at)) return fullName(value, group);
        if ("city".equals(at)) return city(value, group);
        if ("address".equals(at)) return address(value, group);
        if ("company".equals(at)) return company(value, group);
        if ("customerDescription".equals(at)) return customerDescription(value, group);
        if ("freeText".equals(at)) return freeText(value);
        if ("cid".equals(at)) return cid(value, group);
        return null;   // not a pool/text/cid type
    }
}
