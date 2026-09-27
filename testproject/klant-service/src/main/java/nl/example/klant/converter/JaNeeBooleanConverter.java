package nl.example.klant.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** true/false als CHAR(1) 'J'/'N'. */
@Converter
public class JaNeeBooleanConverter implements AttributeConverter<Boolean, String> {

    @Override
    public String convertToDatabaseColumn(Boolean waarde) {
        return waarde == null ? null : waarde ? "J" : "N";
    }

    @Override
    public Boolean convertToEntityAttribute(String waarde) {
        return waarde == null ? null : "J".equals(waarde.trim());
    }
}
