package org.restudio.petclinic.vet;

import java.util.Collection;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.repository.Repository;

public interface VetRepository extends Repository<Vet, Integer> {
  @Cacheable("vets")
  Collection<Vet> findAll();
}
