package nl.example.klant.domein;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import nl.example.klant.converter.JaNeeBooleanConverter;

/** Scenario: @Embeddable met converter. De recipe kent de tabel niet en meldt dit. */
@Embeddable
public class Adres {

    @Column(name = "POSTCODE")
    private String postcode;

    @Convert(converter = JaNeeBooleanConverter.class)
    @Column(name = "ADRES_GEHEIM")
    private Boolean geheim;

    public String getPostcode() {
        return postcode;
    }
}
