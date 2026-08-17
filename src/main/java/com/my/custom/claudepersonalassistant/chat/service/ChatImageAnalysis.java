package com.my.custom.claudepersonalassistant.chat.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.custom.claudepersonalassistant.assistant.api.VisionClient;
import com.my.custom.claudepersonalassistant.assistant.dto.ImagePayload;
import com.my.custom.claudepersonalassistant.assistant.dto.VisionRequest;
import com.my.custom.claudepersonalassistant.assistant.exception.AssistantException;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentEntity;
import com.my.custom.claudepersonalassistant.chat.persistence.AttachmentRepository;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysis;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysisException;
import com.my.custom.claudepersonalassistant.mcp.api.ImageAnalysisRequest;

/**
 * Joins the two halves an MCP tool cannot reach on its own: the stored image and the model.
 *
 * <p>Lives in {@code chat} because {@code chat} is the only module allowed to see both
 * {@code mcp::api} and {@code assistant::api} — {@code mcp} declares {@code allowedDependencies =
 * {}} and so does {@code assistant}. It carries no policy of its own: the prompt comes from the
 * tool that asked, the answer goes straight back, and nothing here knows what a receipt is.
 *
 * <p>The failure translation is the other half of its job. {@code AssistantException} is an
 * {@code assistant} type and would drag that module into {@code mcp}'s view; a tool needs a
 * sentence it can hand to the model instead.
 */
@Service
@RequiredArgsConstructor
class ChatImageAnalysis implements ImageAnalysis {

    private final AttachmentRepository attachments;
    private final VisionClient visionClient;

    @Override
    @Transactional(readOnly = true)
    public String analyze(ImageAnalysisRequest request) {
        AttachmentEntity attachment = attachments.findById(request.imageId())
                .orElseThrow(() -> new ImageAnalysisException(
                        "No image with id %d. Image ids are the ones noted on the message that "
                                .formatted(request.imageId())
                                + "carried them."));
        try {
            return visionClient.extract(new VisionRequest(
                    request.systemPrompt(),
                    request.userPrompt(),
                    new ImagePayload(attachment.getMediaType(), attachment.getData()),
                    request.prefill(),
                    request.stopSequences(),
                    request.maxTokens()));
        }
        catch (AssistantException failure) {
            // The classification is worth keeping: it is the difference between "try again" and
            // "this will never work", and the model is the one deciding whether to retry.
            throw new ImageAnalysisException("Could not read the image (%s): %s"
                    .formatted(failure.classification(), failure.getMessage()), failure);
        }
    }
}
