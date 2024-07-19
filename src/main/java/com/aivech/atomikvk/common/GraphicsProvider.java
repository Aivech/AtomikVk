package com.aivech.atomikvk.common;

public interface GraphicsProvider {
    void init(long window);
    void drawFrame();

    void windowResizeUpdate();

    void cleanup();

}
