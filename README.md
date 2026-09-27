# DB2 → Postgres: OpenRewrite-recipes

Per microservice (één ticket) van DB2 naar Postgres, met zo min mogelijk handwerk en zonder Python.
Dit vervangt de eerdere Python-toolkit.

```
db2-naar-postgres-recipes/
├── pom.xml                              recipe-artifact (mvn install naar ~/.m2)
├── migreer-naar-postgres.sh             per service: kopiëren, rewrite:run, bevindingen
├── src/main/java/nl/example/migratie/
│   ├── Db2NaarPostgresChangelogs.java   changelogs splitsen in db2/ en postgres/, basisdata
│   ├── StemEntitiesAfOpPostgres.java    @Convert en CHAR-uuid weg waar de kolom veranderd is
│   ├── VoegTestcontainersToe.java       TestcontainersConfiguration + Local<App> in src/test
│   ├── ZoekDb2Restanten.java            meldt wat overblijft (past niets aan)
│   ├── ChangelogTekst.java              tekstbewerkingen op changelogs (los getest)
│   ├── PostgresSchema.java              kolomtypes uit de Postgres-baseline
│   └── MigratieBevindingen.java         datatabel met alles wat handmatig moet
├── src/main/resources/META-INF/rewrite/db2-naar-postgres.yml   samengestelde recipes
├── src/test/java/…                      RewriteTest-tests
└── testproject/                         DB2-service (JTOpen) met alle scenario's, zie testproject/LEESMIJ.md
```

## Status

`mvn verify` is groen op JDK 21 (alle 14 tests). Het [testproject](testproject/LEESMIJ.md) draait de hele route
end-to-end: een Spring Boot 4-service op DB2 for i (JTOpen) gaat door `migreer-naar-postgres.sh`, en de tests
draaien daarna groen op Postgres via Testcontainers (met podman).

**OpenRewrite-versies gelijk houden.** Het script zet `rewrite-maven-plugin` vast op 6.46.1 (OpenRewrite 8.89), en de
pom gebruikt `rewrite-recipe-bom` 3.37.0 (ook 8.89). Dat moet gelijk blijven: vanaf 8.8x parset de YAML-parser een
flow-mapping als `{name: id, type: UUID}` als één scalar, en `rewrite-spring` past properties alleen nog aan in
bestanden met een source-set-marker. Beide vielen alleen op in het testproject, niet in de unittests.

**Bouw met JDK 21 of 25.** De Java-parser van OpenRewrite gebruikt de javac van de draaiende JDK, en voor JDK 26+ is er nog geen parser. Onder JDK 26 werden velden met bijvoorbeeld `@JdbcTypeCode(SqlTypes.CHAR)` stilletjes als `J.Erroneous` geparst, en die sloeg de entity-recipe dan over. De enforcer-plugin breekt de build daarom af op JDK 26+.

Nog niet gedraaid op een echte service; zie [Wat er getest is](#wat-er-getest-is).

## Installeren

De recipes gaan niet naar een centrale repository. Installeer ze lokaal in `~/.m2`; het script gebruikt standaard
`nl.example.migratie:db2-naar-postgres-recipes:1.0.0-SNAPSHOT` uit de lokale repository.

```bash
export JAVA_HOME=/pad/naar/jdk-21   # of 25, niet 26+
mvn install                         # met tests; -DskipTests om ze over te slaan
```

Na elke wijziging aan de recipes opnieuw `mvn install`. Controle van de hele route: `testproject/draai-migratie.sh --met-handwerk`.

Bij een upgrade van OpenRewrite: pluginversie in het script en `rewrite-recipe-bom.version` samen ophogen, en het testproject opnieuw draaien.

## Werkwijze per ticket

### Stap 1: voorbereiding (review door het team)

Het resultaat is één map, bijvoorbeeld `pg-review/klant-service/`:

| Bestand | Hoe |
|---|---|
| `000-baseline.yaml` | jullie schema-generator (uuid, boolean, identity zoals jullie die omzetten) |
| `001-basisdata.yaml` | applicatie of `liquibase update` op een **leeg** DB2-schema, daarna `liquibase generate-changelog --diff-types=data --changelog-file=001-basisdata.yaml --exclude-objects="DATABASECHANGELOG.*"` |

Het team reviewt beide. Let bij de basisdata vooral op:
- **De volgorde van de tabellen.** Liquibase zet ze alfabetisch, niet op foreign keys. Staat een kindtabel vóór zijn ouder, verplaats dan de changeset.
- **Ongewenste rijen**, zoals testdata uit een DB2-changelog. Haal die eruit.

De ids en de namen in hoofdletters laat je staan; die zet het recipe recht.

### Stap 2: integratie

```bash
./migreer-naar-postgres.sh /pad/naar/klant-service /pad/naar/pg-review/klant-service
# tests gaan mee over naar Testcontainers; image kiezen met --postgres-image postgres:17
```

De service-repo moet schoon zijn (alles gecommit). Het script maakt de branch `feature/postgres-overstap`, kopieert
de gereviewde map naar `db/changelog/postgres/` en draait één `rewrite:run`. Het commit niets. De bevindingen komen
als CSV in `<service>-postgres-overstap/`, naast de repo. Opties: `--master`, `--schema`, `--postgres-image`,
`--branch`, `--oude-dbms` (zie de kop van het script).

**Wat de recipes doen:**

| Recipe | Wat |
|---|---|
| `Db2NaarPostgresChangelogs` | Bestaande changelogs naar `db2/`, met `dbms: db2` en `logicalFilePath` (het oude pad). Absolute verwijzingen worden bijgewerkt. De oude master komt in `db2/db.changelog-db2.yaml`, en de master wordt: include `db2/` en `postgres/`. |
| | Postgres-changelogs krijgen `dbms: postgresql`, en er komt een postgres-master. |
| | Basisdata: lowercase namen, `0/1` en `J/N` naar `valueBoolean` voor boolean-kolommen, label `basisdata`, een precondition "tabel leeg, anders `MARK_RAN`" en leesbare ids. |
| `StemEntitiesAfOpPostgres` | `@Convert(…BooleanConverter.class)` weg als de kolom boolean is. `@JdbcTypeCode(SqlTypes.CHAR)`, `@JdbcType(CharJdbcType.class)` en `columnDefinition="CHAR(..)"` weg als de kolom uuid is. Op andere kolommen blijft alles staan. |
| `Db2NaarPostgresPom` | DB2-driver vervangen door `org.postgresql:postgresql` (runtime): JTOpen (`net.sf.jt400:jt400`, ook met classifier) en `com.ibm.db2:jcc`. |
| `Db2NaarPostgresSpring` | Datasource-url, driver, dialect en `preferred_uuid_jdbc_type` in de Spring-properties onder `src/main`. Staat op AANPASSEN voor jullie env-var-namen. |
| `H2NaarTestcontainers` | H2 eruit, `testcontainers-postgresql` en `spring-boot-testcontainers` erin. Genereert `TestcontainersConfiguration` en `Local<App>` naast de applicatieklasse in `src/test/java`, zet `@Import(TestcontainersConfiguration.class)` op `@SpringBootTest`/`@DataJpaTest`, en haalt de datasource uit `src/test/resources` (H2 of podman-DB). Image via `--postgres-image`. |
| `ZoekDb2Restanten` | Meldt DB2-SQL (ook `QSYS2.`), drivers (ook JTOpen/`jdbc:as400`/`DB2iDialect`), schema's in hoofdletters (ook `libraries=`) en H2. Past niets aan. |

Alles is idempotent: bestaat `db2/` al, dan doet het changelog-recipe niets.

**Waarom de changelogs als tekst?** Het changelog-recipe bewerkt YAML en SQL op tekstniveau en parset het resultaat daarna opnieuw. Zo blijven opmaak en volgorde exact behouden, en is de diff in de PR minimaal. De entity-recipe werkt wel op de Java-boom, met type-informatie.

### Stap 3: controle

- `mvn verify`, en lokaal draaien tegen Postgres.
- `liquibase status --verbose` tegen een DB2-omgeving moet **0** openstaande changesets tonen. Dat bewijst dat de `logicalFilePath`-truc werkt voor die service.
- Loop de bevindingen-CSV na.

## Draaiboek overstap per omgeving

Voorwaarden in de chart, die je al hebt doorgevoerd:
- **Helper** met de Liquibase-container. In overstap-modus zet die `SPRING_LIQUIBASE_LABEL_FILTER="!basisdata"`.
- **Init container**, zoals altijd.
- **Job** `job-postgres-overstap.yaml`, alleen bij `postgresOverstap.actief: true`. Hook `post-install,post-upgrade`.
- **Replicas** staan op `0` bij `actief: true`.
- **Default** `postgresOverstap.actief: false`, met een `values.schema.json` die `boolean` afdwingt.
- **Liquibase-profiel** met `web-application-type: none` en listeners/scheduling uit.

| # | Wie | Wat |
|---|---|---|
| 1 | ops | Environment repo: nieuwe versie **en** `postgresOverstap.actief: true` in één PR. |
| 2 | Kubernetes | Oude pods weg, geen nieuwe pods. De Job maakt het schema zonder basisdata. |
| 3 | DBA | DB2-data laden. |
| 4 | team | Controle: rijaantallen DB2 tegenover Postgres. |
| 5 | ops | Override weghalen (niet op `false` laten staan). De init container markeert de basisdata als `MARK_RAN` en de pods starten. |

Opruimticket, na de overstap op álle omgevingen:
- `db2/` en de include in de master weghalen;
- het Job-template en de value weghalen;
- de `dbms: postgresql`-eis voor nieuwe changesets laten vallen.

## Wat er getest is

**Unittests:** `mvn verify`, 14 RewriteTest-tests.

**End-to-end op het [testproject](testproject/LEESMIJ.md):** `testproject/draai-migratie.sh --met-handwerk` draait
alle recipes via de Maven-plugin, zoals het script dat doet, en daarna `mvn verify` van de service op Postgres 17
(Testcontainers). Liquibase voert de baseline en de drie basisdata-changesets uit en slaat de 8 DB2-changesets over
(`dbms`). De tests van de service zijn groen.

**Draaiboek-scenario's**, eerder los getest met Liquibase 4.31 op H2 (als stand-in voor DB2) en Postgres 16, op een
YAML-testservice met basisdata uit een echte `liquibase generate-changelog`. Deze zijn sinds het weghalen van de
identity-reset niet opnieuw gedraaid:

| Scenario | Resultaat |
|---|---|
| Bestaande DB2-database na de herstructurering | 0 changesets uitgevoerd, 7 herkend als al gedraaid |
| Overstap, Job met `update --label-filter='!basisdata'` | alleen de baseline, 0 rijen in de tabellen |
| Opschalen na het laden van de data, update zonder filter | basisdata `MARK_RAN` (niets dubbel) |
| Schone installatie | baseline en basisdata, uuid en boolean als echte types |
| Tweede run | 0 changesets |

Let op: bij de Liquibase-CLI staat `--label-filter` **na** het commando (`update --label-filter=…`). In Spring Boot is het `spring.liquibase.label-filter`.

## Open punten (niet in de recipes)

- **Helm:** via de cruft-template: een variabele `postgres` die de job, de db-certificaten, de helpers en de wijzigingen in de deployment aanzet, per service toegepast met `cruft update --variables-to-update '{"postgres": "true"}'`. Daarna kan het script die stap overnemen.
- **Testcontainers in CI:** de CI-runners hebben een container-runtime nodig. Met podman: `DOCKER_HOST` naar de podman-socket en `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` naar de socket in de VM (zie `testproject/draai-migratie.sh`).
- **Janitor:** zijn mutaties moeten ook Postgres-proof worden. Dat hoort bij het ticket van de service of bij de janitor zelf.
- **XML-changelogs:** worden verplaatst maar niet aangepast (gemeld in de bevindingen).
- **Getter-annotaties** (property access) en `@Embeddable`: de entity-recipe meldt ze, maar past ze niet aan.
