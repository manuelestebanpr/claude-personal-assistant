package com.my.custom.claudepersonalassistant.mcp.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Groceries by category, then by name — the order a shopping list is read in, and stable enough
 * that the same listing twice looks the same to the model.
 */
public interface GroceryRepository extends JpaRepository<GroceryEntity, Long> {

    List<GroceryEntity> findAllByOrderByCategoryAscNameAscIdAsc();

    List<GroceryEntity> findByCategoryOrderByNameAscIdAsc(String category);
}
