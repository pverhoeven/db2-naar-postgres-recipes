package nl.example.migratie;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.srcMainResources;
import static org.openrewrite.java.Assertions.srcTestResources;
import static org.openrewrite.yaml.Assertions.yaml;

class Db2NaarPostgresSpringTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("nl.example.migratie.Db2NaarPostgresSpring");
    }

    @Test
    void alleenSrcMainEnPlaceholdersBlijvenStaan() {
        rewriteRun(
          srcMainResources(yaml(
            """
              spring:
                datasource:
                  url: jdbc:as400://${DB_HOST};naming=sql;libraries=KLANTDB
                  driver-class-name: com.ibm.as400.access.AS400JDBCDriver
                jpa:
                  database-platform: org.hibernate.dialect.DB2iDialect
                  open-in-view: false
              """,
            """
              spring:
                datasource:
                  url: jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}
                  driver-class-name: org.postgresql.Driver
                jpa:
                  open-in-view: false
              """,
            s -> s.path("application.yaml"))),
          srcTestResources(yaml(
            """
              spring:
                datasource:
                  url: jdbc:h2:mem:klant;MODE=DB2
                  driver-class-name: org.h2.Driver
              """,
            s -> s.path("application.yaml")))
        );
    }
}
