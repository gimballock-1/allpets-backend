package com.allpets.api.contact.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /contact}. Bean-validated (LLD §10 — validate every
 * public input). {@code website} is a honeypot: a real visitor never fills it; bots
 * usually do. It is intentionally NOT a validation constraint (a filled honeypot is
 * silently accepted, not rejected — see {@code ContactController}).
 */
public record ContactRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @NotBlank
        @Size(max = 5000)
        String message,

        // Honeypot — must stay empty. Full anti-spam (incl. the field contract with the
        // site form, 8.10) is owned by 14.3; this is the minimal server-side trap.
        String website) {
}
