package nl.example.migratie;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.yaml.Assertions.yaml;

class Db2NaarPostgresChangelogsTest implements RewriteTest {

    private static final String ROOT = "src/main/resources/db/changelog/";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new Db2NaarPostgresChangelogs());
    }

    @Test
    void splitstChangelogsInDb2EnPostgres() {
        rewriteRun(
          // master: ter plekke herschreven naar twee includes
          yaml(
            """
              databaseChangeLog:
                - include:
                    file: db/changelog/changes/001-init.yaml
                - include:
                    file: changes/002-data.sql
                    relativeToChangelogFile: true
              """,
            """
              databaseChangeLog:
                - include:
                    file: db2/db.changelog-db2.yaml
                    relativeToChangelogFile: true
                - include:
                    file: postgres/db.changelog-postgres.yaml
                    relativeToChangelogFile: true
              """,
            s -> s.path(ROOT + "db.changelog-master.yaml")
          ),
          // oude inhoud van de master, met bijgewerkte absolute verwijzing
          yaml(
            null,
            """
              databaseChangeLog:
                - include:
                    file: db/changelog/db2/changes/001-init.yaml
                - include:
                    file: changes/002-data.sql
                    relativeToChangelogFile: true
              """,
            s -> s.path(ROOT + "db2/db.changelog-db2.yaml")
          ),
          // YAML-changelog: verhuisd, dbms en logicalFilePath met het oude pad
          yaml(
            """
              databaseChangeLog:
                - changeSet:
                    id: 001-tabellen
                    author: team
                    changes:
                      - createTable:
                          tableName: KLANT
                          columns:
                            - column: {name: ID, type: BIGINT}
              """,
            """
              databaseChangeLog:
                - changeSet:
                    dbms: db2
                    logicalFilePath: db/changelog/changes/001-init.yaml
                    id: 001-tabellen
                    author: team
                    changes:
                      - createTable:
                          tableName: KLANT
                          columns:
                            - column: {name: ID, type: BIGINT}
              """,
            s -> s.path(ROOT + "changes/001-init.yaml")
              .afterRecipe(d -> assertThat(d.getSourcePath())
                .isEqualTo(Paths.get(ROOT + "db2/changes/001-init.yaml")))
          ),
          // formatted SQL
          text(
            """
              --liquibase formatted sql

              --changeset team:002-index
              CREATE INDEX IX_KLANT_NAAM ON KLANT (NAAM);
              """,
            """
              --liquibase formatted sql

              --changeset team:002-index dbms:db2 logicalFilePath:db/changelog/changes/002-data.sql
              CREATE INDEX IX_KLANT_NAAM ON KLANT (NAAM);
              """,
            s -> s.path(ROOT + "changes/002-data.sql")
              .afterRecipe(t -> assertThat(t.getSourcePath())
                .isEqualTo(Paths.get(ROOT + "db2/changes/002-data.sql")))
          ),
          // Postgres-baseline: alleen dbms erbij
          yaml(
            """
              databaseChangeLog:
                - changeSet:
                    id: pg-baseline-1
                    author: db2-migratie
                    changes:
                      - createTable:
                          tableName: klant
                          columns:
                            - column: {name: id, type: BIGINT, autoIncrement: true}
                            - column: {name: actief, type: BOOLEAN}
              """,
            """
              databaseChangeLog:
                - changeSet:
                    dbms: postgresql
                    id: pg-baseline-1
                    author: db2-migratie
                    changes:
                      - createTable:
                          tableName: klant
                          columns:
                            - column: {name: id, type: BIGINT, autoIncrement: true}
                            - column: {name: actief, type: BOOLEAN}
              """,
            s -> s.path(ROOT + "postgres/000-baseline.yaml")
          ),
          // basisdata zoals liquibase generate-changelog --diff-types=data hem maakt
          yaml(
            """
              databaseChangeLog:
              - changeSet:
                  id: 1790504908783-1
                  author: root (generated)
                  changes:
                  - insert:
                      columns:
                      - column:
                          name: ID
                          valueNumeric: 1
                      - column:
                          name: ACTIEF
                          valueNumeric: 1
                      tableName: KLANT
              """,
            """
              databaseChangeLog:
              - changeSet:
                  dbms: postgresql
                  labels: basisdata
                  preConditions:
                    - onFail: MARK_RAN
                    - sqlCheck:
                        expectedResult: 0
                        sql: SELECT COUNT(*) FROM klant
                  id: basisdata-klant
                  author: root (generated)
                  changes:
                  - insert:
                      columns:
                      - column:
                          name: id
                          valueNumeric: 1
                      - column:
                          name: actief
                          valueBoolean: true
                      tableName: klant
              """,
            s -> s.path(ROOT + "postgres/001-basisdata.yaml")
          ),
          // nieuw: postgres-master
          yaml(
            null,
            """
              databaseChangeLog:
                - include:
                    file: 000-baseline.yaml
                    relativeToChangelogFile: true
                - include:
                    file: 001-basisdata.yaml
                    relativeToChangelogFile: true
              """,
            s -> s.path(ROOT + "postgres/db.changelog-postgres.yaml")
          )
        );
    }

    @Test
    void doetNietsAlsDb2AlBestaat() {
        rewriteRun(
          yaml(
            """
              databaseChangeLog:
                - include:
                    file: db2/db.changelog-db2.yaml
                    relativeToChangelogFile: true
              """,
            s -> s.path(ROOT + "db.changelog-master.yaml")
          ),
          yaml(
            """
              databaseChangeLog:
                - include:
                    file: db/changelog/db2/changes/001-init.yaml
              """,
            s -> s.path(ROOT + "db2/db.changelog-db2.yaml")
          )
        );
    }

    @Test
    void optieVoorH2() {
        rewriteRun(
          spec -> spec.recipe(new Db2NaarPostgresChangelogs(null, "db2,h2", null)),
          yaml(
            """
              databaseChangeLog: []
              """,
            """
              databaseChangeLog:
                - include:
                    file: db2/db.changelog-db2.yaml
                    relativeToChangelogFile: true
              """,
            s -> s.path(ROOT + "db.changelog-master.yaml")
          ),
          yaml(
            null,
            """
              databaseChangeLog: []
              """,
            s -> s.path(ROOT + "db2/db.changelog-db2.yaml")
          ),
          yaml(
            """
              databaseChangeLog:
                - changeSet:
                    id: 1
                    author: team
                    changes:
                      - sql: {sql: select 1}
              """,
            """
              databaseChangeLog:
                - changeSet:
                    dbms: db2,h2
                    logicalFilePath: db/changelog/changes/001.yaml
                    id: 1
                    author: team
                    changes:
                      - sql: {sql: select 1}
              """,
            s -> s.path(ROOT + "changes/001.yaml")
          )
        );
    }
}
