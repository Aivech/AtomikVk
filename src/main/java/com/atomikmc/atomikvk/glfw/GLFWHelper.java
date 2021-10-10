package com.atomikmc.atomikvk.glfw;

import com.atomikmc.atomikvk.AtomikVk;
import com.atomikmc.atomikvk.common.GraphicsProvider;
import com.atomikmc.atomikvk.vulkan.Vulkan;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class GLFWHelper {
    private static long window = 0;

    private static int width = 0;
    private static int height = 0;

    public static final int INITIAL_WINDOW_WIDTH = 1280;
    public static final int INITIAL_WINDOW_HEIGHT = 720;

    private static GraphicsProvider provider = new Vulkan();

    public static long glfwSetupWindow() {
        if (!glfwInit()) {
            throw new AssertionError("GLFW init failed");
        }

        glfwSetErrorCallback((error, description) -> {
            AtomikVk.LOGGER.error("GLFW ERROR: " + error);
            AtomikVk.LOGGER.error(GLFWErrorCallback.getDescription(description));
        });

        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new AssertionError("No Vulkan!");
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

        window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT, "AtomikVk", 0, 0);

        if (window == 0) {
            throw new AssertionError("No window!");
        }

        provider.init();

        return window;
    }

    public static void startWindowLoop() {
        try (MemoryStack stack = stackPush()) {
            while (!glfwWindowShouldClose(window)) {
                // provider.update();
                glfwPollEvents();
            }
        }

    }

    private static void updateFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferWidth = stack.mallocInt(1);
            IntBuffer framebufferHeight = stack.mallocInt(1);
            glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            width = framebufferWidth.get(0);
            height = framebufferHeight.get(0);
        }
    }


    public static void glfwCleanup() {
        provider.cleanup();
        if (window != 0) glfwDestroyWindow(0);
        glfwTerminate();
    }
}