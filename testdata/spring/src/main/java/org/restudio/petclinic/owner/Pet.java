package org.restudio.petclinic.owner;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import org.restudio.petclinic.model.BaseEntity;

@Entity
public class Pet extends BaseEntity {
  private String name;
  @ManyToOne
  private PetType type;
  @ManyToOne
  private Owner owner;

  public String getName() { return name; }

  public PetType getType() { return type; }

  public Owner getOwner() { return owner; }
}
