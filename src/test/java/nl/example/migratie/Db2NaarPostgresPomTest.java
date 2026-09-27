package nl.example.migratie;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.maven.Assertions.pomXml;

class Db2NaarPostgresPomTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("nl.example.migratie.Db2NaarPostgresPom");
    }

    private static String pom(String dependency) {
        return """
          <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>nl.example</groupId>
            <artifactId>klant-service</artifactId>
            <version>1.0.0</version>
            <dependencies>
          %s
            </dependencies>
          </project>
          """.formatted(dependency);
    }

    private static String alleenPostgres(String actual) {
        assertThat(actual)
          .contains("<groupId>org.postgresql</groupId>", "<artifactId>postgresql</artifactId>", "<scope>runtime</scope>")
          .doesNotContain("jt400", "com.ibm.db2", "<classifier>");
        return actual;
    }

    @Test
    void vervangtJtOpenMetClassifier() {
        rewriteRun(
          pomXml(pom("""
                <dependency>
                  <groupId>net.sf.jt400</groupId>
                  <artifactId>jt400</artifactId>
                  <version>11.1</version>
                  <classifier>java11</classifier>
                </dependency>
            """), spec -> spec.after(Db2NaarPostgresPomTest::alleenPostgres))
        );
    }

    @Test
    void vervangtJtOpenZonderClassifier() {
        rewriteRun(
          pomXml(pom("""
                <dependency>
                  <groupId>net.sf.jt400</groupId>
                  <artifactId>jt400</artifactId>
                  <version>20.0.7</version>
                </dependency>
            """), spec -> spec.after(Db2NaarPostgresPomTest::alleenPostgres))
        );
    }

    @Test
    void vervangtDb2Jcc() {
        rewriteRun(
          pomXml(pom("""
                <dependency>
                  <groupId>com.ibm.db2</groupId>
                  <artifactId>jcc</artifactId>
                  <version>11.5.9.0</version>
                </dependency>
            """), spec -> spec.after(Db2NaarPostgresPomTest::alleenPostgres))
        );
    }
}
