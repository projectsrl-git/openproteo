package com.legalarchive.orchestrator.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.legalarchive.orchestrator.config.BuildInfo;

/**
 * Makes {@code ${buildId}} available to every page rendered by {@link PageController}, so the
 * templates can append it to the static asset URLs (<code>@{/js/theme.js(v=${buildId})}</code>).
 *
 * <p>The token changes with every build, which makes the browser fetch the new CSS/JS instead of
 * serving the cached copy - the recurring cause of "the deploy changed nothing" after a release.
 * Scoped to PageController on purpose: the API is a @RestController and has no model to populate.</p>
 */
@ControllerAdvice(assignableTypes = PageController.class)
public class BuildIdAdvice {

    @ModelAttribute("buildId")
    public String buildId() {
        return BuildInfo.id();
    }
}
