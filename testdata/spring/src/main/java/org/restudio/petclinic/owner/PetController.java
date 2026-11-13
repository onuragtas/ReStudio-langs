package org.restudio.petclinic.owner;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class PetController {
  private final OwnerRepository owners;
  private final PetTypeRepository types;

  @GetMapping("/pettypes")
  Object petTypes() {
    return types.findAll();
  }

  @GetMapping("/owners/count")
  long ownerCount() {
    return owners.count();
  }
}
