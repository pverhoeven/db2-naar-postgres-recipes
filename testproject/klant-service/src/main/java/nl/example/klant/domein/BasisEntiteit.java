package nl.example.klant.domein;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.MappedSuperclass;
import nl.example.klant.converter.BooleanConverter;

/** Scenario: @MappedSuperclass met converter. De recipe kent de tabel niet en meldt dit. */
@MappedSuperclass
public abstract class BasisEntiteit {

    @Convert(converter = BooleanConverter.class)
    @Column(name = "VERWIJDERD")
    private Boolean verwijderd;

    public Boolean getVerwijderd() {
        return verwijderd;
    }
}
