package org.restudio.petclinic.owner;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class OwnerController {
  private final OwnerRepository owners;

  OwnerController(OwnerRepository owners) {
    this.owners = owners;
  }

  @GetMapping("/owners")
  String find(@RequestParam(defaultValue = "") String lastName, Model model) {
    List<Owner> result = owners.findByLastNameStartingWith(lastName);
    model.addAttribute("owners", result);
    return "owners/ownersList";
  }

  @GetMapping("/owners/{ownerId}")
  String show(@PathVariable int ownerId, Model model) {
    model.addAttribute("owner", owners.findById(ownerId).orElseThrow());
    return "owners/ownerDetails";
  }
}
