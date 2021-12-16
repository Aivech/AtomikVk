package com.atomikmc.atomikvk.glfw;

import com.atomikmc.atomikvk.AtomikVk;
import com.atomikmc.atomikvk.common.GraphicsProvider;
import com.atomikmc.atomikvk.shaderc.SpirVCompiler;
import com.atomikmc.atomikvk.vulkan.Vulkan;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class GLFWHelper {
    private static long window = 0;

    public static final int INITIAL_WINDOW_WIDTH = 1280;
    public static final int INITIAL_WINDOW_HEIGHT = 720;

    private static GraphicsProvider provider = new Vulkan();

    public static void glfwSetupWindow() {
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
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT, "AtomikVk", 0, 0);

        if (window == 0) {
            throw new AssertionError("No window!");
        }

        SpirVCompiler.init();
        provider.init(window);

        glfwSetFramebufferSizeCallback(window, (window1, width, height) -> updateFramebufferSize());
    }

    public static void startWindowLoop() {
        try (MemoryStack stack = stackPush()) {
            while (!glfwWindowShouldClose(window)) {
                // provider.update();
                provider.drawFrame();
                glfwPollEvents();
            }
        }

    }

    private static void updateFramebufferSize() {
        provider.windowResizeUpdate();
    }

    public static void glfwCleanup() {
        final var resizeCallback = glfwSetFramebufferSizeCallback(window, null);
        if (resizeCallback != null) resizeCallback.free();
        provider.cleanup();
        SpirVCompiler.destroy();
        if (window != 0) glfwDestroyWindow(0);
        final var errorCallback = glfwSetErrorCallback(null);
        if (errorCallback != null) errorCallback.free();
        glfwTerminate();
    }
}