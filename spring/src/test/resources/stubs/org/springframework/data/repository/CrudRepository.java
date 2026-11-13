package org.springframework.data.repository;

@NoRepositoryBean
public interface CrudRepository<T, ID> extends Repository<T, ID> { T findById(ID id); }
