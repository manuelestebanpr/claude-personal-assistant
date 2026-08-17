package com.my.custom.claudepersonalassistant.chat.web;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentRepository;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AttachmentController.class)
class AttachmentControllerTest {

    private static final byte[] BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x42};

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttachmentRepository attachments;

    @Test
    void servesTheStoredBytesUnderTheirOwnMediaType() throws Exception {
        given(attachments.findById(7L)).willReturn(Optional.of(attachment("image/jpeg")));

        // The stored type, not a guess from the bytes: the browser renders on the header, and a
        // wrong one shows a broken image rather than a photograph.
        mockMvc.perform(get(AttachmentController.ATTACHMENT_PATH, 7L))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(BYTES));
    }

    /**
     * An attachment never changes after it is written, and a conversation is re-rendered on every
     * reload — without this the browser re-downloads every photograph each time.
     */
    @Test
    void tellsTheBrowserTheImageWillNeverChange() throws Exception {
        given(attachments.findById(7L)).willReturn(Optional.of(attachment("image/png")));

        mockMvc.perform(get(AttachmentController.ATTACHMENT_PATH, 7L))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
    }

    @Test
    void answersNotFoundForAnIdThatIsNotThere() throws Exception {
        given(attachments.findById(404L)).willReturn(Optional.empty());

        mockMvc.perform(get(AttachmentController.ATTACHMENT_PATH, 404L))
                .andExpect(status().isNotFound());
    }

    private AttachmentEntity attachment(String mediaType) {
        AttachmentEntity entity = new AttachmentEntity();
        entity.setId(7L);
        entity.setMessageId(1L);
        entity.setMediaType(mediaType);
        entity.setData(BYTES);
        return entity;
    }
}
