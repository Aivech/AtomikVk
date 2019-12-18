package com.atomikmc.atomikvk.glfw;

import com.atomikmc.atomikvk.AtomikVk;
import com.atomikmc.atomikvk.common.config.AtomikVkConfig;
import com.atomikmc.atomikvk.opengl.OpenGLHelper;
import com.atomikmc.atomikvk.vulkan.VulkanHelper;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;

import static org.lwjgl.glfw.GLFW.*;

public class GLFWHelper {
    private static boolean vulkan = false;

    public static final int INITIAL_WINDOW_WIDTH = 1280;
    public static final int INITIAL_WINDOW_HEIGHT = 720;

    public static long glfwSetup() {
        if (!glfwInit()) {
            throw new AssertionError("GLFW init failed");
        }

        glfwSetErrorCallback((error, description) -> {
            AtomikVk.logger.error("GLFW ERROR: " + error);
            AtomikVk.logger.error(GLFWErrorCallback.getDescription(description));
        });

        if (!GLFWVulkan.glfwVulkanSupported() || AtomikVkConfig.isForceOpenGL()) {
            // TODO: OpenGL fallback mode

            OpenGLHelper.setupOpenGL();

            throw new AssertionError("No Vulkan!");
        } else {
            vulkan = true;

            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);

            long window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT, "AtomikVk", 0, 0);

            if (window == 0) {
                throw new AssertionError("No window!");
            }

            VulkanHelper.setupVulkan(window);

            return window;
        }

    }

    public static boolean hasVulkan() {
        return vulkan;
    }
}
