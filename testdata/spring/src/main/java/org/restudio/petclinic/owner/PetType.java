package org.restudio.petclinic.owner;

import jakarta.persistence.Entity;
import org.restudio.petclinic.model.BaseEntity;

@Entity
public class PetType extends BaseEntity {
  private String name;

  public String getName() { return name; }

  public void setName(String name) { this.name = name; }
}
