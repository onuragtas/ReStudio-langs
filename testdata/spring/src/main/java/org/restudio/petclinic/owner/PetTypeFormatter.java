package org.restudio.petclinic.owner;

import java.text.ParseException;
import java.util.Locale;
import org.springframework.format.Formatter;
import org.springframework.stereotype.Component;

@Component
public class PetTypeFormatter implements Formatter<PetType> {
  private final PetTypeRepository types;

  public PetTypeFormatter(PetTypeRepository types) {
    this.types = types;
  }

  @Override
  public String print(PetType petType, Locale locale) {
    return petType.getName();
  }

  @Override
  public PetType parse(String text, Locale locale) throws ParseException {
    return types.findAll().stream().filter(type -> type.getName().equals(text)).findFirst()
      .orElseThrow(() -> new ParseException("type not found: " + text, 0));
  }
}
