package nl.example.migratie;

import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

/**
 * Alles wat de recipes niet automatisch konden of wilden aanpassen. Met
 * {@code -Drewrite.exportDatatables=true} komt dit als CSV in {@code target/rewrite/datatables/}.
 * <p>
 * Bewust een datatabel en geen search-markering: {@code rewrite:run} zou markeringen als
 * {@code ~~>} in de bestanden zelf schrijven.
 */
public class MigratieBevindingen extends DataTable<MigratieBevindingen.Rij> {

    public MigratieBevindingen(Recipe recipe) {
        super(recipe, "Bevindingen DB2 -> Postgres",
                "Punten die per service handmatig nagelopen moeten worden.");
    }

    public record Rij(
            @Column(displayName = "Bestand", description = "Pad van het bestand.")
            String bestand,
            @Column(displayName = "Regel", description = "Regelnummer, 0 als niet van toepassing.")
            int regel,
            @Column(displayName = "Soort", description = "Aangepast, Laten staan of Handmatig.")
            String soort,
            @Column(displayName = "Melding", description = "Wat er aan de hand is.")
            String melding) {
    }
}
