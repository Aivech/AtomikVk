package com.atomikmc.atomikvk.glfw;

import com.atomikmc.atomikvk.AtomikVk;
import com.atomikmc.atomikvk.common.config.AtomikVkConfig;
import com.atomikmc.atomikvk.opengl.OpenGLHelper;
import com.atomikmc.atomikvk.vulkan.VulkanHelper;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class GLFWHelper {
    private static boolean vulkan = false;
    private static long window = 0;

    private static int width = 0;
    private static int height = 0;

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
            // glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);

            window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT, "AtomikVk", 0, 0);

            if (window == 0) {
                throw new AssertionError("No window!");
            }

            VulkanHelper.setupVulkan(window);
        }

        return window;
    }

    public static void startWindowLoop() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            while (!glfwWindowShouldClose(window)) {
                updateFramebufferSize();
                if (width == 0 || height == 0) continue;

                if (vulkan) VulkanHelper.update(pImageIndex, width, height);

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

    public static boolean hasVulkan() {
        return vulkan;
    }

    public static void glfwCleanup() {
        if (vulkan) VulkanHelper.cleanupVulkan();
        else OpenGLHelper.cleanupOpenGL();
        if (window != 0) glfwDestroyWindow(0);
        glfwTerminate();
    }
}