package com.atomikmc.atomikvk.common;

import java.nio.IntBuffer;

public interface GraphicsProvider {
    void init();
    void update(IntBuffer pImageIndex, int w, int h);
    void cleanup();

}
