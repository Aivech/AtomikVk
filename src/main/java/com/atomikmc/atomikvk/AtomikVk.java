package com.atomikmc.atomikvk;

import com.atomikmc.atomikvk.glfw.GLFWHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.Configuration;

public class AtomikVk {

    public static final Logger LOGGER = LogManager.getLogger("AtomikVk");

    public static void main(String[] args) {
        try {
            Configuration.DEBUG_MEMORY_ALLOCATOR.set(true);
            GLFWHelper.glfwSetupWindow();
            GLFWHelper.startWindowLoop();

        } catch (Throwable e) {
            LOGGER.fatal("Fatal error.", e);
            throw e;
        } finally {
            GLFWHelper.glfwCleanup();
        }
    }
}
