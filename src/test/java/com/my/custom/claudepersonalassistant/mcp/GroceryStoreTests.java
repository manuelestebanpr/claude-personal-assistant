package com.my.custom.claudepersonalassistant.mcp;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.ApplicationModuleTest;

import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.NewGrocery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The groceries store against a real database.
 *
 * <p>Boots the {@code mcp} module alone, which is the point: the module now owns a table, and this
 * proves it can create and use it without any other module in the context.
 */
@ApplicationModuleTest
class GroceryStoreTests {

    @Autowired
    private GroceryStore store;

    /**
     * Unlike the chat tests — which each own a conversation and assert only inside it — every test
     * here shares one table, and the context is cached across them. Emptying it first is what keeps
     * a whole-table assertion meaningful.
     */
    @BeforeEach
    void emptyTheTable() {
        store.delete(store.list(null).stream().map(Grocery::id).toList());
    }

    @Test
    void roundTripsAGroceryThroughTheDatabase() {
        Grocery saved = store.add(new NewGrocery("Leche Alpin", "Lacteos",
                new BigDecimal("0.789"), new BigDecimal("45190"), "0.789kg x 6980"));

        assertThat(saved.id()).isNotNull();
        assertThat(store.list(null)).singleElement().satisfies(found -> {
            assertThat(found.name()).isEqualTo("Leche Alpin");
            assertThat(found.quantity()).isEqualByComparingTo("0.789");
            assertThat(found.price()).isEqualByComparingTo("45190");
            assertThat(found.note()).isEqualTo("0.789kg x 6980");
        });
    }

    /**
     * A model writing "Lacteos" one turn and "lacteos" the next would otherwise split one category
     * into two, and no filter would ever find both.
     */
    @Test
    void normalisesTheCategorySoFilteringIsNotCaseSensitive() {
        store.add(new NewGrocery("Leche", "  LACTEOS ", BigDecimal.ONE, new BigDecimal("6980"), null));
        store.add(new NewGrocery("Queso", "lacteos", BigDecimal.ONE, new BigDecimal("12000"), null));
        store.add(new NewGrocery("Pollo", "carnes", BigDecimal.ONE, new BigDecimal("22000"), null));

        assertThat(store.list("Lacteos")).extracting(Grocery::name)
                .containsExactlyInAnyOrder("Leche", "Queso");
    }

    @Test
    void addsManyInOneCallAndReturnsThemWithIds() {
        List<Grocery> saved = store.addAll(List.of(
                new NewGrocery("Tomate Chon", "frutas y verduras", new BigDecimal("1.085"),
                        new BigDecimal("5444"), "1.085kg x 8980"),
                new NewGrocery("Pan", "panaderia", new BigDecimal("2"), new BigDecimal("4000"), null)));

        assertThat(saved).extracting(Grocery::name, grocery -> grocery.id() != null)
                .containsExactly(tuple("Tomate Chon", true), tuple("Pan", true));
        assertThat(store.list(null)).hasSize(2);
    }

    @Test
    void deletesOnlyTheRequestedIdsAndReportsHowManyWentAway() {
        List<Grocery> saved = store.addAll(List.of(
                new NewGrocery("Leche", "lacteos", BigDecimal.ONE, new BigDecimal("6980"), null),
                new NewGrocery("Pollo", "carnes", BigDecimal.ONE, new BigDecimal("22000"), null)));

        // The unknown id is not an error: the model may be working from a listing that has moved on,
        // and the useful answer is how many rows actually went away.
        int deleted = store.delete(List.of(saved.getFirst().id(), 9_999L));

        assertThat(deleted).isEqualTo(1);
        assertThat(store.list(null)).extracting(Grocery::name).containsExactly("Pollo");
    }
}
