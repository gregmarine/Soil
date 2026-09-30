package com.symmetricalpalmtree.soil.seam;

/**
 * What Soil asks of the app in front. An app attaches one while its paper screen is showing and
 * detaches it as the screen leaves.
 *
 * A paper screen holds the e-ink panel for its ink, and a window drawn over it does not show
 * until the screen lets the panel go. Each call returns once the panel is let go, and Soil
 * waits only so long.
 */
interface ISeamClient {

    /** The side menu is about to be drawn over the app. Let the panel go for a frame. */
    void releasePanel();

    /** A paper screen of Soil's is about to open over the app. Release the pipeline for it. */
    void releaseForHandoff();
}
