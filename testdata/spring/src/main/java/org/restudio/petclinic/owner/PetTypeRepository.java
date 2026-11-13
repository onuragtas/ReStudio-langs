package org.restudio.petclinic.owner;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface PetTypeRepository extends Repository<PetType, Integer> {
  List<PetType> findAll();
}
