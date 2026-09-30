package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.SeamHello;

/**
 * The seam: what a Sprout app may ask of Soil.
 *
 * It is guarded twice. Android refuses the bind to any app that does not hold Soil's signature
 * permission, which only an app signed with Soil's key can hold; and Soil checks the caller's
 * signing certificate again on every call.
 *
 * An app never touches a file and never sees a key. It asks for rows and hands rows back. The
 * calls that do that arrive with the first Sprout app.
 */
interface ISoilSeam {

    /** The seam's version, and whether the library is unlocked. Never prompts. */
    SeamHello hello();
}
