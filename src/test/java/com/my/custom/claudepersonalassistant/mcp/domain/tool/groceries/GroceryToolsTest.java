package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.my.custom.claudepersonalassistant.mcp.domain.ToolExecutionException;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.Grocery;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.GroceryStore;
import com.my.custom.claudepersonalassistant.mcp.domain.grocery.NewGrocery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The four groceries tools that touch nothing but the store. The receipt importer has its own class
 * because it also reaches the model.
 */
class GroceryToolsTest {

    private final GroceryStore store = mock(GroceryStore.class);

    private final GroceriesAddTool addTool = new GroceriesAddTool(store);
    private final GroceriesAddManyTool addManyTool = new GroceriesAddManyTool(store);
    private final GroceriesListTool listTool = new GroceriesListTool(store);
    private final GroceriesDeleteTool deleteTool = new GroceriesDeleteTool(store);

    @Test
    void addsAGroceryWithEveryFieldTheModelSupplied() {
        given(store.add(any())).willReturn(grocery(1L, "Leche Alpin", "lacteos", "0.789", "45190"));

        String result = addTool.execute(Map.of("name", "Leche Alpin", "category", "Lacteos",
                "quantity", 0.789, "price", 45190, "note", "0.789kg x 6980"));

        ArgumentCaptor<NewGrocery> saved = ArgumentCaptor.forClass(NewGrocery.class);
        verify(store).add(saved.capture());
        assertThat(saved.getValue().name()).isEqualTo("Leche Alpin");
        assertThat(saved.getValue().category()).isEqualTo("Lacteos");
        assertThat(saved.getValue().quantity()).isEqualByComparingTo("0.789");
        assertThat(saved.getValue().price()).isEqualByComparingTo("45190");
        assertThat(saved.getValue().note()).isEqualTo("0.789kg x 6980");
        assertThat(result).contains("Leche Alpin").contains("#1");
    }

    /**
     * "Add milk, four thousand pesos, dairy" should be one call. Requiring a quantity the user never
     * said would make the model either ask a pointless question or invent one.
     */
    @Test
    void defaultsQuantityToOneWhenTheModelOmitsIt() {
        given(store.add(any())).willReturn(grocery(1L, "Pan", "panaderia", "1", "4000"));

        addTool.execute(Map.of("name", "Pan", "category", "panaderia", "price", 4000));

        ArgumentCaptor<NewGrocery> saved = ArgumentCaptor.forClass(NewGrocery.class);
        verify(store).add(saved.capture());
        assertThat(saved.getValue().quantity()).isEqualByComparingTo("1");
    }

    @Test
    void rejectsAnAddWithNoPriceRatherThanStoringZero() {
        assertThatThrownBy(() -> addTool.execute(Map.of("name", "Pan", "category", "panaderia")))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("price");
    }

    @Test
    void rejectsANegativePrice() {
        assertThatThrownBy(() -> addTool.execute(
                Map.of("name", "Pan", "category", "panaderia", "price", -10)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("price");
    }

    @Test
    void addsAWholeListInOneCall() {
        given(store.addAll(any())).willReturn(List.of(
                grocery(1L, "Tomate Chon", "frutas y verduras", "1.085", "5444"),
                grocery(2L, "Pan", "panaderia", "2", "4000")));

        String result = addManyTool.execute(Map.of("items", List.of(
                Map.of("name", "Tomate Chon", "category", "frutas y verduras",
                        "quantity", 1.085, "price", 5444),
                Map.of("name", "Pan", "category", "panaderia", "quantity", 2, "price", 4000))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewGrocery>> saved = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(saved.capture());
        assertThat(saved.getValue()).extracting(NewGrocery::name)
                .containsExactly("Tomate Chon", "Pan");
        assertThat(result).contains("2").contains("Tomate Chon").contains("Pan");
    }

    /**
     * Dropping the bad row silently would import a shopping list quietly missing an item — the one
     * failure nobody notices until the total is wrong.
     */
    @Test
    void refusesTheWholeListWhenOneRowIsMalformed() {
        assertThatThrownBy(() -> addManyTool.execute(Map.of("items", List.of(
                Map.of("name", "Pan", "category", "panaderia", "price", 4000),
                Map.of("name", "Leche", "category", "lacteos")))))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("price");
    }

    @Test
    void listsEverythingWithIdsSoTheModelCanDeleteAfterwards() {
        given(store.list(null)).willReturn(List.of(
                grocery(4L, "Leche Alpin", "lacteos", "0.789", "45190"),
                grocery(7L, "Pan", "panaderia", "2", "4000")));

        String result = listTool.execute(Map.of());

        assertThat(result).contains("#4").contains("#7").contains("Leche Alpin").contains("Pan");
    }

    @Test
    void filtersTheListingByCategory() {
        given(store.list("lacteos")).willReturn(List.of(grocery(4L, "Leche", "lacteos", "1", "6980")));

        assertThat(listTool.execute(Map.of("category", "lacteos"))).contains("Leche");
        verify(store).list("lacteos");
    }

    @Test
    void saysSoPlainlyWhenThereIsNothingToList() {
        given(store.list(null)).willReturn(List.of());

        // An empty string here reads to the model as a broken tool; a sentence reads as an answer.
        assertThat(listTool.execute(Map.of())).contains("No groceries");
    }

    @Test
    void deletesTheRequestedIds() {
        given(store.delete(List.of(4L, 7L))).willReturn(2);

        assertThat(deleteTool.execute(Map.of("ids", List.of(4, 7)))).contains("2");
        verify(store).delete(List.of(4L, 7L));
    }

    @Test
    void acceptsABareIdWhereAListIsDeclared() {
        given(store.delete(List.of(4L))).willReturn(1);

        deleteTool.execute(Map.of("ids", 4));

        verify(store).delete(List.of(4L));
    }

    @Test
    void reportsWhenNothingMatchedTheIdsToDelete() {
        given(store.delete(List.of(99L))).willReturn(0);

        assertThat(deleteTool.execute(Map.of("ids", List.of(99)))).contains("No groceries");
    }

    @Test
    void declaresDistinctSnakeCaseNamesAndClosedSchemas() {
        assertThat(List.of(addTool.name(), addManyTool.name(), listTool.name(), deleteTool.name()))
                .containsExactly("groceries_add", "groceries_add_many", "groceries_list", "groceries_delete")
                .doesNotHaveDuplicates();
        assertThat(List.of(addTool, addManyTool, listTool, deleteTool))
                .allSatisfy(tool -> assertThat(tool.inputSchema())
                        .containsEntry("type", "object")
                        .containsEntry("additionalProperties", false));
    }

    private Grocery grocery(Long id, String name, String category, String quantity, String price) {
        return new Grocery(id, name, category, new BigDecimal(quantity), new BigDecimal(price), null,
                Instant.parse("2026-08-17T00:00:00Z"));
    }
}
