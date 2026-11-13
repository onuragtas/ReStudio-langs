package org.restudio.petclinic.vet;

import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VetController {
  @Autowired
  private VetRepository vets;

  @GetMapping("/vets")
  Collection<Vet> vets() {
    return vets.findAll();
  }
}
