package com.my.custom.claudepersonalassistant.mcp.domain.tool.groceries;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;

import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysis;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysisException;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysisRequest;
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
 * The receipt importer: one image in, rows in the groceries table out.
 *
 * <p>The model call is mocked, so what is under test is the contract around it — the prompt that
 * goes out, and how carefully the JSON that comes back is believed.
 */
class GroceriesImportReceiptToolTest {

    private static final String TWO_ITEMS = """
            {"items":[
              {"code":"949","description":"Tomate Chon","price":5444,"quantity":1.085,
               "category":"frutas y verduras","other":"1.085kg x 8980"},
              {"code":"1000","description":"Leche Alpin","price":45190,"quantity":0.789,
               "category":"lacteos","other":"0.789kg x 6980"}
            ],"subtotal":50634}
            """;

    private final GroceryStore store = mock(GroceryStore.class);
    private final ImageAnalysis imageAnalysis = mock(ImageAnalysis.class);

    private final GroceriesImportReceiptTool tool = new GroceriesImportReceiptTool(store,
            providerOf(imageAnalysis), JsonMapper.builder().build());

    /** Same contract-pinning as the other groceries tools: the trigger rule lives in the text. */
    @Test
    void describesItselfAsTheReceiptToGroceriesPath() {
        assertThat(tool.description())
                .contains("when the user asks to add a photographed receipt to the groceries list");
    }

    @Test
    void turnsAReceiptIntoStoredGroceries() {
        given(imageAnalysis.analyze(any())).willReturn(TWO_ITEMS);
        given(store.addAll(any())).willReturn(List.of(
                grocery(1L, "Tomate Chon", "frutas y verduras", "1.085", "5444"),
                grocery(2L, "Leche Alpin", "lacteos", "0.789", "45190")));

        String result = tool.execute(Map.of("image_id", 12));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewGrocery>> saved = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(saved.capture());
        assertThat(saved.getValue()).satisfiesExactly(
                first -> {
                    assertThat(first.name()).isEqualTo("Tomate Chon");
                    assertThat(first.category()).isEqualTo("frutas y verduras");
                    assertThat(first.quantity()).isEqualByComparingTo("1.085");
                    assertThat(first.price()).isEqualByComparingTo("5444");
                    // The code and the unit breakdown are what let a row be traced back to the
                    // paper receipt, and neither has a column of its own.
                    assertThat(first.note()).contains("949").contains("1.085kg x 8980");
                },
                second -> assertThat(second.name()).isEqualTo("Leche Alpin"));
        assertThat(result).contains("Tomate Chon").contains("Leche Alpin");
    }

    @Test
    void sendsTheReceiptPromptWithItsPrefillAndFenceStopSequence() {
        given(imageAnalysis.analyze(any())).willReturn(TWO_ITEMS);
        given(store.addAll(any())).willReturn(List.of());

        tool.execute(Map.of("image_id", 12));

        ArgumentCaptor<ImageAnalysisRequest> sent = ArgumentCaptor.forClass(ImageAnalysisRequest.class);
        verify(imageAnalysis).analyze(sent.capture());
        assertThat(sent.getValue().imageId()).isEqualTo(12L);
        assertThat(sent.getValue().userPrompt())
                .contains("cod")
                .contains("Tomate Chon")
                .contains("quantity")
                .contains("category");
        // Prefill opens the fence, stop sequence closes it: between them the model can only emit
        // the object, so nothing has to be stripped before it is parsed.
        assertThat(sent.getValue().prefill()).endsWith("```json");
        assertThat(sent.getValue().stopSequences()).containsExactly("```");
    }

    /**
     * Anthropic rejects a prefill whose final assistant content ends in whitespace with a flat
     * {@code 400 invalid_request_error} — "final assistant content cannot end with trailing
     * whitespace". It cost a working receipt import once; the newline after the fence has to be the
     * model's to write.
     */
    @Test
    void keepsThePrefillFreeOfTrailingWhitespace() {
        given(imageAnalysis.analyze(any())).willReturn(TWO_ITEMS);
        given(store.addAll(any())).willReturn(List.of());

        tool.execute(Map.of("image_id", 12));

        ArgumentCaptor<ImageAnalysisRequest> sent = ArgumentCaptor.forClass(ImageAnalysisRequest.class);
        verify(imageAnalysis).analyze(sent.capture());
        String prefill = sent.getValue().prefill();
        assertThat(prefill).isEqualTo(prefill.stripTrailing());
    }

    @Test
    void reportsWhenTheReceiptSubtotalDisagreesWithTheRowsItRead() {
        given(imageAnalysis.analyze(any())).willReturn("""
                {"items":[{"code":"1","description":"Pan","price":4000,"quantity":1,
                 "category":"panaderia","other":""}],"subtotal":99999}
                """);
        given(store.addAll(any())).willReturn(List.of(grocery(1L, "Pan", "panaderia", "1", "4000")));

        // Not an error: the rows are still worth storing. But a silent mismatch is a misread line
        // nobody catches until the month's total is wrong.
        assertThat(tool.execute(Map.of("image_id", 12)))
                .contains("99999")
                .containsIgnoringCase("does not match");
    }

    @Test
    void rejectsAReplyThatIsNotJsonAndSaysWhatCameBack() {
        given(imageAnalysis.analyze(any())).willReturn("I could not read that receipt, sorry.");

        assertThatThrownBy(() -> tool.execute(Map.of("image_id", 12)))
                .isInstanceOf(ToolExecutionException.class)
                // Echoed back so the model can see what went wrong and retry differently.
                .hasMessageContaining("I could not read that receipt");
    }

    @Test
    void rejectsAReceiptWithNoItemsRatherThanReportingSuccess() {
        given(imageAnalysis.analyze(any())).willReturn("{\"items\":[],\"subtotal\":0}");

        assertThatThrownBy(() -> tool.execute(Map.of("image_id", 12)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("no items");
    }

    @Test
    void rejectsTheWholeReceiptWhenARowIsMissingAField() {
        given(imageAnalysis.analyze(any())).willReturn("""
                {"items":[{"code":"1","description":"Pan","quantity":1,"category":"panaderia"}],
                 "subtotal":4000}
                """);

        assertThatThrownBy(() -> tool.execute(Map.of("image_id", 12)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("price");
    }

    @Test
    void turnsAFailedReadIntoAResultTheModelCanActOn() {
        given(imageAnalysis.analyze(any()))
                .willThrow(new ImageAnalysisException("No image with id 99."));

        assertThatThrownBy(() -> tool.execute(Map.of("image_id", 99)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("No image with id 99");
    }

    /**
     * Booting {@code mcp} on its own is supported, and there is no {@code ImageAnalysis} bean when
     * it is. The tool has to still register — refusing to start would take every other tool with it
     * — and explain itself only when actually called.
     */
    @Test
    void explainsItselfWhenNothingIsWiredToReadImages() {
        GroceriesImportReceiptTool unwired = new GroceriesImportReceiptTool(store, providerOf(null),
                JsonMapper.builder().build());

        assertThatThrownBy(() -> unwired.execute(Map.of("image_id", 12)))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void declaresImageIdAsItsOnlyArgument() {
        assertThat(tool.name()).isEqualTo("groceries_import_receipt");
        assertThat(tool.inputSchema())
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false)
                .containsEntry("required", List.of("image_id"));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ImageAnalysis> providerOf(ImageAnalysis analysis) {
        ObjectProvider<ImageAnalysis> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(analysis);
        return provider;
    }

    private Grocery grocery(Long id, String name, String category, String quantity, String price) {
        return new Grocery(id, name, category, new BigDecimal(quantity), new BigDecimal(price), null,
                Instant.parse("2026-08-17T00:00:00Z"));
    }
}
