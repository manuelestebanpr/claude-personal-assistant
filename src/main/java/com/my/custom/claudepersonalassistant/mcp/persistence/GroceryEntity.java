package com.my.custom.claudepersonalassistant.mcp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One stored grocery.
 *
 * <p>The first table this module has ever owned. JPA is a library rather than another application
 * module, so this does not widen {@code mcp}'s {@code allowedDependencies = {}} — {@code
 * ModularityTests} is what proves that, and it should stay green.
 *
 * <p>{@code price} and {@code quantity} are {@link BigDecimal} and their columns are fixed-scale
 * decimals, not doubles: this is money and weight, and a receipt that adds up on paper has to add
 * up here. Quantity carries three decimals because supermarkets sell 1.085 kg of tomatoes.
 *
 * <p>{@code note} exists because a receipt line says more than four columns can hold — the raw
 * {@code 1.085kg x 8980} breakdown is kept verbatim rather than parsed into a unit and a unit price
 * that nothing else needs yet.
 */
@Entity
@Table(name = "grocery", indexes = @Index(name = "idx_grocery_category", columnList = "category"))
@Getter
@Setter
@NoArgsConstructor
public class GroceryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
