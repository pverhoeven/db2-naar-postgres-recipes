package nl.example.klant.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB2 kent (op IBM i vóór 7.5) geen BOOLEAN: true/false als SMALLINT 1/0. */
@Converter
public class BooleanConverter implements AttributeConverter<Boolean, Short> {

    @Override
    public Short convertToDatabaseColumn(Boolean waarde) {
        return waarde == null ? null : (short) (waarde ? 1 : 0);
    }

    @Override
    public Boolean convertToEntityAttribute(Short waarde) {
        return waarde == null ? null : waarde == 1;
    }
}
