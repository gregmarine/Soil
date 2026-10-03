package com.symmetricalpalmtree.soil.ext;

import com.symmetricalpalmtree.soil.ext.InkStroke;

/**
 * A handwriting recogniser: Soil's one extension point so far. Engine-neutral and stateless:
 * every argument is bare geometry and a language tag, every result is plain text. Bound by Soil
 * alone, on an app's behalf; every method checks the caller first.
 *
 * Only SecurityException, IllegalArgumentException and IllegalStateException cross.
 */
interface IRecognizer {

    /** One of ExtContract.STATUS_*, for [languageTag]. Fast; never waits on the engine. */
    int status(String languageTag);

    /**
     * Start acquiring what the engine needs for [languageTag] (a model download). Returns at
     * once; poll status(). The ONLY call that may start a download: Soil's apps ask the person
     * first. The recognise calls wait for an acquisition already in flight but never start one.
     */
    void prepare(String languageTag);

    /**
     * Recognise one writing area. [strokes] in the area's px space, [areaWidth]/[areaHeight] > 0,
     * [preContext] the text just before this ink ("" for none). The top candidate, lines joined
     * by '\n' and paragraphs by a blank line, or "". Throws IllegalStateException with
     * ExtContract.NOT_READY when it cannot become ready within the call, any other
     * IllegalStateException on an engine failure, IllegalArgumentException over the caps.
     */
    String recognizeInk(String languageTag, in List<InkStroke> strokes, float areaWidth, float areaHeight, String preContext);

    /** Recognise a whole page: the engine finds lines and paragraphs itself. Same exceptions. */
    String recognizePage(String languageTag, in List<InkStroke> strokes, float pageWidth, float pageHeight);
}
