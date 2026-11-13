package org.restudio.petclinic.owner;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;
import org.restudio.petclinic.model.Person;

@Entity
public class Owner extends Person {
  private String city;

  @OneToMany(cascade = CascadeType.ALL, mappedBy = "owner")
  private final List<Pet> pets = new ArrayList<>();

  public String getCity() { return city; }

  public void setCity(String city) { this.city = city; }

  public List<Pet> getPets() { return pets; }
}
