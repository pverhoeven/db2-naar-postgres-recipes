package nl.example.migratie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tekstbewerkingen op Liquibase-changelogs (YAML en formatted SQL).
 * <p>
 * Bewust op tekstniveau: zo blijven opmaak, volgorde en commentaar exact behouden en is de
 * diff in de PR minimaal. Geen afhankelijkheid van OpenRewrite, zodat deze klasse los te testen is.
 */
public final class ChangelogTekst {

    private static final Pattern CHANGESET = Pattern.compile("^(\\s*)-\\s+changeSet\\s*:\\s*$");
    private static final Pattern SQL_CHANGESET = Pattern.compile("^--\\s*changeset\\s+\\S+.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_NAME = Pattern.compile("\\btableName\\s*:\\s*[\"']?([A-Za-z0-9_$]+)");

    private ChangelogTekst() {
    }

    /** Is dit een YAML-changelog (top-level sleutel databaseChangeLog)? */
    public static boolean isYamlChangelog(String tekst) {
        return Pattern.compile("^databaseChangeLog\\s*:", Pattern.MULTILINE).matcher(tekst).find();
    }

    /** Is dit een Liquibase formatted SQL-changelog? */
    public static boolean isSqlChangelog(String tekst) {
        return Pattern.compile("^\\s*--\\s*liquibase\\s+formatted\\s+sql", Pattern.CASE_INSENSITIVE).matcher(tekst).find();
    }

    /**
     * Absolute verwijzingen ({@code file:}/{@code path:}) naar {@code classpathRoot} omzetten naar
     * {@code classpathRoot + "db2/"}. Relatieve verwijzingen blijven kloppen omdat de hele boom meeverhuist.
     *
     * @param classpathRoot bv. {@code db/changelog/}
     */
    public static String herschrijfVerwijzingen(String tekst, String classpathRoot) {
        String root = classpathRoot.endsWith("/") ? classpathRoot : classpathRoot + "/";
        Pattern p = Pattern.compile("(\\b(?:file|path)\\s*:\\s*[\"']?)((?:classpath\\*?:)?/?)"
                + Pattern.quote(root) + "(?!db2/|postgres/)");
        return p.matcher(tekst).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + root + "db2/"));
    }

    /**
     * Voegt onder elke {@code - changeSet:} de regels toe die {@code nieuweRegels} teruggeeft.
     * De functie krijgt de inspringing van de changeSet-attributen en de regels van het blok.
     */
    public static String bewerkChangeSets(String tekst, BiFunction<String, List<String>, List<String>> nieuweRegels) {
        String[] regels = tekst.split("\n", -1);
        List<String> uit = new ArrayList<>(regels.length + 16);
        for (int i = 0; i < regels.length; i++) {
            uit.add(regels[i]);
            Matcher m = CHANGESET.matcher(regels[i]);
            if (!m.matches()) {
                continue;
            }
            int j = i + 1;
            while (j < regels.length && regels[j].isBlank()) {
                j++;
            }
            if (j >= regels.length) {
                continue;
            }
            String indent = inspringing(regels[j]);
            if (indent.length() <= m.group(1).length()) {
                continue; // lege changeSet
            }
            List<String> blok = new ArrayList<>();
            for (int k = j; k < regels.length && (regels[k].isBlank() || regels[k].startsWith(indent)); k++) {
                blok.add(regels[k]);
            }
            uit.addAll(nieuweRegels.apply(indent, blok));
        }
        return String.join("\n", uit);
    }

    /** Sleutels op het hoogste niveau van een changeSet-blok (id, author, dbms, ...). */
    public static Set<String> sleutels(String indent, List<String> blok) {
        Set<String> s = new LinkedHashSet<>();
        for (String r : blok) {
            if (r.startsWith(indent) && r.length() > indent.length() && !Character.isWhitespace(r.charAt(indent.length()))) {
                int dp = r.indexOf(':');
                if (dp > 0) {
                    s.add(r.substring(indent.length(), dp).trim());
                }
            }
        }
        return s;
    }

    /**
     * Voegt attributen toe aan alle YAML-changeSets die ze nog niet hebben.
     *
     * @param meldingen krijgt een melding per changeSet dat al een {@code dbms} had
     */
    public static String voegAttributenToe(String tekst, Map<String, String> attributen, List<String> meldingen) {
        return bewerkChangeSets(tekst, (indent, blok) -> {
            Set<String> aanwezig = sleutels(indent, blok);
            List<String> r = new ArrayList<>();
            for (Map.Entry<String, String> a : attributen.entrySet()) {
                if (aanwezig.contains(a.getKey())) {
                    if (a.getKey().equals("dbms")) {
                        meldingen.add("changeSet " + id(indent, blok) + " heeft al een dbms, niet aangepast");
                    }
                    continue;
                }
                r.add(indent + a.getKey() + ": " + a.getValue());
            }
            return r;
        });
    }

    /** dbms en logicalFilePath toevoegen aan formatted-SQL-changesets. */
    public static String voegSqlAttributenToe(String tekst, String dbms, String logicalFilePath, List<String> meldingen) {
        String[] regels = tekst.split("\n", -1);
        for (int i = 0; i < regels.length; i++) {
            String r = regels[i];
            if (!SQL_CHANGESET.matcher(r).matches()) {
                continue;
            }
            boolean cr = r.endsWith("\r");
            String kaal = cr ? r.substring(0, r.length() - 1) : r;
            if (kaal.contains("dbms:")) {
                meldingen.add("SQL-changeset heeft al een dbms, niet aangepast: " + kaal.trim());
                continue;
            }
            String extra = " dbms:" + dbms;
            if (logicalFilePath != null && !kaal.contains("logicalFilePath:")) {
                extra += " logicalFilePath:" + logicalFilePath;
            }
            regels[i] = kaal.stripTrailing() + extra + (cr ? "\r" : "");
        }
        return String.join("\n", regels);
    }

    /**
     * Basisdata-changelog: dbms, label {@code basisdata} en een precondition per tabel
     * (alleen uitvoeren als de tabel leeg is, anders MARK_RAN).
     */
    public static String basisdataAttributen(String tekst, String schemaPrefix) {
        return bewerkChangeSets(tekst, (indent, blok) -> {
            Set<String> aanwezig = sleutels(indent, blok);
            List<String> r = new ArrayList<>();
            if (!aanwezig.contains("dbms")) {
                r.add(indent + "dbms: postgresql");
            }
            if (!aanwezig.contains("labels")) {
                r.add(indent + "labels: basisdata");
            }
            Set<String> tabellen = tabellen(String.join("\n", blok));
            if (!aanwezig.contains("preConditions") && !tabellen.isEmpty()) {
                r.add(indent + "preConditions:");
                r.add(indent + "  - onFail: MARK_RAN");
                for (String t : tabellen) {
                    r.add(indent + "  - sqlCheck:");
                    r.add(indent + "      expectedResult: 0");
                    r.add(indent + "      sql: SELECT COUNT(*) FROM " + schemaPrefix + t);
                }
            }
            return r;
        });
    }

    /** Tabellen waarin een stuk changelog insert/loadData doet (lowercase, in volgorde). */
    public static Set<String> tabellen(String tekst) {
        Set<String> s = new LinkedHashSet<>();
        Matcher m = TABLE_NAME.matcher(tekst);
        while (m.find()) {
            s.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return s;
    }

    /** YAML-master met includes, relatief ten opzichte van de master. */
    public static String master(List<String> includes) {
        StringBuilder sb = new StringBuilder("databaseChangeLog:\n");
        for (String i : includes) {
            sb.append("  - include:\n")
                    .append("      file: ").append(i).append('\n')
                    .append("      relativeToChangelogFile: true\n");
        }
        return sb.toString();
    }

    /** Pad vanaf de classpath-root (alles na src/main/resources/). */
    public static String classpathPad(String pad) {
        String s = pad.replace('\\', '/');
        int i = s.indexOf("src/main/resources/");
        return i >= 0 ? s.substring(i + "src/main/resources/".length()) : s;
    }

    static Map<String, String> attributen(String... sleutelWaarde) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < sleutelWaarde.length; i += 2) {
            m.put(sleutelWaarde[i], sleutelWaarde[i + 1]);
        }
        return m;
    }

    private static String id(String indent, List<String> blok) {
        for (String r : blok) {
            if (r.startsWith(indent + "id:")) {
                return r.substring(indent.length() + 3).trim();
            }
        }
        return "?";
    }

    private static String inspringing(String regel) {
        int i = 0;
        while (i < regel.length() && (regel.charAt(i) == ' ' || regel.charAt(i) == '\t')) {
            i++;
        }
        return regel.substring(0, i);
    }
}
