package com.allpets.api.contact.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code POST /contact}. Bean-validated (LLD §10 — validate every
 * public input) — but NOT via {@code @Valid} on the controller argument: the honeypot check
 * must run first, so {@code ContactController} validates explicitly after it.
 *
 * <p>{@code website} is the honeypot (14.3): a real visitor never fills it; bots usually
 * do. It deliberately carries NO constraints at all — any filled value, of any size, must
 * take the fake-success path, never a 400 that would reveal the rejection. It is never
 * persisted or logged, so an oversized value costs nothing beyond the request body itself
 * (bounded by the server's HTTP limits).
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

        String website) {
}
