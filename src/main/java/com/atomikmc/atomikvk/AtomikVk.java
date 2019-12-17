package com.atomikmc.atomikvk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;

public class AtomikVk {

    public static Logger logger = LogManager.getLogger("AtomikVk");

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) {
            throw new Error("GLFW init failed");
        }

        GLFW.glfwSetErrorCallback((error, description) -> {
            logger.error("GLFW ERROR: " + error);
            logger.error(GLFWErrorCallback.getDescription(description));
        });

        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new Error("No Vulkan");
        }

        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);


        GLFW.glfwTerminate();
    }
}
