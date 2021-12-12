package com.atomikmc.atomikvk.common;

import java.nio.IntBuffer;

public interface GraphicsProvider {
    void init(long window);
    void drawFrame();
    void update(IntBuffer pImageIndex, int w, int h);
    void cleanup();

}
