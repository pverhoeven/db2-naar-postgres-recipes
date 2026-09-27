# Testproject: klant-service op DB2 for i

Een kleine Spring Boot 4-service zoals jullie services er vóór de overstap uitzien: JTOpen (`net.sf.jt400:jt400`),
`DB2iDialect`, boolean-converters, CHAR(36)-uuid's en tests op H2. Elk scenario dat de recipes aanpassen of melden
komt er één keer in voor.

```
testproject/
├── klant-service/        uitgangssituatie (DB2), tests groen op H2 in DB2-modus
├── pg-review/            gereviewde Postgres-changelogs (baseline + ruwe generate-changelog-basisdata)
├── handwerk.patch        het handwerk uit de bevindingen, na de recipes
├── draai-migratie.sh     kopie naar werk/, migreren, testen op Postgres
└── werk/                 (gegenereerd) resultaat, als git-repo: git diff --cached
```

## Draaien

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
testproject/draai-migratie.sh                  # alleen de recipes: tests falen op het handwerk
testproject/draai-migratie.sh --met-handwerk   # recipes + handwerk.patch: tests groen op Postgres
```

Het script installeert de recipes in `~/.m2`, kopieert `klant-service` naar `werk/`, draait
`migreer-naar-postgres.sh` en daarna `mvn verify`. De tests draaien op Postgres via Testcontainers. Met podman
zet het script `DOCKER_HOST` en `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` zelf (Ryuk moet de socket ín de VM mounten).
Zonder docker of podman compileert het alleen.

Lokaal starten zonder podman-DB: `LocalKlantServiceApplication` in `src/test/java` (na de migratie).

## Scenario's

| Waar | Scenario | Recipe doet |
|---|---|---|
| `pom.xml` | `net.sf.jt400:jt400` | vervangen door `org.postgresql:postgresql` (runtime) |
| `pom.xml` | `com.h2database:h2` (test) | vervangen door `testcontainers-postgresql`, plus `spring-boot-testcontainers` |
| `application.yaml` | `jdbc:as400:`-url, `AS400JDBCDriver`, `DB2iDialect`, `preferred_uuid_jdbc_type: CHAR` | url en driver naar Postgres, dialect en uuid-mapping weg |
| `application-local.properties` | hetzelfde in properties-vorm, met `hibernate.dialect` | idem |
| `application-ota.yaml` | `default_schema: KLANTDB` | melding (hoofdletters) |
| `src/test/resources/application.yaml` | H2-url en -driver | datasource weg (levert `@ServiceConnection`); productie-url komt er **niet** in |
| `KlantServiceApplicationTest` | `@SpringBootTest` | `@Import(TestcontainersConfiguration.class)`; `TestcontainersConfiguration` en `LocalKlantServiceApplication` gegenereerd |
| `Klant.actief` | `@Convert(BooleanConverter)`, SMALLINT → boolean | `@Convert` weg |
| `Klant.nieuwsbrief` | `@Convert(JaNeeBooleanConverter)`, CHAR(1) J/N → boolean | `@Convert` en import weg |
| `Klant.geblokkeerd` | `@Convert`, blijft SMALLINT (besluit in review) | laten staan, gemeld |
| `Klant.mailOptin` | `Short` op kolom die boolean wordt | melding: type naar `Boolean` |
| `Klant.vip` | `boolean` op SMALLINT zonder converter | melding |
| `Klant.externeId` | `@JdbcTypeCode(SqlTypes.CHAR)` + `columnDefinition = "CHAR(36)"` | beide weg, `name` blijft, imports weg |
| `Klant.klantType`, `Klant.adres` | `@ManyToOne`, `@Embedded` | overgeslagen |
| `Betaalmethode.id` | `@JdbcType(CharJdbcType.class)` | weg |
| `Betaalmethode.actief` | `@Convert` zonder `@Column` | weg, import `BooleanConverter` ook |
| `Betaalmethode.referentie` | `String` op kolom die uuid wordt | melding: overweeg `UUID` |
| `Betaalmethode.sortering` | kolom ontbreekt in de Postgres-baseline | melding |
| `BasisEntiteit` | `@MappedSuperclass` met `@Convert` | melding (tabel onbekend) |
| `Adres` | `@Embeddable` met `@Convert` | melding (tabel onbekend) |
| `AuditRegel` | tabel ontbreekt in de Postgres-baseline | melding, entity ongemoeid |
| `KlantRepository` | native SQL: `ACTIEF = 1`, `WITH UR`, `CURRENT TIMESTAMP`, `SYSIBM.`, `QSYS2.` | meldingen |
| changelogs | YAML, formatted SQL, `include`, `includeAll` | naar `db2/` met `dbms: db2` en `logicalFilePath` |
| `releases/004-audit.xml` | XML-changelog | verplaatst, **niet** aangepast, gemeld |
| `pg-review/001-basisdata.yaml` | hoofdletters, `valueNumeric: 0/1`, `value: N` | lowercase, `valueBoolean`, label `basisdata`, precondition |

## Handwerk (`handwerk.patch`)

Zonder handwerk faalt de test op Postgres op de XML-changelog: die heeft geen `dbms` en draait dus ook op Postgres.
De patch lost alle meldingen op:

- `004-audit.xml`: `dbms="db2"` en `logicalFilePath`;
- `audit_regel` (met index) aan de Postgres-baseline toegevoegd; `sortering` vervalt;
- converters weg in `BasisEntiteit`, `Adres` en `AuditRegel`; `mailOptin` wordt `Boolean`, `vip` krijgt de converter,
  `referentie` wordt `UUID`;
- native queries naar Postgres-SQL;
- schema lowercase in `application-ota.yaml`.
