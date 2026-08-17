package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/**
 * Who the signed-in person is, as the server sees them.
 *
 * `photo` is the *endpoint* that serves the face, never the stored file path —
 * profile pictures live in the private bucket, where the raw path is readable
 * only by its owner. Null means there is nothing to show and the UI draws
 * initials.
 */
@Serializable
data class ProfileDto(
    val user: String = "",
    val full_name: String = "",
    val phone: String? = null,
    val photo: String? = null,
    val designation: String? = null,
    val about: String? = null,
    val has_name: Boolean = false,
    val has_photo: Boolean = false,
    val profile_complete: Boolean = false,
    val chat_tone: String = "default",
    val alert_tone: String = "default",
    val vibrate: Boolean = true,
    val prompt_snoozed_until: String? = null,
    val roles: List<String> = emptyList(),
)

@Serializable
data class DesignationDto(
    val name: String = "",
    val designation_name: String = "",
    val sort_order: Int = 100,
)

@Serializable
data class DesignationsPayload(val designations: List<DesignationDto> = emptyList())

@Serializable
data class TonesPayload(
    val chat_tone: String = "default",
    val alert_tone: String = "default",
    val vibrate: Boolean = true,
    val bundled: List<String> = emptyList(),
)
