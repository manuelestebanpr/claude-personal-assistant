package com.my.custom.claudepersonalassistant.assistant.profile;

/**
 * Module-internal lookup of the full {@link AssistantProfile} behind an assistant id — what the
 * streaming client resolves per turn. Deliberately not part of {@code assistant::api}: the profile
 * carries the model and prompt, which no other module has any business reading.
 */
public interface AssistantProfiles {

    /** The profile for {@code assistantId}; {@code null} or an unknown id yields the default. */
    AssistantProfile profile(String assistantId);
}
