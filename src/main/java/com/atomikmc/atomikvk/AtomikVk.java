package com.atomikmc.atomikvk;

import com.atomikmc.atomikvk.glfw.GLFWHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AtomikVk {

    public static Logger logger = LogManager.getLogger("AtomikVk");

    public static void main(String[] args) {
        try {
            long window = GLFWHelper.glfwSetup();
            GLFWHelper.startWindowLoop();

        } finally {
            GLFWHelper.glfwCleanup();
        }
    }
}
