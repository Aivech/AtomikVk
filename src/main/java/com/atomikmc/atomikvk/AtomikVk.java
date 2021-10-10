package com.atomikmc.atomikvk;

import com.atomikmc.atomikvk.glfw.GLFWHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AtomikVk {

    public static final Logger LOGGER = LogManager.getLogger("AtomikVk");

    public static void main(String[] args) {
        try {
            long window = GLFWHelper.glfwSetupWindow();
            GLFWHelper.startWindowLoop();

        } finally {
            GLFWHelper.glfwCleanup();
        }
    }
}
