package com.fakturacnysoftver.web.attachment;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.util.List;

/** Prilis velky subor: spat na stranku, z ktorej prisiel, so zrozumitelnou chybou (nie chybova stranka servera). */
@ControllerAdvice
class UploadLimitAdvice {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    String tooLarge(HttpServletRequest request, RedirectAttributes redirect) {
        redirect.addFlashAttribute("errors", List.of("Súbor má viac než 10 MB. Veľké videá a fotky dajte na Drive "
                + "a sem vložte odkaz do poznámky."));
        return "redirect:" + samePath(request.getHeader("Referer"), request.getRequestURI().replaceFirst("/prilohy$", ""));
    }

    /** Z Referer berieme len cestu - nikdy nepresmerujeme na cudziu domenu. */
    static String samePath(String referer, String fallback) {
        try {
            String path = referer == null ? null : URI.create(referer).getRawPath();
            return path != null && path.startsWith("/") && !path.startsWith("//") ? path : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
