package org.doorcam.camera;

import com.pedro.library.base.StreamBase;

/** Enables RootEncoder's device-orientation listener for both preview and streaming. */
public final class StreamOrientation {
    private StreamOrientation() { }

    public static void followDevice(StreamBase stream) {
        stream.getGlInterface().setAutoHandleOrientation(true);
    }
}
