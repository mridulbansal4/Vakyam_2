package com.demo.opencv.AI;

import android.content.Context;

/**
 * Adapter preserving compatibility with Gaze-Link's TextEntryManager,
 * delegating sentence generation to on-device Gemma 4 E2B via GemmaManager singleton.
 */
public class GeminiManager {
    private final GemmaManager gemmaManager = GemmaManager.getInstance();

    public void init(Context context) {
        gemmaManager.init(context);
    }

    public void generate(String keywords, String context, String language) {
        gemmaManager.generate(keywords, context, language);
    }
}
